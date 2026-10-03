package cn.blindboxchallenge.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 初始握手在连接自己的事件循环发送，避免跨线程开关读取留下过期的清除任务。 */
@Mixin(Connection.class)
public abstract class ClientLoginConnectionMixin {
    @Shadow
    private void sendPacket(Packet<?> packet, PacketSendListener listener) {
        throw new AssertionError("此方法由 Mixin 绑定原连接实现");
    }

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void blindbox$serializeInitialLogin(Packet<?> packet, PacketSendListener listener, CallbackInfo callback) {
        boolean loginIntention = packet instanceof ClientIntentionPacket intention
                && intention.getIntention() == ConnectionProtocol.LOGIN;
        if (!loginIntention && !(packet instanceof ServerboundHelloPacket)) return;
        Channel channel = ((Connection) (Object) this).channel();
        if (channel.eventLoop().inEventLoop()) return;
        // 仍调用原版完整发送流程。事件循环内再次进入时直接放行，不重发、不强行打开读取。
        channel.eventLoop().execute(() -> sendPacket(packet, listener));
        callback.cancel();
    }
}
