package cn.blindboxchallenge.item;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.Consumer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import cn.blindboxchallenge.client.RoadBarrierHelmetRenderer;
import cn.blindboxchallenge.client.RoadBarrierHelmetItemRenderer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/** 033：仅保留铁头盔的原版装备语义；专属可视模型精修归 P5。 */
public final class RoadBarrierHelmetItem extends ArmorItem implements GeoItem {
    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);
    public RoadBarrierHelmetItem(ArmorMaterial material) {
        super(material, Type.HELMET, new Item.Properties());
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "textures/models/armor/road_barrier_layer_1.png").toString();
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private RoadBarrierHelmetRenderer renderer;
            private RoadBarrierHelmetItemRenderer itemRenderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (itemRenderer == null) itemRenderer = new RoadBarrierHelmetItemRenderer();
                return itemRenderer;
            }

            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack stack,
                                                           EquipmentSlot slot, HumanoidModel<?> original) {
                if (renderer == null) renderer = new RoadBarrierHelmetRenderer();
                renderer.prepForRender(livingEntity, stack, slot, original);
                return renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 当前人工头盔模型为静态装备模型。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return animationCache; }
}
