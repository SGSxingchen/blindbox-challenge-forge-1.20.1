package cn.blindboxchallenge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.QuadTransformers;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 普通物品渲染中的连续盒盖；每次渲染独立取值，不保存玩家或物品状态。 */
public final class BlindBoxBakedModel extends BakedModelWrapper<BakedModel> {
    public static final ResourceLocation BODY = new ResourceLocation("blindboxchallenge", "item/blind_box_body");
    public static final ResourceLocation LID = new ResourceLocation("blindboxchallenge", "item/blind_box_lid");
    private static final ResourceLocation OPENING = new ResourceLocation("blindboxchallenge", "opening");
    private final List<BakedQuad> body;
    private final List<BakedQuad> lid;
    private final List<BakedQuad> quads;
    private final ItemOverrides overrides;

    public BlindBoxBakedModel(BakedModel original, BakedModel bodyModel, BakedModel lidModel) {
        super(original);
        body = List.copyOf(bodyModel.getQuads(null, null, RandomSource.create(0L)));
        lid = List.copyOf(lidModel.getQuads(null, null, RandomSource.create(0L)));
        var combined = new ArrayList<>(body);
        combined.addAll(lid);
        quads = List.copyOf(combined);
        overrides = new ItemOverrides() {
            @Override
            public BakedModel resolve(BakedModel model, ItemStack stack, ClientLevel level, LivingEntity entity, int seed) {
                var property = ItemProperties.getProperty(stack.getItem(), OPENING);
                float progress = property == null ? 0.0F : property.call(stack, level, entity, seed);
                return Float.isFinite(progress) && progress > 0.0F
                        ? new BlindBoxBakedModel(BlindBoxBakedModel.this, Mth.clamp(progress, 0.0F, 1.0F))
                        : BlindBoxBakedModel.this;
            }
        };
    }

    private BlindBoxBakedModel(BlindBoxBakedModel closed, float progress) {
        super(closed.originalModel);
        body = closed.body;
        lid = closed.lid;
        overrides = closed.overrides;
        // 连续缓入缓出，从闭合到90度；帧实例只复制18个盒盖面，共享固定盒身。
        float radians = progress * progress * (3.0F - 2.0F * progress) * Mth.HALF_PI;
        var transform = QuadTransformers.applying(new Transformation(new Matrix4f()
                .translate(0.5F, 11.0F / 16.0F, 13.0F / 16.0F).rotateX(radians)
                .translate(-0.5F, -11.0F / 16.0F, -13.0F / 16.0F)));
        var combined = new ArrayList<>(body);
        for (BakedQuad source : lid) {
            Direction direction = source.getDirection();
            var normal = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ()).rotateX(radians);
            var rotated = new BakedQuad(source.getVertices().clone(), source.getTintIndex(),
                    Direction.getNearest(normal.x(), normal.y(), normal.z()), source.getSprite(),
                    source.isShade(), source.hasAmbientOcclusion());
            // Forge 同时旋转顶点和打包法线，绝不修改共享的烘焙模型。
            transform.processInPlace(rotated);
            combined.add(rotated);
        }
        quads = List.copyOf(combined);
    }

    @Override
    public ItemOverrides getOverrides() { return overrides; }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return side == null ? quads : List.of();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
        return getQuads(state, side, random);
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
        originalModel.applyTransform(context, pose, leftHand);
        return this;
    }

    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) { return List.of(this); }
}
