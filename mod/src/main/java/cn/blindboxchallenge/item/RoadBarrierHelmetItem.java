package cn.blindboxchallenge.item;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 033：仅保留铁头盔的原版装备语义；专属可视模型精修归 P5。 */
public final class RoadBarrierHelmetItem extends ArmorItem {
    public RoadBarrierHelmetItem(ArmorMaterial material) {
        super(material, Type.HELMET, new Item.Properties());
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "textures/models/armor/road_barrier_layer_1.png").toString();
    }
}
