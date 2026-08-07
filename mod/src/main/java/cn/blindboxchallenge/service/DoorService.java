package cn.blindboxchallenge.service;

import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import cn.blindboxchallenge.data.DoorInvalidationSavedData;
import cn.blindboxchallenge.registry.ModBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

/** 037-B 只在逻辑服务端配对和传送；绝不加载远端区块或接受客户端传送数据。 */
public final class DoorService {
    private static final long TELEPORT_COOLDOWN_TICKS = 20L;
    private static final Map<UUID, GlobalPos> SELECTED_DOORS = new HashMap<>();
    private static final Map<UUID, Long> TELEPORT_COOLDOWNS = new HashMap<>();
    /** 落在“门正下方安全点”时玩家的碰撞盒仍位于门格；离开该格前绝不能被反向再传送。 */
    private static final Map<UUID, GlobalPos> ARRIVAL_DOOR_IMMUNITIES = new HashMap<>();
    /**
     * 门不能在 entityInside / 玩家移动包调用栈内直接传送：原版会继续消费同一位置包。这里只保存
     * 已通过入口可信检查且由原版实际 entityInside 调用捕获的源门，ServerTick.END 会按 UUID
     * 与全量门事实重新验；不能在该时点再要求碰撞盒留在门格，服务器追帧会先消费多个移动包。
     */
    private static final Map<UUID, GlobalPos> PENDING_TELEPORTS = new HashMap<>();

    private DoorService() {}

    public static void selectOrPair(ServerPlayer player, Level level, BlockPos clicked) {
        if (!player.mayBuild() || !level.mayInteract(player, clicked) || !(level instanceof ServerLevel sourceLevel)
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
        // Entity#checkInsideBlocks 会复用 MutableBlockPos 继续枚举相邻格。延迟到 ServerTick.END
        // 消费时绝不能保留这个可变游标，否则候选源门会被悄然改写成相邻空气格而永久拒绝。
        BlockPos sourceSnapshot = sourcePos.immutable();
        if (!(level instanceof ServerLevel sourceLevel) || player.isPassenger() || !player.mayBuild()
                || !sourceLevel.mayInteract(player, sourceSnapshot)) return;
        GlobalPos sourceGlobal = GlobalPos.of(sourceLevel.dimension(), sourceSnapshot);
        if (sourceGlobal.equals(ARRIVAL_DOOR_IMMUNITIES.get(player.getUUID()))) return;
        long now = sourceLevel.getGameTime();
        if (TELEPORT_COOLDOWNS.getOrDefault(player.getUUID(), Long.MIN_VALUE) + TELEPORT_COOLDOWN_TICKS > now) return;
        if (!(sourceLevel.getBlockEntity(sourceSnapshot) instanceof AnywhereDoorBlockEntity source)) return;
        reconcileInvalidatedDoor(sourceLevel, source);
        if (!source.linked()) return;
        // 同一 tick 同一玩家只允许一个候选；此 Map 不是授权结果，执行前必须重新校验全部远端事实。
        PENDING_TELEPORTS.putIfAbsent(player.getUUID(), sourceGlobal);
    }

    /** ServerTick.END 在原始移动包已经返回后消费真实入门请求；拒绝死亡、换维或状态变化的请求。 */
    public static void processPendingTeleports(MinecraftServer server) {
        if (PENDING_TELEPORTS.isEmpty()) return;
        Map<UUID, GlobalPos> pending = new HashMap<>(PENDING_TELEPORTS);
        PENDING_TELEPORTS.clear();
        for (Map.Entry<UUID, GlobalPos> entry : pending.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            GlobalPos source = entry.getValue();
            if (player == null || !player.isAlive() || player.isPassenger() || !player.mayBuild()
                    || player.isChangingDimension() || !source.dimension().equals(player.serverLevel().dimension())) continue;
            // PENDING 只能由本服务器 tick 的 Block#entityInside 写入。追帧时同一 tick 可先消费多个
            // 合法移动包，玩家的最终 AABB 已越过门格；以末态 AABB 拒绝会吞掉已经发生的真实入门。
            // 下方 execute 仍对源门、权限、冷却、反链、安全点、区块和出口做完整当前态重验。
            executeVerifiedTeleport(player, player.serverLevel(), source.pos());
        }
    }

    /** 首次进入门体与延迟消费共用这一整套权威重验；延迟路径绝不信任首次排队时的远端状态。 */
    private static void executeVerifiedTeleport(ServerPlayer player, ServerLevel sourceLevel, BlockPos sourcePos) {
        if (player.isPassenger() || !player.mayBuild()
                || !sourceLevel.mayInteract(player, sourcePos)) return;
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
        /*
         * 延迟到 ServerTick.END 后 changeDimension 仍可能立即让目标门收到 entityInside 回调；
         * 因此在迁移前预留已完整校验过的目标门免疫。迁移失败则精确恢复此前状态，不能把
         * 失败传送伪装成永久免疫。
         */
        UUID playerId = player.getUUID();
        GlobalPos previousArrivalImmunity = ARRIVAL_DOOR_IMMUNITIES.put(playerId, targetDoorGlobal);
        if (!moveToVerifiedDestination(player, targetLevel, destination)) {
            if (previousArrivalImmunity == null) ARRIVAL_DOOR_IMMUNITIES.remove(playerId);
            else ARRIVAL_DOOR_IMMUNITIES.put(playerId, previousArrivalImmunity);
            return;
        }
        TELEPORT_COOLDOWNS.put(player.getUUID(), now);
    }

    /** 同维走原版位置包；跨维必须经 Forge 的 changeDimension 更新服务端世界、客户端维度包与跟踪状态。 */
    private static boolean moveToVerifiedDestination(ServerPlayer player, ServerLevel targetLevel, Vec3 destination) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (player.serverLevel().dimension().equals(targetLevel.dimension())) {
            player.teleportTo(targetLevel, destination.x, destination.y, destination.z, yaw, pitch);
        } else if (player.changeDimension(targetLevel, new SafeDoorTeleporter(destination, yaw, pitch)) != player
                || player.serverLevel() != targetLevel || player.position().distanceToSqr(destination) > 1.0E-6D) {
            return false;
        }
        // ServerPlayer 的位置同步不会清除进入源门时的惯性。安全落点是站立格，故必须权威归零并同步速度。
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.resetFallDistance();
        return true;
    }

