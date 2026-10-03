package cn.blindboxchallenge.item;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 038-C：保留原有头部装备语义，只指定口罩穿戴贴图。 */
public final class FaceMaskItem extends ArmorItem {
    private static final String ARMOR_TEXTURE = new ResourceLocation(BlindBoxChallenge.MOD_ID,
            "textures/models/armor/face_mask_layer_1.png").toString();

    public FaceMaskItem(ArmorMaterial material) {
        super(material, Type.HELMET, new Item.Properties());
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return ARMOR_TEXTURE;
    }
}
