package cn.blindboxchallenge.client.model;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.MusicBoxBlockItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class MusicBoxItemModel extends GeoModel<MusicBoxBlockItem> {
    @Override
    public ResourceLocation getModelResource(MusicBoxBlockItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "geo/music_box.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MusicBoxBlockItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "textures/block/music_box.png");
    }

    @Override
    public ResourceLocation getAnimationResource(MusicBoxBlockItem animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "animations/music_box.animation.json");
    }
}
