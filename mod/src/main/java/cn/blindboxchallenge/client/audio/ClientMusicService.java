package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 完整在线音频链只在客户端类加载：异步下载/缓存/解码失败只提示本客户端，绝不阻塞服务端。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, value = Dist.CLIENT)
public final class ClientMusicService {
    static final ExecutorService AUDIO_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-remote-audio");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<UUID> PLAYED_EVENTS = new LinkedHashSet<>();
    private static long connectionEpoch;

    private ClientMusicService() {}

    @SubscribeEvent
    public static void play(MusicBoxPlaybackEvent event) {
        final long eventEpoch;
        synchronized (PLAYED_EVENTS) {
            if (!PLAYED_EVENTS.add(event.eventId())) return;
            if (PLAYED_EVENTS.size() > 256) PLAYED_EVENTS.remove(PLAYED_EVENTS.iterator().next());
            eventEpoch = connectionEpoch;
        }
        final String normalized;
        try {
            normalized = AudioUrlPolicy.normalizeHttpsUrl(event.url());
        } catch (IllegalArgumentException exception) {
            clientMessage("message.blindboxchallenge.music_box_download_failed");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try { return RemoteAudioDownload.fetch(normalized); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }, AUDIO_EXECUTOR).thenApplyAsync(audio -> RemoteMusicSoundInstance.prepare(audio, event.source()), AUDIO_EXECUTOR)
                .thenAccept(sound -> Minecraft.getInstance().execute(() -> {
            if (!isCurrentConnection(eventEpoch)) return;
            Minecraft.getInstance().getSoundManager().play(sound);
        }))
                .exceptionally(exception -> {
                    Minecraft.getInstance().execute(() -> clientMessage("message.blindboxchallenge.music_box_download_failed"));
                    return null;
                });
    }

    /** 断线或换服后旧下载只能自行结束，绝不能把前一服务器的音频带入主菜单或新世界。 */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        synchronized (PLAYED_EVENTS) {
            connectionEpoch++;
            PLAYED_EVENTS.clear();
        }
    }

    private static boolean isCurrentConnection(long eventEpoch) {
        synchronized (PLAYED_EVENTS) {
            return connectionEpoch == eventEpoch && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null;
        }
    }

    private static void clientMessage(String key) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.translatable(key), true);
    }
}
