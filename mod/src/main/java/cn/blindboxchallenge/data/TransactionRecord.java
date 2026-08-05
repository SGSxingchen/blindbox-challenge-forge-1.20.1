package cn.blindboxchallenge.data;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** 保留未完成事务的事实，不在模组重启后猜测性地增删物品。 */
public record TransactionRecord(UUID id, UUID playerId, UUID tokenId, UUID bundleId, Kind kind, Stage stage, PrizeBundle payload) {
    public enum Kind { PACK, OPEN }
    public enum Stage { PREPARED, COMMITTED, MANUAL_REVIEW }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("player_id", playerId);
        tag.putUUID("token_id", tokenId);
        tag.putUUID("bundle_id", bundleId);
        tag.putString("kind", kind.name());
        tag.putString("stage", stage.name());
        tag.put("payload", payload.save());
        return tag;
    }

    public static TransactionRecord load(CompoundTag tag) {
        return new TransactionRecord(tag.getUUID("id"), tag.getUUID("player_id"), tag.getUUID("token_id"), tag.getUUID("bundle_id"),
                Kind.valueOf(tag.getString("kind")), Stage.valueOf(tag.getString("stage")), PrizeBundle.load(tag.getCompound("payload")));
    }

    public TransactionRecord withStage(Stage next) {
        return new TransactionRecord(id, playerId, tokenId, bundleId, kind, next, payload);
    }
}