    /** 非原版传送器禁止创建门户，且只把已完成安全校验的精确坐标交给原版跨维玩家迁移流程。 */
    private static final class SafeDoorTeleporter implements ITeleporter {
        private final Vec3 destination;
        private final float yaw;
        private final float pitch;

        private SafeDoorTeleporter(Vec3 destination, float yaw, float pitch) {
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
        PENDING_TELEPORTS.remove(player.getUUID());
    }

    /** 仅在真实离开抵达门格后撤销免疫，之后再次进入该门仍可正常反向传送。 */
    public static void clearArrivalImmunityAfterExit(ServerPlayer player) {
        GlobalPos arrival = ARRIVAL_DOOR_IMMUNITIES.get(player.getUUID());
        if (arrival == null) return;
        // 跨维迁移尚未完成时，玩家仍可能在源世界的 tick 收尾中；此时绝不能把为目标门
        // 预留的免疫误判为“已离开”，否则同一迁移调用栈的目标门会反向回跳。
        if (player.isChangingDimension()) return;
        // 方块坐标已经跨边界时，玩家宽度 0.6 的碰撞盒仍可能压在无碰撞门格内；必须等真实
        // 碰撞盒完全离开才能解除，避免下一 tick 又从目标门反向排队。
        if (!arrival.dimension().equals(player.serverLevel().dimension()) || !insideDoorCell(player, arrival.pos())) {
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

    /** 与原版无碰撞门的 entityInside 语义对齐：延迟一个 tick 后仍须保持碰撞盒和源门方块相交。 */
    private static boolean insideDoorCell(ServerPlayer player, BlockPos door) {
        return player.getBoundingBox().intersects(new AABB(door.getX(), door.getY(), door.getZ(),
                door.getX() + 1.0D, door.getY() + 1.0D, door.getZ() + 1.0D));
    }
}
