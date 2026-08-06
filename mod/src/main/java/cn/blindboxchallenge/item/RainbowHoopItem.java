package cn.blindboxchallenge.item;

import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** 046-G：长按蓄力、松开后只在逻辑服务端修改使用者的竖直速度。 */
public final class RainbowHoopItem extends Item {
    public static final int MIN_CHARGE_TICKS = 10;
    public static final int MAX_CHARGE_TICKS = 40;
    public static final float MIN_LAUNCH_VELOCITY = 0.45F;
    public static final float MAX_LAUNCH_VELOCITY = 0.90F;

    public RainbowHoopItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (level.isClientSide) return;
        int chargedTicks = getUseDuration(stack) - timeLeft;
        float velocity = launchVelocity(chargedTicks);
        if (velocity <= 0.0F) return;
        living.setDeltaMovement(living.getDeltaMovement().x, velocity, living.getDeltaMovement().z);
        living.hurtMarked = true;
    }

    /** 小于 10 tick 不触发；10-40 tick 在线性蓄力，40 tick 后封顶，避免超长按过强。 */
    public static float launchVelocity(int chargedTicks) {
        if (chargedTicks < MIN_CHARGE_TICKS) return 0.0F;
        float progress = (Mth.clamp(chargedTicks, MIN_CHARGE_TICKS, MAX_CHARGE_TICKS) - MIN_CHARGE_TICKS)
                / (float) (MAX_CHARGE_TICKS - MIN_CHARGE_TICKS);
        return MIN_LAUNCH_VELOCITY + (MAX_LAUNCH_VELOCITY - MIN_LAUNCH_VELOCITY) * progress;
    }

    @Override public int getUseDuration(ItemStack stack) { return 72000; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.BOW; }
}
