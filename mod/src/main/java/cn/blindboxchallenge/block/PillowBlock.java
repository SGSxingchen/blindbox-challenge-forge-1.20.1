package cn.blindboxchallenge.block;

import cn.blindboxchallenge.entity.PillowSeatEntity;
import cn.blindboxchallenge.entity.PillowVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 008、016 的可坐抱枕方块；座位创建和乘骑只由逻辑服务端决定。 */
public final class PillowBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 7.0D, 15.0D);
    private final PillowVariant variant;

    public PillowBlock(PillowVariant variant, BlockBehaviour.Properties properties) {
        super(properties);
        this.variant = variant;
    }

    public PillowVariant variant() {
        return variant;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() || player.isSpectator()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        PillowSeatEntity seat = PillowSeatEntity.findOrCreate(level, pos, variant);
        if (seat == null) return InteractionResult.CONSUME;
        boolean hadPassenger = !seat.getPassengers().isEmpty();
        if (!player.startRiding(seat, false) && !hadPassenger) seat.discard();
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) PillowSeatEntity.removeAt(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
