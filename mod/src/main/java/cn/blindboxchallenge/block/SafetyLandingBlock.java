package cn.blindboxchallenge.block;

import cn.blindboxchallenge.service.DoorService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 任意门落点的独立完整方块；被拆除时只清理已加载邻门，不跨维强加载。 */
public final class SafetyLandingBlock extends Block {
    public SafetyLandingBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) DoorService.invalidateNearbySafety(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
