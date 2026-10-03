package cn.blindboxchallenge.network;

import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.service.BlindBoxService;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

/** 客户端不能携带物品内容，只能提交显示时观察到的槽位、数量和指纹。 */
public record CommitPackingPacket(int containerId, UUID sessionId, List<BlindBoxService.Selection> selections) {
    private static final int MAX_SELECTIONS = 36;
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void encode(CommitPackingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeUUID(packet.sessionId());
        buffer.writeVarInt(packet.selections().size());
        for (BlindBoxService.Selection selection : packet.selections()) {
            buffer.writeVarInt(selection.slot());
            buffer.writeVarInt(selection.count());
            buffer.writeUtf(selection.fingerprint(), 64);
        }
    }

    public static CommitPackingPacket decode(FriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        UUID session = buffer.readUUID();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_SELECTIONS) throw new IllegalArgumentException("选择数量超限");
        List<BlindBoxService.Selection> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(new BlindBoxService.Selection(buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(64)));
        return new CommitPackingPacket(id, session, result);
    }

    public static void handle(CommitPackingPacket packet, java.util.function.Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> commit(context.getSender(), packet));
        context.setPacketHandled(true);
    }

    /** 与真实网络处理共用业务入口；消费提交后即结束会话，失败可重开而旧包不能重放。 */
    public static boolean commit(ServerPlayer player, CommitPackingPacket packet) {
        if (!authorizeAndConsume(player, packet)) return false;
        try {
            return BlindBoxService.pack(player, packet.selections());
        } catch (RuntimeException exception) {
            LOGGER.error("打包提交失败，已结束本次菜单会话", exception);
            player.displayClientMessage(Component.translatable("screen.blindboxchallenge.invalid_selection"), true);
            return false;
        } finally {
            player.closeContainer();
        }
    }

    /** 只检查菜单实例与会话形状，不消费会话；供负例探针检查使用。 */
    public static boolean isAuthorized(ServerPlayer player, CommitPackingPacket packet) {
        return player != null && player.containerMenu instanceof PackingMenu
                && player.containerMenu.containerId == packet.containerId()
                && player.containerMenu.stillValid(player)
                && ((PackingMenu) player.containerMenu).sessionId().equals(packet.sessionId())
                && !((PackingMenu) player.containerMenu).submissionConsumed();
    }

    /** 服务端主线程原子消费一次性提交权，合法但业务失败的请求也不能原会话重放。 */
    public static boolean authorizeAndConsume(ServerPlayer player, CommitPackingPacket packet) {
        if (!isAuthorized(player, packet)) return false;
        return ((PackingMenu) player.containerMenu).consumeSubmission();
    }
}
