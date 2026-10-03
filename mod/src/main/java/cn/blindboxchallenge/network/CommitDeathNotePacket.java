package cn.blindboxchallenge.network;

import cn.blindboxchallenge.menu.DeathNoteMenu;
import cn.blindboxchallenge.service.DeathNoteService;
import cn.blindboxchallenge.service.LetterService;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

/** 死亡笔记只接受短玩家名；服务端在线解析为 UUID 并持久排程。 */
public record CommitDeathNotePacket(int containerId, UUID sessionId, int heldSlot, UUID instanceId, int revision, String targetName) {
    private static final int MAX_TARGET_NAME_LENGTH = 16;
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void encode(CommitDeathNotePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeUUID(packet.sessionId());
        buffer.writeVarInt(packet.heldSlot());
        buffer.writeUUID(packet.instanceId());
        buffer.writeVarInt(packet.revision());
        buffer.writeUtf(packet.targetName(), MAX_TARGET_NAME_LENGTH);
    }

    public static CommitDeathNotePacket decode(FriendlyByteBuf buffer) {
        return new CommitDeathNotePacket(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readUtf(MAX_TARGET_NAME_LENGTH));
    }

    public static void handle(CommitDeathNotePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> commit(context.getSender(), packet));
        context.setPacketHandled(true);
    }

    /** 离线目标失败也结束已消费会话；重开获得新会话，旧请求仍被拒绝。 */
    public static boolean commit(ServerPlayer player, CommitDeathNotePacket packet) {
        if (!isAuthorized(player, packet) || !DeathNoteService.isValidPlayerName(packet.targetName())) return false;
        DeathNoteMenu menu = (DeathNoteMenu) player.containerMenu;
        if (!menu.consumeSubmission()) return false;
        try {
            if (!DeathNoteService.schedule(player, packet.targetName())) return false;
            ItemStack stack = LetterService.textItemAt(player, packet.heldSlot());
            LetterService.advanceRevision(stack);
            player.containerMenu.broadcastChanges();
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("死亡笔记提交失败，已结束本次菜单会话", exception);
            player.displayClientMessage(Component.translatable("message.blindboxchallenge.death_note_invalid_target"), true);
            return false;
        } finally {
            player.closeContainer();
        }
    }

    public static boolean isAuthorized(ServerPlayer player, CommitDeathNotePacket packet) {
        if (player == null || !(player.containerMenu instanceof DeathNoteMenu menu)
                || player.containerMenu.containerId != packet.containerId() || !menu.stillValid(player) || menu.submissionConsumed()) return false;
        return menu.sessionId().equals(packet.sessionId()) && menu.heldSlot() == packet.heldSlot()
                && menu.instanceId().equals(packet.instanceId()) && menu.revision() == packet.revision()
                && LetterService.isMatchingTextItem(player, packet.heldSlot(), packet.instanceId(), packet.revision(), false);
    }
}
