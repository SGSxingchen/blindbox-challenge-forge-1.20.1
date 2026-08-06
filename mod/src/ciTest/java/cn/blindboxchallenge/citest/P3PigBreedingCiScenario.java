package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.common.Mod;

/**
 * 011 高效养猪技术的双客户端专项场景。
 *
 * <p>它只会由 ciTest 命令显式启动：服务端先创建两只可见父猪，再通过正式书本的
 * {@link net.minecraft.world.item.Item#use} 入口繁殖。两个客户端只能从各自收到的实体跟踪包中
 * 取得 UUID；服务端随后逐字段回查 marker，绝不把预写文件或单纯文件存在当成通过。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P3PigBreedingCiScenario {
    private static final int OBSERVATION_TICKS = 60;
    /** 只用于让客户端在同一批真实跟踪实体中识别 CI 夹具，不携带或编码 UUID。 */
    public static final String PARENT_ONE_FIXTURE_NAME = "BlindBoxCiPigParentOne";
    public static final String PARENT_TWO_FIXTURE_NAME = "BlindBoxCiPigParentTwo";
    public static final String CHILD_FIXTURE_NAME = "BlindBoxCiPigChild";
    private static ActiveScenario active;

    private P3PigBreedingCiScenario() {
    }

    /** 后续由 {@code blindboxcitest start_p3_pig_clients} 接入。 */
    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P3 高效养猪双客户端场景未清理"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_PIG_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P3 pig breeding client-observation scenario", exception);
            source.sendFailure(Component.literal("CI P3 高效养猪场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    /** 后续由 {@code blindboxcitest verify_p3_pig_clients} 接入。 */
    public static int verifyClientMarkers(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的 P3 高效养猪双客户端场景"));
            return 0;
        }
        try {
            active.verifyClientMarkers();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_PIG_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P3 pig breeding client markers", exception);
            source.sendFailure(Component.literal("CI P3 高效养猪客户端断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    /** 后续由 {@code blindboxcitest cleanup_p3_pig_clients} 接入。 */
    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P3 高效养猪双客户端场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_PIG_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P3 pig breeding client-observation scenario", exception);
            source.sendFailure(Component.literal("CI P3 高效养猪场景清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    private enum Phase {
        OBSERVING,
        READY,
        FAILED
    }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerLevel fixtureLevel;
        private final ServerPlayerSnapshot aliceBefore;
        private final ServerPlayerSnapshot bobBefore;
        private final ItemStack aliceMainHandBefore;
        private final Map<BlockPos, BlockState> originalSupportBlocks;
        private final AABB fixtureBounds;
        private final UUID aliceUuid;
        private final UUID bobUuid;
        private UUID parentOneId;
        private UUID parentTwoId;
        private UUID childId;
        private Phase phase = Phase.OBSERVING;
        private int observationTicks;
        private String failure;

        private ActiveScenario(MinecraftServer server, ServerLevel fixtureLevel, ServerPlayer alice, ServerPlayer bob,
                               Map<BlockPos, BlockState> originalSupportBlocks, AABB fixtureBounds) {
            this.server = server;
            this.fixtureLevel = fixtureLevel;
            this.aliceBefore = ServerPlayerSnapshot.capture(alice);
            this.bobBefore = ServerPlayerSnapshot.capture(bob);
            this.aliceMainHandBefore = alice.getMainHandItem().copy();
            this.originalSupportBlocks = originalSupportBlocks;
            this.fixtureBounds = fixtureBounds;
            this.aliceUuid = alice.getUUID();
            this.bobUuid = bob.getUUID();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            // marker 目录由专服环境变量指定；提前拒绝旧文件，保证不能复用上轮或脚本预写的结果。
            Path markerDirectory = markerDirectory();
            ensureMarkersAbsent(markerDirectory);
            ServerPlayer alice = player(server, "BlindBoxAlice");
            ServerPlayer bob = player(server, "BlindBoxBob");
            ServerLevel level = server.overworld();
            BlockPos spawn = level.getSharedSpawnPos();
            // 复用世界出生点的已加载 X/Z 柱，在 y=200 搭最小临时平台隔离自然生成实体。
            BlockPos center = new BlockPos(spawn.getX(), 200, spawn.getZ());
            AABB fixtureBounds = new AABB(center).inflate(16.0D);
            if (!level.getEntitiesOfClass(Pig.class, fixtureBounds).isEmpty()) {
                throw new IllegalStateException("P3 高效养猪双客户端夹具区已有猪实体，拒绝覆盖未知实体");
            }
            Map<BlockPos, BlockState> support = captureSupportBlocks(level, center);
            ActiveScenario scenario = new ActiveScenario(server, level, alice, bob, support, fixtureBounds);
            try {
                scenario.setupFixture(alice, bob, center);
                return scenario;
            } catch (Exception exception) {
                scenario.cleanup();
                throw exception;
            }
        }

        private void setupFixture(ServerPlayer alice, ServerPlayer bob, BlockPos center) {
            placeSupportPlatforms(center);
            // 两名真实客户端都站在同一小平台内，必须自己收到三只猪的跟踪数据；不依赖命令或文件注入 UUID。
            alice.stopRiding();
            bob.stopRiding();
            alice.teleportTo(fixtureLevel, center.getX() - 5.5D, center.getY() + 1.0D, center.getZ() + 0.5D, 0.0F, 0.0F);
            bob.teleportTo(fixtureLevel, center.getX() + 5.5D, center.getY() + 1.0D, center.getZ() + 0.5D, 180.0F, 0.0F);
            alice.setDeltaMovement(0.0D, 0.0D, 0.0D);
            bob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            alice.hurtMarked = true;
            bob.hurtMarked = true;

            Pig first = spawnFixturePig(center.getX() - 1.5D, center.getY() + 1.0D, center.getZ() + 0.5D);
            Pig second = spawnFixturePig(center.getX() + 1.5D, center.getY() + 1.0D, center.getZ() + 0.5D);
            first.setCustomName(Component.literal(PARENT_ONE_FIXTURE_NAME));
            first.setCustomNameVisible(false);
            second.setCustomName(Component.literal(PARENT_TWO_FIXTURE_NAME));
            second.setCustomNameVisible(false);
            parentOneId = first.getUUID();
            parentTwoId = second.getUUID();
            normalizeParentOrder();

            ItemStack book = new ItemStack(ModItems.EFFICIENT_PIG_BREEDING.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND, book);
            InteractionResult result = book.getItem().use(fixtureLevel, alice, InteractionHand.MAIN_HAND).getResult();
            if (!result.consumesAction() || book.getCount() != 1
                    || !alice.getCooldowns().isOnCooldown(ModItems.EFFICIENT_PIG_BREEDING.get())) {
                throw new IllegalStateException("高效养猪技术没有从真实书本入口完成且保留书本/服务端冷却");
            }

            Pig child = findOnlyChild();
            childId = child.getUUID();
            child.setCustomName(Component.literal(CHILD_FIXTURE_NAME));
            child.setCustomNameVisible(false);
            // 生产入口已经生成并结算该幼猪。之后仅冻结这一小段观察窗口，防止 AI 漫游导致客户端
            // 未在相同视野内看到完整家庭；不会伪造 UUID、繁殖结果或 marker。
            freezeForObservation(first, center.getX() - 1.5D, center.getY() + 1.0D, center.getZ() + 0.5D);
            freezeForObservation(second, center.getX() + 1.5D, center.getY() + 1.0D, center.getZ() + 0.5D);
            freezeForObservation(child, center.getX(), center.getY() + 1.0D, center.getZ() + 2.0D);
            assertServerFixture();
            CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_PIG_SERVER_CREATED=success parent_one={} parent_two={} child={}",
                    parentOneId, parentTwoId, childId);
        }

        private void tick() {
            if (phase == Phase.FAILED || phase == Phase.READY) return;
            observationTicks++;
            if (observationTicks >= OBSERVATION_TICKS) {
                assertServerFixture();
                phase = Phase.READY;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_PIG_SERVER=success parent_one={} parent_two={} child={}",
                        parentOneId, parentTwoId, childId);
            }
        }

        private void verifyClientMarkers() throws IOException {
            if (phase == Phase.FAILED) {
                throw new IllegalStateException("P3 高效养猪服务端场景已失败：" + failure);
            }
            if (phase != Phase.READY) {
                throw new IllegalStateException("P3 高效养猪实体观察窗口尚未稳定完成");
            }
            assertServerFixture();
            Path directory = markerDirectory();
            verifyMarker(readMarker(directory.resolve("client-1-p3-pig-observed.marker")), aliceUuid, "客户端一");
            verifyMarker(readMarker(directory.resolve("client-2-p3-pig-observed.marker")), bobUuid, "客户端二");
        }

        private void verifyMarker(Map<String, String> marker, UUID expectedObserver, String clientName) {
            if (!"1".equals(marker.get("schema"))
                    || !expectedObserver.toString().equals(marker.get("observer_uuid"))) {
                throw new IllegalStateException(clientName + " 未由对应真实客户端写入高效养猪观察 marker");
            }
            assertMarkerUuid(marker, "parent_one", parentOneId, clientName);
            assertMarkerUuid(marker, "parent_two", parentTwoId, clientName);
            assertMarkerUuid(marker, "child", childId, clientName);
        }

        private void assertServerFixture() {
            Map<UUID, Pig> pigs = new HashMap<>();
            for (Pig pig : fixtureLevel.getEntitiesOfClass(Pig.class, fixtureBounds)) pigs.put(pig.getUUID(), pig);
            if (pigs.size() != 3 || !pigs.containsKey(parentOneId) || !pigs.containsKey(parentTwoId) || !pigs.containsKey(childId)) {
                throw new IllegalStateException("服务端高效养猪夹具不再精确包含同一对父猪及一只幼猪");
            }
            if (pigs.get(parentOneId).getAge() < 0 || pigs.get(parentTwoId).getAge() < 0 || pigs.get(childId).getAge() >= 0) {
                throw new IllegalStateException("服务端高效养猪夹具的父猪/幼猪年龄状态异常");
            }
        }

        private Pig findOnlyChild() {
            List<Pig> children = fixtureLevel.getEntitiesOfClass(Pig.class, fixtureBounds,
                    pig -> !parentOneId.equals(pig.getUUID()) && !parentTwoId.equals(pig.getUUID()) && pig.getAge() < 0);
            if (children.size() != 1) {
                throw new IllegalStateException("真实书本入口没有恰好生成一只可观察幼猪，实际=" + children.size());
            }
            return children.get(0);
        }

        private Pig spawnFixturePig(double x, double y, double z) {
            Pig pig = EntityType.PIG.create(fixtureLevel);
            if (pig == null) throw new IllegalStateException("无法创建高效养猪双客户端父猪夹具");
            pig.setAge(0);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setPos(x, y, z);
            if (!fixtureLevel.addFreshEntity(pig)) {
                throw new IllegalStateException("高效养猪双客户端父猪夹具未进入服务端世界");
            }
            return pig;
        }

        private static void freezeForObservation(Pig pig, double x, double y, double z) {
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setDeltaMovement(0.0D, 0.0D, 0.0D);
            pig.setPos(x, y, z);
        }

        private void normalizeParentOrder() {
            if (parentOneId.toString().compareTo(parentTwoId.toString()) > 0) {
                UUID swap = parentOneId;
                parentOneId = parentTwoId;
                parentTwoId = swap;
            }
        }

        private void cleanup() {
            fixtureLevel.getEntitiesOfClass(Pig.class, fixtureBounds,
                    pig -> pig.getUUID().equals(parentOneId) || pig.getUUID().equals(parentTwoId) || pig.getUUID().equals(childId))
                    .forEach(Pig::discard);
            ServerPlayer alice = server.getPlayerList().getPlayer(aliceUuid);
            if (alice != null) {
                alice.getCooldowns().removeCooldown(ModItems.EFFICIENT_PIG_BREEDING.get());
                alice.setItemInHand(InteractionHand.MAIN_HAND, aliceMainHandBefore.copy());
                aliceBefore.restore(alice);
                alice.containerMenu.broadcastChanges();
            }
            ServerPlayer bob = server.getPlayerList().getPlayer(bobUuid);
            if (bob != null) bobBefore.restore(bob);
            originalSupportBlocks.forEach((position, state) -> fixtureLevel.setBlock(position, state, 3));
        }

        private void fail(Exception exception) {
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P3_PIG=failed {}", failure, exception);
        }

        private void placeSupportPlatforms(BlockPos center) {
            for (int x = -6; x <= -4; x++) {
                for (int z = -1; z <= 1; z++) fixtureLevel.setBlock(center.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
            for (int x = 4; x <= 6; x++) {
                for (int z = -1; z <= 1; z++) fixtureLevel.setBlock(center.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -1; z <= 3; z++) fixtureLevel.setBlock(center.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private record ServerPlayerSnapshot(ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        private static ServerPlayerSnapshot capture(ServerPlayer player) {
            return new ServerPlayerSnapshot(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }

        private void restore(ServerPlayer player) {
            player.teleportTo(level, x, y, z, yRot, xRot);
            player.setDeltaMovement(0.0D, 0.0D, 0.0D);
            player.hurtMarked = true;
        }
    }

    private static Map<BlockPos, BlockState> captureSupportBlocks(ServerLevel level, BlockPos center) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -1; z <= 3; z++) {
                if ((x >= -6 && x <= -4) || (x >= 4 && x <= 6) || (x >= -2 && x <= 2)) {
                    BlockPos position = center.offset(x, 0, z);
                    result.put(position, level.getBlockState(position));
                }
            }
        }
        return result;
    }

    private static ServerPlayer player(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalStateException(name + " 不在线");
        return player;
    }

    private static Path markerDirectory() throws IOException {
        String configured = System.getenv("BLINDBOX_CITEST_PIG_MARKER_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("缺少受控的 BLINDBOX_CITEST_PIG_MARKER_DIR");
        }
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) throw new IllegalStateException("P3 高效养猪 marker 目录不存在：" + directory);
        return directory;
    }

    private static void ensureMarkersAbsent(Path directory) throws IOException {
        for (String fileName : List.of("client-1-p3-pig-observed.marker", "client-2-p3-pig-observed.marker")) {
            Path marker = directory.resolve(fileName);
            if (Files.exists(marker)) throw new IllegalStateException("P3 高效养猪 marker 已存在，拒绝沿用旧结果：" + marker);
        }
    }

    private static Map<String, String> readMarker(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少客户端真实高效养猪观察 marker：" + marker);
        Map<String, String> fields = new HashMap<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("高效养猪客户端 marker 格式非法：" + marker);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (key.isBlank() || value.isBlank() || fields.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("高效养猪客户端 marker 有空字段或重复字段：" + marker);
            }
        }
        if (fields.size() != 5) throw new IllegalStateException("高效养猪客户端 marker 字段数量不正确：" + marker);
        return fields;
    }

    private static void assertMarkerUuid(Map<String, String> marker, String key, UUID expected, String clientName) {
        String actual = marker.get(key);
        if (actual == null || !expected.toString().equals(actual)) {
            throw new IllegalStateException(clientName + " 未观察到同一 " + key + " UUID，期望=" + expected + "，实际=" + actual);
        }
    }
}
