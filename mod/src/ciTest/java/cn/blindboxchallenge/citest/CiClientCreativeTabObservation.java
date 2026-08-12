package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.registry.ModCreativeModeTabs;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.CreativeTabsScreenPage;
import net.minecraftforge.common.CreativeModeTabRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/**
 * 仅随 ciTest Jar 安装的创造模式标签页真实客户端观察器。
 *
 * <p>脚本只能在对真实联机客户端下达 {@code gamemode creative} 后放置阶段旗标；本类仍须实际收到
 * 创造能力同步，再打开原版 {@link CreativeModeInventoryScreen}，通过其公开鼠标按下/松开路径选择
 * 本模组标签页，最后比对屏幕菜单与生产注册源的全部物品顺序。成功 marker 仅由这个真实客户端写入。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientCreativeTabObservation {
    private static final int EXPECTED_ITEM_COUNT = 67;
    private static final int SCREEN_STABLE_TICKS = 5;
    private static boolean screenOpened;
    private static boolean tabClicked;
    private static boolean markerWritten;
    private static int stableTicks;

    private CiClientCreativeTabObservation() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || markerWritten) return;
        Path stageDirectory = stageDirectory();
        if (stageDirectory == null || !Files.isRegularFile(stageDirectory.resolve("creative-tab-enabled.flag"))) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || minecraft.getConnection() == null || !player.getAbilities().instabuild
                || minecraft.gameMode == null || !minecraft.gameMode.hasInfiniteItems()) return;

        if (!screenOpened) {
            // 使用原版创造物品栏构造器和 Minecraft#setScreen，不伪造标签内容或直接改写菜单集合。
            minecraft.setScreen(new CreativeModeInventoryScreen(player, minecraft.level.enabledFeatures(), player.canUseGameMasterBlocks()));
            screenOpened = true;
            CiTestProbe.LOGGER.info("创造模式标签页 CI：真实客户端已打开原版创造物品栏");
            return;
        }
        if (!(minecraft.screen instanceof CreativeModeInventoryScreen screen)) {
            throw new IllegalStateException("创造模式标签页 CI 打开后不再是 CreativeModeInventoryScreen");
        }
        if (++stableTicks < SCREEN_STABLE_TICKS) return;

        CreativeModeTab tab = ModCreativeModeTabs.BLIND_BOX_CHALLENGE.get();
        if (!tab.shouldDisplay()) throw new IllegalStateException("盲盒挑战创造模式标签页未进入可显示状态");
        if (!screen.getCurrentPage().getVisibleTabs().contains(tab)) {
            showTabPage(screen, tab);
            return;
        }
        if (!tabClicked) {
            selectByRealScreenMousePath(screen, tab);
            tabClicked = true;
            return;
        }

        List<ResourceLocation> expected = expectedItemIds();
        List<ResourceLocation> tabItems = itemIds(tab.getDisplayItems(), "标签页显示集合");
        List<ResourceLocation> screenItems = itemIds(screen.getMenu().items, "已打开创造物品栏菜单");
        assertExactItems(expected, tabItems, "标签页显示集合");
        assertExactItems(expected, screenItems, "已打开创造物品栏菜单");

        writeMarker(requiredMarker(), expected, tabItems, screenItems, player);
        markerWritten = true;
        // 观察完成即关闭屏幕，随后脚本把玩家切回生存并进入原有 P5 单客户端回归；不让 GUI 状态干扰真实交互。
        minecraft.setScreen(null);
        CiTestProbe.LOGGER.info("创造模式标签页 CI：真实屏幕已验证 {} 个条目并关闭", expected.size());
    }

    /**
     * Forge 1.20.1 会把排序后的扩展标签每十个拆为一个屏幕页。当前页可能不是目标所在页，故按
     * Forge 的同一公开排序和分页规则重建目标页并调用公开 setCurrentPage；随后仍通过真实 GUI 的
     * 鼠标按下/松开路径选择标签，绝不直接调用私有 selectTab 或改写菜单条目。
     */
    private static void showTabPage(CreativeModeInventoryScreen screen, CreativeModeTab tab) {
        List<CreativeModeTab> sortedTabs = CreativeModeTabRegistry.getSortedCreativeModeTabs();
        int index = sortedTabs.indexOf(tab);
        if (index < 0) throw new IllegalStateException("盲盒挑战创造模式标签页未进入 Forge 排序注册表");
        int pageStart = (index / 10) * 10;
        CreativeTabsScreenPage page = new CreativeTabsScreenPage(sortedTabs.subList(pageStart, Math.min(pageStart + 10, sortedTabs.size())));
        if (!page.getVisibleTabs().contains(tab)) {
            throw new IllegalStateException("盲盒挑战创造模式标签页未进入其 Forge 目标分页");
        }
        screen.setCurrentPage(page);
        if (!screen.getCurrentPage().getVisibleTabs().contains(tab)) {
            throw new IllegalStateException("原版创造物品栏未接受盲盒挑战标签页所在 Forge 分页");
        }
        CiTestProbe.LOGGER.info("创造模式标签页 CI：已切换到包含盲盒挑战标签页的 Forge 分页，page_start={}", pageStart);
    }

    /**
     * Forge 1.20.1 将扩展标签分页。这里基于公开的当前页、列号和 GUI 尺寸计算真实标签按钮中心，
     * 再调用该已打开屏幕的公开鼠标按下/松开处理器；选择逻辑和菜单填充仍由原版私有 selectTab 路径执行。
     */
    private static void selectByRealScreenMousePath(CreativeModeInventoryScreen screen, CreativeModeTab tab) {
        int column = screen.getCurrentPage().getColumn(tab);
        int tabX = 27 * column;
        if (tab.isAlignedRight()) tabX = screen.getXSize() - 27 * (7 - column) + 1;
        int tabY = screen.getCurrentPage().isTop(tab) ? -32 : screen.getYSize();
        double clickX = screen.getGuiLeft() + tabX + 13.0D;
        double clickY = screen.getGuiTop() + tabY + 16.0D;
        if (!screen.mouseClicked(clickX, clickY, 0)) {
            throw new IllegalStateException("原版创造物品栏未接受盲盒挑战标签页鼠标按下");
        }
        // 标签选择在原版 mouseClicked 内完成；仍发送匹配的鼠标松开事件，但 release 的返回值
        // 不表示标签是否已选中，下一 tick 会以屏幕菜单条目集合作为严格成功依据。
        screen.mouseReleased(clickX, clickY, 0);
        CiTestProbe.LOGGER.info("创造模式标签页 CI：已通过真实屏幕鼠标路径选择盲盒挑战标签页");
    }

    private static List<ResourceLocation> expectedItemIds() {
        List<ResourceLocation> result = ModItems.playerCreativeEntries().map(RegistryObject::getId).toList();
        if (result.size() != EXPECTED_ITEM_COUNT) {
            throw new IllegalStateException("创造模式标签页注册物品数异常：预期=" + EXPECTED_ITEM_COUNT + "，实际=" + result.size());
        }
        assertNoDuplicates(result, "生产注册物品集合");
        return result;
    }

    private static List<ResourceLocation> itemIds(Iterable<ItemStack> stacks, String label) {
        List<ResourceLocation> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) throw new IllegalStateException(label + " 包含空物品栈");
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) throw new IllegalStateException(label + " 包含未注册物品");
            result.add(id);
        }
        assertNoDuplicates(result, label);
        return result;
    }

    private static void assertExactItems(List<ResourceLocation> expected, List<ResourceLocation> actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " 与生产注册源不一致：expected=" + expected.size() + "，actual=" + actual.size()
                    + "，expected_first=" + expected.get(0) + "，actual_first=" + firstOrNone(actual)
                    + "，expected_last=" + expected.get(expected.size() - 1) + "，actual_last=" + lastOrNone(actual));
        }
    }

    private static void assertNoDuplicates(List<ResourceLocation> ids, String label) {
        Set<ResourceLocation> unique = new HashSet<>(ids);
        if (unique.size() != ids.size()) throw new IllegalStateException(label + " 存在重复条目：count=" + ids.size() + "，unique=" + unique.size());
    }

    private static String firstOrNone(List<ResourceLocation> ids) {
        return ids.isEmpty() ? "<none>" : ids.get(0).toString();
    }

    private static String lastOrNone(List<ResourceLocation> ids) {
        return ids.isEmpty() ? "<none>" : ids.get(ids.size() - 1).toString();
    }

    private static Path stageDirectory() {
        String configured = System.getProperty("blindbox.ci.creativeTabStageDir");
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    private static Path requiredMarker() {
        String configured = System.getProperty("blindbox.ci.creativeTabMarker");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 blindbox.ci.creativeTabMarker");
        return Path.of(configured).toAbsolutePath();
    }

    private static void writeMarker(Path marker, List<ResourceLocation> expected, List<ResourceLocation> tabItems,
                                    List<ResourceLocation> screenItems, LocalPlayer player) {
        int middle = expected.size() / 2;
        String value = "schema=1\n"
                + "observer_uuid=" + player.getUUID() + "\n"
                + "creative_ability=" + player.getAbilities().instabuild + "\n"
                + "screen=CreativeModeInventoryScreen\n"
                + "tab=blindboxchallenge:blind_box_challenge\n"
                + "expected_count=" + expected.size() + "\n"
                + "tab_count=" + tabItems.size() + "\n"
                + "screen_count=" + screenItems.size() + "\n"
                + "duplicates=false\n"
                + "order_matches=true\n"
                + "first=" + expected.get(0) + "\n"
                + "middle=" + expected.get(middle) + "\n"
                + "last=" + expected.get(expected.size() - 1) + "\n";
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException("创造模式标签页 marker 缺少父目录：" + marker);
            Files.createDirectories(parent);
            Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("无法原子写入创造模式标签页真实观察 marker：" + marker, exception);
        }
    }
}
