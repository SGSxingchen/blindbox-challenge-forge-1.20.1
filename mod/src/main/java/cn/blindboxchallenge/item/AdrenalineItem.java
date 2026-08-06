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

/** 003：服务端权威施加 30 秒药效的注射型消耗品。 */
public final class AdrenalineItem extends Item {
    public AdrenalineItem() {
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
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 1));
            living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 600, 1));
            living.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 3));
            living.addEffect(new MobEffectInstance(MobEffects.SATURATION, 1, 0));
            level.playSound(null, living.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.6F, 1.25F);
            if (!(living instanceof Player player) || !player.getAbilities().instabuild) stack.shrink(1);
        }
        return stack;
    }

    @Override public int getUseDuration(ItemStack stack) { return 24; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
}
