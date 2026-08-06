package cn.blindboxchallenge.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;

/** 005-B：木剑基线，并提供等同击退 II 的近战击退增量。 */
public final class FairyWandItem extends SwordItem {
    private static final UUID KNOCKBACK_UUID = UUID.fromString("80e3c896-f72c-44ac-a277-3ca819fd5005");
    private final Multimap<Attribute, AttributeModifier> mainHandAttributes;

    public FairyWandItem() {
        super(Tiers.WOOD, 3, -2.4F, new Item.Properties().durability(Tiers.WOOD.getUses()));
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.putAll(super.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND));
        builder.put(Attributes.ATTACK_KNOCKBACK,
                new AttributeModifier(KNOCKBACK_UUID, "Fairy wand knockback", 2.0D, AttributeModifier.Operation.ADDITION));
        mainHandAttributes = builder.build();
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? mainHandAttributes : super.getDefaultAttributeModifiers(slot);
    }
}
