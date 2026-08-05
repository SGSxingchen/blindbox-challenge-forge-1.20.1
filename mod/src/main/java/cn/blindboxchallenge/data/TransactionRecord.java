package cn.blindboxchallenge.data;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 盲盒跨玩家存档与世界 SavedData 的持久事务证据。
 * schema v1 记录只可审计，不做猜测性恢复；新事务统一写 schema v2。
 */
public record TransactionRecord(
        int schemaVersion,
        UUID id,
        UUID playerId,
        UUID tokenId,
        UUID bundleId,
        Kind kind,
        Stage stage,
        PrizeBundle payload,
        long createdGameTime,
        long updatedGameTime,
        CompoundTag receipts,
        String beforeInventoryDigest,
        String afterInventoryDigest,
        int recoveryAttempts,
        String lastRecoveryResult) {
    public static final int CURRENT_SCHEMA = 2;

    public enum Kind { PACK, OPEN }
    public enum Stage {
        PREPARED,
        PLAYER_APPLIED,
        WORLD_APPLIED,
        COMMITTED,
        ROLLED_BACK,
        MANUAL_REVIEW;

        public boolean terminal() {
            return this == COMMITTED || this == ROLLED_BACK || this == MANUAL_REVIEW;
        }
    }

    public TransactionRecord {
        if (schemaVersion < 1) schemaVersion = 1;
        receipts = receipts == null ? new CompoundTag() : receipts.copy();
        beforeInventoryDigest = beforeInventoryDigest == null ? "" : beforeInventoryDigest;
        afterInventoryDigest = afterInventoryDigest == null ? "" : afterInventoryDigest;
        lastRecoveryResult = lastRecoveryResult == null ? "" : lastRecoveryResult;
        recoveryAttempts = Math.max(0, recoveryAttempts);
    }

    public static TransactionRecord createV2(UUID id, UUID playerId, UUID tokenId, UUID bundleId, Kind kind,
                                             PrizeBundle payload, long gameTime, CompoundTag receipts,
                                             String beforeInventoryDigest, String afterInventoryDigest) {
        return new TransactionRecord(CURRENT_SCHEMA, id, playerId, tokenId, bundleId, kind, Stage.PREPARED,
                payload, gameTime, gameTime, receipts, beforeInventoryDigest, afterInventoryDigest, 0, "");
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", schemaVersion);
        tag.putUUID("id", id);
        tag.putUUID("player_id", playerId);
        tag.putUUID("token_id", tokenId);
        tag.putUUID("bundle_id", bundleId);
        tag.putString("kind", kind.name());
        tag.putString("stage", stage.name());
        tag.put("payload", payload.save());
        tag.putLong("created_game_time", createdGameTime);
        tag.putLong("updated_game_time", updatedGameTime);
        tag.put("receipts", receipts.copy());
        tag.putString("before_inventory_digest", beforeInventoryDigest);
        tag.putString("after_inventory_digest", afterInventoryDigest);
        tag.putInt("recovery_attempts", recoveryAttempts);
        tag.putString("last_recovery_result", lastRecoveryResult);
        return tag;
    }

    public static TransactionRecord load(CompoundTag tag) {
        int schema = tag.contains("schema_version", Tag.TAG_INT) ? Math.max(1, tag.getInt("schema_version")) : 1;
        Stage savedStage = Stage.valueOf(tag.getString("stage"));
        // v1 没有逐槽收据，除已提交记录外必须进入人工隔离，禁止猜测性补发或删除。
        Stage safeStage = schema < CURRENT_SCHEMA && savedStage != Stage.COMMITTED ? Stage.MANUAL_REVIEW : savedStage;
        return new TransactionRecord(
                schema,
                tag.getUUID("id"),
                tag.getUUID("player_id"),
                tag.getUUID("token_id"),
                tag.getUUID("bundle_id"),
                Kind.valueOf(tag.getString("kind")),
                safeStage,
                PrizeBundle.load(tag.getCompound("payload")),
                tag.getLong("created_game_time"),
                tag.getLong("updated_game_time"),
                tag.contains("receipts", Tag.TAG_COMPOUND) ? tag.getCompound("receipts") : new CompoundTag(),
                tag.getString("before_inventory_digest"),
                tag.getString("after_inventory_digest"),
                tag.getInt("recovery_attempts"),
                tag.getString("last_recovery_result"));
    }

    public TransactionRecord withStage(Stage next, long gameTime) {
        if (stage == Stage.COMMITTED && next != Stage.COMMITTED) return this;
        return new TransactionRecord(schemaVersion, id, playerId, tokenId, bundleId, kind, next, payload,
                createdGameTime, Math.max(updatedGameTime, gameTime), receipts,
                beforeInventoryDigest, afterInventoryDigest, recoveryAttempts, lastRecoveryResult);
    }

    public TransactionRecord withRecoveryResult(Stage next, long gameTime, String result) {
        return new TransactionRecord(schemaVersion, id, playerId, tokenId, bundleId, kind, next, payload,
                createdGameTime, Math.max(updatedGameTime, gameTime), receipts,
                beforeInventoryDigest, afterInventoryDigest, recoveryAttempts + 1, result);
    }
}
