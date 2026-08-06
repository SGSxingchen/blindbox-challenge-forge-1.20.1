package cn.blindboxchallenge.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import java.util.UUID;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.common.ForgeMod;

/** 015：保持原版石斧的耐久、伤害、攻速与斧类交互，并增加 2 格实体攻击距离。 */
public final class ChainsawSwordItem extends AxeItem {
    private static final UUID REACH_UUID = UUID.fromString("bb7dfb24-a005-4f4c-96cc-4d9a5e0b7015");
    private final Multimap<Attribute, AttributeModifier> mainHandAttributes;

    public ChainsawSwordItem() {
        super(Tiers.STONE, 7.0F, -3.2F, new Item.Properties().durability(Tiers.STONE.getUses()));
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        builder.putAll(super.getDefaultAttributeModifiers(EquipmentSlot.MAINHAND));
        builder.put(ForgeMod.ENTITY_REACH.get(), new AttributeModifier(REACH_UUID, "Chainsaw sword reach", 2.0D,
                AttributeModifier.Operation.ADDITION));
        mainHandAttributes = builder.build();
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return slot == EquipmentSlot.MAINHAND ? mainHandAttributes : super.getDefaultAttributeModifiers(slot);
    }
}
