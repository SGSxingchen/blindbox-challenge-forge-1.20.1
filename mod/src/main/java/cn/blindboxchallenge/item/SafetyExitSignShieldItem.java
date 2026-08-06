package cn.blindboxchallenge.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/** 029：耐久固定为 5；成功格挡后的反伤在服务端 ShieldBlockEvent 中处理。 */
public final class SafetyExitSignShieldItem extends ShieldItem {
    public static final int DURABILITY = 5;
    public static final float REFLECTION_RATIO = 0.5F;

    public SafetyExitSignShieldItem() {
        super(new Properties().durability(DURABILITY));
    }

    /** 保留单一的生产计算入口，探针以边界值验证而不依赖随机或实体战斗时序。 */
    public static float reflectedDamage(float blockedDamage) {
        return Math.max(0.0F, blockedDamage) * REFLECTION_RATIO;
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repairCandidate) {
        return false;
    }
}
