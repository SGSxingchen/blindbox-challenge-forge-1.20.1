package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import cn.blindboxchallenge.registry.ModBlocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.util.ITeleporter;

/** 同一 SIGKILL 会话的跨维门恢复探针：杀前持久关联，杀后只能由 Alice 真实行走穿门。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P4DoorRecoveryCiScenario {
    public static final BlockPos SOURCE_OFFSET = new BlockPos(64, 160, 0);
    public static final BlockPos NETHER_TARGET = new BlockPos(64, 180, 64);
    /** 起点碰撞盒完全在门格外，但保留足够短的真实步行距离，避免低帧 CI 在 100 客户端刻前尚未进门。 */
    private static final double SOURCE_START_DISTANCE = 1.5D;
    private static final String MANIFEST = "p4-door-recovery-before.properties";
    private static ActiveScenario active;

    private P4DoorRecoveryCiScenario() {}

    /** SIGKILL 前只用生产 BE 状态写入跨维关联，并留下原子证据供重启后比对；不写成功 marker。 */
    public static int prepare(CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            ServerLevel overworld = server.overworld();
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null) throw new IllegalStateException("下界未加载");
            BlockPos sourceDoor = overworld.getSharedSpawnPos().offset(SOURCE_OFFSET);
            BlockPos targetDoor = NETHER_TARGET;
            List<BlockPos> overworldPositions = List.of(sourceDoor.below(), sourceDoor, sourceDoor.south().below(), sourceDoor.south(2).below());
            List<BlockPos> netherPositions = List.of(targetDoor.below(), targetDoor, targetDoor.east().below(), targetDoor.east(2).below());
            for (BlockPos position : overworldPositions) if (!overworld.getBlockState(position).isAir()) throw new IllegalStateException("P4 门主世界夹具位置不是空气");
            for (BlockPos position : netherPositions) if (!nether.getBlockState(position).isAir()) throw new IllegalStateException("P4 门下界夹具位置不是空气");
            overworld.setBlock(sourceDoor.below(), ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            overworld.setBlock(sourceDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            overworld.setBlock(sourceDoor.south().below(), Blocks.STONE.defaultBlockState(), 3);
            overworld.setBlock(sourceDoor.south(2).below(), Blocks.STONE.defaultBlockState(), 3);
            nether.setBlock(targetDoor.below(), ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            nether.setBlock(targetDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            nether.setBlock(targetDoor.east().below(), Blocks.STONE.defaultBlockState(), 3);
            nether.setBlock(targetDoor.east(2).below(), Blocks.STONE.defaultBlockState(), 3);
            if (!(overworld.getBlockEntity(sourceDoor) instanceof AnywhereDoorBlockEntity first)
                    || !(nether.getBlockEntity(targetDoor) instanceof AnywhereDoorBlockEntity second)) {
                throw new IllegalStateException("P4 跨维门方块实体未创建");
            }
            first.link(second.doorId(), GlobalPos.of(nether.dimension(), targetDoor), GlobalPos.of(nether.dimension(), targetDoor.below()));
            second.link(first.doorId(), GlobalPos.of(overworld.dimension(), sourceDoor), GlobalPos.of(overworld.dimension(), sourceDoor.below()));
            writeManifest(markerDirectory(), first, second, overworld.dimension(), nether.dimension(), sourceDoor, targetDoor);
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_DOOR_RECOVERY_PREPARED=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot prepare P4 cross-dimension door recovery fixture", exception);
            source.sendFailure(Component.literal("CI P4 跨维门恢复夹具失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P4 跨维门恢复场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_DOOR_RECOVERY_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P4 cross-dimension door recovery scenario", exception);
            source.sendFailure(Component.literal("CI P4 跨维门恢复场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P4 跨维门恢复场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot cleanup P4 cross-dimension door recovery scenario", exception);
            source.sendFailure(Component.literal("CI P4 跨维门恢复清理失败：" + exception.getClass().getSimpleName()));
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

    private enum Phase { WAIT_FOR_FIXTURE_SERVER_SYNC, WAIT_FOR_FIXTURE_CLIENT_SYNC, WAIT_FOR_CROSSING, READY, FAILED }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final UUID aliceId;
        private final UUID bobId;
        private final BlockPos sourceDoor;
        private final BlockPos targetDoor;
        private final Path markerDirectory;
        private final ItemStack originalAliceHand;
        private final Vec3 originalAlicePosition;
        private final ResourceKey<Level> originalAliceDimension;
        private final float originalAliceYaw;
        private final float originalAlicePitch;
        private final Vec3 originalBobPosition;
        private final ResourceKey<Level> originalBobDimension;
        private final long startedAt;
        private Phase phase = Phase.WAIT_FOR_FIXTURE_SERVER_SYNC;
        /** 一旦生产门已把 Alice 送入下界，任何回到源维度都是生产跨维回跳，不能等待客户端超时掩盖。 */
        private boolean reachedTargetDimension;
        private boolean sourceMovementObserved;

        private ActiveScenario(MinecraftServer server, ServerLevel overworld, ServerLevel nether, ServerPlayer alice, ServerPlayer bob, BlockPos sourceDoor,
                               BlockPos targetDoor, Path markerDirectory) {
            this.server = server;
            this.overworld = overworld;
            this.nether = nether;
            this.aliceId = alice.getUUID();
            this.bobId = bob.getUUID();
            this.sourceDoor = sourceDoor;
            this.targetDoor = targetDoor;
            this.markerDirectory = markerDirectory;
            this.originalAliceHand = alice.getMainHandItem().copy();
            this.originalAlicePosition = alice.position();
            this.originalAliceDimension = alice.serverLevel().dimension();
            this.originalAliceYaw = alice.getYRot();
            this.originalAlicePitch = alice.getXRot();
            this.originalBobPosition = bob.position();
            this.originalBobDimension = bob.serverLevel().dimension();
            this.startedAt = overworld.getGameTime();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            ServerLevel overworld = server.overworld();
            ServerLevel nether = server.getLevel(Level.NETHER);
            ServerPlayer alice = player(server, "BlindBoxAlice");
            ServerPlayer bob = player(server, "BlindBoxBob");
            if (nether == null) throw new IllegalStateException("下界未加载");
            BlockPos sourceDoor = overworld.getSharedSpawnPos().offset(SOURCE_OFFSET);
            BlockPos targetDoor = NETHER_TARGET;
            // 先保存两名真实玩家的场景前位置；Bob 随后进入下界只为按正常玩家语义加载目标区块。
            ActiveScenario scenario = new ActiveScenario(server, overworld, nether, alice, bob, sourceDoor, targetDoor, markerDirectory());
            // 夹具通过完整的 Forge 玩家跨维迁移让 Bob 加载目标区块；之后才读取 BE，绝不由门逻辑强加载。
            moveFixturePlayer(server, nether, bob, new Vec3(targetDoor.getX() + 2.5D, targetDoor.getY(), targetDoor.getZ() + 0.5D), 90.0F, 0.0F);
            scenario.verifyPersistedLinks();
            scenario.ensureNoOldMarkers();
            // P3 强杀恢复后 Alice 在下界；夹具完整迁移回主世界源门，随后只能靠生产门逻辑跨维。
            moveFixturePlayer(server, overworld, alice, fixtureStart(sourceDoor), 180.0F, 0.0F);
            // 原版跨维命令可能替换服务器侧玩家对象；夹具和后续断言都必须按 UUID 重新取当前在线对象。
            ServerPlayer movedAlice = scenario.alice();
            movedAlice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            movedAlice.containerMenu.broadcastChanges();
            return scenario;
        }

        private void tick() throws IOException {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            ServerPlayer alice = alice();
            if (overworld.getGameTime() - startedAt > 800L) {
                ServerPlayer bob = bob();
                throw new IllegalStateException("P4 跨维门恢复场景超时：phase=" + phase
                        + ", alice_changing=" + alice.isChangingDimension() + ", alice=" + alice.position()
                        + ", bob_changing=" + bob.isChangingDimension() + ", bob=" + bob.position());
            }
            if (phase == Phase.WAIT_FOR_FIXTURE_SERVER_SYNC) {
                // Alice 是唯一要按前进键的本地客户端，必须先确认其 teleport id；确认前客户端仍可能
                // 发旧维度位置包。Bob 仅为目标区块观察者，其客户端目标同步仍由后续 marker 严格复验。
                if (alice.isChangingDimension()) return;
                verifyFixtureServerPositions(alice, bob());
                // 先让 Alice 在没有任何前进输入时连续观察源门起点，再由服务端复验这份客户端事实。
                // 该阶段不是门成功 marker，专门排除重启后滞留的旧维度移动包。
                phase = Phase.WAIT_FOR_FIXTURE_CLIENT_SYNC;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_DOOR_RECOVERY_FIXTURE_SERVER_READY=success");
                return;
            }
            if (phase == Phase.WAIT_FOR_FIXTURE_CLIENT_SYNC) {
                if (alice.isChangingDimension()) return;
                verifyFixtureServerPositions(alice, bob());
                if (!verifyAliceFixtureMarker()) return;
                phase = Phase.WAIT_FOR_CROSSING;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_DOOR_RECOVERY_FIXTURE_SYNCED=success");
                return;
            }
            if (phase == Phase.WAIT_FOR_CROSSING && !sourceMovementObserved && alice.serverLevel().dimension().equals(overworld.dimension())
                    && alice.position().distanceToSqr(fixtureStart(sourceDoor)) > 0.08D) {
                sourceMovementObserved = true;
                CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_DOOR_RECOVERY_SOURCE_MOVED=observed, alice={}", alice.position());
            }
            if (alice.serverLevel().dimension().equals(nether.dimension())) {
                reachedTargetDimension = true;
                // 触发门的移动包会在 changeDimension 返回后继续执行；原版仅在客户端确认 teleport id 后
                // 才以 awaiting 目标坐标完成服务端落点。确认前既不写 marker 也不放宽结果，只继续等待超时。
                if (alice.isChangingDimension()) return;
                Vec3 expected = Vec3.atBottomCenterOf(targetDoor);
                Vec3 actual = alice.position();
                double distanceSqr = actual.distanceToSqr(expected);
                if (distanceSqr > 0.08D) {
                    throw new IllegalStateException("杀后进入任意门未抵达下界安全站立格：expected=" + expected
                            + ", actual=" + actual + ", distance_sqr=" + distanceSqr + ", velocity=" + alice.getDeltaMovement());
                }
                // changeDimension 的落点确认和首个原版物理 tick 可能同帧完成：落地前的重力增量不是
                // 源门惯性。位置必须始终精确，且只有在真实落地、速度已归零后才能写成功结果。
                // 若原版未在 800 tick 内稳定，保留超时失败，绝不以短暂位置或速度状态放宽验收。
                if (!alice.onGround() || alice.getDeltaMovement().lengthSqr() > 1.0E-8D) return;
                verifyPersistedLinks();
                if (verifyAliceMarker() && verifyBobMarker()) {
                    phase = Phase.READY;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLIENTS=success");
                }
            } else if (reachedTargetDimension) {
                throw new IllegalStateException("P4 跨维门抵达下界后回到源维度：alice=" + alice.position()
                        + ", velocity=" + alice.getDeltaMovement() + ", changing=" + alice.isChangingDimension()
                        + ", phase=" + phase);
            } else if (!alice.serverLevel().dimension().equals(overworld.dimension())) {
                throw new IllegalStateException("Alice 杀后任意门进入了非预期维度");
            }
        }

        private void verifyPersistedLinks() throws IOException {
            Map<String, String> manifest = readManifest(markerDirectory);
            AnywhereDoorBlockEntity source = door(overworld, sourceDoor, "主世界");
            AnywhereDoorBlockEntity target = door(nether, targetDoor, "下界");
            if (!source.doorId().toString().equals(manifest.get("source_id")) || !target.doorId().toString().equals(manifest.get("target_id"))
                    || !overworld.dimension().location().toString().equals(manifest.get("source_dimension"))
                    || !nether.dimension().location().toString().equals(manifest.get("target_dimension"))
                    || !position(sourceDoor).equals(manifest.get("source_position")) || !position(targetDoor).equals(manifest.get("target_position"))
                    || !source.partnerDoorId().filter(target.doorId()::equals).isPresent() || !target.partnerDoorId().filter(source.doorId()::equals).isPresent()
                    || !source.partnerDoor().filter(GlobalPos.of(nether.dimension(), targetDoor)::equals).isPresent()
                    || !target.partnerDoor().filter(GlobalPos.of(overworld.dimension(), sourceDoor)::equals).isPresent()
                    || !source.destinationSafety().filter(GlobalPos.of(nether.dimension(), targetDoor.below())::equals).isPresent()
                    || !target.destinationSafety().filter(GlobalPos.of(overworld.dimension(), sourceDoor.below())::equals).isPresent()
                    || !overworld.getBlockState(sourceDoor.below()).is(ModBlocks.SAFETY_LANDING.get())
                    || !nether.getBlockState(targetDoor.below()).is(ModBlocks.SAFETY_LANDING.get())) {
                throw new IllegalStateException("SIGKILL 后跨维门 UUID、反链或安全点不一致");
            }
        }

        private void verifyFixtureServerPositions(ServerPlayer alice, ServerPlayer bob) {
            Vec3 expectedAlice = fixtureStart(sourceDoor);
            Vec3 expectedBob = new Vec3(targetDoor.getX() + 2.5D, targetDoor.getY(), targetDoor.getZ() + 0.5D);
            if (!alice.serverLevel().dimension().equals(overworld.dimension()) || alice.position().distanceToSqr(expectedAlice) > 0.08D
                    || !bob.serverLevel().dimension().equals(nether.dimension()) || bob.position().distanceToSqr(expectedBob) > 0.08D) {
                throw new IllegalStateException("P4 跨维门夹具确认后玩家未处于预期站立格：Alice=" + alice.position()
                        + ", Bob=" + bob.position());
            }
        }

        private boolean verifyAliceFixtureMarker() throws IOException {
            Path path = markerDirectory.resolve("client-1-p4-door-fixture-ready.marker");
            if (!Files.isRegularFile(path)) return false;
            Map<String, String> marker = readMarker(path);
            return "1".equals(marker.get("schema")) && aliceId.toString().equals(marker.get("observer_uuid"))
                    && "minecraft:overworld".equals(marker.get("dimension")) && position(sourceDoor).equals(marker.get("source"))
                    && fixtureStartPosition(sourceDoor).equals(marker.get("standing"))
                    && "true".equals(marker.get("source_door_and_safety_synced"));
        }

        private boolean verifyAliceMarker() throws IOException {
            Path path = markerDirectory.resolve("client-1-p4-door-arrived.marker");
            // 真实换维包、客户端观察和原子落盘晚于服务端 teleport；缺少文件只是尚未观察完成，
            // 必须继续等待，而已写出但损坏的文件仍由 readMarker 严格失败。
            if (!Files.isRegularFile(path)) return false;
            Map<String, String> marker = readMarker(path);
            return "1".equals(marker.get("schema")) && aliceId.toString().equals(marker.get("observer_uuid")) && "minecraft:the_nether".equals(marker.get("dimension"))
                    && position(targetDoor).equals(marker.get("arrival")) && "true".equals(marker.get("source_and_target_synced"));
        }

        private boolean verifyBobMarker() throws IOException {
            Path path = markerDirectory.resolve("client-2-p4-door-observed.marker");
            if (!Files.isRegularFile(path)) return false;
            Map<String, String> marker = readMarker(path);
            ServerPlayer bob = bob();
            Vec3 expectedBob = new Vec3(targetDoor.getX() + 2.5D, targetDoor.getY(), targetDoor.getZ() + 0.5D);
            return "1".equals(marker.get("schema")) && bob.serverLevel().dimension().equals(nether.dimension())
                    && bob.position().distanceToSqr(expectedBob) <= 0.35D && bobId.toString().equals(marker.get("observer_uuid")) && aliceId.toString().equals(marker.get("alice_uuid"))
                    && "minecraft:the_nether".equals(marker.get("dimension")) && position(targetDoor).equals(marker.get("arrival"))
                    && "true".equals(marker.get("target_door_and_safety_synced")) && "true".equals(marker.get("observer_near_target"));
        }

        private void ensureNoOldMarkers() {
            for (String name : List.of("client-1-p4-door-fixture-ready.marker", "client-1-p4-door-arrived.marker", "client-2-p4-door-observed.marker")) {
                if (Files.exists(markerDirectory.resolve(name))) throw new IllegalStateException("P4 跨维门 marker 已存在，拒绝复用旧结果");
            }
        }

        private void cleanup() {
            ServerPlayer alice = alice();
            ServerPlayer bob = bob();
            ServerLevel aliceLevel = server.getLevel(originalAliceDimension);
            ServerLevel bobLevel = server.getLevel(originalBobDimension);
            if (aliceLevel == null || bobLevel == null) throw new IllegalStateException("P4 跨维门清理时原维度不可用");
            moveFixturePlayer(server, aliceLevel, alice, originalAlicePosition, originalAliceYaw, originalAlicePitch);
            moveFixturePlayer(server, bobLevel, bob, originalBobPosition, bob.getYRot(), bob.getXRot());
            alice = alice();
            alice.setItemInHand(InteractionHand.MAIN_HAND, originalAliceHand.copy());
            for (BlockPos position : List.of(sourceDoor, sourceDoor.below(), sourceDoor.south().below(), sourceDoor.south(2).below())) {
                overworld.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            }
            for (BlockPos position : List.of(targetDoor, targetDoor.below(), targetDoor.east().below(), targetDoor.east(2).below())) {
                nether.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            }
            alice.containerMenu.broadcastChanges();
        }

        private ServerPlayer alice() {
            ServerPlayer player = server.getPlayerList().getPlayer(aliceId);
            if (player == null) throw new IllegalStateException("P4 跨维门场景中的 Alice 已离线");
            return player;
        }

        private ServerPlayer bob() {
            ServerPlayer player = server.getPlayerList().getPlayer(bobId);
            if (player == null) throw new IllegalStateException("P4 跨维门场景中的 Bob 已离线");
            return player;
        }

        private void fail(Exception exception) {
            if (phase == Phase.FAILED) return;
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P4_DOOR_RECOVERY=failed", exception);
        }
    }

    private static AnywhereDoorBlockEntity door(ServerLevel level, BlockPos position, String name) {
        if (level.getBlockEntity(position) instanceof AnywhereDoorBlockEntity door) return door;
        throw new IllegalStateException(name + "跨维门方块实体缺失");
    }

    private static ServerPlayer player(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalStateException("P4 跨维门场景缺少 " + name);
        return player;
    }

    /** 仅用于隔离夹具定位；必须走完整玩家跨维迁移，并在结束后严格核验世界、坐标和静止状态。 */
    private static void moveFixturePlayer(MinecraftServer server, ServerLevel destinationLevel, ServerPlayer player,
                                          Vec3 destination, float yaw, float pitch) {
        if (player.serverLevel().dimension().equals(destinationLevel.dimension())) {
            player.teleportTo(destinationLevel, destination.x, destination.y, destination.z, yaw, pitch);
        } else if (player.changeDimension(destinationLevel, new FixtureTeleporter(destination, yaw, pitch)) != player) {
            throw new IllegalStateException("P4 跨维门夹具未迁移当前玩家对象：" + player.getGameProfile().getName());
        }
        if (player.serverLevel() != destinationLevel || player.position().distanceToSqr(destination) > 1.0E-6D) {
            throw new IllegalStateException("P4 跨维门夹具未抵达预期维度或坐标：" + player.getGameProfile().getName());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.resetFallDistance();
    }

    /** 夹具不创建门户，只复用原版玩家跨维握手把已知定位坐标写入目标维度包。 */
    private static final class FixtureTeleporter implements ITeleporter {
        private final Vec3 destination;
        private final float yaw;
        private final float pitch;

        private FixtureTeleporter(Vec3 destination, float yaw, float pitch) {
            this.destination = destination;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destinationLevel, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(destination, Vec3.ZERO, yaw, pitch);
        }

        @Override
        public Entity placeEntity(Entity entity, ServerLevel currentLevel, ServerLevel destinationLevel, float suggestedYaw,
                                  Function<Boolean, Entity> repositionEntity) {
            return repositionEntity.apply(false);
        }

        @Override
        public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceLevel, ServerLevel destinationLevel) {
            return false;
        }
    }

    private static Path markerDirectory() {
        String configured = System.getenv("BLINDBOX_CITEST_P4_MARKER_DIR");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_P4_MARKER_DIR");
        Path directory = Path.of(configured).toAbsolutePath();
        if (!Files.isDirectory(directory)) throw new IllegalStateException("P4 跨维门 marker 目录不存在");
        return directory;
    }

    private static void writeManifest(Path directory, AnywhereDoorBlockEntity source, AnywhereDoorBlockEntity target,
                                      ResourceKey<Level> sourceDimension, ResourceKey<Level> targetDimension,
                                      BlockPos sourcePos, BlockPos targetPos) throws IOException {
        String data = "source_id=" + source.doorId() + "\n"
                + "target_id=" + target.doorId() + "\n"
                + "source_dimension=" + sourceDimension.location() + "\n"
                + "target_dimension=" + targetDimension.location() + "\n"
                + "source_position=" + position(sourcePos) + "\n"
                + "target_position=" + position(targetPos) + "\n";
        Path targetFile = directory.resolve(MANIFEST);
        Path temporary = Files.createTempFile(directory, MANIFEST, ".part");
        try {
            Files.writeString(temporary, data, StandardCharsets.UTF_8);
            try { Files.move(temporary, targetFile, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, targetFile); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, String> readManifest(Path directory) throws IOException { return readMarker(directory.resolve(MANIFEST)); }

    private static Map<String, String> readMarker(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少 P4 跨维门证据：" + marker);
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) throw new IllegalStateException("P4 跨维门证据格式非法");
            if (values.put(line.substring(0, separator), line.substring(separator + 1)) != null) throw new IllegalStateException("P4 跨维门证据字段重复");
        }
        return values;
    }

    public static Vec3 fixtureStart(BlockPos door) {
        return new Vec3(door.getX() + 0.5D, door.getY(), door.getZ() + SOURCE_START_DISTANCE);
    }

    public static String fixtureStartPosition(BlockPos door) {
        Vec3 position = fixtureStart(door);
        return position.x + "," + position.y + "," + position.z;
    }

    public static String position(BlockPos position) { return position.getX() + "," + position.getY() + "," + position.getZ(); }
}
