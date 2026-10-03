package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.service.BlindBoxService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅 ciTest：等待真实生产菜单/C2S，两轮分别验证业务失败零变更、重开后选择数量及奖池守恒。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PackingCiScenario {
    private static ActiveScenario active;
    private static final String FIXTURE_KEY = "blindbox_citest_packing_fixture";

    private PackingCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有打包真实界面场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_PACKING_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("无法启动打包真实界面场景", exception);
            source.sendFailure(Component.literal("打包场景启动失败：" + exception.getMessage()));
            return 0;
        }
    }

    public static int verify(CommandSourceStack source) {
        try {
            if (active == null) throw new IllegalStateException("没有运行中的打包场景");
            active.verify();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_PACKING_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("打包真实界面断言失败", exception);
            source.sendFailure(Component.literal("打包断言失败：" + exception.getMessage()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        try {
            if (active == null) throw new IllegalStateException("没有可归还的打包夹具");
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_PACKING_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("打包夹具归还失败", exception);
            source.sendFailure(Component.literal("打包夹具归还失败：" + exception.getMessage()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.phase = Phase.FAILED;
            active.failure = exception.getMessage();
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_PACKING_SERVER=failed", exception);
        }
    }

    private enum Phase { FIRST_SCREEN, FIRST_CLOSE, SECOND_SCREEN, SECOND_CLOSE, READY, FAILED }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerPlayer player;
        private final BlindBoxPoolSavedData data;
        private final Path marker;
        private final Path stageFlag;
        private final UUID fixture = UUID.randomUUID();
        private final List<ItemStack> originalMain = new ArrayList<>();
        private final List<ItemStack> fixtureMain = new ArrayList<>();
        private final Map<UUID, CompoundTag> originalBundles = new LinkedHashMap<>();
        private final Set<UUID> originalTransactions = new HashSet<>();
        private final int originalSelected;
        private final long startedAt;
        private PackingMenu firstMenu;
        private PackingMenu secondMenu;
        private Phase phase = Phase.FIRST_SCREEN;
        private String failure;

        private ActiveScenario(MinecraftServer server, ServerPlayer player, Path marker, Path stageFlag) {
            this.server = server;
            this.player = player;
            this.marker = marker;
            this.stageFlag = stageFlag;
            this.data = BlindBoxPoolSavedData.get(server.overworld());
            this.originalSelected = player.getInventory().selected;
            this.startedAt = server.overworld().getGameTime();
            for (int slot = 0; slot < 36; slot++) originalMain.add(player.getInventory().getItem(slot).copy());
            for (PrizeBundle bundle : data.bundles()) originalBundles.put(bundle.id(), bundle.save());
            for (TransactionRecord record : data.transactions()) originalTransactions.add(record.id());
        }

        private static ActiveScenario create(MinecraftServer server) throws Exception {
            ServerPlayer player = server.getPlayerList().getPlayerByName("BlindBoxAlice");
            if (player == null || !player.isAlive() || player.isSpectator()) throw new IllegalStateException("缺少存活的非旁观玩家 BlindBoxAlice");
            if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
                throw new IllegalStateException("玩家须先关闭菜单并清空鼠标携带物");
            }
            Path marker = configuredPath("blindbox.ci.packingMarker");
            Path stageFlag = configuredPath("blindbox.ci.packingStageDir").resolve("packing-enabled.flag");
            if (Files.exists(marker) || Files.exists(stageFlag)) throw new IllegalStateException("打包 marker 或阶段旗标已存在，拒绝复用旧结果");
            ActiveScenario scenario = new ActiveScenario(server, player, marker, stageFlag);
            try {
                scenario.prepareInventory();
                Files.createDirectories(stageFlag.getParent());
                Files.writeString(stageFlag, "observer_uuid=" + player.getUUID() + "\n", StandardCharsets.UTF_8);
                return scenario;
            } catch (Exception exception) {
                scenario.cleanup();
                throw exception;
            }
        }

        private void prepareInventory() {
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            player.getInventory().setItem(0, new ItemStack(ModItems.PACKING_TOOL.get()));
            player.getInventory().setItem(9, prize(Items.DIAMOND, 8));
            player.getInventory().setItem(10, prize(Items.EMERALD, 5));
            player.getInventory().setItem(11, prize(Items.GOLD_INGOT, 6));
            for (int slot = 0; slot < 36; slot++) fixtureMain.add(player.getInventory().getItem(slot).copy());
            player.getInventory().selected = 0;
            player.containerMenu.broadcastChanges();
            player.connection.send(new ClientboundSetCarriedItemPacket(0));
        }

        private ItemStack prize(Item item, int count) {
            ItemStack stack = new ItemStack(item, count);
            stack.getOrCreateTag().putUUID(FIXTURE_KEY, fixture);
            return stack;
        }

        private void tick() {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            if (server.overworld().getGameTime() - startedAt > 1600L) throw new IllegalStateException("打包场景超时：" + phase);
            if (!player.isAlive() || server.getPlayerList().getPlayer(player.getUUID()) != player) throw new IllegalStateException("打包观察玩家死亡或离线");
            switch (phase) {
                case FIRST_SCREEN -> {
                    if (player.containerMenu instanceof PackingMenu menu) {
                        firstMenu = menu;
                        phase = Phase.FIRST_CLOSE;
                    }
                }
                case FIRST_CLOSE -> {
                    if (player.containerMenu == player.inventoryMenu) {
                        require(firstMenu.submissionConsumed(), "首轮未通过生产 C2S 消费会话");
                        assertMain(fixtureMain);
                        assertOriginalBundles();
                        require(data.bundleCount() == originalBundles.size(), "失败打包改变了奖池数量");
                        require(data.transactions().size() == originalTransactions.size(), "失败打包建立了多余事务");
                        // 首轮只选部分堆叠，没有空格接收盲盒；确认零变化后只释放一个夹具填充格。
                        player.getInventory().setItem(1, ItemStack.EMPTY);
                        player.containerMenu.broadcastChanges();
                        phase = Phase.SECOND_SCREEN;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_PACKING_FAILURE_UNCHANGED=success");
                    }
                }
                case SECOND_SCREEN -> {
                    if (player.containerMenu instanceof PackingMenu menu) {
                        require(!menu.sessionId().equals(firstMenu.sessionId()), "重开的打包菜单沿用了旧会话");
                        secondMenu = menu;
                        phase = Phase.SECOND_CLOSE;
                    }
                }
                case SECOND_CLOSE -> {
                    if (player.containerMenu == player.inventoryMenu) {
                        require(secondMenu.submissionConsumed(), "第二轮未通过生产 C2S 消费会话");
                        assertSuccess();
                        phase = Phase.READY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_PACKING_SERVER=success");
                    }
                }
                default -> { }
            }
        }

        private void assertSuccess() {
            assertOriginalBundles();
            List<PrizeBundle> added = data.bundles().stream().filter(bundle -> !originalBundles.containsKey(bundle.id())).toList();
            require(data.bundleCount() == originalBundles.size() + 1 && added.size() == 1, "打包没有恰好加入一个完整奖项");
            PrizeBundle bundle = added.get(0);
            require(bundle.creator().equals(player.getUUID()) && bundle.stacks().size() == 2, "奖项创建者或所选种类错误");
            require(same(bundle.stacks().get(0), prize(Items.DIAMOND, 6)), "钻石选择数量或 NBT 未保持");
            require(same(bundle.stacks().get(1), prize(Items.GOLD_INGOT, 5)), "金锭选择数量或 NBT 未保持");
            List<TransactionRecord> addedTransactions = data.transactions().stream().filter(record -> !originalTransactions.contains(record.id())).toList();
            require(addedTransactions.size() == 1, "有效提交没有恰好建立一次事务");
            TransactionRecord transaction = addedTransactions.get(0);
            require(transaction.kind() == TransactionRecord.Kind.PACK && transaction.stage() == TransactionRecord.Stage.COMMITTED
                    && transaction.playerId().equals(player.getUUID()) && transaction.bundleId().equals(bundle.id())
                    && transaction.payload().save().equals(bundle.save()), "打包事务与奖项不一致或未提交");
            ItemStack box = player.getInventory().getItem(1);
            require(box.is(ModItems.BLIND_BOX.get()) && box.getCount() == 1 && box.hasTag()
                    && box.getTag().hasUUID(BlindBoxService.TOKEN_KEY)
                    && box.getTag().getUUID(BlindBoxService.TOKEN_KEY).equals(transaction.tokenId()), "盲盒收据 token 或数量不一致");
            List<ItemStack> expected = fixtureMain.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            expected.set(1, box.copy());
            expected.set(9, prize(Items.DIAMOND, 2));
            expected.set(11, prize(Items.GOLD_INGOT, 1));
            assertMain(expected);
            for (Item item : List.of(Items.DIAMOND, Items.EMERALD, Items.GOLD_INGOT)) {
                int before = fixtureMain.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
                int after = player.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum()
                        + bundle.stacks().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
                require(before == after, "背包与奖池合计不守恒：" + item);
            }
        }

        private void verify() throws Exception {
            require(phase == Phase.READY, "服务端打包流程尚未通过：" + phase + (failure == null ? "" : "/" + failure));
            assertSuccess();
            require(Files.isRegularFile(marker), "缺少真实打包界面 marker");
            String text = Files.readString(marker, StandardCharsets.UTF_8);
            for (String field : List.of("schema=1", "observer_uuid=" + player.getUUID(), "first_session=" + firstMenu.sessionId(),
                    "second_session=" + secondMenu.sessionId(), "production_screen_observed_twice=true", "real_use_key_injected_twice=true",
                    "left_select_and_cancel=true", "right_decrease=true", "selection_inventory_unchanged=true", "confirm_clicked_twice=true",
                    "failure_close_observed=true", "reopened_after_failure=true", "success_close_observed=true")) {
                require(text.lines().anyMatch(field::equals), "打包 marker 缺少字段：" + field);
            }
        }

        private void assertMain(List<ItemStack> expected) {
            for (int slot = 0; slot < 36; slot++) require(same(player.getInventory().getItem(slot), expected.get(slot)), "真实背包第 " + slot + " 格不符");
            require(player.containerMenu.getCarried().isEmpty(), "打包后鼠标携带物不为空");
        }

        private void assertOriginalBundles() {
            for (Map.Entry<UUID, CompoundTag> entry : originalBundles.entrySet()) {
                require(data.bundle(entry.getKey()).map(bundle -> entry.getValue().equals(bundle.save())).orElse(false), "原有奖项被修改或删除");
            }
        }

        private void cleanup() throws Exception {
            player.closeContainer();
            for (PrizeBundle bundle : data.bundles()) {
                if (!originalBundles.containsKey(bundle.id()) && bundle.stacks().stream().anyMatch(stack -> stack.hasTag()
                        && stack.getTag().hasUUID(FIXTURE_KEY) && stack.getTag().getUUID(FIXTURE_KEY).equals(fixture))) {
                    require(data.removeReservedBundle(bundle.id(), UUID.randomUUID()), "夹具奖项仍被其他开盒事务占用");
                }
            }
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, originalMain.get(slot).copy());
            player.getInventory().selected = originalSelected;
            player.containerMenu.broadcastChanges();
            player.connection.send(new ClientboundSetCarriedItemPacket(originalSelected));
            assertOriginalBundles();
            require(data.bundleCount() == originalBundles.size(), "归还夹具后奖池数量不一致");
            Files.deleteIfExists(stageFlag);
            // 已提交事务保留在独立 CI 世界中作审计证据，生产接口不删除历史记录。
        }

        private static boolean same(ItemStack first, ItemStack second) { return first.save(new CompoundTag()).equals(second.save(new CompoundTag())); }
        private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
        private static Path configuredPath(String property) {
            String configured = System.getProperty(property);
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少启动参数 " + property);
            return Path.of(configured).toAbsolutePath();
        }
    }
}
