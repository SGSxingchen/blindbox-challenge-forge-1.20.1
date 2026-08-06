package cn.blindboxchallenge.item;

import cn.blindboxchallenge.entity.PillowProjectileEntity;
import cn.blindboxchallenge.entity.PillowVariant;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** 对空气长按蓄力投掷；对方块仍沿用 BlockItem 原版放置路径。 */
public final class PillowBlockItem extends BlockItem {
    public static final int MIN_CHARGE_TICKS = 10;
    public static final int MAX_CHARGE_TICKS = 40;
    public static final float MIN_THROW_SPEED = 0.8F;
    public static final float MAX_THROW_SPEED = 1.5F;
    private final PillowVariant variant;

    public PillowBlockItem(Block block, PillowVariant variant) {
        super(block, new Properties());
        this.variant = variant;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (level.isClientSide || !(living instanceof Player player)) return;
        int chargedTicks = getUseDuration(stack) - timeLeft;
        float speed = throwSpeed(chargedTicks);
        if (speed <= 0.0F || stack.isEmpty()) return;

        PillowProjectileEntity projectile = new PillowProjectileEntity(level, player);
        projectile.setPillowStack(stack);
        projectile.setReturnItem(!player.isCreative());
        projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, speed, 1.0F);
        // 实体真实进入服务端世界后才扣除原栈，避免添加失败时吞物。
        if (level.addFreshEntity(projectile) && !player.isCreative()) stack.shrink(1);
    }

    public PillowVariant variant() {
        return variant;
    }

    /** 少于 10 tick 不投掷；10–40 tick 线性蓄力，之后封顶。 */
    public static float throwSpeed(int chargedTicks) {
        if (chargedTicks < MIN_CHARGE_TICKS) return 0.0F;
        float progress = (Mth.clamp(chargedTicks, MIN_CHARGE_TICKS, MAX_CHARGE_TICKS) - MIN_CHARGE_TICKS)
                / (float) (MAX_CHARGE_TICKS - MIN_CHARGE_TICKS);
        return MIN_THROW_SPEED + (MAX_THROW_SPEED - MIN_THROW_SPEED) * progress;
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
