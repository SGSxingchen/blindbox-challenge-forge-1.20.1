package cn.blindboxchallenge.network;

import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.service.BlindBoxService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** 客户端不能携带物品内容，只能提交显示时观察到的槽位、数量和指纹。 */
public record CommitPackingPacket(int containerId, List<BlindBoxService.Selection> selections) {
    private static final int MAX_SELECTIONS = 36;

    public static void encode(CommitPackingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeVarInt(packet.selections().size());
        for (BlindBoxService.Selection selection : packet.selections()) {
            buffer.writeVarInt(selection.slot());
            buffer.writeVarInt(selection.count());
            buffer.writeUtf(selection.fingerprint(), 64);
        }
    }

    public static CommitPackingPacket decode(FriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_SELECTIONS) throw new IllegalArgumentException("选择数量超限");
        List<BlindBoxService.Selection> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(new BlindBoxService.Selection(buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(64)));
        return new CommitPackingPacket(id, result);
    }

    public static void handle(CommitPackingPacket packet, java.util.function.Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.containerMenu instanceof PackingMenu && player.containerMenu.containerId == packet.containerId()) {
                BlindBoxService.pack(player, packet.selections());
            }
        });
        context.setPacketHandled(true);
    }
}
