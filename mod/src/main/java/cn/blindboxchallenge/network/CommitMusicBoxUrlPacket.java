package cn.blindboxchallenge.network;

import cn.blindboxchallenge.blockentity.MusicBoxBlockEntity;
import cn.blindboxchallenge.menu.MusicBoxMenu;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** 客户端只能提交编辑意图；方块位置、实例、修订、距离与会话均由服务端重读。 */
public record CommitMusicBoxUrlPacket(int containerId, UUID sessionId, BlockPos position, UUID instanceId, int revision, String url) {
    public static void encode(CommitMusicBoxUrlPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeUUID(packet.sessionId());
        buffer.writeBlockPos(packet.position());
        buffer.writeUUID(packet.instanceId());
        buffer.writeVarInt(packet.revision());
        buffer.writeUtf(packet.url(), AudioUrlPolicy.MAX_URL_LENGTH);
    }

    public static CommitMusicBoxUrlPacket decode(FriendlyByteBuf buffer) {
        return new CommitMusicBoxUrlPacket(buffer.readVarInt(), buffer.readUUID(), buffer.readBlockPos(), buffer.readUUID(),
                buffer.readVarInt(), buffer.readUtf(AudioUrlPolicy.MAX_URL_LENGTH));
    }

    public static void handle(CommitMusicBoxUrlPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!isAuthorized(player, packet)) return;
            MusicBoxMenu menu = (MusicBoxMenu) player.containerMenu;
            MusicBoxBlockEntity box = (MusicBoxBlockEntity) player.level().getBlockEntity(packet.position());
            String normalized;
            try {
                normalized = AudioUrlPolicy.normalizeHttpsUrl(packet.url());
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (!menu.consumeSubmission()) return;
            box.setUrl(normalized);
            player.closeContainer();
        });
        context.setPacketHandled(true);
    }

    public static boolean isAuthorized(ServerPlayer player, CommitMusicBoxUrlPacket packet) {
        if (player == null || !(player.containerMenu instanceof MusicBoxMenu menu)
                || player.containerMenu.containerId != packet.containerId() || !menu.stillValid(player) || menu.submissionConsumed()
                || !menu.sessionId().equals(packet.sessionId()) || !menu.position().equals(packet.position())
                || !menu.instanceId().equals(packet.instanceId()) || menu.revision() != packet.revision()) return false;
            if (!(player.level().getBlockEntity(packet.position()) instanceof MusicBoxBlockEntity box)
                    || !box.instanceId().equals(packet.instanceId()) || box.revision() != packet.revision()) return false;
        return player.mayBuild() && player.level().mayInteract(player, packet.position());
    }
}
