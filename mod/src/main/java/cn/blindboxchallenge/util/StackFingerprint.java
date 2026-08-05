package cn.blindboxchallenge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** 只用于检测菜单提交期间的陈旧槽位；物品本身始终从服务端库存读取。 */
public final class StackFingerprint {
    private StackFingerprint() {}

    public static String of(ItemStack stack) {
        CompoundTag tag = stack.save(new CompoundTag());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(tag.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 17 必须提供 SHA-256", exception);
        }
    }
}
