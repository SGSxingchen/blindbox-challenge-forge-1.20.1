package cn.blindboxchallenge.data;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 主世界持久化的死亡笔记排程；只保存 UUID 和到期游戏刻，不保存玩家对象。 */
public final class DeathNoteSavedData extends SavedData {
    public static final String DATA_NAME = "blindboxchallenge_death_notes";
    private final List<Entry> entries = new ArrayList<>();

    public static DeathNoteSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(DeathNoteSavedData::load, DeathNoteSavedData::new, DATA_NAME);
    }

    public static DeathNoteSavedData load(CompoundTag tag) {
        DeathNoteSavedData data = new DeathNoteSavedData();
        ListTag saved = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag value = saved.getCompound(index);
            if (value.hasUUID("id") && value.hasUUID("owner") && value.hasUUID("target") && value.contains("due_tick", Tag.TAG_LONG)) {
                data.entries.add(new Entry(value.getUUID("id"), value.getUUID("owner"), value.getUUID("target"), value.getLong("due_tick")));
            }
        }
        return data;
    }

    public synchronized void schedule(UUID owner, UUID target, long dueTick) {
        entries.add(new Entry(UUID.randomUUID(), owner, target, dueTick));
        setDirty();
    }

    /** 原子取走到期项，确保重启、重复 tick 或异常伤害不会造成第二次结算。 */
    public synchronized List<Entry> takeDue(long gameTime) {
        List<Entry> due = new ArrayList<>();
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.dueTick() <= gameTime) {
                due.add(entry);
                iterator.remove();
            }
        }
        if (!due.isEmpty()) setDirty();
        return due;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag saved = new ListTag();
        for (Entry entry : entries) {
            CompoundTag value = new CompoundTag();
            value.putUUID("id", entry.id());
            value.putUUID("owner", entry.owner());
            value.putUUID("target", entry.target());
            value.putLong("due_tick", entry.dueTick());
            saved.add(value);
        }
        tag.put("entries", saved);
        return tag;
    }

    public record Entry(UUID id, UUID owner, UUID target, long dueTick) {}
}
