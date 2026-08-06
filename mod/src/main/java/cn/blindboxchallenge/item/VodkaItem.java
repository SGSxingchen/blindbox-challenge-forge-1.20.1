package cn.blindboxchallenge.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** 020：微醺持续时间完全由逻辑服务端维护；客户端只使用原版已同步的恶心视觉。 */
public final class VodkaItem extends Item {
    public static final int DRUNK_DURATION_TICKS = 600;

    public VodkaItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity living) {
        if (!level.isClientSide) {
            applyDrunkEffect(living);
            level.playSound(null, living.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.6F, 0.9F);
            if (!(living instanceof Player player) || !player.getAbilities().instabuild) stack.shrink(1);
        }
        return stack;
    }

    /** 供服务端业务探针走生产效果写入路径；没有任何客户端计时状态。 */
    public static void applyDrunkEffect(LivingEntity living) {
        living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, DRUNK_DURATION_TICKS, 0, false, true, true));
    }

    @Override public int getUseDuration(ItemStack stack) { return 24; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
}
