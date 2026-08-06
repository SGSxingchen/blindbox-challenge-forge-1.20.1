package cn.blindboxchallenge.data;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 不强加载伙伴区块时留下的门 UUID 失效回执。伙伴门下次被交互或进入时消费回执并清链，
 * 从而同时满足“拆除立即逻辑失效”和“绝不为清理关系强加载区块”。
 */
public final class DoorInvalidationSavedData extends SavedData {
    private static final String DATA_NAME = "blindboxchallenge_door_invalidations";
    private final Set<UUID> invalidDoorIds = new LinkedHashSet<>();

    public static DoorInvalidationSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(DoorInvalidationSavedData::load, DoorInvalidationSavedData::new, DATA_NAME);
    }

    public static DoorInvalidationSavedData load(CompoundTag tag) {
        DoorInvalidationSavedData data = new DoorInvalidationSavedData();
        ListTag saved = tag.getList("door_ids", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag value = saved.getCompound(index);
            if (value.hasUUID("id")) data.invalidDoorIds.add(value.getUUID("id"));
        }
        return data;
    }

    public synchronized void mark(UUID doorId) {
        if (invalidDoorIds.add(doorId)) setDirty();
    }

    /** 仅由拥有同一 UUID 的已加载门消费；不会因无关方块或损坏坐标删除回执。 */
    public synchronized boolean consume(UUID doorId) {
        if (!invalidDoorIds.remove(doorId)) return false;
        setDirty();
        return true;
    }

    public synchronized Set<UUID> ids() {
        return Set.copyOf(invalidDoorIds);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag saved = new ListTag();
        for (UUID id : invalidDoorIds) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", id);
            saved.add(value);
        }
        tag.put("door_ids", saved);
        return tag;
    }
}
