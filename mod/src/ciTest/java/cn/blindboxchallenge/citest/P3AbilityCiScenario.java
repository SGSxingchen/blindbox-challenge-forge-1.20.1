package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.capability.ModCapabilities;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.service.PlayerAbilityService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 009 的独立端到端场景。生产代码没有 CI 开关：此类只在 ciTest Jar 内调度真实书本、
 * 原版死亡/跨维命令和真实客户端 marker，再由服务端反查 Capability 与固定 UUID 属性。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P3AbilityCiScenario {
    private static final int DETRACK_SETTLE_TICKS = 60;
    private static final int TRACKING_SETTLE_TICKS = 20;
    private static ActiveScenario active;

    private P3AbilityCiScenario() {
    }

    public static int startClientPath(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P3 易筋经 CI 场景未收尾"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_ABILITY_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot start P3 ability CI scenario", exception);
            source.sendFailure(Component.literal("CI P3 易筋经场景启动失败：" + exception.getClass().getSimpleName()));
            active = null;
            return 0;
        }
    }

    public static int verifyClientPath(CommandSourceStack source) {
        return run(source, "BLINDBOX_CITEST_P3_ABILITY_CLIENTS=success", scenario -> scenario.verifyClientPath());
    }

    public static int startDeathClone(CommandSourceStack source) {
        return run(source, "BLINDBOX_CITEST_P3_ABILITY_CLONE_STARTED=success", scenario -> scenario.startDeathClone(source));
    }

    public static int startDimensionChange(CommandSourceStack source) {
        return run(source, "BLINDBOX_CITEST_P3_ABILITY_DIMENSION_STARTED=success", scenario -> scenario.startDimensionChange(source));
    }

    public static int verifyLifecycleClient(CommandSourceStack source) {
        return run(source, "BLINDBOX_CITEST_P3_ABILITY_LIFECYCLE_CLIENT=success", scenario -> scenario.verifyLifecycleClient());
    }

    public static int verifyAfterRecovery(CommandSourceStack source) {
        // SIGKILL 后 JVM 内的 active 必然丢失；恢复断言必须只信同世界重新读出的玩家事实和真实客户端 marker。
        if (active == null) {
            try {
                verifyRecoveredServerState(source.getServer());
                source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_ABILITY_RECOVERY=success"), false);
                return 1;
            } catch (Exception exception) {
                CiTestProbe.LOGGER.error("P3 ability SIGKILL recovery assertion failed", exception);
                source.sendFailure(Component.literal("CI P3 易筋经强杀恢复断言失败：" + exception.getClass().getSimpleName()));
                return 0;
            }
        }
        return run(source, "BLINDBOX_CITEST_P3_ABILITY_RECOVERY=success", scenario -> scenario.verifyAfterRecovery());
    }

    /** 恢复断言完成后才清理，避免既有重连夹具继承本场景的永久能力。 */
    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            try {
                // 强杀后的临时场景不会留在内存；本 CI 世界每次新建，立即复位为原版默认 false。
                ServerPlayer alice = player(source.getServer(), "BlindBoxAlice");
                resetAbility(alice);
                alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                source.getServer().getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(false, source.getServer());
                source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_ABILITY_CLEANUP=success"), false);
                return 1;
            } catch (Exception exception) {
                CiTestProbe.LOGGER.error("Cannot clean recovered P3 ability CI scenario", exception);
                source.sendFailure(Component.literal("CI P3 易筋经恢复场景清理失败：" + exception.getClass().getSimpleName()));
                return 0;
            }
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_ABILITY_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P3 ability CI scenario", exception);
            source.sendFailure(Component.literal("CI P3 易筋经场景清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    private static int run(CommandSourceStack source, String success, CheckedAction action) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可执行的 P3 易筋经 CI 场景"));
            return 0;
        }
        try {
            action.run(active);
            source.sendSuccess(() -> Component.literal(success), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("P3 ability CI action failed", exception);
            source.sendFailure(Component.literal("CI P3 易筋经断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (active != null) active.onClone(event);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (active != null) active.onDimensionChange(event);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (active != null) active.onStartTracking(event);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run(ActiveScenario scenario) throws Exception;
    }

    private enum Phase {
        WAITING_DETRACK,
        WAITING_FOR_CLIENT_KEY,
        WAITING_FOR_CLONE,
        CLONE_READY,
        WAITING_FOR_DIMENSION,
        DIMENSION_READY,
        FAILED
    }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final UUID aliceUuid;
        private final UUID bobUuid;
        private final ServerLevel origin;
        private final boolean originalImmediateRespawn;
        private final int initialAliceEntityId;
        private BlockPos aliceSupport;
        private BlockPos bobSupport;
        private BlockState originalAliceSupport;
        private BlockState originalBobSupport;
        private final Map<BlockPos, BlockState> originalLandingBlocks = new HashMap<>();
        private Phase phase = Phase.WAITING_DETRACK;
        private int phaseTicks;
        private boolean clientPathVerified;
        private boolean clientKeyAcceptedByServer;
        private boolean startTrackingEventSeen;
        private boolean cloneEventSeen;
        private boolean dimensionEventSeen;
        private String failure;

        private ActiveScenario(MinecraftServer server, ServerPlayer alice, ServerPlayer bob) {
            this.server = server;
            this.aliceUuid = alice.getUUID();
            this.bobUuid = bob.getUUID();
            this.origin = alice.serverLevel();
            this.initialAliceEntityId = alice.getId();
            this.originalImmediateRespawn = server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).get();
        }

        private static ActiveScenario create(MinecraftServer server) {
            ServerPlayer alice = player(server, "BlindBoxAlice");
            ServerPlayer bob = player(server, "BlindBoxBob");
            if (alice.serverLevel() != bob.serverLevel()) {
                throw new IllegalStateException("P3 易筋经客户端场景要求 Alice 与 Bob 初始同维度");
            }
            markerDirectory();
            ActiveScenario scenario = new ActiveScenario(server, alice, bob);
            scenario.setupClientPath(alice, bob);
            return scenario;
        }

        private void setupClientPath(ServerPlayer alice, ServerPlayer bob) {
            resetAbility(alice);
            // 先让 Bob 离开追踪范围并等待服务器实际撤销追踪，再学习；因此 Bob 的 true 快照只能来自随后真实 StartTracking。
            double x = Math.floor(alice.getX()) + 0.5D;
            double z = Math.floor(alice.getZ()) + 0.5D;
            // 采用短落差而非高空：平台移除后客户端有数 tick 的真实腾空时间触发 C2S，
            // 即使客户端未及时按键也会安全落地，不会再次触发专服 anti-fly 断线。
            aliceSupport = BlockPos.containing(x, 120.0D, z);
            bobSupport = BlockPos.containing(x + 512.0D, 120.0D, z);
            // 先让两名真实客户端站在高空临时平台，避免等待撤销追踪/同步时被专服的
            // anti-fly 机制踢出；只在学习后移除 Alice 平台以走合法的腾空 C2S 路径。
            originalAliceSupport = origin.getBlockState(aliceSupport);
            originalBobSupport = origin.getBlockState(bobSupport);
            origin.setBlock(aliceSupport, Blocks.STONE.defaultBlockState(), 3);
            origin.setBlock(bobSupport, Blocks.STONE.defaultBlockState(), 3);
            for (int dx = -1; dx <= 3; dx++) {
                for (int dz = -1; dz <= 3; dz++) {
                    BlockPos landing = aliceSupport.offset(dx, -8, dz);
                    originalLandingBlocks.put(landing, origin.getBlockState(landing));
                    origin.setBlock(landing, Blocks.STONE.defaultBlockState(), 3);
                }
            }
            alice.stopRiding();
            bob.stopRiding();
            alice.teleportTo(origin, x, 121.0D, z, 0.0F, 0.0F);
            alice.setDeltaMovement(0.0D, 0.0D, 0.0D);
            alice.hurtMarked = true;
            bob.teleportTo(origin, x + 512.0D, 121.0D, z, 0.0F, 0.0F);
            bob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            bob.hurtMarked = true;
        }

        private void tick() {
            if (phase == Phase.FAILED) return;
            tickAfterPhase();
            if (phase == Phase.CLONE_READY || phase == Phase.DIMENSION_READY) return;
            if (phase == Phase.WAITING_FOR_CLIENT_KEY) {
                player(server, "BlindBoxAlice").getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
                    if (data.hasUsedDoubleJump()) clientKeyAcceptedByServer = true;
                });
            }
            phaseTicks++;
            if (phase == Phase.WAITING_DETRACK && phaseTicks >= DETRACK_SETTLE_TICKS) {
                ServerPlayer alice = player(server, "BlindBoxAlice");
                releaseAliceForAirJump(alice);
                learnThroughProductionItem(alice);
                phase = Phase.WAITING_FOR_CLIENT_KEY;
                phaseTicks = 0;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_ABILITY_SYNC_DISPATCHED=success entity={}", initialAliceEntityId);
                return;
            }
            if (phase == Phase.WAITING_FOR_CLIENT_KEY && phaseTicks == TRACKING_SETTLE_TICKS) {
                ServerPlayer alice = player(server, "BlindBoxAlice");
                ServerPlayer bob = player(server, "BlindBoxBob");
                bob.teleportTo(origin, alice.getX() + 2.0D, alice.getY(), alice.getZ() + 2.0D, 0.0F, 0.0F);
                bob.setDeltaMovement(0.0D, 0.0D, 0.0D);
                bob.hurtMarked = true;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_ABILITY_TRACKING_REQUESTED=success target={}", initialAliceEntityId);
            }
        }

        private void verifyClientPath() throws IOException {
            if (phase != Phase.WAITING_FOR_CLIENT_KEY) {
                throw new IllegalStateException("客户端按键场景尚未进入等待状态：" + phase);
            }
            ServerPlayer alice = player(server, "BlindBoxAlice");
            requireLearned(alice);
            if (!clientKeyAcceptedByServer) {
                throw new IllegalStateException("服务端没有收到真实客户端二段跳 C2S 请求");
            }
            if (!startTrackingEventSeen) {
                throw new IllegalStateException("Bob 回到范围后未触发真实 PlayerEvent.StartTracking");
            }
            Path directory = markerDirectory();
            Map<String, String> aliceMarker = readMarker(directory.resolve("client-1-p3-ability-key.marker"), 7);
            if (!"1".equals(aliceMarker.get("schema")) || !"alice".equals(aliceMarker.get("role"))
                    || !aliceUuid.toString().equals(aliceMarker.get("self_uuid"))
                    || !Integer.toString(initialAliceEntityId).equals(aliceMarker.get("self_entity_id"))
                    || !"true".equals(aliceMarker.get("received_self_sync"))
                    || !"true".equals(aliceMarker.get("key_injected"))
                    || !"true".equals(aliceMarker.get("server_velocity_observed"))) {
                throw new IllegalStateException("Alice 真实 S2C/按键结果 marker 与服务端实体不一致");
            }
            Map<String, String> bobMarker = readMarker(directory.resolve("client-2-p3-ability-tracking.marker"), 6);
            if (!"1".equals(bobMarker.get("schema")) || !"bob".equals(bobMarker.get("role"))
                    || !bobUuid.toString().equals(bobMarker.get("self_uuid"))
                    || !aliceUuid.toString().equals(bobMarker.get("tracked_uuid"))
                    || !Integer.toString(initialAliceEntityId).equals(bobMarker.get("tracked_entity_id"))
                    || !"true".equals(bobMarker.get("received_tracking_sync"))) {
                throw new IllegalStateException("Bob 未从真实 StartTracking 路径观察到 Alice 能力快照");
            }
            clientPathVerified = true;
        }

        private void startDeathClone(CommandSourceStack source) {
            if (!clientPathVerified || phase != Phase.WAITING_FOR_CLIENT_KEY) {
                throw new IllegalStateException("必须先通过真实客户端 C2S 与 StartTracking 核验");
            }
            server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(true, server);
            phase = Phase.WAITING_FOR_CLONE;
            phaseTicks = 0;
            int result = server.getCommands().performPrefixedCommand(source.withPermission(4).withSuppressedOutput(), "kill BlindBoxAlice");
            if (result <= 0) throw new IllegalStateException("原版 kill 命令没有执行成功");
        }

        private void startDimensionChange(CommandSourceStack source) {
            if (phase != Phase.CLONE_READY) throw new IllegalStateException("死亡 Clone 尚未完成属性与能力复核");
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null) throw new IllegalStateException("专服没有下界维度");
            phase = Phase.WAITING_FOR_DIMENSION;
            phaseTicks = 0;
            int result = server.getCommands().performPrefixedCommand(source.withPermission(4).withLevel(nether).withSuppressedOutput(),
                    "tp BlindBoxAlice 0 128 0");
            if (result <= 0) throw new IllegalStateException("原版跨维 tp 命令没有执行成功");
        }

        private void onClone(PlayerEvent.Clone event) {
            if (phase != Phase.WAITING_FOR_CLONE || !(event.getEntity() instanceof ServerPlayer replacement)
                    || !aliceUuid.equals(replacement.getUUID()) || !event.isWasDeath()) return;
            cloneEventSeen = true;
        }

        private void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (phase != Phase.WAITING_FOR_DIMENSION || !(event.getEntity() instanceof ServerPlayer player)
                    || !aliceUuid.equals(player.getUUID()) || !Level.NETHER.equals(event.getTo())) return;
            dimensionEventSeen = true;
        }

        private void onStartTracking(PlayerEvent.StartTracking event) {
            if (phase != Phase.WAITING_FOR_CLIENT_KEY || !(event.getEntity() instanceof ServerPlayer observer)
                    || !(event.getTarget() instanceof ServerPlayer target)
                    || !bobUuid.equals(observer.getUUID()) || !aliceUuid.equals(target.getUUID())) return;
            startTrackingEventSeen = true;
        }

        private void verifyLifecycleClient() throws IOException {
            if (phase != Phase.DIMENSION_READY || !cloneEventSeen || !dimensionEventSeen) {
                throw new IllegalStateException("真实死亡 Clone 或跨维服务端断言尚未完成");
            }
            Map<String, String> marker = readMarker(markerDirectory().resolve("client-1-p3-ability-lifecycle.marker"), 8);
            if (!"1".equals(marker.get("schema")) || !"alice".equals(marker.get("role"))
                    || !aliceUuid.toString().equals(marker.get("self_uuid"))
                    || !"true".equals(marker.get("client_clone_event"))
                    || !"true".equals(marker.get("learned_after_clone"))
                    || !"minecraft:the_nether".equals(marker.get("dimension"))
                    || !"true".equals(marker.get("received_dimension_sync"))
                    || !"true".equals(marker.get("key_result_retained"))) {
                throw new IllegalStateException("Alice 未在真实 Clone/跨维后收到并观察能力同步");
            }
        }

        private void verifyAfterRecovery() throws IOException {
            ServerPlayer alice = player(server, "BlindBoxAlice");
            if (!Level.NETHER.equals(alice.level().dimension())) {
                throw new IllegalStateException("SIGKILL 重启后 Alice 未回到已保存的下界维度");
            }
            requireLearned(alice);
            Map<String, String> marker = readMarker(markerDirectory().resolve("client-1-p3-ability-recovered.marker"), 6);
            if (!"1".equals(marker.get("schema")) || !"alice".equals(marker.get("role"))
                    || !aliceUuid.toString().equals(marker.get("self_uuid"))
                    || !"minecraft:the_nether".equals(marker.get("dimension"))
                    || !"true".equals(marker.get("reconnected_after_server_kill"))
                    || !"true".equals(marker.get("received_recovery_sync"))) {
                throw new IllegalStateException("SIGKILL 后真实客户端能力同步 marker 与服务端状态不一致");
            }
        }

        private void onCloneOrDimensionReady() {
            ServerPlayer alice = player(server, "BlindBoxAlice");
            requireLearned(alice);
            if (phase == Phase.WAITING_FOR_CLONE && cloneEventSeen) {
                phase = Phase.CLONE_READY;
                phaseTicks = 0;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_ABILITY_CLONE=success entity={}", alice.getId());
            } else if (phase == Phase.WAITING_FOR_DIMENSION && dimensionEventSeen && Level.NETHER.equals(alice.level().dimension())) {
                phase = Phase.DIMENSION_READY;
                phaseTicks = 0;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_ABILITY_DIMENSION=success entity={}", alice.getId());
            }
        }

        private void learnThroughProductionItem(ServerPlayer alice) {
            ItemStack manual = new ItemStack(ModItems.YIJIN_MANUAL.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND, manual);
            InteractionResult result = manual.getItem().use(alice.serverLevel(), alice, InteractionHand.MAIN_HAND).getResult();
            if (!result.consumesAction() || !manual.isEmpty()) {
                throw new IllegalStateException("易筋经真实右键入口没有恰好消耗首本书");
            }
            requireLearned(alice);
            alice.containerMenu.broadcastChanges();
        }

        /** 平台只在客户端已完成稳定追踪后移除，留出小于 anti-fly 阈值的真实腾空窗口。 */
        private void releaseAliceForAirJump(ServerPlayer alice) {
            if (aliceSupport != null) origin.setBlock(aliceSupport, Blocks.AIR.defaultBlockState(), 3);
            alice.setOnGround(false);
            alice.setDeltaMovement(0.0D, -0.08D, 0.0D);
            alice.hurtMarked = true;
        }

        private void cleanup() {
            ServerPlayer alice = server.getPlayerList().getPlayer(aliceUuid);
            if (alice != null) {
                resetAbility(alice);
                alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                alice.containerMenu.broadcastChanges();
            }
            if (aliceSupport != null && originalAliceSupport != null) origin.setBlock(aliceSupport, originalAliceSupport, 3);
            if (bobSupport != null && originalBobSupport != null) origin.setBlock(bobSupport, originalBobSupport, 3);
            originalLandingBlocks.forEach((position, state) -> origin.setBlock(position, state, 3));
            server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(originalImmediateRespawn, server);
        }

        private void fail(Exception exception) {
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P3_ABILITY=failed {}", failure, exception);
        }

        private void tickAfterPhase() {
            if ((phase == Phase.WAITING_FOR_CLONE && cloneEventSeen)
                    || (phase == Phase.WAITING_FOR_DIMENSION && dimensionEventSeen)) {
                onCloneOrDimensionReady();
            }
        }
    }

    private static ServerPlayer player(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalStateException(name + " 不在线");
        return player;
    }

    private static void resetAbility(ServerPlayer player) {
        player.getCapability(ModCapabilities.PLAYER_ABILITY).ifPresent(data -> {
            data.setLearnedYiJin(false);
            data.setUsedDoubleJump(false);
            data.setNextDoubleJumpTick(0L);
            PlayerAbilityService.reconcileAttributes(player, data);
            PlayerAbilityService.syncTrackingAndSelf(player, data);
        });
    }

    private static void requireLearned(ServerPlayer player) {
        var data = player.getCapability(ModCapabilities.PLAYER_ABILITY).resolve()
                .orElseThrow(() -> new IllegalStateException("P3 玩家 Capability 缺失"));
        if (!data.hasLearnedYiJin()) throw new IllegalStateException("易筋经永久能力未保留");
        var health = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        var attack = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (health == null || attack == null
                || health.getModifier(PlayerAbilityService.YIJIN_MAX_HEALTH_UUID) == null
                || attack.getModifier(PlayerAbilityService.YIJIN_ATTACK_DAMAGE_UUID) == null) {
            throw new IllegalStateException("易筋经固定 UUID 属性没有从 Capability 对账恢复");
        }
    }

    private static void verifyRecoveredServerState(MinecraftServer server) throws IOException {
        ServerPlayer alice = player(server, "BlindBoxAlice");
        if (!Level.NETHER.equals(alice.level().dimension())) {
            throw new IllegalStateException("SIGKILL 重启后 Alice 未回到已保存的下界维度");
        }
        requireLearned(alice);
        Map<String, String> marker = readMarker(markerDirectory().resolve("client-1-p3-ability-recovered.marker"), 6);
        if (!"1".equals(marker.get("schema")) || !"alice".equals(marker.get("role"))
                || !alice.getUUID().toString().equals(marker.get("self_uuid"))
                || !"minecraft:the_nether".equals(marker.get("dimension"))
                || !"true".equals(marker.get("reconnected_after_server_kill"))
                || !"true".equals(marker.get("received_recovery_sync"))) {
            throw new IllegalStateException("SIGKILL 后真实客户端能力同步 marker 与服务端状态不一致");
        }
    }

    private static Path markerDirectory() {
        String configured = System.getenv("BLINDBOX_CITEST_ABILITY_MARKER_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("缺少受控的 BLINDBOX_CITEST_ABILITY_MARKER_DIR");
        }
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) throw new IllegalStateException("P3 能力 marker 目录不存在：" + directory);
        return directory;
    }

    private static Map<String, String> readMarker(Path marker, int expectedFields) throws IOException {
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少客户端真实能力 marker：" + marker);
        Map<String, String> fields = new HashMap<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("客户端能力 marker 格式非法：" + marker);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (key.isBlank() || value.isBlank() || fields.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("客户端能力 marker 有空字段或重复字段：" + marker);
            }
        }
        if (fields.size() != expectedFields) {
            throw new IllegalStateException("客户端能力 marker 字段数量不正确：" + marker);
        }
        return fields;
    }
}
