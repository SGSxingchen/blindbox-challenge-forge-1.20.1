package cn.blindboxchallenge.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.common.ForgeMod;

/** 014：铁剑属性，并额外增加 1 格实体攻击距离。 */
public final class LongScrewdriverItem extends SwordItem {
    private static final UUID REACH_UUID = UUID.fromString("4f67b4d8-0d69-4ea6-bd0e-2a91c0e9e014");
    private final Multimap<Attribute, AttributeModifier> mainHandAttributes;

    public LongScrewdriverItem() {
        super(Tiers.IRON, 3, -2.4F, new Item.Properties().durability(Tiers.IRON.getUses()));
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.putAll(super.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND));
        builder.put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(REACH_UUID, "Long screwdriver reach", 1.0D, AttributeModifier.Operation.ADDITION));
        mainHandAttributes = builder.build();
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? mainHandAttributes : super.getDefaultAttributeModifiers(slot);
    }
}
