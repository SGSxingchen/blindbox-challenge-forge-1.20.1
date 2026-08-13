package cn.blindboxchallenge.client;

import cn.blindboxchallenge.client.model.RoadBarrierHelmetModel;
import cn.blindboxchallenge.item.RoadBarrierHelmetItem;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.cache.object.BakedGeoModel;

public final class RoadBarrierHelmetRenderer extends GeoArmorRenderer<RoadBarrierHelmetItem> {
    public RoadBarrierHelmetRenderer() {
        super(new RoadBarrierHelmetModel());
    }

    @Override
    protected void grabRelevantBones(BakedGeoModel bakedModel) {
        this.head = bakedModel.getBone("Head").orElse(null);
    }
}
