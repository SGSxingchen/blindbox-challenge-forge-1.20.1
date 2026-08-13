package cn.blindboxchallenge.client;

import cn.blindboxchallenge.client.model.MusicBoxItemModel;
import cn.blindboxchallenge.item.MusicBoxBlockItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public final class MusicBoxItemRenderer extends GeoItemRenderer<MusicBoxBlockItem> {
    public MusicBoxItemRenderer() {
        super(new MusicBoxItemModel());
    }
}
