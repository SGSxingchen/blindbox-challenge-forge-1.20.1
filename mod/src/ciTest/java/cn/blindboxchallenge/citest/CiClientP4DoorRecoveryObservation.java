package cn.blindboxchallenge.citest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import cn.blindboxchallenge.registry.ModBlocks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 杀后跨维门的两端真实观察：Alice 只按前进键走入门，Bob 只观察同步后的远程 Alice。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientP4DoorRecoveryObservation {
    /** Hosted Runner 启动后可能有 120 个服务器刻追帧；仍小于服务端 800 刻场景总超时。 */
    private static final int WALKING_TIMEOUT_TICKS = 400;
    private static boolean sourceSeen;
    private static boolean fixtureReady;
    private static int fixtureStableTicks;
    private static boolean walking;
    private static int walkingTicks;
    private static boolean aliceWritten;
    private static boolean bobWritten;

    private CiClientP4DoorRecoveryObservation() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Path directory = markerDirectory();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer self = minecraft.player;
        if (directory == null || self == null || minecraft.level == null || minecraft.getConnection() == null) return;
        // 此旗标仅允许“重启后服务端已验证杀前持久字段”的阶段开始，绝不是任何成功 marker。
        // 杀前夹具已经存在时，客户端必须完全不走动、不观察、更不能写 marker。
        boolean observeFixture = Files.isRegularFile(directory.resolve("p4-door-recovery-fixture-observe.flag"));
        boolean enabled = Files.isRegularFile(directory.resolve("p4-door-recovery-enabled.flag"));
        if (!observeFixture && !enabled) return;
        BlockPos source = minecraft.level.getSharedSpawnPos().offset(P4DoorRecoveryCiScenario.SOURCE_OFFSET);
        BlockPos target = P4DoorRecoveryCiScenario.NETHER_TARGET;
        if (isAlice(self)) observeAlice(minecraft, self, source, target, directory, observeFixture, enabled);
        else observeBob(minecraft, self, target, directory);
    }

    private static void observeAlice(Minecraft minecraft, LocalPlayer self, BlockPos source, BlockPos target, Path directory,
                                     boolean observeFixture, boolean enabled) {
        if (minecraft.level.dimension().equals(Level.OVERWORLD)) {
            if (isDoorWithSafety(minecraft, source)) sourceSeen = true;
            Vec3 expectedStart = P4DoorRecoveryCiScenario.fixtureStart(source);
            boolean atStart = sourceSeen && ((Entity) self).position().distanceToSqr(expectedStart) < 0.08D && minecraft.screen == null;
            // 仅观察阶段绝不产生移动输入；连续 20 个真实客户端 tick 看到精确起点后才写阶段证据。
            if (observeFixture && !fixtureReady) {
                if (atStart) fixtureStableTicks++;
                else fixtureStableTicks = 0;
                if (fixtureStableTicks >= 20) {
                    writeMarker(directory.resolve("client-1-p4-door-fixture-ready.marker"), "schema=1\n"
                            + "observer_uuid=" + ((Entity) self).getUUID() + "\n"
                            + "dimension=minecraft:overworld\n"
                            + "source=" + P4DoorRecoveryCiScenario.position(source) + "\n"
                            + "standing=" + P4DoorRecoveryCiScenario.fixtureStartPosition(source) + "\n"
                            + "source_door_and_safety_synced=true\n");
                    fixtureReady = true;
                }
            }
            if (!enabled || !fixtureReady) {
                KeyMapping.set(minecraft.options.keyUp.getKey(), false);
                return;
            }
            if (!walking && atStart) {
                KeyMapping.set(minecraft.options.keyUp.getKey(), true);
                walking = true;
            }
            // 夹具固定面朝北（yaw=180）。不能只因 blockPosition 变为门格就松键：中心刚浅入
            // 门格时，ServerTick.END 可能尚未消费对应位置包。必须以真实按键深入门格，确保服务端
            // 已有可供 AABB 重验的入门位置；到门格北侧深处才松开，避免输入带进跨维同步首帧。
            if (walking && enteredFixtureDoorDeeply(self, source)) KeyMapping.set(minecraft.options.keyUp.getKey(), false);
            if (walking && ++walkingTicks > WALKING_TIMEOUT_TICKS) {
                throw new IllegalStateException("真实前进键未进入 P4 跨维门：client=" + ((Entity) self).position()
                        + ", source=" + source + ", ticks=" + walkingTicks);
            }
            return;
        }
        KeyMapping.set(minecraft.options.keyUp.getKey(), false);
        if (aliceWritten || !minecraft.level.dimension().equals(Level.NETHER) || !sourceSeen || !isDoorWithSafety(minecraft, target)) return;
        Vec3 expectedArrival = Vec3.atBottomCenterOf(target);
        if (((Entity) self).position().distanceToSqr(expectedArrival) > 0.08D) return;
        writeMarker(directory.resolve("client-1-p4-door-arrived.marker"), "schema=1\n"
                + "observer_uuid=" + ((Entity) self).getUUID() + "\n"
                + "dimension=minecraft:the_nether\n"
                + "arrival=" + P4DoorRecoveryCiScenario.position(target) + "\n"
                + "source_and_target_synced=true\n");
        aliceWritten = true;
    }

    private static void observeBob(Minecraft minecraft, LocalPlayer self, BlockPos target, Path directory) {
        if (bobWritten || !minecraft.level.dimension().equals(Level.NETHER) || !isDoorWithSafety(minecraft, target)) return;
        Vec3 expectedObserver = new Vec3(target.getX() + 2.5D, target.getY(), target.getZ() + 0.5D);
        if (((Entity) self).position().distanceToSqr(expectedObserver) > 0.35D) return;
        // UUID 由服务端在 marker 复验；客户端只从当前已同步的远程玩家列表中按测试昵称定位 Alice。
        Player alice = null;
        for (Player player : minecraft.level.players()) {
            if ("BlindBoxAlice".equals(player.getGameProfile().getName())) {
                alice = player;
                break;
            }
        }
        if (alice == null) return;
        if (((Entity) alice).position().distanceToSqr(Vec3.atBottomCenterOf(target)) > 0.08D) return;
        writeMarker(directory.resolve("client-2-p4-door-observed.marker"), "schema=1\n"
                + "observer_uuid=" + ((Entity) self).getUUID() + "\n"
                + "alice_uuid=" + ((Entity) alice).getUUID() + "\n"
                + "dimension=minecraft:the_nether\n"
                + "arrival=" + P4DoorRecoveryCiScenario.position(target) + "\n"
                + "target_door_and_safety_synced=true\n"
                + "observer_near_target=true\n");
        bobWritten = true;
    }

    private static boolean isDoorWithSafety(Minecraft minecraft, BlockPos door) {
        return minecraft.level.getBlockState(door).is(ModBlocks.ANYWHERE_DOOR.get())
                && minecraft.level.getBlockState(door.below()).is(ModBlocks.SAFETY_LANDING.get());
    }

    /** 仅 P4 固定朝北夹具：玩家中心到达门格北侧 0.2 格以内，碰撞盒仍明显与门格相交。 */
    private static boolean enteredFixtureDoorDeeply(LocalPlayer player, BlockPos door) {
        Vec3 position = ((Entity) player).position();
        return Math.abs(position.x - (door.getX() + 0.5D)) < 0.2D
                && Math.abs(position.y - door.getY()) < 0.2D
                && position.z <= door.getZ() + 0.2D;
    }

    private static boolean isAlice(LocalPlayer player) { return "BlindBoxAlice".equals(player.getGameProfile().getName()); }

    private static Path markerDirectory() {
        String configured = System.getProperty("blindbox.ci.p4DoorMarkerDir");
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    private static void writeMarker(Path target, String value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
            try {
                Files.writeString(temporary, value, StandardCharsets.UTF_8);
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入真实跨维门客户端 marker：" + target, exception);
        }
    }
}
