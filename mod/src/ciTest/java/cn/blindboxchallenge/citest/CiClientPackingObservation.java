package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.client.PackingScreen;
import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.util.StackFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅 ciTest：真实右键打开生产打包页，以鼠标选择数量并点击生产确认按钮，不直接构造业务包。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientPackingObservation {
    private enum Phase { FIRST_USE, FIRST_SCREEN, FIRST_SELECTION, FIRST_CLOSE, SECOND_USE, SECOND_SCREEN, SECOND_SELECTION, SECOND_CLOSE, DONE, FAILED }
    private static Phase phase = Phase.FIRST_USE;
    private static UUID firstSession;
    private static UUID secondSession;
    private static List<String> beforeSelection;
    private static int action;
    private static int delay;
    private static int ticks;

    private CiClientPackingObservation() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || phase == Phase.DONE || phase == Phase.FAILED) return;
        String configured = System.getProperty("blindbox.ci.packingMarker");
        String stageDirectory = System.getProperty("blindbox.ci.packingStageDir");
        if (configured == null || configured.isBlank() || stageDirectory == null || stageDirectory.isBlank()) return;
        if (!Files.isRegularFile(Path.of(stageDirectory).toAbsolutePath().resolve("packing-enabled.flag"))) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) return;
        try {
            if (++ticks > 1600) throw new IllegalStateException("打包客户端操作超时：" + phase + "，" + entranceState(minecraft, player));
            if (ticks % 100 == 0 && (phase == Phase.FIRST_USE || phase == Phase.SECOND_USE)) {
                CiTestProbe.LOGGER.info("打包真实入口等待，阶段={}，{}", phase, entranceState(minecraft, player));
            }
            switch (phase) {
                case FIRST_USE -> {
                    if (minecraft.screen == null && player.getMainHandItem().is(ModItems.PACKING_TOOL.get())
                            && player.getInventory().getItem(9).is(Items.DIAMOND)
                            && player.getInventory().getItem(9).getCount() == 8) {
                        KeyMapping.click(minecraft.options.keyUse.getKey());
                        phase = Phase.FIRST_SCREEN;
                    }
                }
                case FIRST_SCREEN, SECOND_SCREEN -> {
                    if (minecraft.screen instanceof PackingScreen screen) {
                        UUID session = baseScreen(screen).getMenu().sessionId();
                        if (phase == Phase.FIRST_SCREEN) firstSession = session;
                        else {
                            secondSession = session;
                            if (secondSession.equals(firstSession)) throw new IllegalStateException("失败后重开的打包会话没有更新");
                        }
                        beforeSelection = inventoryEvidence(player);
                        if (packButton(screen).active) throw new IllegalStateException("未选择物品时打包按钮已经可用");
                        action = 0;
                        delay = 0;
                        phase = phase == Phase.FIRST_SCREEN ? Phase.FIRST_SELECTION : Phase.SECOND_SELECTION;
                    }
                }
                case FIRST_SELECTION, SECOND_SELECTION -> {
                    if (!(minecraft.screen instanceof PackingScreen screen)) throw new IllegalStateException("选择期间打包界面提前关闭");
                    // 分隔鼠标事件，避免把两次左键切换误变成原版的快速双击操作。
                    if (++delay < 10) return;
                    delay = 0;
                    switch (action++) {
                        case 0 -> {
                            clickSlot(screen, 0, 0);
                            if (packButton(screen).active) throw new IllegalStateException("打包工具被当成可选择奖品");
                        }
                        case 1 -> clickSlot(screen, 9, 0);
                        case 2, 3 -> clickSlot(screen, 9, 1);
                        case 4, 5 -> clickSlot(screen, 10, 0);
                        case 6 -> clickSlot(screen, 11, 0);
                        case 7 -> clickSlot(screen, 11, 1);
                        case 8 -> {
                            if (!beforeSelection.equals(inventoryEvidence(player))
                                    || !((AbstractContainerMenu) baseScreen(screen).getMenu()).getCarried().isEmpty()) {
                                throw new IllegalStateException("打包选择移动了真实库存或鼠标携带物");
                            }
                            Button button = packButton(screen);
                            if (!button.active) throw new IllegalStateException("有效数量选择不能提交");
                            baseScreen(screen).mouseClicked(button.getX() + button.getWidth() / 2.0D, button.getY() + button.getHeight() / 2.0D, 0);
                            baseScreen(screen).mouseReleased(button.getX() + button.getWidth() / 2.0D, button.getY() + button.getHeight() / 2.0D, 0);
                            phase = phase == Phase.FIRST_SELECTION ? Phase.FIRST_CLOSE : Phase.SECOND_CLOSE;
                        }
                        default -> throw new IllegalStateException("打包鼠标操作序号超限");
                    }
                }
                case FIRST_CLOSE -> {
                    if (minecraft.screen == null) phase = Phase.SECOND_USE;
                }
                case SECOND_USE -> {
                    if (minecraft.screen == null && player.getInventory().getItem(1).isEmpty()
                            && player.getMainHandItem().is(ModItems.PACKING_TOOL.get())) {
                        KeyMapping.click(minecraft.options.keyUse.getKey());
                        phase = Phase.SECOND_SCREEN;
                    }
                }
                case SECOND_CLOSE -> {
                    if (minecraft.screen == null && player.getInventory().getItem(1).is(ModItems.BLIND_BOX.get())) {
                        Path marker = Path.of(configured).toAbsolutePath();
                        Files.createDirectories(marker.getParent());
                        Files.writeString(marker, "schema=1\nobserver_uuid=" + player.getUUID()
                                + "\nfirst_session=" + firstSession + "\nsecond_session=" + secondSession
                                + "\nproduction_screen_observed_twice=true\nreal_use_key_injected_twice=true"
                                + "\nleft_select_and_cancel=true\nright_decrease=true\nselection_inventory_unchanged=true"
                                + "\nconfirm_clicked_twice=true\nfailure_close_observed=true\nreopened_after_failure=true"
                                + "\nsuccess_close_observed=true\n", StandardCharsets.UTF_8);
                        phase = Phase.DONE;
                    }
                }
                default -> { }
            }
        } catch (Exception | LinkageError exception) {
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_PACKING_CLIENT=failed", exception);
        }
    }

    private static List<String> inventoryEvidence(LocalPlayer player) {
        return java.util.stream.IntStream.range(0, 36)
                .mapToObj(slot -> StackFingerprint.of(player.getInventory().getItem(slot))).toList();
    }

    private static String entranceState(Minecraft minecraft, LocalPlayer player) {
        var main = player.getMainHandItem();
        var prize = player.getInventory().getItem(9);
        return "屏幕=" + (minecraft.screen == null ? "无" : minecraft.screen.getClass().getSimpleName())
                + "，菜单=" + player.containerMenu.getClass().getSimpleName()
                + "，背包菜单=" + (player.containerMenu == player.inventoryMenu)
                + "，手持=" + main.getDescriptionId() + "×" + main.getCount()
                + "，第9格=" + prize.getDescriptionId() + "×" + prize.getCount();
    }

    private static Button packButton(PackingScreen screen) {
        return baseScreen(screen).children().stream().filter(child -> child instanceof Button).map(child -> (Button) child)
                .findFirst().orElseThrow(() -> new IllegalStateException("生产打包页缺少确认按钮"));
    }

    private static void clickSlot(PackingScreen screen, int inventorySlot, int button) {
        AbstractContainerScreen<PackingMenu> base = baseScreen(screen);
        AbstractContainerMenu menu = base.getMenu();
        Slot slot = menu.slots.stream().filter(value -> value.getSlotIndex() == inventorySlot).findFirst().orElseThrow();
        double x = (base.width - 176) / 2 + slot.x + 8;
        double y = (base.height - 166) / 2 + slot.y + 8;
        base.mouseClicked(x, y, button);
        base.mouseReleased(x, y, button);
    }

    /** 探针 Jar 不含生产类；经 Minecraft 父类访问继承成员，保证正式映射正确且仍虚调用生产屏幕。 */
    private static AbstractContainerScreen<PackingMenu> baseScreen(PackingScreen screen) { return screen; }
}
