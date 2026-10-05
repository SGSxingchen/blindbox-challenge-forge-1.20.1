package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.client.BlindBoxBakedModel;
import cn.blindboxchallenge.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.model.data.ModelData;

/** 主菜单中驱动61个进度值检查连续几何和正式物品渲染入口，退出前恢复生产函数。 */
final class CiBlindBoxRenderingAssertions {
    static String verify(Minecraft minecraft) {
        ItemStack stack = new ItemStack(ModItems.BLIND_BOX.get());
        ResourceLocation key = new ResourceLocation("blindboxchallenge", "opening");
        ItemPropertyFunction original = ItemProperties.getProperty(stack.getItem(), key);
        require(original != null && original.call(stack, null, null, 0) == 0.0F, "盲盒空实体预览未闭盖");
        BakedModel base = minecraft.getItemRenderer().getItemModelShaper().getItemModel(stack);
        require(base instanceof BlindBoxBakedModel, "盲盒连续模型未注册到正式物品入口");
        List<BakedQuad> closed = base.getQuads(null, null, RandomSource.create(0L));
        int closedHash = geometryHash(closed);
        Set<Integer> geometries = new HashSet<>();
        BakedModel retained = null;
        int retainedHash = 0;
        try {
            for (int sample = 0; sample <= 60; sample++) {
                float progress = sample / 60.0F;
                ItemProperties.register(stack.getItem(), key, (item, level, entity, seed) -> progress);
                BakedModel model = base.getOverrides().resolve(base, stack, null, null, 0);
                require(model instanceof BlindBoxBakedModel && model.isGui3d(), "盲盒没有保持连续立体模型");
                require(model.getRenderPasses(stack, false).equals(List.of(model)), "实际渲染阶段退回静态模型");
                for (ItemDisplayContext context : ItemDisplayContext.values()) {
                    require(model.applyTransform(context, new PoseStack(), false) == model
                            && model.applyTransform(context, new PoseStack(), true) == model,
                            "视角或手别变换丢失动态盒盖：" + context);
                }
                var quads = model.getQuads(null, null, RandomSource.create(0L));
                require(quads.size() == 114 && quads.equals(model.getQuads(null, null,
                        RandomSource.create(0L), ModelData.EMPTY, null)), "普通或Forge渲染入口面数量异常");
                for (var quad : quads) {
                    require(!quad.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation()),
                            "盲盒存在缺失材质：" + progress);
                }
                // 前96个面是固定盒身，不能随盒盖旋转；18个盒盖面按帧独立生成。
                require(geometryHash(quads.subList(0, 96)) == geometryHash(closed.subList(0, 96)), "盒身被一起旋转");
                geometries.add(geometryHash(quads));
                if (sample == 20) {
                    retained = model;
                    retainedHash = geometryHash(quads);
                }
            }
            require(geometries.size() == 61, "连续61个进度未产生61种不同几何");
            require(geometryHash(closed) == closedHash && retained != null
                    && geometryHash(retained.getQuads(null, null, RandomSource.create(0L))) == retainedHash,
                    "新帧污染了共享模型或其他持有者的旧帧");
        } finally {
            ItemProperties.register(stack.getItem(), key, original);
        }
        require(base.getOverrides().resolve(base, stack, null, null, 0) == base, "恢复后物品预览未闭盖");
        return "blind_box_continuous_geometry_samples=61\nblind_box_render_paths_preserved=true\n"
                + "blind_box_shared_geometry_unchanged=true\nblind_box_missing_textures=0\nblind_box_idle_closed=true\n";
    }

    private static int geometryHash(List<BakedQuad> quads) {
        int hash = 1;
        for (BakedQuad quad : quads) hash = 31 * hash + java.util.Arrays.hashCode(quad.getVertices());
        return hash;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private CiBlindBoxRenderingAssertions() {}
}
