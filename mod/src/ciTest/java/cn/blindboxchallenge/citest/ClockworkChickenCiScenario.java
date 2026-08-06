package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.entity.ClockworkChickenEntity;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * P4 发条小黄鸡的独立动态夹具：生产 {@code Item#use} 武装、两个真实客户端跟踪同一实体和 Fuse，
 * 随后等待默认 60 秒倒计时的真实 TNT 语义爆炸。marker 只记录客户端实际收到的实体 UUID，服务端会反查。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClockworkChickenCiScenario {
    private static ActiveScenario active;

    private ClockworkChickenCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有发条小黄鸡场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_CHICKEN_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P4 clockwork chicken scenario", exception);
            source.sendFailure(Component.literal("CI 发条小黄鸡场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verify(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的发条小黄鸡场景"));
            return 0;
        }
        try {
            active.verify();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_CHICKEN=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P4 clockwork chicken scenario", exception);
            source.sendFailure(Component.literal("CI 发条小黄鸡断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的发条小黄鸡场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_CHICKEN_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P4 clockwork chicken scenario", exception);
            source.sendFailure(Component.literal("CI 发条小黄鸡清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    @SubscribeEvent
    public static void explosion(ExplosionEvent.Detonate event) {
        if (active != null && !event.getLevel().isClientSide) active.observeExplosion(event);
    }

    private enum Phase { OBSERVING, WAITING_FOR_EXPLOSION, READY, FAILED }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final ItemStack aliceOriginalHand;
        private final Vec3 aliceOriginalPosition;
        private final Vec3 bobOriginalPosition;
        private final float aliceYaw;
        private final float alicePitch;
        private final float bobYaw;
        private final float bobPitch;
        private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        private final Path aliceMarker;
        private final Path bobMarker;
        private final int armedFuse;
        private final int armedPower;
        private final long startedAt;
        private UUID chickenId;
        private int explosions;
        private Phase phase = Phase.OBSERVING;

        private ActiveScenario(MinecraftServer server, ServerLevel level, ServerPlayer alice, ServerPlayer bob,
                               Path aliceMarker, Path bobMarker) {
            this.server = server;
            this.level = level;
            this.alice = alice;
            this.bob = bob;
            this.aliceOriginalHand = alice.getMainHandItem().copy();
            this.aliceOriginalPosition = alice.position();
            this.bobOriginalPosition = bob.position();
            this.aliceYaw = alice.getYRot();
            this.alicePitch = alice.getXRot();
            this.bobYaw = bob.getYRot();
            this.bobPitch = bob.getXRot();
            this.aliceMarker = aliceMarker;
            this.bobMarker = bobMarker;
            this.armedFuse = ModServerConfig.CLOCKWORK_CHICKEN_FUSE_TICKS.get();
            this.armedPower = ModServerConfig.CLOCKWORK_CHICKEN_EXPLOSION_POWER.get();
            this.startedAt = level.getGameTime();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            ServerPlayer alice = requiredPlayer(server, "BlindBoxAlice");
            ServerPlayer bob = requiredPlayer(server, "BlindBoxBob");
            Path directory = markerDirectory();
            Path aliceMarker = directory.resolve("client-1-p4-chicken-observed.marker");
            Path bobMarker = directory.resolve("client-2-p4-chicken-observed.marker");
            if (Files.exists(aliceMarker) || Files.exists(bobMarker)) {
                throw new IllegalStateException("发条小黄鸡客户端 marker 已存在，拒绝复用旧结果");
            }
            ActiveScenario scenario = new ActiveScenario(server, server.overworld(), alice, bob, aliceMarker, bobMarker);
            scenario.armThroughProductionItemUse();
            return scenario;
        }

        private void armThroughProductionItemUse() {
            // 在生成点旁的临时黑曜石平台执行真实生产 Item#use；二人随后由真实客户端接收该实体的同步包。
            BlockPos platform = level.getSharedSpawnPos().offset(28, 8, 0);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) rememberAndSet(platform.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState());
            }
            alice.teleportTo(level, platform.getX() + 0.5D, platform.getY() + 1.0D, platform.getZ() + 0.5D, 0.0F, 0.0F);
            bob.teleportTo(level, platform.getX() + 3.5D, platform.getY() + 1.0D, platform.getZ() + 0.5D, 0.0F, 0.0F);
            ItemStack chickenStack = new ItemStack(ModItems.CLOCKWORK_CHICKEN.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND, chickenStack);
            alice.containerMenu.broadcastChanges();
            if (!ModItems.CLOCKWORK_CHICKEN.get().use(level, alice, InteractionHand.MAIN_HAND).getResult().consumesAction()) {
                throw new IllegalStateException("生产小黄鸡 Item#use 未消耗操作");
            }
            if (!alice.getAbilities().instabuild && !chickenStack.isEmpty()) {
                throw new IllegalStateException("生存模式小黄鸡在成功生成后未扣除一件");
            }
            var chickens = level.getEntitiesOfClass(ClockworkChickenEntity.class,
                    new AABB(alice.position().add(-2.0D, -2.0D, -2.0D), alice.position().add(2.0D, 3.0D, 2.0D)));
            if (chickens.size() != 1) throw new IllegalStateException("生产 Item#use 未生成唯一小黄鸡实体");
            ClockworkChickenEntity chicken = chickens.get(0);
            if (!alice.getUUID().equals(chicken.ownerUuid()) || chicken.armedGameTime() != startedAt
                    || chicken.getFuse() != armedFuse || chicken.explosionPower() != armedPower) {
                throw new IllegalStateException("小黄鸡武装字段与服务端配置不一致");
            }
            chickenId = chicken.getUUID();
        }

        private void tick() throws IOException {
            if (phase == Phase.FAILED || phase == Phase.READY) return;
            long now = level.getGameTime();
            if (phase == Phase.OBSERVING) {
                if (Files.isRegularFile(aliceMarker) && Files.isRegularFile(bobMarker)) {
                    verifyObservationMarkers();
                    if (!(level.getEntity(chickenId) instanceof ClockworkChickenEntity chicken)
                            || chicken.getFuse() <= 0 || chicken.getFuse() >= armedFuse) {
                        throw new IllegalStateException("客户端观察前小黄鸡 Fuse 未按真实服务端 tick 递减");
                    }
                    // 两人已实际跟踪同一实体后才移至爆炸安全距离，绝不由脚本预写观察结果。
                    BlockPos safe = level.getSharedSpawnPos().offset(-24, 3, 0);
                    alice.teleportTo(level, safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D, 0.0F, 0.0F);
                    bob.teleportTo(level, safe.getX() + 3.5D, safe.getY(), safe.getZ() + 0.5D, 0.0F, 0.0F);
                    phase = Phase.WAITING_FOR_EXPLOSION;
                } else if (now - startedAt > 240L) {
                    throw new IllegalStateException("两个真实客户端未在观察窗口内同步小黄鸡实体");
                }
            }
            if (phase == Phase.WAITING_FOR_EXPLOSION) {
                if (explosions > 1) throw new IllegalStateException("同一小黄鸡触发了多次爆炸");
                if (explosions == 1) {
                    phase = Phase.READY;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_CHICKEN_SERVER=success");
                } else if (now - startedAt > armedFuse + 160L) {
                    throw new IllegalStateException("默认倒计时结束后未观察到小黄鸡 TNT 爆炸");
                }
            }
        }

        private void observeExplosion(ExplosionEvent.Detonate event) {
            if (phase != Phase.WAITING_FOR_EXPLOSION || chickenId == null) return;
            if (!(event.getExplosion().getExploder() instanceof ClockworkChickenEntity chicken)
                    || !chickenId.equals(chicken.getUUID())) return;
            if (chicken.explosionPower() != armedPower) {
                fail(new IllegalStateException("爆炸时小黄鸡未保留武装威力"));
                return;
            }
            explosions++;
        }

        private void verify() throws IOException {
            if (phase != Phase.READY || explosions != 1) {
                throw new IllegalStateException("小黄鸡服务端业务尚未完成：" + phase + "，爆炸次数=" + explosions);
            }
            verifyObservationMarkers();
            if (armedFuse != 1200 || armedPower != 8) {
                throw new IllegalStateException("Hosted Runner 默认小黄鸡配置不是 60 秒/Fuse 1200 与 TNT 威力 8");
            }
        }

        private void verifyObservationMarkers() throws IOException {
            Map<String, String> aliceValues = markerValues(aliceMarker);
            Map<String, String> bobValues = markerValues(bobMarker);
            String expectedId = chickenId.toString();
            if (!expectedId.equals(aliceValues.get("chicken")) || !expectedId.equals(bobValues.get("chicken"))
                    || !alice.getUUID().toString().equals(aliceValues.get("observer_uuid"))
                    || !bob.getUUID().toString().equals(bobValues.get("observer_uuid"))) {
                throw new IllegalStateException("两个客户端未实际观察到同一只服务端小黄鸡");
            }
            int aliceFuse = Integer.parseInt(aliceValues.getOrDefault("fuse", "-1"));
            int bobFuse = Integer.parseInt(bobValues.getOrDefault("fuse", "-1"));
            if (aliceFuse <= 0 || aliceFuse > armedFuse || bobFuse <= 0 || bobFuse > armedFuse) {
                throw new IllegalStateException("客户端 marker 缺少已同步的 Fuse");
            }
        }

        private void cleanup() {
            if (chickenId != null && level.getEntity(chickenId) instanceof ClockworkChickenEntity chicken) chicken.discard();
            originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
            alice.setItemInHand(InteractionHand.MAIN_HAND, aliceOriginalHand.copy());
            alice.teleportTo(level, aliceOriginalPosition.x, aliceOriginalPosition.y, aliceOriginalPosition.z, aliceYaw, alicePitch);
            bob.teleportTo(level, bobOriginalPosition.x, bobOriginalPosition.y, bobOriginalPosition.z, bobYaw, bobPitch);
            alice.containerMenu.broadcastChanges();
        }

        private void rememberAndSet(BlockPos pos, BlockState state) {
            originalBlocks.putIfAbsent(pos, level.getBlockState(pos));
            level.setBlock(pos, state, 3);
        }

        private void fail(Exception exception) {
            if (phase == Phase.FAILED) return;
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P4_CHICKEN=failed", exception);
        }

        private static ServerPlayer requiredPlayer(MinecraftServer server, String name) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) throw new IllegalStateException("发条小黄鸡探针缺少玩家：" + name);
            return player;
        }

        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_P4_MARKER_DIR");
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 P4 marker 目录环境变量");
            return Path.of(configured).toAbsolutePath();
        }

        private static Map<String, String> markerValues(Path marker) throws IOException {
            if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少真实客户端小黄鸡 marker：" + marker);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
            }
            if (!"1".equals(values.get("schema"))) throw new IllegalStateException("小黄鸡 marker schema 不正确");
            return values;
        }
    }
}
