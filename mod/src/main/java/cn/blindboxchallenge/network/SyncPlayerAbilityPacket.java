package cn.blindboxchallenge.network;

import cn.blindboxchallenge.event.PlayerAbilitySyncEvent;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkEvent;

/** 服务端能力快照；公共包不引用客户端类，只在客户端事件总线上发布数据事件。 */
public record SyncPlayerAbilityPacket(int entityId, boolean learnedYiJin) {
    public static void encode(SyncPlayerAbilityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeBoolean(packet.learnedYiJin);
    }

    public static SyncPlayerAbilityPacket decode(FriendlyByteBuf buffer) {
        return new SyncPlayerAbilityPacket(buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(SyncPlayerAbilityPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide().isClient()) {
                MinecraftForge.EVENT_BUS.post(new PlayerAbilitySyncEvent(packet.entityId, packet.learnedYiJin));
            }
        });
        context.setPacketHandled(true);
    }
}
