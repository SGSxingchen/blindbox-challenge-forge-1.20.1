package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** 蝴蝶翅膀复用原版飞行、站立和蹲伏姿态，始终使用本模组贴图。 */
public final class PinkButterflyWingsLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public static final ResourceLocation TEXTURE = new ResourceLocation(BlindBoxChallenge.MOD_ID,
            "textures/entity/pink_butterfly_wings.png");
    private final ElytraModel<AbstractClientPlayer> model;

    public PinkButterflyWingsLayer(PlayerRenderer renderer, EntityModelSet models) {
        super(renderer);
        model = new ElytraModel<>(models.bakeLayer(ModelLayers.ELYTRA));
    }

    public boolean shouldRender(ItemStack stack) {
        return stack.is(ModItems.PINK_BUTTERFLY_WINGS.get());
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!shouldRender(stack)) return;

        poseStack.pushPose();
        try {
            poseStack.translate(0.0F, 0.0F, 0.125F);
            getParentModel().copyPropertiesTo(model);
            model.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // 不走原版皮肤/披风纹理优先分支；原版鞘翅由其既有渲染层处理。
            VertexConsumer vertices = ItemRenderer.getArmorFoilBuffer(buffers, RenderType.armorCutoutNoCull(TEXTURE),
                    false, stack.hasFoil());
            model.renderToBuffer(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            poseStack.popPose();
        }
    }
}
