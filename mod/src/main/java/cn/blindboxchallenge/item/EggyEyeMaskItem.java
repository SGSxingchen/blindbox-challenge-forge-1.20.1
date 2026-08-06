package cn.blindboxchallenge.item;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;

/** 037-C：头部饰品；由逻辑服务端在穿戴变化时维护其失明效果。 */
public final class EggyEyeMaskItem extends ArmorItem {
    private static final String OWNED_BLINDNESS_KEY = "blindboxchallenge_eggy_eye_mask_blindness";

    public EggyEyeMaskItem(ArmorMaterial material) {
        super(material, Type.HELMET, new Item.Properties());
    }

    /** 仅在尚未存在外部失明时写入无限时长效果，避免覆盖其他玩法来源的失明。 */
    public static void onEquipped(LivingEntity wearer) {
        if (wearer.hasEffect(MobEffects.BLINDNESS)) return;
        wearer.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, -1, 0, false, false, false));
        wearer.getPersistentData().putBoolean(OWNED_BLINDNESS_KEY, true);
    }

    /** 只移除由本物品写入且仍为无限时长的效果，避免误清除外部短时失明。 */
    public static void onUnequipped(LivingEntity wearer) {
        if (wearer.getPersistentData().getBoolean(OWNED_BLINDNESS_KEY)) {
            MobEffectInstance blindness = wearer.getEffect(MobEffects.BLINDNESS);
            if (blindness != null && blindness.isInfiniteDuration()) wearer.removeEffect(MobEffects.BLINDNESS);
        }
        wearer.getPersistentData().remove(OWNED_BLINDNESS_KEY);
    }
}
