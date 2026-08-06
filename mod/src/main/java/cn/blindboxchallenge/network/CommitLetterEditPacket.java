package cn.blindboxchallenge.network;

import cn.blindboxchallenge.menu.LetterEditMenu;
import cn.blindboxchallenge.service.LetterService;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/** 客户端仅提交文本意图；会话、手持实例、槽位和修订均在服务端重读。 */
public record CommitLetterEditPacket(int containerId, UUID sessionId, int heldSlot, UUID instanceId, int revision, String body) {
    public static void encode(CommitLetterEditPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeUUID(packet.sessionId());
        buffer.writeVarInt(packet.heldSlot());
        buffer.writeUUID(packet.instanceId());
        buffer.writeVarInt(packet.revision());
        buffer.writeUtf(packet.body(), LetterService.MAX_NETWORK_BODY_LENGTH);
    }

    public static CommitLetterEditPacket decode(FriendlyByteBuf buffer) {
        return new CommitLetterEditPacket(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(),
                buffer.readUtf(LetterService.MAX_NETWORK_BODY_LENGTH));
    }

    public static void handle(CommitLetterEditPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!isAuthorized(player, packet)) return;
            String normalized;
            try {
                normalized = LetterService.normalizeAndValidateBody(packet.body());
            } catch (IllegalArgumentException ignored) {
                return;
            }
            LetterEditMenu menu = (LetterEditMenu) player.containerMenu;
            if (!menu.consumeSubmission()) return;
            ItemStack stack = LetterService.textItemAt(player, packet.heldSlot());
            LetterService.saveLetterBody(stack, normalized);
            player.containerMenu.broadcastChanges();
            player.closeContainer();
        });
        context.setPacketHandled(true);
    }

    public static boolean isAuthorized(ServerPlayer player, CommitLetterEditPacket packet) {
        if (player == null || !(player.containerMenu instanceof LetterEditMenu menu)
                || player.containerMenu.containerId != packet.containerId() || !menu.stillValid(player) || menu.submissionConsumed()) return false;
        return menu.sessionId().equals(packet.sessionId()) && menu.heldSlot() == packet.heldSlot()
                && menu.instanceId().equals(packet.instanceId()) && menu.revision() == packet.revision()
                && LetterService.isMatchingTextItem(player, packet.heldSlot(), packet.instanceId(), packet.revision(), true);
    }
}
