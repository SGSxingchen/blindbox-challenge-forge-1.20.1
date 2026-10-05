package cn.blindboxchallenge.network;

import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.service.AudioDownloadLimits;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkEvent;

/** 只发给触发时在线的玩家；新登录者没有历史事件，因此不会补播。 */
public record PlayMusicBoxPacket(UUID eventId, String url, BlockPos source, long serverGameTime, AudioDownloadLimits limits) {
    public static void encode(PlayMusicBoxPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.eventId());
        buffer.writeUtf(packet.url(), AudioUrlPolicy.MAX_URL_LENGTH);
        buffer.writeBlockPos(packet.source());
        buffer.writeLong(packet.serverGameTime());
        buffer.writeVarInt(packet.limits().maxBytes());
        buffer.writeVarInt(packet.limits().connectTimeoutMillis());
        buffer.writeVarInt(packet.limits().readTimeoutMillis());
        buffer.writeVarInt(packet.limits().totalTimeoutMillis());
    }

    public static PlayMusicBoxPacket decode(FriendlyByteBuf buffer) {
        return new PlayMusicBoxPacket(buffer.readUUID(), buffer.readUtf(AudioUrlPolicy.MAX_URL_LENGTH), buffer.readBlockPos(),
                buffer.readLong(), new AudioDownloadLimits(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
    }

    public static void handle(PlayMusicBoxPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide().isClient()) {
                MinecraftForge.EVENT_BUS.post(new MusicBoxPlaybackEvent(packet.eventId(), packet.url(), packet.source(),
                        packet.serverGameTime(), packet.limits()));
            }
        });
        context.setPacketHandled(true);
    }
}
