package cn.blindboxchallenge.block;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.service.MusicBoxService;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 047-B：潜行或未配置时打开服务端菜单，普通右键只由服务端向当时在线玩家广播一次播放事件。 */
public final class MusicBoxBlock extends BaseEntityBlock {
    public MusicBoxBlock(Properties properties) { super(properties); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof MusicBoxBlockEntity box)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown() || !box.configured()) MusicBoxService.openEditor(serverPlayer, box);
        else MusicBoxService.play(serverPlayer, box);
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MusicBoxBlockEntity(pos, state); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
}
