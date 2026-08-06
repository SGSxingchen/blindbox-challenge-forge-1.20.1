package cn.blindboxchallenge.service;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.registry.ModItems;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** P4 文本物品的 NBT 身份、槽位校验与纯文本规则。 */
public final class LetterService {
    public static final String INSTANCE_ID_KEY = "blindboxchallenge_instance_id";
    public static final String REVISION_KEY = "blindboxchallenge_revision";
    public static final String LETTER_BODY_KEY = "blindboxchallenge_letter_body";
    public static final int OFFHAND_SLOT = 40;
    /** 配置允许 4096 个码点；最坏 UTF-8 编码占 4 字节，网络边界必须大于字符数。 */
    public static final int MAX_NETWORK_BODY_LENGTH = 16384;

    private LetterService() {}

    public static int handSlot(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : OFFHAND_SLOT;
    }

    public static UUID ensureInstanceId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.hasUUID(INSTANCE_ID_KEY)) tag.putUUID(INSTANCE_ID_KEY, UUID.randomUUID());
        return tag.getUUID(INSTANCE_ID_KEY);
    }

    public static int revision(ItemStack stack) {
        return stack.hasTag() ? Math.max(0, stack.getTag().getInt(REVISION_KEY)) : 0;
    }

    public static String body(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getString(LETTER_BODY_KEY) : "";
    }

    /** 已存在 NBT 的读取边界不依赖当前较小的服务器配置，仍拒绝注入和超出网络上限的损坏数据。 */
    public static String safeBodyForRead(ItemStack stack) {
        String normalized = normalizePureText(body(stack));
        validateLimits(normalized, 4096, 16);
        return normalized;
    }

    public static boolean isMatchingTextItem(ServerPlayer player, int slot, UUID instanceId, int revision, boolean letter) {
        if (slot < 0 || slot > OFFHAND_SLOT || (slot >= 36 && slot != OFFHAND_SLOT)) return false;
        ItemStack held = player.getInventory().getItem(slot);
        if (held.isEmpty() || !(letter ? held.is(ModItems.LETTER.get()) : held.is(ModItems.DEATH_NOTE.get()))) return false;
        return held.hasTag() && held.getTag().hasUUID(INSTANCE_ID_KEY)
                && instanceId.equals(held.getTag().getUUID(INSTANCE_ID_KEY)) && revision(held) == revision;
    }

    public static ItemStack textItemAt(ServerPlayer player, int slot) {
        return player.getInventory().getItem(slot);
    }

    /** 只接受可显示的纯文本，统一所有换行后再按服务端阈值计数。 */
    public static String normalizeAndValidateBody(String raw) {
        if (raw == null) throw new IllegalArgumentException("正文为空");
        String normalized = normalizePureText(raw);
        validateLimits(normalized, ModServerConfig.LETTER_MAX_CODE_POINTS.get(), ModServerConfig.LETTER_MAX_LINES.get());
        return normalized;
    }

    private static String normalizePureText(String raw) {
        if (raw == null) throw new IllegalArgumentException("正文为空");
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\u00a7') >= 0) throw new IllegalArgumentException("不允许格式码");
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint) && codePoint != '\n') throw new IllegalArgumentException("不允许控制字符");
            index += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static void validateLimits(String normalized, int maximumCodePoints, int maximumLines) {
        int codePoints = normalized.codePointCount(0, normalized.length());
        int lines = normalized.isEmpty() ? 1 : normalized.split("\\n", -1).length;
        if (codePoints > maximumCodePoints || lines > maximumLines) {
            throw new IllegalArgumentException("正文超过服务器限制");
        }
    }

    public static void saveLetterBody(ItemStack stack, String normalized) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(LETTER_BODY_KEY, normalized);
        tag.putInt(REVISION_KEY, revision(stack) + 1);
    }

    public static void advanceRevision(ItemStack stack) {
        stack.getOrCreateTag().putInt(REVISION_KEY, revision(stack) + 1);
    }
}
