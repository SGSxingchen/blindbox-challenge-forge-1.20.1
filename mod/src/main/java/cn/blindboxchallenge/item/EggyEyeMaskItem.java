package cn.blindboxchallenge.item;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.registry.ModItems;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 037-C：头部饰品；仅由服务端装备和效果事件维护，不遍历世界中的生物。 */
public final class EggyEyeMaskItem extends ArmorItem {
    private static final String ARMOR_TEXTURE = new ResourceLocation(BlindBoxChallenge.MOD_ID,
            "textures/models/armor/eggy_eye_mask_layer_1.png").toString();
    private static final String OWNED_BLINDNESS_KEY = "blindboxchallenge_eggy_eye_mask_blindness";
    /** 效果到期/被清除的事件发生在原版修改效果表之前，延后到服务端刻末补回。 */
    private static final Set<LivingEntity> PENDING_BLINDNESS = Collections.newSetFromMap(new WeakHashMap<>());

    public EggyEyeMaskItem(ArmorMaterial material) {
        super(material, Type.HELMET, new Item.Properties());
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return ARMOR_TEXTURE;
    }

    /** 仅在尚未存在外部失明时写入无限时长效果，避免覆盖其他玩法来源的失明。 */
    public static void onEquipped(LivingEntity wearer) {
        if (wearer.level().isClientSide) return;
        if (wearer.hasEffect(MobEffects.BLINDNESS)) return;
        wearer.getPersistentData().remove(OWNED_BLINDNESS_KEY);
        if (wearer.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, -1, 0, false, false, false))) {
            wearer.getPersistentData().putBoolean(OWNED_BLINDNESS_KEY, true);
        }
    }

    /** 只移除由本物品写入且仍为无限时长的效果，避免误清除外部短时失明。 */
    public static void onUnequipped(LivingEntity wearer) {
        if (wearer.level().isClientSide) return;
        boolean owned = ownsCurrentBlindness(wearer);
        wearer.getPersistentData().remove(OWNED_BLINDNESS_KEY);
        PENDING_BLINDNESS.remove(wearer);
        if (owned) wearer.removeEffect(MobEffects.BLINDNESS);
    }

    public static boolean isWearing(LivingEntity wearer) {
        return wearer.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.EGGY_EYE_MASK.get());
    }

    public static boolean ownsCurrentBlindness(LivingEntity wearer) {
        MobEffectInstance blindness = wearer.getEffect(MobEffects.BLINDNESS);
        return wearer.getPersistentData().getBoolean(OWNED_BLINDNESS_KEY)
                && blindness != null && blindness.isInfiniteDuration() && blindness.getAmplifier() == 0
                && !blindness.isAmbient() && !blindness.isVisible() && !blindness.showIcon();
    }

    /** 在原版读取旧效果之前让出本物品效果，避免外部短时失明下方藏入无限失明。 */
    public static void beforeExternalBlindness(LivingEntity wearer) {
        if (!ownsCurrentBlindness(wearer)) return;
        wearer.getPersistentData().remove(OWNED_BLINDNESS_KEY);
        wearer.removeEffect(MobEffects.BLINDNESS);
    }

    public static void queueBlindnessMaintenance(LivingEntity wearer) {
        if (!wearer.level().isClientSide && isWearing(wearer)) PENDING_BLINDNESS.add(wearer);
    }

    /** 只处理本刻收到效果事件的穿戴者；弱引用避免退出世界后保留实体。 */
    public static void processPendingBlindness() {
        var pending = PENDING_BLINDNESS.iterator();
        while (pending.hasNext()) {
            LivingEntity wearer = pending.next();
            pending.remove();
            if (!wearer.isRemoved() && wearer.isAlive() && isWearing(wearer)) onEquipped(wearer);
        }
    }
}
