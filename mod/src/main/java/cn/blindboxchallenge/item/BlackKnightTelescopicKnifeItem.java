package cn.blindboxchallenge.item;

import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;

/** 001：服务端维护伸缩状态的木剑基线近战物品。 */
public final class BlackKnightTelescopicKnifeItem extends SwordItem {
    public static final String EXTENDED_KEY = "Extended";
    /** 默认 20%，仅在已经伸出的刀命中后由逻辑服务端掷骰。 */
    public static final float AUTO_RETRACT_CHANCE = 0.20F;

    public BlackKnightTelescopicKnifeItem() {
        super(Tiers.WOOD, 3, -2.4F, new Item.Properties().durability(Tiers.WOOD.getUses()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 客户端只回报原版右键成功以发起请求；NBT 只能由服务端改写并随 ItemStack 同步。
        if (!level.isClientSide) setExtended(stack, !isExtended(stack));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        boolean damaged = super.hurtEnemy(stack, target, attacker);
        if (!attacker.level().isClientSide) applyAutoRetractAfterHit(stack, attacker.getRandom().nextFloat());
        return damaged;
    }

    /** 供客户端模型谓词和服务端探针安全读取；损坏或缺失 NBT 一律按收缩处理。 */
    public static boolean isExtended(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(EXTENDED_KEY, Tag.TAG_BYTE)
                && stack.getTag().getBoolean(EXTENDED_KEY);
    }

    /** 仅由生产服务端逻辑调用；状态保存在单个 ItemStack，绝不放入玩家全局状态。 */
    public static void setExtended(ItemStack stack, boolean extended) {
        stack.getOrCreateTag().putBoolean(EXTENDED_KEY, extended);
    }

    /**
     * 将一次由服务端随机源取得的掷骰结果应用到已伸出的刀；便于保持命中逻辑和确定性探针共用同一规则。
     * 等于概率阈值或处于收缩状态时均不收缩。
     */
    public static boolean applyAutoRetractAfterHit(ItemStack stack, float roll) {
        if (!isExtended(stack) || !(roll < AUTO_RETRACT_CHANCE)) return false;
        setExtended(stack, false);
        return true;
    }
}
