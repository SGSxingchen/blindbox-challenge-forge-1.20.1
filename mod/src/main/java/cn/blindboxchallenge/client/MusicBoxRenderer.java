package cn.blindboxchallenge.client;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.client.model.MusicBoxModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public final class MusicBoxRenderer extends GeoBlockRenderer<MusicBoxBlockEntity> {
    public MusicBoxRenderer(BlockEntityRendererProvider.Context context) {
        super(new MusicBoxModel());
    }
}
