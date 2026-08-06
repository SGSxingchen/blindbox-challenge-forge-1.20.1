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
    /** bundle UUID -> unfinished OPEN transaction UUID. Persisted so restart cannot hand one prize to two players. */
    private final Map<UUID, UUID> openReservations = new LinkedHashMap<>();
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
        ListTag savedReservations = tag.getList("open_reservations", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedReservations.size(); i++) {
            CompoundTag reservation = savedReservations.getCompound(i);
            if (reservation.hasUUID("bundle_id") && reservation.hasUUID("transaction_id")) {
                data.openReservations.put(reservation.getUUID("bundle_id"), reservation.getUUID("transaction_id"));
            }
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

    public synchronized void markStage(UUID transactionId, TransactionRecord.Stage stage, long gameTime) {
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) transactions.put(transactionId, record.withStage(stage, gameTime));
        setDirty();
    }

    public synchronized void commitPack(UUID transactionId, PrizeBundle bundle, long gameTime) {
        bundles.putIfAbsent(bundle.id(), bundle);
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) transactions.put(transactionId, record.withStage(TransactionRecord.Stage.COMMITTED, gameTime));
        setDirty();
    }

    public synchronized void commitOpen(UUID transactionId, UUID bundleId, long gameTime) {
        UUID owner = openReservations.get(bundleId);
        if (owner != null && !owner.equals(transactionId)) return;
        openReservations.remove(bundleId);
        bundles.remove(bundleId);
        TransactionRecord record = transactions.get(transactionId);
        if (record != null) transactions.put(transactionId, record.withStage(TransactionRecord.Stage.COMMITTED, gameTime));
        setDirty();
    }

    public synchronized Optional<PrizeBundle> randomBundle(net.minecraft.util.RandomSource random) {
        List<PrizeBundle> available = bundles.values().stream()
                .filter(bundle -> !bundle.stacks().isEmpty() && !openReservations.containsKey(bundle.id())).toList();
        if (available.isEmpty()) return Optional.empty();
        return Optional.of(available.get(random.nextInt(available.size())));
    }


    public synchronized boolean reserveOpen(UUID bundleId, UUID transactionId) {
        if (!bundles.containsKey(bundleId)) return false;
        UUID existing = openReservations.get(bundleId);
        if (existing != null && !existing.equals(transactionId)) return false;
        openReservations.put(bundleId, transactionId);
        setDirty();
        return true;
    }

    public synchronized boolean reservedBy(UUID bundleId, UUID transactionId) {
        return transactionId.equals(openReservations.get(bundleId));
    }

    public synchronized void releaseOpen(UUID bundleId, UUID transactionId) {
        if (transactionId.equals(openReservations.get(bundleId))) {
            openReservations.remove(bundleId);
            setDirty();
        }
    }

    public synchronized boolean removeReservedBundle(UUID bundleId, UUID transactionId) {
        UUID owner = openReservations.get(bundleId);
        if (owner != null && !owner.equals(transactionId)) return false;
        openReservations.remove(bundleId);
        bundles.remove(bundleId);
        setDirty();
        return true;
    }

    public synchronized boolean containsBundle(UUID id) { return bundles.containsKey(id); }
    public synchronized Optional<PrizeBundle> bundle(UUID id) { return Optional.ofNullable(bundles.get(id)); }
    public synchronized boolean ensureBundle(PrizeBundle bundle) {
        PrizeBundle existing = bundles.get(bundle.id());
        if (existing != null) return existing.save().equals(bundle.save());
        bundles.put(bundle.id(), bundle);
        setDirty();
        return true;
    }
    public synchronized int bundleCount() { return bundles.size(); }
    public synchronized Collection<PrizeBundle> bundles() { return List.copyOf(bundles.values()); }
    /** CI/管理员审计只读快照；返回不可变副本，调用方不能修改 SavedData。 */
    public synchronized Collection<TransactionRecord> transactions() { return List.copyOf(transactions.values()); }
    /** CI/管理员审计只读快照：bundle UUID -> OPEN transaction UUID。 */
    public synchronized Map<UUID, UUID> openReservations() { return Map.copyOf(openReservations); }
    public synchronized Collection<TransactionRecord> pendingFor(UUID playerId) {
        return transactions.values().stream().filter(record -> record.playerId().equals(playerId) && !record.stage().terminal()).toList();
    }
    public synchronized void resolveRecovery(UUID id, TransactionRecord.Stage stage, long gameTime, String result) {
        TransactionRecord record = transactions.get(id);
        if (record != null && !record.stage().terminal()) transactions.put(id, record.withRecoveryResult(stage, gameTime, result));
        setDirty();
    }
    public synchronized void markManualReview(UUID id, long gameTime, String reason) {
        resolveRecovery(id, TransactionRecord.Stage.MANUAL_REVIEW, gameTime, reason);
    }
    public synchronized void clearForDebug() { bundles.clear(); setDirty(); }
    public synchronized void injectForDebug(PrizeBundle bundle) { bundles.put(bundle.id(), bundle); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("next_version", nextVersion);
        ListTag savedBundles = new ListTag();
        for (PrizeBundle bundle : bundles.values()) savedBundles.add(bundle.save());
        tag.put("bundles", savedBundles);
        ListTag savedReservations = new ListTag();
        for (Map.Entry<UUID, UUID> entry : openReservations.entrySet()) {
            CompoundTag reservation = new CompoundTag();
            reservation.putUUID("bundle_id", entry.getKey());
            reservation.putUUID("transaction_id", entry.getValue());
            savedReservations.add(reservation);
        }
        tag.put("open_reservations", savedReservations);
        ListTag savedTransactions = new ListTag();
        for (TransactionRecord record : transactions.values()) savedTransactions.add(record.save());
        tag.put("transactions", savedTransactions);
        return tag;
    }
}
