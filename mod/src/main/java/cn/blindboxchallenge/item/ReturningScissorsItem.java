package cn.blindboxchallenge.item;

import cn.blindboxchallenge.entity.ReturningScissorsEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/** 045：使用三叉戟的物品基线，释放时只在逻辑服务端生成自定义返航实体。 */
public final class ReturningScissorsItem extends TridentItem {
    public static final int THROW_THRESHOLD_TICKS = 10;
    public static final float THROW_SPEED = 2.5F;

    public ReturningScissorsItem() {
        super(new Item.Properties().durability(250));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 与原版三叉戟一致：最后一点耐久不能开始蓄力，避免松键时才无提示拒绝投掷。
        if (!player.getAbilities().instabuild && stack.isDamageableItem()
                && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (!(living instanceof ServerPlayer player)) return;
        int chargedTicks = getUseDuration(stack) - timeLeft;
        if (chargedTicks < THROW_THRESHOLD_TICKS || stack.isEmpty() || level.isClientSide) return;

        ItemStack thrownStack = stack.copyWithCount(1);
        // 耐久只预先写入将由实体持有的副本；实体加入失败时原手持栈（数量与 NBT）保持完全不变。
        if (!player.getAbilities().instabuild && (thrownStack.hurt(1, player.getRandom(), player) || thrownStack.isEmpty())) {
            // 不把已折断或空栈写入实体：拒绝本次投掷，原手持栈仍保持不变，避免生成永久无法回收的空实体。
            return;
        }
        ReturningScissorsEntity scissors = new ReturningScissorsEntity(level, player, thrownStack);
        scissors.setReturnItem(!player.getAbilities().instabuild);
        scissors.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, THROW_SPEED, 1.0F);
        // 先确认实体已进入服务端世界，再从生存玩家原栈扣除，实体创建失败时绝不吞物。
        if (level.addFreshEntity(scissors)) {
            if (!player.getAbilities().instabuild) stack.shrink(1);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THROW,
                    SoundSource.PLAYERS, 1.0F, 1.0F);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }
}
