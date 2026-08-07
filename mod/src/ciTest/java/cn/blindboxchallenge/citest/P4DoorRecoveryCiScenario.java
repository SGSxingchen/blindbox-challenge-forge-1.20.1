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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 同一 SIGKILL 会话的跨维门恢复探针：杀前持久关联，杀后只能由 Alice 真实行走穿门。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P4DoorRecoveryCiScenario {
    public static final BlockPos SOURCE_OFFSET = new BlockPos(64, 160, 0);
    public static final BlockPos NETHER_TARGET = new BlockPos(64, 180, 64);
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

    private enum Phase { WAIT_FOR_CROSSING, READY, FAILED }

    private static final class ActiveScenario {
        private final ServerLevel overworld;
        private final ServerLevel nether;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final UUID aliceId;
        private final UUID bobId;
        private final BlockPos sourceDoor;
        private final BlockPos targetDoor;
        private final Path markerDirectory;
        private final ItemStack originalAliceHand;
        private final Vec3 originalAlicePosition;
        private final float originalAliceYaw;
        private final float originalAlicePitch;
        private final Vec3 originalBobPosition;
        private final ResourceKey<Level> originalBobDimension;
        private final long startedAt;
        private Phase phase = Phase.WAIT_FOR_CROSSING;

        private ActiveScenario(ServerLevel overworld, ServerLevel nether, ServerPlayer alice, ServerPlayer bob, BlockPos sourceDoor,
                               BlockPos targetDoor, Path markerDirectory) {
            this.overworld = overworld;
            this.nether = nether;
            this.alice = alice;
            this.bob = bob;
            this.aliceId = alice.getUUID();
            this.bobId = bob.getUUID();
            this.sourceDoor = sourceDoor;
            this.targetDoor = targetDoor;
            this.markerDirectory = markerDirectory;
            this.originalAliceHand = alice.getMainHandItem().copy();
            this.originalAlicePosition = alice.position();
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
            ActiveScenario scenario = new ActiveScenario(overworld, nether, alice, bob, sourceDoor, targetDoor, markerDirectory());
            // 夹具通过原版跨维 tp 让 Bob 的真实玩家加载目标区块；之后才读取 BE，绝不由门逻辑强加载。
            moveFixturePlayer(server, nether, bob, new Vec3(targetDoor.getX() + 2.5D, targetDoor.getY(), targetDoor.getZ() + 0.5D), 90.0F, 0.0F);
            scenario.verifyPersistedLinks();
            scenario.ensureNoOldMarkers();
            alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            // P3 强杀恢复后 Alice 在下界；同样使用原版跨维 tp 返回主世界源门，随后只能靠生产门逻辑跨维。
            moveFixturePlayer(server, overworld, alice, new Vec3(sourceDoor.getX() + 0.5D, sourceDoor.getY(), sourceDoor.getZ() + 2.5D), 180.0F, 0.0F);
            alice.containerMenu.broadcastChanges();
            return scenario;
        }

        private void tick() throws IOException {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            if (overworld.getGameTime() - startedAt > 800L) throw new IllegalStateException("P4 跨维门恢复场景超时");
            if (alice.serverLevel().dimension().equals(nether.dimension())) {
                Vec3 expected = Vec3.atBottomCenterOf(targetDoor);
                Vec3 actual = alice.position();
                double distanceSqr = actual.distanceToSqr(expected);
                if (distanceSqr > 0.08D) {
                    throw new IllegalStateException("杀后进入任意门未抵达下界安全站立格：expected=" + expected
                            + ", actual=" + actual + ", distance_sqr=" + distanceSqr + ", velocity=" + alice.getDeltaMovement());
                }
                if (alice.getDeltaMovement().lengthSqr() > 1.0E-8D) throw new IllegalStateException("杀后进入任意门仍保留源门移动速度");
                verifyPersistedLinks();
                if (verifyAliceMarker() && verifyBobMarker()) {
                    phase = Phase.READY;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLIENTS=success");
                }
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
            Vec3 expectedBob = new Vec3(targetDoor.getX() + 2.5D, targetDoor.getY(), targetDoor.getZ() + 0.5D);
            return "1".equals(marker.get("schema")) && bob.serverLevel().dimension().equals(nether.dimension())
                    && bob.position().distanceToSqr(expectedBob) <= 0.35D && bobId.toString().equals(marker.get("observer_uuid")) && aliceId.toString().equals(marker.get("alice_uuid"))
                    && "minecraft:the_nether".equals(marker.get("dimension")) && position(targetDoor).equals(marker.get("arrival"))
                    && "true".equals(marker.get("target_door_and_safety_synced")) && "true".equals(marker.get("observer_near_target"));
        }

        private void ensureNoOldMarkers() {
            for (String name : List.of("client-1-p4-door-arrived.marker", "client-2-p4-door-observed.marker")) {
                if (Files.exists(markerDirectory.resolve(name))) throw new IllegalStateException("P4 跨维门 marker 已存在，拒绝复用旧结果");
            }
        }

        private void cleanup() {
            alice.setItemInHand(InteractionHand.MAIN_HAND, originalAliceHand.copy());
            alice.teleportTo(overworld, originalAlicePosition.x, originalAlicePosition.y, originalAlicePosition.z, originalAliceYaw, originalAlicePitch);
            ServerLevel bobLevel = bob.getServer().getLevel(originalBobDimension);
            if (bobLevel != null) bob.teleportTo(bobLevel, originalBobPosition.x, originalBobPosition.y, originalBobPosition.z, bob.getYRot(), bob.getXRot());
            for (BlockPos position : List.of(sourceDoor, sourceDoor.below(), sourceDoor.south().below(), sourceDoor.south(2).below())) {
                overworld.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            }
            for (BlockPos position : List.of(targetDoor, targetDoor.below(), targetDoor.east().below(), targetDoor.east(2).below())) {
                nether.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            }
            alice.containerMenu.broadcastChanges();
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

    /** 仅用于隔离夹具定位；必须走原版跨维命令，并在命令后严格核验世界、坐标和静止状态。 */
    private static void moveFixturePlayer(MinecraftServer server, ServerLevel destinationLevel, ServerPlayer player,
                                          Vec3 destination, float yaw, float pitch) {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withLevel(destinationLevel).withSuppressedOutput();
        String command = "tp " + player.getGameProfile().getName() + " " + destination.x + " " + destination.y + " " + destination.z
                + " " + yaw + " " + pitch;
        if (server.getCommands().performPrefixedCommand(source, command) <= 0
                || player.serverLevel() != destinationLevel || player.position().distanceToSqr(destination) > 1.0E-6D) {
            throw new IllegalStateException("P4 跨维门夹具原版 tp 未抵达预期维度或坐标：" + player.getGameProfile().getName());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.resetFallDistance();
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

    public static String position(BlockPos position) { return position.getX() + "," + position.getY() + "," + position.getZ(); }
}
