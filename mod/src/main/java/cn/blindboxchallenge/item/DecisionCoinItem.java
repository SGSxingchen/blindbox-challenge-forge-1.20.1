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
import net.minecraft.world.level.Level;

/** 039-B：掷币与效果仅在服务端决定；正反两面均消耗恰好一枚。 */
public final class DecisionCoinItem extends Item {
    public static final int STRENGTH_DURATION_TICKS = 200;

    public DecisionCoinItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            applyOutcome(player, level.random.nextBoolean());
            if (!player.getAbilities().instabuild) stack.shrink(1);
            level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.55F, 1.0F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * true 为正面：力量 II 十秒；false 为反面：仅清除该实体当前的有益效果。
     * 此纯业务分支也让服务端探针可以覆盖两面，而无需伪造或固定真实随机源。
     */
    public static void applyOutcome(LivingEntity living, boolean heads) {
        if (heads) {
            living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, STRENGTH_DURATION_TICKS, 1));
        } else {
            living.getActiveEffects().stream()
                    .filter(effect -> effect.getEffect().isBeneficial())
                    .map(MobEffectInstance::getEffect)
                    .toList()
                    .forEach(living::removeEffect);
        }
    }
}
