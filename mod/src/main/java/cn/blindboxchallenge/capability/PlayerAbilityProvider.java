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
    /**
     * 玩家死亡时原实体会先被原版移除，Forge 随即使附件的 LazyOptional 失效；而稍后的
     * {@code PlayerEvent.Clone} 允许通过 {@code reviveCaps()} 读取同一份持久数据。LazyOptional
     * 本身不能重新生效，因此在实体已复活后首次查询时重新包装既有数据对象。
     */
    private LazyOptional<PlayerAbilityData> optional = createOptional();

    private LazyOptional<PlayerAbilityData> createOptional() {
        return LazyOptional.of(() -> data);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
        if (capability != ModCapabilities.PLAYER_ABILITY) return LazyOptional.empty();
        // Entity#reviveCaps 只恢复外层 CapabilityProvider 的可访问性，不能让已失效的
        // LazyOptional 再次有效。这里不创建新的数据，因而不会丢失死亡前的持久 NBT。
        if (!optional.isPresent()) optional = createOptional();
        return optional.cast();
    }

    @Override public CompoundTag serializeNBT() { return data.serializeNBT(); }
    @Override public void deserializeNBT(CompoundTag nbt) { data.deserializeNBT(nbt); }
    public void invalidate() { optional.invalidate(); }
}
