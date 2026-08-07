package cn.blindboxchallenge.citest;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 仅随 ciTest Jar 安装的 P5 客户端观察与输入器。
 *
 * <p>它只在脚本创建的阶段旗标存在、自己已经收到了服务端定位/手持物/支撑方块后，才使用真实
 * KeyMapping 输入原版右键和攻击路径。marker 不由脚本预写，且只在本客户端实际收到三项生产
 * BlockState 与三枚正常 ItemEntity 后写入；服务端仍按 UUID、物品和坐标反查。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientP5DecorObservation {
    private static final int AIM_STABLE_TICKS = 8;
    private static final boolean[] placedObserved = new boolean[P5DecorCiScenario.ROUNDS.size()];
    private static final UUID[] dropObserved = new UUID[P5DecorCiScenario.ROUNDS.size()];
    private static final boolean[] useInjected = new boolean[P5DecorCiScenario.ROUNDS.size()];
    private static final boolean[] attackInjected = new boolean[P5DecorCiScenario.ROUNDS.size()];
    private static final int[] placementAimTicks = new int[P5DecorCiScenario.ROUNDS.size()];
    private static final int[] breakingAimTicks = new int[P5DecorCiScenario.ROUNDS.size()];
    // 失败时只留一次本地真实前置快照，既不写 marker，也不把诊断当成通过结论。
    private static final boolean[] placementPrerequisiteLogged = new boolean[P5DecorCiScenario.ROUNDS.size()];
    private static int diagnosticsTicks;
    private static boolean markerWritten;

    private CiClientP5DecorObservation() {
    }

    // 在正式 Minecraft 输入处理之前注入 use click；攻击则保持 keyAttack down，仍由正式客户端每 tick
    // 计算 HitResult、发送 start/stop destroy C2S 与结算破坏，探针不直接发送网络包。
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        String role = role();
        Path stageDirectory = stageDirectory();
        if (role == null || stageDirectory == null || !Files.isRegularFile(stageDirectory.resolve("p5-decor-enabled.flag"))) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || minecraft.getConnection() == null) return;

        observeProductionState(minecraft, player);
        writePreconditionDiagnostic(minecraft, player, role, stageDirectory);
        driveRealInputs(minecraft, player, role, stageDirectory);
        if (!markerWritten && allObserved()) {
            writeMarker(Path.of(required("blindbox.ci.p5DecorMarker")).toAbsolutePath(), player);
            markerWritten = true;
        }
    }

    private static void observeProductionState(Minecraft minecraft, LocalPlayer observer) {
        for (P5DecorCiScenario.DecorRound round : P5DecorCiScenario.ROUNDS) {
            int slot = round.index() - 1;
            BlockPos target = P5DecorCiScenario.target(minecraft.level, round.index());
            if (minecraft.level.getBlockState(target).is(round.block().get())) placedObserved[slot] = true;
            if (!placedObserved[slot] || dropObserved[slot] != null) continue;
            for (ItemEntity entity : minecraft.level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.75D))) {
                if (entity.isAlive() && entity.getItem().is(round.item().get()) && entity.getItem().getCount() == 1) {
                    dropObserved[slot] = entity.getUUID();
                    break;
                }
            }
        }
    }

    private static void driveRealInputs(Minecraft minecraft, LocalPlayer player, String role, Path stageDirectory) {
        boolean singleClient = Boolean.getBoolean("blindbox.ci.p5DecorSingle");
        for (P5DecorCiScenario.DecorRound round : P5DecorCiScenario.ROUNDS) {
            if (!round.actorName().equals(role) && !(singleClient && "BlindBoxAlice".equals(role))) continue;
            int slot = round.index() - 1;
            BlockPos target = P5DecorCiScenario.target(minecraft.level, round.index());
            BlockPos support = target.below();
            boolean placeEnabled = Files.isRegularFile(stageDirectory.resolve("p5-decor-place-" + round.index() + ".flag"));
            boolean expectedItem = player.getMainHandItem().is(round.item().get());
            boolean targetAir = minecraft.level.getBlockState(target).isAir();
            boolean supportStone = minecraft.level.getBlockState(support).is(Blocks.STONE);
            boolean atPlacementStance = at(player.position(), P5DecorCiScenario.placementStance(minecraft.level, round.index()));
            if (placeEnabled && !useInjected[slot] && !placementPrerequisiteLogged[slot]) {
                placementPrerequisiteLogged[slot] = true;
                logPlacementPrerequisites(minecraft, player, role, round, target, support, expectedItem, targetAir, supportStone, atPlacementStance);
            }
            if (!useInjected[slot] && placeEnabled && expectedItem && targetAir && supportStone && atPlacementStance) {
                aimAt(player, new Vec3(support.getX() + 0.5D, target.getY(), support.getZ() + 0.5D));
                if (hits(minecraft, support)) placementAimTicks[slot]++; else placementAimTicks[slot] = 0;
                if (placementAimTicks[slot] >= AIM_STABLE_TICKS) {
                    KeyMapping.click(minecraft.options.keyUse.getKey());
                    useInjected[slot] = true;
                    CiTestProbe.LOGGER.info("P5 装饰方块客户端已注入真实右键映射：轮次={}，玩家={}", round.index(), role);
                }
            }
            Path breakFlag = stageDirectory.resolve("p5-decor-break-" + round.index() + ".flag");
            if (!Files.isRegularFile(breakFlag)) continue;
            if (minecraft.level.getBlockState(target).isAir()) {
                if (attackInjected[slot]) minecraft.options.keyAttack.setDown(false);
                continue;
            }
            if (!attackInjected[slot] && at(player.position(), P5DecorCiScenario.breakingStance(minecraft.level, round.index()))
                    && minecraft.level.getBlockState(target).is(round.block().get())) {
                // 地面画板只有 2/16 格高。瞄准方块几何中心会让射线从其选择轮廓上方穿过，
                // 命中目标后方的石地；三种装饰的底座都覆盖此低点，因此仍是原版可达的真实方块命中。
                aimAt(player, new Vec3(target.getX() + 0.5D, target.getY() + 0.0625D, target.getZ() + 0.5D));
                if (hits(minecraft, target)) breakingAimTicks[slot]++; else breakingAimTicks[slot] = 0;
                if (breakingAimTicks[slot] >= AIM_STABLE_TICKS) {
                    // 先向原版攻击键映射压入一次按下事件，再保持按键；低矮画板已证明单纯保持状态会
                    // 命中却不开始新的 destroy 动作。这里不调用 gameMode、不构造 C2S，后续挖掘
                    // 进度、开始/停止包和掉落仍全部由 Minecraft 正式输入循环处理。
                    KeyMapping.click(minecraft.options.keyAttack.getKey());
                    minecraft.options.keyAttack.setDown(true);
                    attackInjected[slot] = true;
                    CiTestProbe.LOGGER.info("P5 装饰方块客户端已按住真实攻击映射：轮次={}，玩家={}", round.index(), role);
                }
            }
        }
    }

    private static boolean at(Vec3 actual, Vec3 expected) {
        return actual.distanceToSqr(expected) <= 0.36D;
    }

    private static boolean hits(Minecraft minecraft, BlockPos expected) {
        HitResult hit = minecraft.hitResult;
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(expected);
    }

    /**
     * 只记录真实客户端在阶段旗标首次可见时已经同步到的本地状态，用来定位输入门槛；
     * 不创建成功文件、不包含 URL/路径，也不会改变任何生产世界状态。
     */
    private static void logPlacementPrerequisites(Minecraft minecraft, LocalPlayer player, String role, P5DecorCiScenario.DecorRound round,
                                                   BlockPos target, BlockPos support, boolean expectedItem, boolean targetAir,
                                                   boolean supportStone, boolean atPlacementStance) {
        String hit = minecraft.hitResult instanceof BlockHitResult blockHit
                ? P5DecorCiScenario.position(blockHit.getBlockPos()) : String.valueOf(minecraft.hitResult == null ? "null" : minecraft.hitResult.getType());
        CiTestProbe.LOGGER.info("P5 装饰方块客户端放置前置状态：轮次={}，玩家={}，expected_item={}，target_air={}，support_stone={}，at_stance={}，position={}，expected_position={}，target_state={}，support_state={}，hit={}",
                round.index(), role, expectedItem, targetAir, supportStone, atPlacementStance,
                player.blockPosition(), P5DecorCiScenario.placementStance(minecraft.level, round.index()),
                minecraft.level.getBlockState(target), minecraft.level.getBlockState(support), hit);
    }

    private static void aimAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    private static boolean allObserved() {
        for (int index = 0; index < P5DecorCiScenario.ROUNDS.size(); index++) {
            if (!placedObserved[index] || dropObserved[index] == null) return false;
        }
        return true;
    }

    /**
     * 超时定位只记录客户端已看到的前置事实，绝不代表放置、破坏、掉落或 marker 成功。每 20 tick
     * 原子覆盖一次，使 Hosted artifact 能区分定位、手持物、支撑、目标和 HitResult，而不能借此放宽断言。
     */
    private static void writePreconditionDiagnostic(Minecraft minecraft, LocalPlayer player, String role, Path stageDirectory) {
        if (++diagnosticsTicks % 20 != 0) return;
        String configured = System.getProperty("blindbox.ci.p5DecorDiagnostic");
        if (configured == null || configured.isBlank()) return;
        int roundIndex = activeRound(stageDirectory);
        if (roundIndex < 1) return;
        int slot = roundIndex - 1;
        P5DecorCiScenario.DecorRound round = P5DecorCiScenario.ROUNDS.get(slot);
        BlockPos target = P5DecorCiScenario.target(minecraft.level, roundIndex);
        BlockPos support = target.below();
        String hit = "none";
        if (minecraft.hitResult instanceof BlockHitResult blockHit) hit = P5DecorCiScenario.position(blockHit.getBlockPos());
        String value = "schema=1\n"
                + "role=" + role + "\n"
                + "observer_uuid=" + player.getUUID() + "\n"
                + "round=" + roundIndex + "\n"
                + "position=" + position(player.position()) + "\n"
                + "placement_expected=" + position(P5DecorCiScenario.placementStance(minecraft.level, roundIndex)) + "\n"
                + "breaking_expected=" + position(P5DecorCiScenario.breakingStance(minecraft.level, roundIndex)) + "\n"
                + "at_placement=" + at(player.position(), P5DecorCiScenario.placementStance(minecraft.level, roundIndex)) + "\n"
                + "main_item=" + BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()) + "\n"
                + "expected_item=" + round.itemId() + "\n"
                + "target_state=" + BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(target).getBlock()) + "\n"
                + "support_state=" + BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(support).getBlock()) + "\n"
                + "hit=" + hit + "\n"
                + "placement_aim_ticks=" + placementAimTicks[slot] + "\n"
                + "use_injected=" + useInjected[slot] + "\n"
                + "break_aim_ticks=" + breakingAimTicks[slot] + "\n"
                + "attack_injected=" + attackInjected[slot] + "\n";
        atomicWrite(Path.of(configured).toAbsolutePath(), value, "P5 装饰方块前置诊断");
    }

    private static int activeRound(Path stageDirectory) {
        for (int index = P5DecorCiScenario.ROUNDS.size() - 1; index >= 0; index--) {
            P5DecorCiScenario.DecorRound round = P5DecorCiScenario.ROUNDS.get(index);
            if (Files.isRegularFile(stageDirectory.resolve("p5-decor-place-" + round.index() + ".flag"))
                    || Files.isRegularFile(stageDirectory.resolve("p5-decor-break-" + round.index() + ".flag"))) return round.index();
        }
        return -1;
    }

    private static String position(Vec3 position) {
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", position.x, position.y, position.z);
    }

    private static String role() {
        String configured = System.getProperty("blindbox.ci.p5DecorRole");
        if ("BlindBoxAlice".equals(configured) || "BlindBoxBob".equals(configured)) return configured;
        return null;
    }

    private static Path stageDirectory() {
        String configured = System.getProperty("blindbox.ci.p5DecorStageDir");
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少 " + property);
        return value;
    }

    private static void writeMarker(Path marker, LocalPlayer observer) {
        StringBuilder value = new StringBuilder("schema=1\nobserver_uuid=").append(observer.getUUID()).append('\n');
        for (P5DecorCiScenario.DecorRound round : P5DecorCiScenario.ROUNDS) {
            int slot = round.index() - 1;
            String prefix = "round" + round.index() + "_";
            value.append(prefix).append("block=").append(P5DecorCiScenario.position(P5DecorCiScenario.target(observer.level(), round.index()))).append('\n');
            value.append(prefix).append("state=").append(round.blockId()).append('\n');
            value.append(prefix).append("drop=").append(dropObserved[slot]).append('\n');
            value.append(prefix).append("item=").append(round.itemId()).append('\n');
        }
        atomicWrite(marker, value.toString(), "P5 装饰方块真实观察 marker");
    }

    private static void atomicWrite(Path marker, String value, String label) {
        try {
            Path parent = marker.getParent();
            if (parent == null) throw new IllegalStateException(label + " 缺少父目录：" + marker);
            Files.createDirectories(parent);
            // 脚本只能把文件存在当作“可以请求服务端逐字段核验”，不能读到半写文件。
            Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("无法原子写入" + label + "：" + marker, exception);
        }
    }
}
