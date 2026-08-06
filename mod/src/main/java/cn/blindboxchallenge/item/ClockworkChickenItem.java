package cn.blindboxchallenge.item;

import cn.blindboxchallenge.entity.ClockworkChickenEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

/** 046-D：仅逻辑服务端创建、武装和扣除小黄鸡；客户端不计算 Fuse 或爆炸。 */
public final class ClockworkChickenItem extends Item {
    public ClockworkChickenItem() {
        super(new Properties().stacksTo(16));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            ClockworkChickenEntity chicken = new ClockworkChickenEntity(level, player);
            if (!level.addFreshEntity(chicken)) return InteractionResultHolder.fail(stack);
            if (!player.getAbilities().instabuild) stack.shrink(1);
            player.awardStat(Stats.ITEM_USED.get(this));
            level.playSound(null, chicken.stableBlockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.0F, 1.0F);
            level.gameEvent(player, GameEvent.PRIME_FUSE, chicken.position());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
