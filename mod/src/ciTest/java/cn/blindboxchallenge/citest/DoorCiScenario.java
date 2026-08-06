package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.DoorService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** P4 门的服务端核心探针：正式 Block#use、entityInside、反链与不安全落点拒绝均在真实专服执行。 */
public final class DoorCiScenario {
    private DoorCiScenario() {}

    public static int run(CommandSourceStack source) {
        ServerPlayer alice = source.getServer().getPlayerList().getPlayerByName("BlindBoxAlice");
        if (alice == null) {
            source.sendFailure(Component.literal("CI 任意门探针缺少 Alice"));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        BlockPos origin = level.getSharedSpawnPos().offset(0, 160, 0);
        BlockPos firstDoor = origin;
        BlockPos firstSafety = firstDoor.below();
        BlockPos secondDoor = origin.east(6);
        BlockPos secondSafety = secondDoor.below();
        BlockPos firstSideSafety = firstDoor.west();
        BlockPos secondSideSafety = secondDoor.east();
        BlockPos extraFirstSafety = firstDoor.east();
        BlockPos collisionExit = secondSideSafety.above();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos pos : java.util.List.of(firstDoor, firstSafety, secondDoor, secondSafety, firstSideSafety, secondSideSafety, extraFirstSafety, collisionExit)) {
            before.put(pos, level.getBlockState(pos));
            if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("任意门夹具区域必须为空气，拒绝覆盖既有方块实体");
        }
        Vec3 originalPosition = alice.position();
        float originalYaw = alice.getYRot();
        float originalPitch = alice.getXRot();
        try {
            level.setBlock(firstSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            level.setBlock(firstDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            level.setBlock(secondSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            level.setBlock(secondDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(firstDoor) instanceof AnywhereDoorBlockEntity first)
                    || !(level.getBlockEntity(secondDoor) instanceof AnywhereDoorBlockEntity second)) {
                throw new IllegalStateException("任意门方块实体未创建");
            }
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            assertBidirectionallyLinked(first, second);
            alice.teleportTo(level, firstDoor.getX() + 0.5D, firstDoor.getY() + 0.1D, firstDoor.getZ() + 0.5D, 0.0F, 0.0F);
            ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(firstDoor), level, firstDoor, alice);
            Vec3 expected = Vec3.atBottomCenterOf(secondSafety.above());
            if (alice.position().distanceToSqr(expected) > 1.0D) throw new IllegalStateException("进入门体后未抵达目标安全落点");
            // 目标安全点在门下方时，抵达位置仍在无碰撞门格。立即再次触发门体不能反向回跳。
            ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(secondDoor), level, secondDoor, alice);
            if (alice.position().distanceToSqr(expected) > 1.0D) throw new IllegalStateException("下方安全点抵达后发生反向回跳");
            // 配对后临时增加第二个相邻安全点也会破坏“两门各恰有一个安全点”不变量，必须拒绝并清链。
            level.setBlock(extraFirstSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            assertRejectedAtSource(level, alice, firstDoor, "存在第二安全点时仍发生传送");
            if (first.linked() || second.linked()) throw new IllegalStateException("存在第二安全点时未清理双方关联");
            level.setBlock(extraFirstSafety, Blocks.AIR.defaultBlockState(), 3);
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            assertBidirectionallyLinked(first, second);
            level.setBlock(secondSafety, Blocks.AIR.defaultBlockState(), 3);
            if (first.linked() || second.linked()) throw new IllegalStateException("拆除目标侧安全点后未清理双方关联");
            DoorService.clearSelection(alice);
            alice.teleportTo(level, firstDoor.getX() + 0.5D, firstDoor.getY() + 0.1D, firstDoor.getZ() + 0.5D, 0.0F, 0.0F);
            ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(firstDoor), level, firstDoor, alice);
            if (alice.blockPosition().distSqr(firstDoor) > 4.0D) throw new IllegalStateException("缺失安全落点时仍发生传送");

            // 再配对后拆入口侧安全点，同样必须立即令伙伴门失效。
            level.setBlock(secondSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            level.setBlock(firstSafety, Blocks.AIR.defaultBlockState(), 3);
            if (first.linked() || second.linked()) throw new IllegalStateException("拆除入口侧安全点后未清理双方关联");

            // 拆任一门都必须清理另一门；此处同时覆盖 onRemove 的服务端路径。
            level.setBlock(firstSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            level.setBlock(firstDoor, Blocks.AIR.defaultBlockState(), 3);
            if (second.linked()) throw new IllegalStateException("拆除第一扇门后伙伴门仍保留关联");
            level.setBlock(firstDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(firstDoor) instanceof AnywhereDoorBlockEntity rebuiltFirst)) {
                throw new IllegalStateException("重建第一扇门后方块实体缺失");
            }
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            level.setBlock(secondDoor, Blocks.AIR.defaultBlockState(), 3);
            if (rebuiltFirst.linked()) throw new IllegalStateException("拆除第二扇门后伙伴门仍保留关联");

            // 损坏 NBT 中的自指、未加载远端与出口碰撞都只能拒绝，且不能创建远端区块票据。
            level.setBlock(secondDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(firstDoor) instanceof AnywhereDoorBlockEntity currentFirst)) {
                throw new IllegalStateException("任意门重建后的方块实体缺失");
            }
            currentFirst.link(currentFirst.doorId(), net.minecraft.core.GlobalPos.of(level.dimension(), firstDoor),
                    net.minecraft.core.GlobalPos.of(level.dimension(), firstSafety));
            assertRejectedAtSource(level, alice, firstDoor, "自指门关联仍发生传送");
            BlockPos unloaded = new BlockPos(2_000_000, firstDoor.getY(), 2_000_000);
            if (level.hasChunkAt(unloaded)) throw new IllegalStateException("未加载门夹具坐标意外已加载");
            currentFirst.link(UUID.randomUUID(), net.minecraft.core.GlobalPos.of(level.dimension(), unloaded),
                    net.minecraft.core.GlobalPos.of(level.dimension(), unloaded.below()));
            assertRejectedAtSource(level, alice, firstDoor, "未加载目标门仍发生传送");
            if (level.hasChunkAt(unloaded)) throw new IllegalStateException("拒绝未加载门时错误强加载了远端区块");

            level.setBlock(firstSafety, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(secondSafety, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(firstSideSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            level.setBlock(secondSideSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            currentFirst.clearLink();
            if (!(level.getBlockEntity(secondDoor) instanceof AnywhereDoorBlockEntity currentSecond)) {
                throw new IllegalStateException("第二扇门重建后的方块实体缺失");
            }
            currentSecond.clearLink();
            pairWithProductionUse(level, alice, firstDoor, secondDoor);
            assertBidirectionallyLinked(currentFirst, currentSecond);
            level.setBlock(collisionExit, Blocks.OBSIDIAN.defaultBlockState(), 3);
            assertRejectedAtSource(level, alice, firstDoor, "出口碰撞时仍发生传送");
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_DOOR=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 任意门探针失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P4 door scenario", exception);
            return 0;
        } finally {
            alice.setShiftKeyDown(false);
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
            alice.teleportTo(level, originalPosition.x, originalPosition.y, originalPosition.z, originalYaw, originalPitch);
        }
    }

    private static void pairWithProductionUse(ServerLevel level, ServerPlayer player, BlockPos first, BlockPos second) {
        DoorService.clearSelection(player);
        player.setShiftKeyDown(true);
        try {
            ModBlocks.ANYWHERE_DOOR.get().use(level.getBlockState(first), level, first, player,
                    net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(first), net.minecraft.core.Direction.UP, first, false));
            ModBlocks.ANYWHERE_DOOR.get().use(level.getBlockState(second), level, second, player,
                    net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(second), net.minecraft.core.Direction.UP, second, false));
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    private static void assertBidirectionallyLinked(AnywhereDoorBlockEntity first, AnywhereDoorBlockEntity second) {
        if (!first.linked() || !second.linked() || !first.partnerDoorId().filter(second.doorId()::equals).isPresent()
                || !second.partnerDoorId().filter(first.doorId()::equals).isPresent()) {
            throw new IllegalStateException("潜行正式配对入口没有写入双向反链");
        }
    }

    private static void assertRejectedAtSource(ServerLevel level, ServerPlayer player, BlockPos source, String message) {
        DoorService.clearSelection(player);
        player.teleportTo(level, source.getX() + 0.5D, source.getY() + 0.1D, source.getZ() + 0.5D, 0.0F, 0.0F);
        ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(source), level, source, player);
        if (player.blockPosition().distSqr(source) > 4.0D) throw new IllegalStateException(message);
    }
}
