package cn.blindboxchallenge.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeMod;

/** 004：主、副手各提供攻击力 +1 与实体攻击距离 +2 的美甲。 */
public final class NailItem extends Item {
    /*
     * AttributeModifier 的 UUID 会参与同一实体上的去重。四个槽位/属性组合必须各自独立，
     * 否则同时装备在主副手时，后加入的一侧会覆盖前一侧的效果。
     */
    private static final UUID MAIN_HAND_DAMAGE_UUID = UUID.fromString("bb7dfb24-a005-4f4c-96cc-4d9a5e0b7004");
    private static final UUID MAIN_HAND_REACH_UUID = UUID.fromString("bb7dfb24-a005-4f4c-96cc-4d9a5e0b7005");
    private static final UUID OFF_HAND_DAMAGE_UUID = UUID.fromString("bb7dfb24-a005-4f4c-96cc-4d9a5e0b7006");
    private static final UUID OFF_HAND_REACH_UUID = UUID.fromString("bb7dfb24-a005-4f4c-96cc-4d9a5e0b7007");

    private final Multimap<Attribute, AttributeModifier> mainHandAttributes;
    private final Multimap<Attribute, AttributeModifier> offHandAttributes;

    public NailItem() {
        super(new Properties().stacksTo(1));
        mainHandAttributes = createAttributes(MAIN_HAND_DAMAGE_UUID, MAIN_HAND_REACH_UUID, "Main-hand nail art");
        offHandAttributes = createAttributes(OFF_HAND_DAMAGE_UUID, OFF_HAND_REACH_UUID, "Off-hand nail art");
    }

    private static Multimap<Attribute, AttributeModifier> createAttributes(UUID damageUuid, UUID reachUuid, String name) {
        return ImmutableMultimap.<Attribute, AttributeModifier>builder()
                .put(Attributes.ATTACK_DAMAGE, new AttributeModifier(damageUuid, name + " damage", 1.0D,
                        AttributeModifier.Operation.ADDITION))
                .put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(reachUuid, name + " reach", 2.0D,
                        AttributeModifier.Operation.ADDITION))
                .build();
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) return mainHandAttributes;
        if (slot == EquipmentSlot.OFFHAND) return offHandAttributes;
        return super.getDefaultAttributeModifiers(slot);
    }
}
