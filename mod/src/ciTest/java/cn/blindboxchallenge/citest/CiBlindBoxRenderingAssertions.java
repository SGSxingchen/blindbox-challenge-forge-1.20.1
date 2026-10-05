package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.registry.ModItems;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/** 仅在探针的主菜单检查中暂时驱动模型谓词，退出前恢复生产函数。 */
final class CiBlindBoxRenderingAssertions {
    static String verify(Minecraft minecraft) {
        ItemStack stack = new ItemStack(ModItems.BLIND_BOX.get());
        ResourceLocation key = new ResourceLocation("blindboxchallenge", "opening");
        ItemPropertyFunction original = ItemProperties.getProperty(stack.getItem(), key);
        require(original != null && original.call(stack, null, null, 0) == 0.0F, "盲盒空实体预览未闭盖");
        BakedModel base = minecraft.getItemRenderer().getItemModelShaper().getItemModel(stack);
        Set<BakedModel> stages = new HashSet<>();
        Set<Integer> geometries = new HashSet<>();
        try {
            for (float progress : new float[]{0.0F, 0.2F, 0.4F, 0.7F, 1.0F}) {
                ItemProperties.register(stack.getItem(), key, (item, level, entity, seed) -> progress);
                BakedModel model = base.getOverrides().resolve(base, stack, null, null, 0);
                require(model != null && model != minecraft.getModelManager().getMissingModel()
                        && model.isGui3d(), "盲盒阶段不是可用立体模型：" + progress);
                var quads = model.getQuads(null, null, RandomSource.create(0L));
                require(!quads.isEmpty(), "盲盒阶段没有几何面：" + progress);
                int geometry = 1;
                for (var quad : quads) {
                    require(!quad.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation()),
                            "盲盒存在缺失材质：" + progress);
                    geometry = 31 * geometry + java.util.Arrays.hashCode(quad.getVertices());
                }
                stages.add(model);
                geometries.add(geometry);
            }
            require(stages.size() == 5 && geometries.size() == 5, "盲盒五档未解析为不同盒盖几何");
        } finally {
            ItemProperties.register(stack.getItem(), key, original);
        }
        require(base.getOverrides().resolve(base, stack, null, null, 0) == base, "恢复后物品预览未闭盖");
        return "blind_box_baked_3d_stages=5\nblind_box_missing_textures=0\nblind_box_idle_closed=true\n";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private CiBlindBoxRenderingAssertions() {}
}
