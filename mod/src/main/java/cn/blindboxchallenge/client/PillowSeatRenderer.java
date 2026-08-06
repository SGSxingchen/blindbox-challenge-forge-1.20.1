package cn.blindboxchallenge.client;

import cn.blindboxchallenge.entity.PillowSeatEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;

/** 客户端专用不可见座位渲染器；乘骑同步由原版实体包处理。 */
public final class PillowSeatRenderer extends EntityRenderer<PillowSeatEntity> {
    public PillowSeatRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PillowSeatEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        // 座位只承载原版乘骑同步，不绘制几何或名称。
    }

    @Override
    public ResourceLocation getTextureLocation(PillowSeatEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
