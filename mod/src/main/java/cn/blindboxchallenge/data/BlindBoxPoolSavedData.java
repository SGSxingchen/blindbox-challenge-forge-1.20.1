package cn.blindboxchallenge.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 只存在于主世界的全服奖池与恢复日志。所有写入都发生在逻辑服务端主线程。 */
public final class BlindBoxPoolSavedData extends SavedData {
    public static final String DATA_NAME = "blindboxchallenge_pool";
    private final Map<UUID, PrizeBundle> bundles = new LinkedHashMap<>();
    private final Map<UUID, TransactionRecord> transactions = new LinkedHashMap<>();
    private long nextVersion = 1L;

    public static BlindBoxPoolSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(BlindBoxPoolSavedData::load, BlindBoxPoolSavedData::new, DATA_NAME);
    }

    public static BlindBoxPoolSavedData load(CompoundTag tag) {
        BlindBoxPoolSavedData data = new BlindBoxPoolSavedData();
        data.nextVersion = Math.max(1L, tag.getLong("next_version"));
        ListTag savedBundles = tag.getList("bundles", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedBundles.size(); i++) {
            PrizeBundle bundle = PrizeBundle.load(savedBundles.getCompound(i));
            data.bundles.put(bundle.id(), bundle);
        }
        ListTag savedTransactions = tag.getList("transactions", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedTransactions.size(); i++) {
            try {
                TransactionRecord record = TransactionRecord.load(savedTransactions.getCompound(i));
                data.transactions.put(record.id(), record);
            } catch (IllegalArgumentException ignored) {
                // 损坏的历史记录不应阻止世界加载；保留其它可恢复记录。
            }
        }
        return data;
    }

    public synchronized PrizeBundle createBundle(UUID creator, long gameTime, List<net.minecraft.world.item.ItemStack> stacks) {
        return new PrizeBundle(UUID.randomUUID(), creator, gameTime, nextVersion++, stacks);
    }

    public synchronized void prepare(TransactionRecord record) {
        transactions.put(record.id(), record);
        setDirty();
    }

    public synchronized void commitPack(UUID transactionId, PrizeBundle bundle) {
        bundles.put(bundle.id(), bundle);
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) transactions.put(transactionId, record.withStage(TransactionRecord.Stage.COMMITTED));
        setDirty();
    }

    public synchronized void commitOpen(UUID transactionId, UUID bundleId) {
        bundles.remove(bundleId);
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) transactions.put(transactionId, record.withStage(TransactionRecord.Stage.COMMITTED));
        setDirty();
    }

    public synchronized Optional<PrizeBundle> randomBundle(net.minecraft.util.RandomSource random) {
        if (bundles.isEmpty()) return Optional.empty();
        int wanted = random.nextInt(bundles.size());
        return bundles.values().stream().skip(wanted).findFirst();
    }

    public synchronized boolean containsBundle(UUID id) { return bundles.containsKey(id); }
    public synchronized int bundleCount() { return bundles.size(); }
    public synchronized Collection<PrizeBundle> bundles() { return List.copyOf(bundles.values()); }
    public synchronized Collection<TransactionRecord> pendingFor(UUID playerId) {
        return transactions.values().stream().filter(record -> record.playerId().equals(playerId) && record.stage() != TransactionRecord.Stage.COMMITTED).toList();
    }
    public synchronized void markManualReview(UUID id) {
        TransactionRecord record = transactions.get(id);
        if (record != null) transactions.put(id, record.withStage(TransactionRecord.Stage.MANUAL_REVIEW));
        setDirty();
    }
    public synchronized void clearForDebug() { bundles.clear(); setDirty(); }
    public synchronized void injectForDebug(PrizeBundle bundle) { bundles.put(bundle.id(), bundle); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("next_version", nextVersion);
        ListTag savedBundles = new ListTag();
        for (PrizeBundle bundle : bundles.values()) savedBundles.add(bundle.save());
        tag.put("bundles", savedBundles);
        ListTag savedTransactions = new ListTag();
        for (TransactionRecord record : transactions.values()) savedTransactions.add(record.save());
        tag.put("transactions", savedTransactions);
        return tag;
    }
}
