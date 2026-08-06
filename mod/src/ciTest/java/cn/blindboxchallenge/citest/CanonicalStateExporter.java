package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.data.DeathNoteSavedData;
import cn.blindboxchallenge.util.InventoryEvidence;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 从真实服务端状态生成稳定 JSON；只存在于 CI 探针 Jar。 */
public final class CanonicalStateExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static Path export(MinecraftServer server, Path target, String productSha) throws IOException {
        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(server.overworld());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1);
        root.put("world", server.overworld().dimension().location().toString());
        root.put("game_time", server.overworld().getGameTime());
        root.put("product_sha256", productSha == null ? "" : productSha);

        List<Map<String, Object>> players = new ArrayList<>();
        server.getPlayerList().getPlayers().stream()
                .sorted(Comparator.comparing(player -> player.getUUID().toString()))
                .forEach(player -> players.add(player(player)));
        root.put("players", players);

        List<Map<String, Object>> bundles = new ArrayList<>();
        data.bundles().stream().sorted(Comparator.comparing(bundle -> bundle.id().toString()))
                .forEach(bundle -> bundles.add(bundle(bundle)));
        root.put("bundles", bundles);

        List<Map<String, Object>> transactions = new ArrayList<>();
        data.transactions().stream().sorted(Comparator.comparing(record -> record.id().toString()))
                .forEach(record -> transactions.add(transaction(record)));
        root.put("transactions", transactions);

        List<Map<String, String>> reservations = new ArrayList<>();
        data.openReservations().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reservations.add(Map.of(
                        "bundle_id", entry.getKey().toString(),
                        "transaction_id", entry.getValue().toString())));
        root.put("open_reservations", reservations);

        List<Map<String, Object>> deathNotes = new ArrayList<>();
        DeathNoteSavedData.get(server.overworld()).entries().stream().sorted(Comparator.comparing(entry -> entry.id().toString()))
                .forEach(entry -> deathNotes.add(Map.of(
                        "id", entry.id().toString(), "owner", entry.owner().toString(), "target", entry.target().toString(),
                        "due_tick", entry.dueTick())));
        root.put("death_note_entries", deathNotes);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static Map<String, Object> player(ServerPlayer player) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uuid", player.getUUID().toString());
        out.put("name", player.getGameProfile().getName());
        out.put("inventory_digest", InventoryEvidence.digest(player.getInventory()));
        List<Map<String, Object>> main = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) main.add(slot(slot, player.getInventory().getItem(slot)));
        out.put("main", main);
        out.put("offhand", stack(player.getInventory().offhand.get(0)));
        out.put("carried", stack(player.containerMenu.getCarried()));
        return out;
    }

    private static Map<String, Object> slot(int slot, ItemStack stack) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slot", slot);
        out.put("stack", stack(stack));
        return out;
    }

    private static Map<String, Object> stack(ItemStack stack) {
        CompoundTag saved = InventoryEvidence.stack(stack);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical_nbt", InventoryEvidence.canonical(saved));
        out.put("item", stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.put("count", stack.getCount());
        return out;
    }

    private static Map<String, Object> bundle(PrizeBundle bundle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", bundle.id().toString());
        out.put("creator", bundle.creator().toString());
        out.put("created_game_time", bundle.createdGameTime());
        out.put("version", bundle.version());
        out.put("canonical_nbt", InventoryEvidence.canonical(bundle.save()));
        return out;
    }

    private static Map<String, Object> transaction(TransactionRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", record.id().toString());
        out.put("player_id", record.playerId().toString());
        out.put("token_id", record.tokenId().toString());
        out.put("bundle_id", record.bundleId().toString());
        out.put("kind", record.kind().name());
        out.put("stage", record.stage().name());
        out.put("schema_version", record.schemaVersion());
        out.put("recovery_attempts", record.recoveryAttempts());
        out.put("last_recovery_result", record.lastRecoveryResult());
        out.put("before_inventory_digest", record.beforeInventoryDigest());
        out.put("after_inventory_digest", record.afterInventoryDigest());
        out.put("canonical_receipts", InventoryEvidence.canonical(record.receipts()));
        return out;
    }

    private CanonicalStateExporter() {}
}
