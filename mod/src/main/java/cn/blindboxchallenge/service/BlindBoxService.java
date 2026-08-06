package cn.blindboxchallenge.service;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.util.InventoryEvidence;
import cn.blindboxchallenge.util.StackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** P1 的服务端事务入口。调用方必须已经在逻辑服务端主线程。 */
public final class BlindBoxService {
    public static final String TOKEN_KEY = "blindboxchallenge_token";
    private BlindBoxService() {}

    public record Selection(int slot, int count, String fingerprint) {}

    public static ItemStack createBlindBox(UUID token) {
        ItemStack box = new ItemStack(ModItems.BLIND_BOX.get());
        box.getOrCreateTag().putUUID(TOKEN_KEY, token);
        return box;
    }

    public static UUID ensureToken(ItemStack box) {
        if (!box.hasTag() || !box.getTag().hasUUID(TOKEN_KEY)) box.getOrCreateTag().putUUID(TOKEN_KEY, UUID.randomUUID());
        return box.getTag().getUUID(TOKEN_KEY);
    }

    public static boolean isForbidden(ItemStack stack) {
        return stack.is(ModItems.BLIND_BOX.get()) || stack.is(ModItems.PACKING_TOOL.get());
    }

    public static boolean pack(ServerPlayer player, List<Selection> selections) {
        if (selections.isEmpty() || selections.size() > 36) return fail(player, "请选择至少一种可打包物品。");
        Inventory inventory = player.getInventory();
        boolean[] seen = new boolean[36];
        List<ItemStack> payload = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection.slot() < 0 || selection.slot() >= 36 || selection.count() <= 0 || seen[selection.slot()]) return fail(player, "打包请求包含无效或重复槽位。");
            seen[selection.slot()] = true;
            ItemStack source = inventory.getItem(selection.slot());
            if (source.isEmpty() || isForbidden(source) || selection.count() > source.getCount() || !StackFingerprint.of(source).equals(selection.fingerprint())) {
                return fail(player, "背包已变化，未执行打包。请重新选择物品。");
            }
            ItemStack copy = source.copy();
            copy.setCount(selection.count());
            payload.add(copy);
        }
        if (!canFitAfterRemoving(inventory, selections, List.of(createBlindBox(UUID.randomUUID())))) return fail(player, "背包没有空间接收盲盒，未执行打包。");

        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(player.serverLevel());
        UUID transactionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        PrizeBundle bundle = data.createBundle(player.getUUID(), player.level().getGameTime(), payload);
        List<ItemStack> beforeMain = InventoryEvidence.copyMain(inventory);
        List<ItemStack> afterMain = copyStacks(beforeMain);
        for (Selection selection : selections) afterMain.get(selection.slot()).shrink(selection.count());
        ItemStack expectedBox = createBlindBox(token);
        if (!insertAll(afterMain, List.of(expectedBox.copy()))) return fail(player, "背包没有空间接收盲盒，未执行打包。");
        CompoundTag receipts = packReceipts(selections, beforeMain, afterMain, expectedBox);
        String beforeDigest = InventoryEvidence.digest(beforeMain, inventory.offhand.get(0), inventory.player.containerMenu.getCarried());
        String afterDigest = InventoryEvidence.digest(afterMain, inventory.offhand.get(0), inventory.player.containerMenu.getCarried());
        data.prepare(TransactionRecord.createV2(transactionId, player.getUUID(), token, bundle.id(), TransactionRecord.Kind.PACK, bundle,
                player.level().getGameTime(), receipts, beforeDigest, afterDigest));

        // 重新从服务端槽位扣除；上方验证与同一主线程保证此处不会被并发改变。
        for (Selection selection : selections) inventory.removeItem(selection.slot(), selection.count());
        ItemStack box = expectedBox.copy();
        if (!inventory.add(box)) {
            // 理论上已预演；若模组交互导致失败，立即回滚并保留 PREPARED 记录供登录恢复审计。
            for (ItemStack stack : payload) inventory.add(stack.copy());
            data.markManualReview(transactionId, player.level().getGameTime(), "inventory_delivery_failed");
            return fail(player, "检测到异常背包状态，物品已尝试回滚；事务已隔离供管理员检查。");
        }
        data.markStage(transactionId, TransactionRecord.Stage.PLAYER_APPLIED, player.level().getGameTime());
        data.commitPack(transactionId, bundle, player.level().getGameTime());
        player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.literal("已打包 " + payload.size() + " 种物品并加入全局奖池。"), true);
        return true;
    }

    public static boolean open(ServerPlayer player, ItemStack held) {
        if (!held.is(ModItems.BLIND_BOX.get())) return false;
        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(player.serverLevel());
        PrizeBundle bundle = data.randomBundle(player.getRandom()).orElse(null);
        if (bundle == null) return fail(player, "全局奖池为空，盲盒未消耗。");
        UUID token = ensureToken(held);
        if (!canFitAfterOpening(player.getInventory(), token, bundle.stacks())) return fail(player, "背包空间不足，盲盒和奖池均未改变。");

        UUID transactionId = UUID.randomUUID();
        if (!data.reserveOpen(bundle.id(), transactionId)) return fail(player, "奖项刚被其他玩家锁定，请重新开盒。");
        List<ItemStack> beforeMain = InventoryEvidence.copyMain(player.getInventory());
        ItemStack beforeOffhand = player.getOffhandItem().copy();
        int tokenSlot = findTokenSlot(beforeMain, token);
        boolean tokenInOffhand = tokenSlot < 0 && hasToken(beforeOffhand, token);
        if (tokenSlot < 0 && !tokenInOffhand) {
            data.releaseOpen(bundle.id(), transactionId);
            return fail(player, "手中盲盒状态已变化，未执行开盒。");
        }
        List<ItemStack> afterMain = copyStacks(beforeMain);
        ItemStack afterOffhand = beforeOffhand.copy();
        if (tokenInOffhand) afterOffhand.shrink(1); else afterMain.get(tokenSlot).shrink(1);
        if (!insertAll(afterMain, bundle.stacks())) {
            data.releaseOpen(bundle.id(), transactionId);
            return fail(player, "背包空间不足，盲盒和奖池均未改变。");
        }
        CompoundTag receipts = openReceipts(tokenInOffhand ? -1 : tokenSlot, beforeMain, afterMain, beforeOffhand, afterOffhand, bundle.stacks());
        String beforeDigest = InventoryEvidence.digest(beforeMain, beforeOffhand, player.containerMenu.getCarried());
        String afterDigest = InventoryEvidence.digest(afterMain, afterOffhand, player.containerMenu.getCarried());
        data.prepare(TransactionRecord.createV2(transactionId, player.getUUID(), token, bundle.id(), TransactionRecord.Kind.OPEN, bundle,
                player.level().getGameTime(), receipts, beforeDigest, afterDigest));
        held.shrink(1);
        for (ItemStack reward : bundle.stacks()) {
            if (!player.getInventory().add(reward.copy())) {
                data.markManualReview(transactionId, player.level().getGameTime(), "payload_delivery_failed");
                return fail(player, "异常：奖品交付未完成，事务已隔离，未生成地面掉落。");
            }
        }
        data.markStage(transactionId, TransactionRecord.Stage.PLAYER_APPLIED, player.level().getGameTime());
        data.commitOpen(transactionId, bundle.id(), player.level().getGameTime());
        player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.literal("盲盒已开启，获得一个完整奖项。"), true);
        return true;
    }

    /** 登录时只恢复能由逐槽收据、唯一 token 与 bundle 内容共同证明的 PACK 状态。 */
    public static void inspectRecovery(ServerPlayer player) {
        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(player.serverLevel());
        List<TransactionRecord> pending = data.pendingFor(player.getUUID()).stream().limit(32).toList();
        if (pending.isEmpty()) return;
        player.closeContainer();
        int recovered = 0;
        int isolated = 0;
        for (TransactionRecord record : pending) {
            if (record.schemaVersion() < TransactionRecord.CURRENT_SCHEMA) {
                data.markManualReview(record.id(), player.level().getGameTime(), "legacy_recovery_not_supported");
                isolated++;
                continue;
            }
            RecoveryResult result = record.kind() == TransactionRecord.Kind.PACK
                    ? recoverPack(player, data, record) : recoverOpen(player, data, record);
            if (result == RecoveryResult.RECOVERED) recovered++; else isolated++;
        }
        player.containerMenu.broadcastChanges();
        if (recovered > 0) player.sendSystemMessage(Component.literal("已幂等恢复 " + recovered + " 个盲盒打包事务。").withStyle(ChatFormatting.GREEN));
        if (isolated > 0) player.sendSystemMessage(Component.literal("另有 " + isolated + " 个事务证据冲突，已隔离且未自动增删资产。")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static RecoveryResult recoverPack(ServerPlayer player, BlindBoxPoolSavedData data, TransactionRecord record) {
        CompoundTag receipts = record.receipts();
        ListTag sources = receipts.getList("source_receipts", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (sources.isEmpty() || !receipts.contains("token_receipt", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            data.markManualReview(record.id(), player.level().getGameTime(), "missing_pack_receipts");
            return RecoveryResult.ISOLATED;
        }
        boolean allBefore = true;
        boolean allAfter = true;
        for (int i = 0; i < sources.size(); i++) {
            CompoundTag receipt = sources.getCompound(i);
            int slot = receipt.getInt("slot");
            if (slot < 0 || slot >= 36) {
                data.markManualReview(record.id(), player.level().getGameTime(), "invalid_source_slot");
                return RecoveryResult.ISOLATED;
            }
            CompoundTag current = InventoryEvidence.stack(player.getInventory().getItem(slot));
            allBefore &= current.equals(receipt.getCompound("before"));
            allAfter &= current.equals(receipt.getCompound("after"));
        }
        int tokenCount = countToken(player, record.tokenId());
        PrizeBundle existing = data.bundle(record.bundleId()).orElse(null);
        boolean bundleMatches = existing != null && existing.save().equals(record.payload().save());
        boolean bundleAbsent = existing == null;

        if (allBefore && tokenCount == 0 && bundleAbsent) {
            data.resolveRecovery(record.id(), TransactionRecord.Stage.ROLLED_BACK, player.level().getGameTime(), "pack_before_state_intact");
            return RecoveryResult.RECOVERED;
        }
        if (allAfter && tokenCount == 1 && (bundleAbsent || bundleMatches)) {
            if (!data.ensureBundle(record.payload())) {
                data.markManualReview(record.id(), player.level().getGameTime(), "bundle_content_conflict");
                return RecoveryResult.ISOLATED;
            }
            data.resolveRecovery(record.id(), TransactionRecord.Stage.COMMITTED, player.level().getGameTime(),
                    bundleAbsent ? "pack_bundle_reinserted" : "pack_commit_confirmed");
            return RecoveryResult.RECOVERED;
        }
        if (allAfter && tokenCount == 0 && bundleAbsent) {
            for (int i = 0; i < sources.size(); i++) {
                CompoundTag receipt = sources.getCompound(i);
                player.getInventory().setItem(receipt.getInt("slot"), ItemStack.of(receipt.getCompound("before")));
            }
            data.resolveRecovery(record.id(), TransactionRecord.Stage.ROLLED_BACK, player.level().getGameTime(), "pack_sources_restored");
            return RecoveryResult.RECOVERED;
        }
        String reason = tokenCount > 1 ? "duplicate_token" : (!bundleAbsent && !bundleMatches ? "bundle_content_conflict" : "unproven_pack_state");
        data.markManualReview(record.id(), player.level().getGameTime(), reason);
        return RecoveryResult.ISOLATED;
    }

    private static int countToken(ServerPlayer player, UUID tokenId) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (hasToken(player.getInventory().getItem(slot), tokenId)) count += player.getInventory().getItem(slot).getCount();
        if (hasToken(player.getOffhandItem(), tokenId)) count += player.getOffhandItem().getCount();
        ItemStack carried = player.containerMenu.getCarried();
        if (hasToken(carried, tokenId)) count += carried.getCount();
        return count;
    }

    private static boolean hasToken(ItemStack stack, UUID tokenId) {
        return stack.is(ModItems.BLIND_BOX.get()) && stack.hasTag() && stack.getTag().hasUUID(TOKEN_KEY)
                && tokenId.equals(stack.getTag().getUUID(TOKEN_KEY));
    }

    private static RecoveryResult recoverOpen(ServerPlayer player, BlindBoxPoolSavedData data, TransactionRecord record) {
        CompoundTag receipts = record.receipts();
        ListTag beforeTag = receipts.getList("before_main", net.minecraft.nbt.Tag.TAG_COMPOUND);
        ListTag afterTag = receipts.getList("after_main", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (beforeTag.size() != 36 || afterTag.size() != 36 || !receipts.contains("token_slot", net.minecraft.nbt.Tag.TAG_INT)) {
            data.markManualReview(record.id(), player.level().getGameTime(), "missing_open_receipts");
            return RecoveryResult.ISOLATED;
        }
        List<ItemStack> before = loadMain(beforeTag);
        List<ItemStack> after = loadMain(afterTag);
        ItemStack beforeOffhand = ItemStack.of(receipts.getCompound("before_offhand"));
        ItemStack afterOffhand = ItemStack.of(receipts.getCompound("after_offhand"));
        boolean isBefore = mainMatches(player.getInventory(), before)
                && InventoryEvidence.stack(player.getOffhandItem()).equals(InventoryEvidence.stack(beforeOffhand));
        boolean isAfter = mainMatches(player.getInventory(), after)
                && InventoryEvidence.stack(player.getOffhandItem()).equals(InventoryEvidence.stack(afterOffhand));
        int tokenCount = countToken(player, record.tokenId());
        PrizeBundle existing = data.bundle(record.bundleId()).orElse(null);
        boolean bundleMatches = existing != null && existing.save().equals(record.payload().save());
        boolean bundleAbsent = existing == null;
        if (isBefore && tokenCount == 1 && bundleMatches) {
            data.releaseOpen(record.bundleId(), record.id());
            data.resolveRecovery(record.id(), TransactionRecord.Stage.ROLLED_BACK, player.level().getGameTime(), "open_before_state_intact");
            return RecoveryResult.RECOVERED;
        }
        if (isAfter && tokenCount == 0 && (bundleAbsent || bundleMatches)) {
            if (!bundleAbsent && !data.removeReservedBundle(record.bundleId(), record.id())) {
                data.markManualReview(record.id(), player.level().getGameTime(), "open_reservation_conflict");
                return RecoveryResult.ISOLATED;
            }
            data.resolveRecovery(record.id(), TransactionRecord.Stage.COMMITTED, player.level().getGameTime(),
                    bundleAbsent ? "open_commit_confirmed" : "open_bundle_removed");
            return RecoveryResult.RECOVERED;
        }
        if (tokenCount > 1) {
            data.markManualReview(record.id(), player.level().getGameTime(), "duplicate_token");
        } else if (existing != null && !bundleMatches) {
            data.markManualReview(record.id(), player.level().getGameTime(), "bundle_content_conflict");
        } else {
            data.markManualReview(record.id(), player.level().getGameTime(), "unproven_open_state");
        }
        return RecoveryResult.ISOLATED;
    }

    private static List<ItemStack> loadMain(ListTag tag) {
        List<ItemStack> result = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) result.add(ItemStack.of(tag.getCompound(i).getCompound("stack")));
        return result;
    }

    private static boolean mainMatches(Inventory inventory, List<ItemStack> expected) {
        for (int i = 0; i < 36; i++) {
            if (!InventoryEvidence.stack(inventory.getItem(i)).equals(InventoryEvidence.stack(expected.get(i)))) return false;
        }
        return true;
    }

    private static int findTokenSlot(List<ItemStack> main, UUID token) {
        for (int i = 0; i < main.size(); i++) if (hasToken(main.get(i), token)) return i;
        return -1;
    }

    private static CompoundTag openReceipts(int tokenSlot, List<ItemStack> before, List<ItemStack> after,
                                            ItemStack beforeOffhand, ItemStack afterOffhand, List<ItemStack> payload) {
        CompoundTag receipts = new CompoundTag();
        receipts.putInt("token_slot", tokenSlot);
        receipts.put("before_main", saveMain(before));
        receipts.put("after_main", saveMain(after));
        receipts.put("before_offhand", InventoryEvidence.stack(beforeOffhand));
        receipts.put("after_offhand", InventoryEvidence.stack(afterOffhand));
        ListTag payloadReceipts = new ListTag();
        for (ItemStack stack : payload) payloadReceipts.add(InventoryEvidence.stack(stack));
        receipts.put("payload_receipts", payloadReceipts);
        return receipts;
    }

    private static ListTag saveMain(List<ItemStack> stacks) {
        ListTag result = new ListTag();
        for (int i = 0; i < 36; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", i);
            entry.put("stack", InventoryEvidence.stack(stacks.get(i)));
            result.add(entry);
        }
        return result;
    }

    private enum RecoveryResult { RECOVERED, ISOLATED }

    private static boolean canFitAfterRemoving(Inventory inventory, List<Selection> selections, List<ItemStack> additions) {
        List<ItemStack> virtual = copyMain(inventory);
        for (Selection selection : selections) virtual.get(selection.slot()).shrink(selection.count());
        return canInsertAll(virtual, additions);
    }

    private static boolean canFitAfterOpening(Inventory inventory, UUID token, List<ItemStack> rewards) {
        List<ItemStack> virtual = copyMain(inventory);
        int tokenSlot = findTokenSlot(virtual, token);
        if (tokenSlot >= 0) virtual.get(tokenSlot).shrink(1);
        else if (!hasToken(inventory.player.getOffhandItem(), token)) return false;
        return canInsertAll(virtual, rewards);
    }

    private static List<ItemStack> copyMain(Inventory inventory) {
        return InventoryEvidence.copyMain(inventory);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) result.add(stack.copy());
        return result;
    }

    private static CompoundTag packReceipts(List<Selection> selections, List<ItemStack> before, List<ItemStack> after, ItemStack token) {
        CompoundTag receipts = new CompoundTag();
        ListTag sources = new ListTag();
        for (Selection selection : selections) {
            CompoundTag receipt = new CompoundTag();
            receipt.putInt("slot", selection.slot());
            receipt.putInt("removed_count", selection.count());
            receipt.put("before", InventoryEvidence.stack(before.get(selection.slot())));
            receipt.put("after", InventoryEvidence.stack(after.get(selection.slot())));
            sources.add(receipt);
        }
        receipts.put("source_receipts", sources);
        CompoundTag tokenReceipt = new CompoundTag();
        tokenReceipt.put("stack", InventoryEvidence.stack(token));
        tokenReceipt.putInt("expected_count", 1);
        receipts.put("token_receipt", tokenReceipt);
        return receipts;
    }

    private static boolean canInsertAll(List<ItemStack> slots, List<ItemStack> additions) {
        return insertAll(copyStacks(slots), additions);
    }

    private static boolean insertAll(List<ItemStack> slots, List<ItemStack> additions) {
        for (ItemStack original : additions) {
            ItemStack remaining = original.copy();
            for (ItemStack slot : slots) {
                if (!remaining.isEmpty() && !slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                    int moved = Math.min(remaining.getCount(), Math.min(slot.getMaxStackSize(), 64) - slot.getCount());
                    if (moved > 0) { slot.grow(moved); remaining.shrink(moved); }
                }
            }
            for (int i = 0; i < slots.size() && !remaining.isEmpty(); i++) {
                if (slots.get(i).isEmpty()) {
                    int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    ItemStack inserted = remaining.copy(); inserted.setCount(moved); slots.set(i, inserted); remaining.shrink(moved);
                }
            }
            if (!remaining.isEmpty()) return false;
        }
        return true;
    }

    private static boolean fail(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.RED), true);
        return false;
    }
}
