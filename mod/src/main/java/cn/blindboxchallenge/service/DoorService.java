package cn.blindboxchallenge.service;

import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import cn.blindboxchallenge.data.DoorInvalidationSavedData;
import cn.blindboxchallenge.registry.ModBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 037-B 只在逻辑服务端配对和传送；绝不加载远端区块或接受客户端传送数据。 */
public final class DoorService {
    private static final long TELEPORT_COOLDOWN_TICKS = 20L;
    private static final Map<UUID, GlobalPos> SELECTED_DOORS = new HashMap<>();
    private static final Map<UUID, Long> TELEPORT_COOLDOWNS = new HashMap<>();
    /** 落在“门正下方安全点”时玩家的碰撞盒仍位于门格；离开该格前绝不能被反向再传送。 */
    private static final Map<UUID, GlobalPos> ARRIVAL_DOOR_IMMUNITIES = new HashMap<>();

    private DoorService() {}

    public static void selectOrPair(ServerPlayer player, Level level, BlockPos clicked) {
        if (!player.mayBuild(clicked) || !(level instanceof ServerLevel sourceLevel)
                || !(sourceLevel.getBlockEntity(clicked) instanceof AnywhereDoorBlockEntity current)) return;
        reconcileInvalidatedDoor(sourceLevel, current);
        if (current.linked()) {
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_already_linked"), true);
            return;
        }
        GlobalPos currentGlobal = GlobalPos.of(sourceLevel.dimension(), clicked);
        GlobalPos selected = SELECTED_DOORS.get(player.getUUID());
        if (selected == null) {
            SELECTED_DOORS.put(player.getUUID(), currentGlobal);
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_selected"), true);
            return;
        }
        if (selected.equals(currentGlobal)) {
            SELECTED_DOORS.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_same_rejected"), true);
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel firstLevel = server.getLevel(selected.dimension());
        if (firstLevel == null || !firstLevel.hasChunkAt(selected.pos())
                || !(firstLevel.getBlockEntity(selected.pos()) instanceof AnywhereDoorBlockEntity first)) {
            SELECTED_DOORS.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_first_invalid"), true);
            return;
        }
        reconcileInvalidatedDoor(firstLevel, first);
        if (first.linked()) {
            SELECTED_DOORS.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_first_invalid"), true);
            return;
        }
        List<BlockPos> firstSafety = adjacentSafety(firstLevel, selected.pos());
        List<BlockPos> secondSafety = adjacentSafety(sourceLevel, clicked);
        if (firstSafety.size() != 1 || secondSafety.size() != 1) {
            SELECTED_DOORS.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_safety_required"), true);
            return;
        }
        GlobalPos firstGlobal = selected;
        GlobalPos secondGlobal = currentGlobal;
        first.link(current.doorId(), secondGlobal, GlobalPos.of(sourceLevel.dimension(), secondSafety.get(0)));
        current.link(first.doorId(), firstGlobal, GlobalPos.of(firstLevel.dimension(), firstSafety.get(0)));
        SELECTED_DOORS.remove(player.getUUID());
        player.displayClientMessage(Component.translatable("message.blindboxchallenge.door_linked"), true);
    }

    /** 玩家进入无碰撞门格后重验所有事实；任一失败都保持原地且不加载区块。 */
    public static void tryTeleport(ServerPlayer player, Level level, BlockPos sourcePos) {
        if (!(level instanceof ServerLevel sourceLevel) || player.isPassenger()) return;
        GlobalPos sourceGlobal = GlobalPos.of(sourceLevel.dimension(), sourcePos);
        if (sourceGlobal.equals(ARRIVAL_DOOR_IMMUNITIES.get(player.getUUID()))) return;
        long now = sourceLevel.getGameTime();
        if (TELEPORT_COOLDOWNS.getOrDefault(player.getUUID(), Long.MIN_VALUE) + TELEPORT_COOLDOWN_TICKS > now) return;
        if (!(sourceLevel.getBlockEntity(sourcePos) instanceof AnywhereDoorBlockEntity source)) return;
        reconcileInvalidatedDoor(sourceLevel, source);
        if (!source.linked()) return;
        GlobalPos targetDoorGlobal = source.partnerDoor().orElse(null);
        GlobalPos targetSafetyGlobal = source.destinationSafety().orElse(null);
        UUID targetDoorId = source.partnerDoorId().orElse(null);
        if (targetDoorGlobal == null || targetSafetyGlobal == null || targetDoorId == null
                || targetDoorGlobal.equals(sourceGlobal)
                || !targetDoorGlobal.dimension().equals(targetSafetyGlobal.dimension())) return;
        List<BlockPos> sourceSafety = adjacentSafety(sourceLevel, sourcePos);
        if (sourceSafety.size() != 1) {
            invalidateDoor(sourceLevel, sourcePos);
            return;
        }

        MinecraftServer server = player.serverLevel().getServer();
        ServerLevel targetLevel = server.getLevel(targetDoorGlobal.dimension());
        // hasChunkAt 不会创建票据或强加载；任何未加载远端都一律拒绝。
        if (targetLevel == null || !targetLevel.hasChunkAt(targetDoorGlobal.pos()) || !targetLevel.hasChunkAt(targetSafetyGlobal.pos())) return;
        if (!(targetLevel.getBlockEntity(targetDoorGlobal.pos()) instanceof AnywhereDoorBlockEntity target)) return;
        reconcileInvalidatedDoor(targetLevel, target);
        if (!target.linked()) return;
        List<BlockPos> targetSafety = adjacentSafety(targetLevel, targetDoorGlobal.pos());
        if (targetSafety.size() != 1) {
            invalidateDoor(targetLevel, targetDoorGlobal.pos());
            return;
        }
        if (target.doorId().equals(source.doorId()) || !target.doorId().equals(targetDoorId)
                || !target.partnerDoorId().filter(source.doorId()::equals).isPresent()
                || !target.partnerDoor().filter(sourceGlobal::equals).isPresent()) return;
        if (!isAdjacentSafety(targetDoorGlobal.pos(), targetSafetyGlobal.pos())
                || !targetSafety.get(0).equals(targetSafetyGlobal.pos())
                || !targetLevel.getBlockState(targetSafetyGlobal.pos()).is(ModBlocks.SAFETY_LANDING.get())) return;
        GlobalPos reverseSafety = target.destinationSafety().orElse(null);
        if (reverseSafety == null || !reverseSafety.dimension().equals(sourceLevel.dimension())
                || !isAdjacentSafety(sourcePos, reverseSafety.pos())
                || !sourceSafety.get(0).equals(reverseSafety.pos())
                || !sourceLevel.getBlockState(reverseSafety.pos()).is(ModBlocks.SAFETY_LANDING.get())) return;
        BlockPos standingBlock = targetSafetyGlobal.pos().above();
        if (!targetLevel.isInWorldBounds(standingBlock) || !targetLevel.getWorldBorder().isWithinBounds(standingBlock)) return;
        Vec3 destination = Vec3.atBottomCenterOf(standingBlock);
        AABB playerBox = player.getDimensions(Pose.STANDING).makeBoundingBox(destination);
        if (!targetLevel.noCollision(player, playerBox) || targetLevel.containsAnyLiquid(playerBox)) return;
        player.teleportTo(targetLevel, destination.x, destination.y, destination.z, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        TELEPORT_COOLDOWNS.put(player.getUUID(), now);
        ARRIVAL_DOOR_IMMUNITIES.put(player.getUUID(), targetDoorGlobal);
    }

    public static void invalidateDoor(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sourceLevel) || !(sourceLevel.getBlockEntity(pos) instanceof AnywhereDoorBlockEntity source)) return;
        GlobalPos remoteGlobal = source.partnerDoor().orElse(null);
        UUID localId = source.doorId();
        UUID remoteId = source.partnerDoorId().orElse(null);
        source.clearLink();
        if (remoteGlobal == null || remoteId == null) return;
        ServerLevel remote = sourceLevel.getServer().getLevel(remoteGlobal.dimension());
        if (remote != null && remote.hasChunkAt(remoteGlobal.pos()) && remote.getBlockEntity(remoteGlobal.pos()) instanceof AnywhereDoorBlockEntity other
                && other.doorId().equals(remoteId) && other.partnerDoorId().filter(localId::equals).isPresent()) {
            other.clearLink();
        } else {
            DoorInvalidationSavedData.get(sourceLevel).mark(remoteId);
        }
    }

