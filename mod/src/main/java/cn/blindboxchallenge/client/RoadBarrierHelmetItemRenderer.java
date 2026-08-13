package cn.blindboxchallenge.client;

import cn.blindboxchallenge.client.model.RoadBarrierHelmetModel;
import cn.blindboxchallenge.item.RoadBarrierHelmetItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public final class RoadBarrierHelmetItemRenderer extends GeoItemRenderer<RoadBarrierHelmetItem> {
    public RoadBarrierHelmetItemRenderer() {
        super(new RoadBarrierHelmetModel());
    }
}
