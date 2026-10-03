package cn.blindboxchallenge.citest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.AbstractNioChannel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import java.nio.channels.SelectionKey;

/** 仅用于隔离首连诊断：原样透传字节，不保存报文内容，不修改握手和超时。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiLoginTransportObservation extends ChannelDuplexHandler {
    private static final String HANDLER_NAME = "blindbox_ci_login_transport";
    private static final AttributeKey<Boolean> INSTALLED = AttributeKey.valueOf(HANDLER_NAME);
    private final Connection connection;
    private final String side;
    private final long installedAtMillis = System.currentTimeMillis();
    private long readBytes;
    private long submittedBytes;
    private long writtenBytes;
    private long unobservableWriteBytes;
    private boolean firstReadLogged;
    private boolean firstWriteLogged;
    private boolean writeFailureLogged;
    private boolean finished;
    private ScheduledFuture<?> deadline;
    private ScheduledFuture<?> sample;

    private CiLoginTransportObservation(Connection connection, String side) {
        this.connection = connection;
        this.side = side;
    }

    @SubscribeEvent
    public static void onNegotiation(PlayerNegotiationEvent event) {
        observe(event.getConnection(), "server:" + event.getProfile().getName());
    }

    public static void observe(Connection connection, String side) {
        if (!Boolean.getBoolean("blindbox.ci.connectionDiagnostics")) return;
        Channel channel = connection.channel();
        if (channel == null || !channel.isActive() || !channel.attr(INSTALLED).compareAndSet(null, true)) return;
        try {
            channel.eventLoop().execute(() -> {
                if (!channel.isActive()) return;
                try {
                    // 服务端协商事件先于第一条登录消息；此任务与随后的发送任务在同一事件循环排队。
                    // 客户端日志明确记录实际安装时刻，不把安装前的数据误报为“从未收到”。
                    CiLoginTransportObservation observer = new CiLoginTransportObservation(connection, side);
                    channel.pipeline().addFirst(HANDLER_NAME, observer);
                    observer.deadline = channel.eventLoop().schedule(() -> observer.finish("45秒诊断上限"), 45, TimeUnit.SECONDS);
                    observer.sample = channel.eventLoop().scheduleAtFixedRate(() -> {
                        if (!observer.finishIfPlaying()) {
                            CiTestProbe.LOGGER.info("首连读取等待状态：read_bytes={}，{}，{}", observer.readBytes, observer.state(), observer.readInterest());
                        }
                    }, 2, 5, TimeUnit.SECONDS);
                    CiTestProbe.LOGGER.info("首连传输观察安装：{}", observer.state());
                } catch (RuntimeException exception) {
                    CiTestProbe.LOGGER.warn("首连传输观察安装失败，继续原连接：{}", side, exception);
                }
            });
        } catch (RuntimeException exception) {
            CiTestProbe.LOGGER.warn("首连传输观察任务不可用，继续原连接：{}", side, exception);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (finishIfPlaying()) {
            super.channelRead(context, message);
            return;
        }
        if (message instanceof ByteBuf buffer) {
            int bytes = buffer.readableBytes();
            readBytes += bytes;
            if (!firstReadLogged && bytes > 0) {
                firstReadLogged = true;
                CiTestProbe.LOGGER.info("首连首次原始字节接收：bytes={}，{}", bytes, state());
            }
        }
        super.channelRead(context, message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (finishIfPlaying()) {
            super.write(context, message, promise);
            return;
        }
        if (message instanceof ByteBuf buffer) {
            int bytes = buffer.readableBytes();
            submittedBytes += bytes;
            // 只观察原有写入结果；成功仅代表本端写入完成，不代表对端已经收到。
            if (promise.isVoid()) unobservableWriteBytes += bytes;
            else promise.addListener(result -> {
                if (result.isSuccess()) {
                    writtenBytes += bytes;
                    if (!firstWriteLogged && bytes > 0) {
                        firstWriteLogged = true;
                        CiTestProbe.LOGGER.info("首连首次原始字节写入完成：bytes={}，{}", bytes, state());
                    }
                } else if (!writeFailureLogged) {
                    writeFailureLogged = true;
                    CiTestProbe.LOGGER.warn("首连原始字节写入失败：{}", state(), result.cause());
                }
            });
        }
        super.write(context, message, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        finish("连接关闭");
        super.channelInactive(context);
    }

    private boolean finishIfPlaying() {
        if (connection.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get() != ConnectionProtocol.PLAY) return false;
        finish("进入游戏协议");
        return true;
    }

    private void finish(String reason) {
        if (finished) return;
        finished = true;
        if (deadline != null) deadline.cancel(false);
        if (sample != null) sample.cancel(false);
        CiTestProbe.LOGGER.info("首连传输观察结束：reason={}，read_bytes={}，submitted_bytes={}，written_bytes={}，unobservable_write_bytes={}，{}",
                reason, readBytes, submittedBytes, writtenBytes, unobservableWriteBytes, state());
        if (connection.channel().pipeline().context(this) != null) connection.channel().pipeline().remove(this);
    }

    private String state() {
        Channel channel = connection.channel();
        return "side=" + side + ", installed_at_ms=" + installedAtMillis
                + ", local=" + channel.localAddress() + ", remote=" + channel.remoteAddress()
                + ", protocol=" + channel.attr(Connection.ATTRIBUTE_PROTOCOL).get()
                + ", active=" + channel.isActive() + ", auto_read=" + channel.config().isAutoRead();
    }

    /** 只在所属事件循环读 Netty 选择键；不调用 read 或更改兴趣位。 */
    private String readInterest() {
        Channel channel = connection.channel();
        if (!(channel instanceof AbstractNioChannel)) return "nio=false";
        try {
            Field field = AbstractNioChannel.class.getDeclaredField("selectionKey");
            field.setAccessible(true);
            SelectionKey key = (SelectionKey) field.get(channel);
            return "nio=true, key_valid=" + (key != null && key.isValid())
                    + (key != null && key.isValid() ? ", interest_ops=" + key.interestOps() + ", ready_ops=" + key.readyOps() : "");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return "nio=true, key_observation=" + exception.getClass().getSimpleName();
        }
    }
}
