package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.event.MusicBoxPlaybackFailedEvent;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
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
    /** 下载、预解码及正在播放的远程 PCM 至多两条，避免恶意全服事件让客户端积压无界任务/内存。 */
    private static final Semaphore REMOTE_AUDIO_SLOTS = new Semaphore(2);
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
        if (!REMOTE_AUDIO_SLOTS.tryAcquire()) {
            clientMessage("message.blindboxchallenge.music_box_download_failed");
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseSlot = () -> {
            if (released.compareAndSet(false, true)) REMOTE_AUDIO_SLOTS.release();
        };
        CompletableFuture.supplyAsync(() -> {
            try { return RemoteAudioDownload.fetch(normalized); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }, AUDIO_EXECUTOR).thenApplyAsync(audio -> RemoteMusicSoundInstance.prepare(audio, event.source(), event.eventId(), releaseSlot), AUDIO_EXECUTOR)
                .thenAccept(sound -> Minecraft.getInstance().execute(() -> {
            if (!isCurrentConnection(eventEpoch)) {
                sound.discard();
                return;
            }
            try {
                Minecraft.getInstance().getSoundManager().play(sound);
            } catch (RuntimeException exception) {
                sound.discard();
                clientMessage("message.blindboxchallenge.music_box_download_failed");
            }
        }))
                .exceptionally(exception -> {
                    releaseSlot.run();
                    Minecraft.getInstance().execute(() -> {
                        MinecraftForge.EVENT_BUS.post(new MusicBoxPlaybackFailedEvent(event.eventId(), normalized, event.source()));
                        clientMessage("message.blindboxchallenge.music_box_download_failed");
                    });
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
