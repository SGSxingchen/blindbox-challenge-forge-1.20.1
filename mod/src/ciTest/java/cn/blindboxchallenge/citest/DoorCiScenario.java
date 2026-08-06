package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.service.DoorService;
import java.util.LinkedHashMap;
import java.util.Map;
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
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos pos : java.util.List.of(firstDoor, firstSafety, secondDoor, secondSafety)) before.put(pos, level.getBlockState(pos));
        try {
            level.setBlock(firstSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            level.setBlock(firstDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            level.setBlock(secondSafety, ModBlocks.SAFETY_LANDING.get().defaultBlockState(), 3);
            level.setBlock(secondDoor, ModBlocks.ANYWHERE_DOOR.get().defaultBlockState(), 3);
            if (!(level.getBlockEntity(firstDoor) instanceof AnywhereDoorBlockEntity first)
                    || !(level.getBlockEntity(secondDoor) instanceof AnywhereDoorBlockEntity second)) {
                throw new IllegalStateException("任意门方块实体未创建");
            }
            alice.setShiftKeyDown(true);
            ModBlocks.ANYWHERE_DOOR.get().use(level.getBlockState(firstDoor), level, firstDoor, alice,
                    net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(firstDoor), net.minecraft.core.Direction.UP, firstDoor, false));
            ModBlocks.ANYWHERE_DOOR.get().use(level.getBlockState(secondDoor), level, secondDoor, alice,
                    net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(secondDoor), net.minecraft.core.Direction.UP, secondDoor, false));
            alice.setShiftKeyDown(false);
            if (!first.linked() || !second.linked() || !first.partnerDoorId().filter(second.doorId()::equals).isPresent()
                    || !second.partnerDoorId().filter(first.doorId()::equals).isPresent()) {
                throw new IllegalStateException("潜行正式配对入口没有写入双向反链");
            }
            alice.teleportTo(level, firstDoor.getX() + 0.5D, firstDoor.getY() + 0.1D, firstDoor.getZ() + 0.5D, 0.0F, 0.0F);
            ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(firstDoor), level, firstDoor, alice);
            Vec3 expected = Vec3.atBottomCenterOf(secondSafety.above());
            if (alice.position().distanceToSqr(expected) > 1.0D) throw new IllegalStateException("进入门体后未抵达目标安全落点");
            level.setBlock(secondSafety, Blocks.AIR.defaultBlockState(), 3);
            DoorService.clearSelection(alice);
            alice.teleportTo(level, firstDoor.getX() + 0.5D, firstDoor.getY() + 0.1D, firstDoor.getZ() + 0.5D, 0.0F, 0.0F);
            ModBlocks.ANYWHERE_DOOR.get().entityInside(level.getBlockState(firstDoor), level, firstDoor, alice);
            if (alice.blockPosition().distSqr(firstDoor) > 4.0D) throw new IllegalStateException("缺失安全落点时仍发生传送");
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_DOOR=success"), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("CI 任意门探针失败：" + exception.getClass().getSimpleName()));
            CiTestProbe.LOGGER.error("Cannot run P4 door scenario", exception);
            return 0;
        } finally {
            alice.setShiftKeyDown(false);
            before.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
    }
}
