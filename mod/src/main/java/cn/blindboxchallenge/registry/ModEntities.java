package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.entity.PillowProjectileEntity;
import cn.blindboxchallenge.entity.PillowSeatEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** P3 的可持久化实体注册表；渲染器只在客户端入口注册。 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlindBoxChallenge.MOD_ID);

    public static final RegistryObject<EntityType<PillowSeatEntity>> PILLOW_SEAT = ENTITIES.register("pillow_seat",
            () -> EntityType.Builder.of(PillowSeatEntity::new, MobCategory.MISC)
                    .sized(0.01F, 0.01F)
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .build(BlindBoxChallenge.MOD_ID + ":pillow_seat"));
    public static final RegistryObject<EntityType<PillowProjectileEntity>> THROWN_PILLOW = ENTITIES.register("thrown_pillow",
            () -> EntityType.Builder.of(PillowProjectileEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build(BlindBoxChallenge.MOD_ID + ":thrown_pillow"));

    private ModEntities() {}
}
