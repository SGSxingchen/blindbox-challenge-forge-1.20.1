package cn.blindboxchallenge.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** 046-E：长按完成时由服务端抽取正面药效，并由服务端写入 20 秒冷却。 */
public final class BirthdayCandleItem extends Item {
    public static final int USE_DURATION_TICKS = 32;
    public static final int EFFECT_DURATION_TICKS = 600;
    public static final int COOLDOWN_TICKS = 400;
    private static final MobEffect[] POSITIVE_EFFECTS = {
            MobEffects.MOVEMENT_SPEED, MobEffects.DAMAGE_BOOST, MobEffects.DAMAGE_RESISTANCE, MobEffects.REGENERATION
    };

    public BirthdayCandleItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) return InteractionResultHolder.fail(stack);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity living) {
        if (!level.isClientSide && living instanceof Player player && !player.getCooldowns().isOnCooldown(this)) {
            applyPositiveEffect(player, level.random.nextInt(POSITIVE_EFFECTS.length));
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.15F);
        }
        return stack;
    }

    /** 将服务端随机结果映射为稳定候选；探针逐项传入固定索引，避免概率性门禁。 */
    public static void applyPositiveEffect(LivingEntity living, int roll) {
        MobEffect effect = POSITIVE_EFFECTS[Math.floorMod(roll, POSITIVE_EFFECTS.length)];
        living.addEffect(new MobEffectInstance(effect, EFFECT_DURATION_TICKS, 0));
    }

    public static int positiveEffectCount() {
        return POSITIVE_EFFECTS.length;
    }

    @Override public int getUseDuration(ItemStack stack) { return USE_DURATION_TICKS; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.SPYGLASS; }
}
