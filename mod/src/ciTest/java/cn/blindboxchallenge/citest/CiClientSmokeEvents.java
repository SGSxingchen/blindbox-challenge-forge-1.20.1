package cn.blindboxchallenge.citest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅存在于 CI 探针 Jar：验证标题界面或真实多人连接。 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CiClientSmokeEvents {
    private static int stableTitleTicks;
    private static int joinedTicks;
    private static boolean connectStarted;
    private static boolean completed;
    private static boolean everJoined;
    private static boolean reconnectStarted;
    private static int reconnectJoinedTicks;
    private static int disconnectedTicks;

    private CiClientSmokeEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || completed) {
            return;
        }
        boolean titleSmoke = Boolean.getBoolean("blindbox.ci.clientSmoke");
        boolean multiplayerSmoke = Boolean.getBoolean("blindbox.ci.multiplayerSmoke");
        if (!titleSmoke && !multiplayerSmoke) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (multiplayerSmoke) {
            runMultiplayerSmoke(minecraft);
            return;
        }
        if (minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
            stableTitleTicks++;
        } else {
            stableTitleTicks = 0;
        }
        if (stableTitleTicks >= 20) {
            complete(minecraft, "title-screen-stable-20-ticks\n");
        }
    }

    private static void runMultiplayerSmoke(Minecraft minecraft) {
        if (!connectStarted && minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
            String address = System.getProperty("blindbox.ci.serverAddress", "127.0.0.1:25565");
            ServerData data = new ServerData("BlindBox CI", address, false);
            connectStarted = true;
            ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address), data, false);
            return;
        }
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            joinedTicks = 0;
            if (everJoined && Boolean.getBoolean("blindbox.ci.reconnect") && !reconnectStarted) {
                disconnectedTicks++;
                // 断线界面在不同机器/时序下可能短暂被其他 Screen 替代；以连接状态为权威，
                // 等待若干客户端 Tick 让旧连接完全清理后再发起一次重连。
                if (disconnectedTicks >= 10) {
                    String address = System.getProperty("blindbox.ci.serverAddress", "127.0.0.1:25565");
                    ServerData data = new ServerData("BlindBox CI reconnect", address, false);
                    reconnectStarted = true;
                    ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address), data, false);
                }
            }
            return;
        }
        disconnectedTicks = 0;
        joinedTicks++;
        if (!everJoined && joinedTicks == 40) {
            everJoined = true;
            writeMarker("multiplayer-connected-40-ticks\n");
        } else if (reconnectStarted && everJoined) {
            reconnectJoinedTicks++;
            if (reconnectJoinedTicks == 40) writeReconnectMarker();
        }
        String releaseValue = System.getProperty("blindbox.ci.clientRelease");
        if (joinedTicks >= 40 && releaseValue != null && Files.isRegularFile(Path.of(releaseValue).toAbsolutePath())) {
            completed = true;
            minecraft.stop();
        }
    }

    private static void writeReconnectMarker() {
        String markerValue = System.getProperty("blindbox.ci.reconnectMarker");
        if (markerValue == null || markerValue.isBlank()) throw new IllegalStateException("缺少 blindbox.ci.reconnectMarker");
        Path marker = Path.of(markerValue).toAbsolutePath();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "multiplayer-reconnected-40-ticks\n");
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入客户端重连 CI 标志：" + marker, exception);
        }
    }

    private static void complete(Minecraft minecraft, String value) {
        writeMarker(value);
        completed = true;
        minecraft.stop();
    }

    private static void writeMarker(String value) {
        String markerValue = System.getProperty("blindbox.ci.clientMarker");
        if (markerValue == null || markerValue.isBlank()) {
            throw new IllegalStateException("缺少 blindbox.ci.clientMarker");
        }
        Path marker = Path.of(markerValue).toAbsolutePath();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, value);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入客户端 CI 标志：" + marker, exception);
        }
    }
}