    public static void invalidateNearbySafety(Level level, BlockPos safetyPos) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(safetyPos.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) candidates.add(safetyPos.relative(direction));
        for (BlockPos candidate : candidates) {
            // 无论被拆的是本门的安全点还是伙伴门的安全点，双向关联都已不再满足
            // “双方各有唯一安全点”的不变量。立即清理本门并在已加载时反向清理伙伴门，
            // 避免只让一个方向因目标缺块而拒绝、另一个方向仍残留过期关联。
            if (serverLevel.getBlockEntity(candidate) instanceof AnywhereDoorBlockEntity) invalidateDoor(serverLevel, candidate);
        }
    }

    public static void clearSelection(ServerPlayer player) {
        SELECTED_DOORS.remove(player.getUUID());
        TELEPORT_COOLDOWNS.remove(player.getUUID());
        ARRIVAL_DOOR_IMMUNITIES.remove(player.getUUID());
    }

    /** 仅在真实离开抵达门格后撤销免疫，之后再次进入该门仍可正常反向传送。 */
    public static void clearArrivalImmunityAfterExit(ServerPlayer player) {
        GlobalPos arrival = ARRIVAL_DOOR_IMMUNITIES.get(player.getUUID());
        if (arrival == null) return;
        if (!arrival.dimension().equals(player.serverLevel().dimension()) || !arrival.pos().equals(player.blockPosition())) {
            ARRIVAL_DOOR_IMMUNITIES.remove(player.getUUID());
        }
    }

    /** 已加载门进入服务端可信入口时消费持久失效回执；整个过程不创建区块票据。 */
    private static void reconcileInvalidatedDoor(ServerLevel level, AnywhereDoorBlockEntity door) {
        if (DoorInvalidationSavedData.get(level).consume(door.doorId())) door.clearLink();
    }

    private static List<BlockPos> adjacentSafety(ServerLevel level, BlockPos doorPos) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos below = doorPos.below();
        if (level.getBlockState(below).is(ModBlocks.SAFETY_LANDING.get())) found.add(below);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = doorPos.relative(direction);
            if (level.getBlockState(candidate).is(ModBlocks.SAFETY_LANDING.get())) found.add(candidate);
        }
        return found;
    }

    private static boolean isAdjacentSafety(BlockPos door, BlockPos safety) {
        if (safety.equals(door.below())) return true;
        for (Direction direction : Direction.Plane.HORIZONTAL) if (safety.equals(door.relative(direction))) return true;
        return false;
    }
}
