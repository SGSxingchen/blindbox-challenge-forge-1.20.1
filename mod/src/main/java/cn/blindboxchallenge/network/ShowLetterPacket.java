package cn.blindboxchallenge.network;

import cn.blindboxchallenge.event.LetterReadEvent;
import cn.blindboxchallenge.service.LetterService;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkEvent;

/** 服务端读取信件后向对应客户端发送的只读纯文本快照。 */
public record ShowLetterPacket(UUID instanceId, int revision, String body) {
    public static void encode(ShowLetterPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.instanceId());
        buffer.writeVarInt(packet.revision());
        buffer.writeUtf(packet.body(), LetterService.MAX_NETWORK_BODY_LENGTH);
    }

    public static ShowLetterPacket decode(FriendlyByteBuf buffer) {
        return new ShowLetterPacket(buffer.readUUID(), buffer.readVarInt(), buffer.readUtf(LetterService.MAX_NETWORK_BODY_LENGTH));
    }

    public static void handle(ShowLetterPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide().isClient()) {
                MinecraftForge.EVENT_BUS.post(new LetterReadEvent(packet.instanceId(), packet.revision(), packet.body()));
            }
        });
        context.setPacketHandled(true);
    }
}
