package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.client.PinkButterflyWingsLayer;
import cn.blindboxchallenge.registry.ModItems;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/** 随创造栏探针读取资源索引和实际玩家渲染层，不读取屏幕或图像缓冲区。 */
final class CiWearableRenderingAssertions {
    private CiWearableRenderingAssertions() {
    }

    static String verify(Minecraft minecraft) {
        String eyeTexture = verifyArmorTexture(minecraft, new ItemStack(ModItems.EGGY_EYE_MASK.get()),
                "blindboxchallenge:textures/models/armor/eggy_eye_mask_layer_1.png");
        String faceTexture = verifyArmorTexture(minecraft, new ItemStack(ModItems.FACE_MASK.get()),
                "blindboxchallenge:textures/models/armor/face_mask_layer_1.png");
        ResourceLocation wingTexture = new ResourceLocation("blindboxchallenge", "textures/entity/pink_butterfly_wings.png");
        require(PinkButterflyWingsLayer.TEXTURE.equals(wingTexture), "蝴蝶翅膀没有使用固定穿戴贴图");
        requireResource(minecraft, wingTexture);

        ItemStack wings = new ItemStack(ModItems.PINK_BUTTERFLY_WINGS.get());
        ItemStack vanillaElytra = new ItemStack(Items.ELYTRA);
        for (String skin : List.of("default", "slim")) {
            require(minecraft.getEntityRenderDispatcher().getSkinMap().get(skin) instanceof PlayerRenderer,
                    "缺少真实玩家渲染器：" + skin);
            PlayerRenderer renderer = (PlayerRenderer) minecraft.getEntityRenderDispatcher().getSkinMap().get(skin);
            // Forge 官方反射助手只读取已注册层；此 SRG 字段在开发与正式映射环境均可解析。
            List<?> layers = ObfuscationReflectionHelper.getPrivateValue(LivingEntityRenderer.class, renderer, "f_115291_");
            require(layers != null, "玩家渲染器层列表为空：" + skin);
            int butterflyLayers = 0;
            int vanillaLayers = 0;
            for (Object layer : layers) {
                if (layer instanceof PinkButterflyWingsLayer butterfly) {
                    butterflyLayers++;
                    require(butterfly.shouldRender(wings) && !butterfly.shouldRender(vanillaElytra)
                                    && !butterfly.shouldRender(ItemStack.EMPTY),
                            "蝴蝶翅膀层的物品选择异常：" + skin);
                }
                if (layer instanceof ElytraLayer<?, ?> elytra) {
                    vanillaLayers++;
                    require(!elytra.shouldRender(wings, null) && elytra.shouldRender(vanillaElytra, null),
                            "原版鞘翅层与蝴蝶翅膀重复选择：" + skin);
                }
            }
            require(butterflyLayers == 1 && vanillaLayers == 1,
                    "玩家翅膀层注册异常：" + skin + "，蝴蝶=" + butterflyLayers + "，原版=" + vanillaLayers);
        }
        return "wearable_eye_texture=" + eyeTexture + "\n"
                + "wearable_face_texture=" + faceTexture + "\n"
                + "wearable_wing_texture=" + wingTexture + "\n"
                + "wearable_resources_present=true\n"
                + "wearable_default_butterfly_layers=1\n"
                + "wearable_slim_butterfly_layers=1\n"
                + "wearable_vanilla_elytra_exclusive=true\n";
    }

    private static String verifyArmorTexture(Minecraft minecraft, ItemStack stack, String expected) {
        String actual = stack.getItem().getArmorTexture(stack, minecraft.player, EquipmentSlot.HEAD, null);
        require(expected.equals(actual), "头部饰品穿戴贴图异常：" + actual);
        requireResource(minecraft, new ResourceLocation(actual));
        return actual;
    }

    private static void requireResource(Minecraft minecraft, ResourceLocation texture) {
        require(minecraft.getResourceManager().getResource(texture).isPresent(), "缺少穿戴贴图资源：" + texture);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
