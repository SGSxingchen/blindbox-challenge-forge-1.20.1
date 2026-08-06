package cn.blindboxchallenge.capability;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 仅由实体 Capability 生命周期持有，不缓存玩家或世界对象。 */
public final class PlayerAbilityProvider implements ICapabilitySerializable<CompoundTag> {
    public static final ResourceLocation ID = new ResourceLocation(BlindBoxChallenge.MOD_ID, "player_ability");
    private final PlayerAbilityData data = new PlayerAbilityData();
    private final LazyOptional<PlayerAbilityData> optional = LazyOptional.of(() -> data);

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        return capability == ModCapabilities.PLAYER_ABILITY ? optional.cast() : LazyOptional.empty();
    }

    @Override public CompoundTag serializeNBT() { return data.serializeNBT(); }
    @Override public void deserializeNBT(CompoundTag nbt) { data.deserializeNBT(nbt); }
    public void invalidate() { optional.invalidate(); }
}
