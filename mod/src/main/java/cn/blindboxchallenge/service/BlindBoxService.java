package cn.blindboxchallenge.service;

import cn.blindboxchallenge.data.BlindBoxPoolSavedData;
import cn.blindboxchallenge.data.PrizeBundle;
import cn.blindboxchallenge.data.TransactionRecord;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.util.StackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
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
        data.prepare(TransactionRecord.createV2(transactionId, player.getUUID(), token, bundle.id(), TransactionRecord.Kind.PACK, bundle,
                player.level().getGameTime(), new CompoundTag(), "", ""));

        // 重新从服务端槽位扣除；上方验证与同一主线程保证此处不会被并发改变。
        for (Selection selection : selections) inventory.removeItem(selection.slot(), selection.count());
        ItemStack box = createBlindBox(token);
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
        if (!canFitAfterOpening(player.getInventory(), held, bundle.stacks())) return fail(player, "背包空间不足，盲盒和奖池均未改变。");

        UUID transactionId = UUID.randomUUID();
        data.prepare(TransactionRecord.createV2(transactionId, player.getUUID(), token, bundle.id(), TransactionRecord.Kind.OPEN, bundle,
                player.level().getGameTime(), new CompoundTag(), "", ""));
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

    /** 保守恢复：只自动标记并通知。无法从两个独立存档精确证明前后态时不猜测补发或删除。 */
    public static void inspectRecovery(ServerPlayer player) {
        BlindBoxPoolSavedData data = BlindBoxPoolSavedData.get(player.serverLevel());
        int pending = data.pendingFor(player.getUUID()).size();
        if (pending > 0) {
            for (TransactionRecord record : data.pendingFor(player.getUUID())) data.markManualReview(record.id(), player.level().getGameTime(), "legacy_or_unproven_state");
            player.sendSystemMessage(Component.literal("检测到 " + pending + " 个未完成盲盒事务，已隔离且未自动删改物品；请管理员使用 /blindbox pool count 检查。")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static boolean canFitAfterRemoving(Inventory inventory, List<Selection> selections, List<ItemStack> additions) {
        List<ItemStack> virtual = copyMain(inventory);
        for (Selection selection : selections) virtual.get(selection.slot()).shrink(selection.count());
        return canInsertAll(virtual, additions);
    }

    private static boolean canFitAfterOpening(Inventory inventory, ItemStack held, List<ItemStack> rewards) {
        List<ItemStack> virtual = copyMain(inventory);
        // 盲盒仅堆叠为 1；按同一物品引用的主背包槽位扣一件。
        for (ItemStack stack : virtual) {
            if (ItemStack.isSameItemSameTags(stack, held) && stack.getCount() > 0) { stack.shrink(1); break; }
        }
        return canInsertAll(virtual, rewards);
    }

    private static List<ItemStack> copyMain(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) result.add(inventory.getItem(slot).copy());
        return result;
    }

    private static boolean canInsertAll(List<ItemStack> slots, List<ItemStack> additions) {
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
