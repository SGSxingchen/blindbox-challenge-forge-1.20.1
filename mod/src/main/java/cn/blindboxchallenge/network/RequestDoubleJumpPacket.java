package cn.blindboxchallenge.network;

import cn.blindboxchallenge.service.PlayerAbilityService;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** 客户端只能请求二段跳；不携带速度、位置、次数或能力状态。 */
public final class RequestDoubleJumpPacket {
    public static void encode(RequestDoubleJumpPacket packet, FriendlyByteBuf buffer) {}
    public static RequestDoubleJumpPacket decode(FriendlyByteBuf buffer) { return new RequestDoubleJumpPacket(); }

    public static void handle(RequestDoubleJumpPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender != null) PlayerAbilityService.requestDoubleJump(sender);
        });
        context.setPacketHandled(true);
    }
}
