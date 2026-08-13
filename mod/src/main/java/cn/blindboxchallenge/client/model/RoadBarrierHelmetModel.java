package cn.blindboxchallenge.client.model;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.RoadBarrierHelmetItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class RoadBarrierHelmetModel extends GeoModel<RoadBarrierHelmetItem> {
    @Override
    public ResourceLocation getModelResource(RoadBarrierHelmetItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "geo/road_barrier_helmet.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(RoadBarrierHelmetItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "textures/item/road_barrier_helmet.png");
    }

    @Override
    public ResourceLocation getAnimationResource(RoadBarrierHelmetItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "animations/road_barrier_helmet.animation.json");
    }
}
