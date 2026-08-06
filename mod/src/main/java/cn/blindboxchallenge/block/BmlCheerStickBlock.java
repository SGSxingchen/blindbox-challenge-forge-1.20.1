package cn.blindboxchallenge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/** 028：仅由逻辑服务端切换的应援棒发光状态。 */
public final class BmlCheerStickBlock extends TorchBlock {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public BmlCheerStickBlock(BlockBehaviour.Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIT) ? 14 : 0), ParticleTypes.END_ROD);
        registerDefaultState(stateDefinition.any().setValue(LIT, false));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            boolean lit = !state.getValue(LIT);
            level.setBlock(pos, state.setValue(LIT, lit), 3);
            level.playSound(null, pos, lit ? SoundEvents.GLOW_ITEM_FRAME_ADD_ITEM : SoundEvents.GLOW_ITEM_FRAME_REMOVE_ITEM,
                    SoundSource.BLOCKS, 0.6F, lit ? 1.25F : 0.8F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(LIT);
    }

    /** 墙面变体复用完全相同的服务端开关语义，保持与原版火把一致的放置入口。 */
    public static final class Wall extends WallTorchBlock {
        public Wall(BlockBehaviour.Properties properties) {
            super(properties.lightLevel(state -> state.getValue(LIT) ? 14 : 0), ParticleTypes.END_ROD);
            registerDefaultState(stateDefinition.any().setValue(LIT, false));
        }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                     InteractionHand hand, BlockHitResult hitResult) {
            if (!level.isClientSide) {
                boolean lit = !state.getValue(LIT);
                level.setBlock(pos, state.setValue(LIT, lit), 3);
                level.playSound(null, pos, lit ? SoundEvents.GLOW_ITEM_FRAME_ADD_ITEM : SoundEvents.GLOW_ITEM_FRAME_REMOVE_ITEM,
                        SoundSource.BLOCKS, 0.6F, lit ? 1.25F : 0.8F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
            builder.add(LIT);
        }
    }
}
