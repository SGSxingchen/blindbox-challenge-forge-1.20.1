package cn.blindboxchallenge.citest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅在 CI 客户端启用：要求标题界面连续稳定 20 tick，再写出机器标志并正常退出。 */
public final class CiClientSmokeEvents {
    private static int stableTitleTicks;
    private static boolean completed;

    private CiClientSmokeEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(CiClientSmokeEvents.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || completed || !Boolean.getBoolean("blindbox.ci.clientSmoke")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
            stableTitleTicks++;
        } else {
            stableTitleTicks = 0;
        }
        if (stableTitleTicks < 20) {
            return;
        }
        String markerValue = System.getProperty("blindbox.ci.clientMarker");
        if (markerValue == null || markerValue.isBlank()) {
            throw new IllegalStateException("缺少 blindbox.ci.clientMarker");
        }
        Path marker = Path.of(markerValue).toAbsolutePath();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "title-screen-stable-20-ticks\n");
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入客户端 CI 标志：" + marker, exception);
        }
        completed = true;
        minecraft.stop();
    }
}
