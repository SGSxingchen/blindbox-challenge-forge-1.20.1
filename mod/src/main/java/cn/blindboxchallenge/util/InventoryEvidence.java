package cn.blindboxchallenge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** 为崩溃恢复保存完整 ItemStack 证据，并生成与 Compound 键插入顺序无关的摘要。 */
public final class InventoryEvidence {
    private InventoryEvidence() {}

    public static List<ItemStack> copyMain(Inventory inventory) {
        List<ItemStack> result = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) result.add(inventory.getItem(slot).copy());
        return result;
    }

    public static CompoundTag stack(ItemStack stack) {
        return stack.save(new CompoundTag());
    }

    public static String digest(Inventory inventory) {
        return digest(copyMain(inventory), inventory.offhand.get(0), inventory.player.containerMenu.getCarried());
    }

    public static String digest(List<ItemStack> main, ItemStack offhand, ItemStack carried) {
        CompoundTag root = new CompoundTag();
        ListTag slots = new ListTag();
        for (int slot = 0; slot < 36; slot++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("stack", stack(main.get(slot)));
            slots.add(entry);
        }
        root.put("main", slots);
        root.put("offhand", stack(offhand));
        root.put("carried", stack(carried));
        return sha256(canonical(root));
    }

    public static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            StringBuilder out = new StringBuilder("{");
            compound.getAllKeys().stream().sorted(Comparator.naturalOrder()).forEach(key -> {
                out.append(key.length()).append(':').append(key).append('=').append(canonical(compound.get(key))).append(';');
            });
            return out.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder out = new StringBuilder("[");
            for (Tag element : list) out.append(canonical(element)).append(';');
            return out.append(']').toString();
        }
        return tag == null ? "null" : tag.getId() + ":" + tag;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte part : bytes) result.append(String.format("%02x", part));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 17 必须提供 SHA-256", exception);
        }
    }
}
