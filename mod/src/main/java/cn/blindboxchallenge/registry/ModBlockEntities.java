package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.blockentity.AnywhereDoorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** P4 仅将门关联保存为方块实体；安全落点本身不保存运行状态。 */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<BlockEntityType<AnywhereDoorBlockEntity>> ANYWHERE_DOOR = BLOCK_ENTITIES.register("anywhere_door",
            () -> BlockEntityType.Builder.of(AnywhereDoorBlockEntity::new, ModBlocks.ANYWHERE_DOOR.get()).build(null));

    private ModBlockEntities() {}
}
