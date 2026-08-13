package cn.blindboxchallenge.client.model;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public final class MusicBoxModel extends GeoModel<MusicBoxBlockEntity> {
    @Override
    public ResourceLocation getModelResource(MusicBoxBlockEntity animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "geo/music_box.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MusicBoxBlockEntity animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "textures/block/music_box.png");
    }

    @Override
    public ResourceLocation getAnimationResource(MusicBoxBlockEntity animatable) {
        return new ResourceLocation(BlindBoxChallenge.MOD_ID, "animations/music_box.animation.json");
    }
}
