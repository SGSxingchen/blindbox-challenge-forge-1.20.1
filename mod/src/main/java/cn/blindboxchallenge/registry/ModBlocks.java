package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.block.BmlCheerStickBlock;
import cn.blindboxchallenge.block.GlowStickBlock;
import cn.blindboxchallenge.block.PillowBlock;
import cn.blindboxchallenge.block.AnywhereDoorBlock;
import cn.blindboxchallenge.block.SafetyLandingBlock;
import cn.blindboxchallenge.block.MusicBoxBlock;
import cn.blindboxchallenge.block.DecorativeBlock;
import cn.blindboxchallenge.entity.PillowVariant;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** P2 可放置物的服务端方块注册表。 */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BlindBoxChallenge.MOD_ID);

    public static final RegistryObject<Block> GLOW_STICK = BLOCKS.register("glow_stick",
            () -> new GlowStickBlock(BlockBehaviour.Properties.of().noCollission().instabreak()));
    public static final RegistryObject<Block> GLOW_STICK_WALL = BLOCKS.register("glow_stick_wall",
            () -> new WallTorchBlock(BlockBehaviour.Properties.of().noCollission().instabreak().lightLevel(state -> 14), ParticleTypes.END_ROD));
    public static final RegistryObject<Block> BML_CHEER_STICK = BLOCKS.register("bml_cheer_stick",
            () -> new BmlCheerStickBlock(BlockBehaviour.Properties.of().noCollission().instabreak()));
    public static final RegistryObject<Block> BML_CHEER_STICK_WALL = BLOCKS.register("bml_cheer_stick_wall",
            () -> new BmlCheerStickBlock.Wall(BlockBehaviour.Properties.of().noCollission().instabreak()));
    public static final RegistryObject<Block> STONE_PILLOW = BLOCKS.register("stone_pillow",
            () -> new PillowBlock(PillowVariant.STONE, BlockBehaviour.Properties.of().strength(0.5F).sound(SoundType.WOOL).noOcclusion()));
    public static final RegistryObject<Block> DIAMOND_PILLOW = BLOCKS.register("diamond_pillow",
            () -> new PillowBlock(PillowVariant.DIAMOND, BlockBehaviour.Properties.of().strength(0.5F).sound(SoundType.WOOL).noOcclusion()));
    public static final RegistryObject<Block> ANYWHERE_DOOR = BLOCKS.register("anywhere_door",
            () -> new AnywhereDoorBlock(BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion().noCollission()));
    public static final RegistryObject<Block> SAFETY_LANDING = BLOCKS.register("safety_landing",
            () -> new SafetyLandingBlock(BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD)));
    public static final RegistryObject<Block> MUSIC_BOX = BLOCKS.register("music_box",
            () -> new MusicBoxBlock(BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion()));
    /** P5 三项均为无额外交互的中性原创装饰方块；不含任何角色、赛事或画作复刻。 */
    public static final RegistryObject<Block> ABSTRACT_WHITE_FIGURINE = BLOCKS.register("abstract_white_figurine",
            () -> new DecorativeBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.STONE).noOcclusion(),
                    Shapes.or(Block.box(4.0D, 0.0D, 4.0D, 12.0D, 9.0D, 12.0D),
                            Block.box(5.0D, 9.0D, 5.0D, 11.0D, 14.0D, 11.0D))));
    public static final RegistryObject<Block> FLOOR_ART_PANEL = BLOCKS.register("floor_art_panel",
            () -> new DecorativeBlock(BlockBehaviour.Properties.of().strength(0.6F).sound(SoundType.WOOD).noOcclusion(),
                    Block.box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D)));
    public static final RegistryObject<Block> NEUTRAL_TROPHY = BLOCKS.register("neutral_trophy",
            () -> new DecorativeBlock(BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.METAL).noOcclusion(),
                    Shapes.or(Block.box(4.0D, 0.0D, 4.0D, 12.0D, 3.0D, 12.0D),
                            Block.box(7.0D, 3.0D, 7.0D, 9.0D, 7.0D, 9.0D),
                            Block.box(5.0D, 7.0D, 5.0D, 11.0D, 12.0D, 11.0D))));

    private ModBlocks() {}
}
