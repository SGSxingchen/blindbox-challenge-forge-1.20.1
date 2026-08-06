package cn.blindboxchallenge.block;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 026：落地式荧光棒，沿用火把的碰撞、支撑与照明行为。 */
public final class GlowStickBlock extends TorchBlock {
    public GlowStickBlock(BlockBehaviour.Properties properties) {
        super(properties.lightLevel(state -> 14), ParticleTypes.END_ROD);
    }
}
