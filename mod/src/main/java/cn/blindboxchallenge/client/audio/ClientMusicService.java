package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import cn.blindboxchallenge.event.MusicBoxPlaybackFailedEvent;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/** 完整在线音频链只在客户端类加载：异步下载/缓存/解码失败只提示本客户端，绝不阻塞服务端。 */
@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, value = Dist.CLIENT)
public final class ClientMusicService {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final ExecutorService AUDIO_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-remote-audio");
        thread.setDaemon(true);
        return thread;
    });
    /** 下载、预解码及正在播放的远程 PCM 至多两条，避免恶意全服事件让客户端积压无界任务/内存。 */
    private static final Semaphore REMOTE_AUDIO_SLOTS = new Semaphore(2);
    private static final Set<UUID> PLAYED_EVENTS = new LinkedHashSet<>();
    /** 只在客户端线程维护，数量受同一个两槽信号量约束。 */
    private static final Map<RemoteMusicSoundInstance, Playback> PLAYING = new LinkedHashMap<>();
    private static long connectionEpoch;

    private static final class Playback {
        private final AtomicBoolean released;
        private SoundInstance playingSound;
        private PlaySoundEvent playEvent;

        private Playback(RemoteMusicSoundInstance sound, AtomicBoolean released) {
            this.playingSound = sound;
            this.released = released;
        }
    }

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
        Minecraft minecraft = Minecraft.getInstance();
        if (!isCurrentConnection(eventEpoch) || !canHearRecords(minecraft)) return;
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
            Minecraft client = Minecraft.getInstance();
            if (!isCurrentConnection(eventEpoch) || !canHearRecords(client)) {
                sound.discard();
                return;
            }
            cleanupInactive(client);
            Playback playback = new Playback(sound, released);
            PLAYING.put(sound, playback);
            try {
                client.getSoundManager().play(sound);
                // 其他模组和 ciTest 可替换或取消声音。等整个事件派发结束，再读取最终的引擎入参。
                if (playback.playEvent != null) playback.playingSound = playback.playEvent.getSound();
                playback.playEvent = null;
                cleanupInactive(client);
            } catch (RuntimeException exception) {
                PLAYING.remove(sound);
                if (playback.playEvent != null) playback.playingSound = playback.playEvent.getSound();
                playback.playEvent = null;
                try {
                    if (playback.playingSound != null) client.getSoundManager().stop(playback.playingSound);
                } catch (RuntimeException stopFailure) {
                    LOGGER.warn("在线音频停止失败（仅客户端）：{}", stopFailure.getClass().getSimpleName());
                } finally {
                    sound.discard();
                }
                clientMessage("message.blindboxchallenge.music_box_download_failed");
            }
        }))
                .exceptionally(exception -> {
                    releaseSlot.run();
                    Minecraft.getInstance().execute(() -> {
                        if (!isCurrentConnection(eventEpoch)) return;
                        // 仅记录最内层异常类型，不打印 URL、Cookie、令牌、本地路径、消息或完整网络栈；这既让实际
                        // 客户端失败可诊断，也不把本地下载细节回传给服务器。
                        LOGGER.warn("在线音频下载或解码失败（仅客户端）：{}", failureSummary(exception));
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
        stopRemotePlayback(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void observeSound(PlaySoundEvent event) {
        Playback playback = PLAYING.get(event.getOriginalSound());
        if (playback != null) playback.playEvent = event;
    }

    /** 引擎未启动、停止或重载后也要回收；暂停中的活跃声音继续保留自己的 PCM 和槽位。 */
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PLAYING.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) stopRemotePlayback(minecraft);
        else cleanupInactive(minecraft);
    }

    private static void cleanupInactive(Minecraft minecraft) {
        var iterator = PLAYING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Playback playback = entry.getValue();
            if (playback.released.get() || playback.playingSound == null
                    || !minecraft.getSoundManager().isActive(playback.playingSound)) {
                iterator.remove();
                entry.getKey().discard();
            }
        }
    }

    private static void stopRemotePlayback(Minecraft minecraft) {
        for (var entry : PLAYING.entrySet()) {
            try {
                if (entry.getValue().playingSound != null) minecraft.getSoundManager().stop(entry.getValue().playingSound);
            } catch (RuntimeException exception) {
                LOGGER.warn("在线音频停止失败（仅客户端）：{}", exception.getClass().getSimpleName());
            } finally {
                entry.getKey().discard();
            }
        }
        PLAYING.clear();
    }

    private static boolean canHearRecords(Minecraft minecraft) {
        return minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F
                && minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) > 0.0F;
    }

    private static boolean isCurrentConnection(long eventEpoch) {
        synchronized (PLAYED_EVENTS) {
            return connectionEpoch == eventEpoch && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null;
        }
    }

    private static void clientMessage(String key) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.translatable(key), true);
    }

    private static String failureSummary(Throwable failure) {
        Throwable current = failure;
        RemoteAudioDownload.FailureStage stage = RemoteAudioDownload.FailureStage.UNKNOWN;
        String connectionAttempts = "";
        Throwable root = failure;
        while (current != null) {
            if (current instanceof RemoteAudioDownload.AudioFailureException staged) {
                stage = staged.stage();
                if (!staged.connectionAttemptSummary().isEmpty()) connectionAttempts = staged.connectionAttemptSummary();
            }
            root = current;
            if (current.getCause() == null || current.getCause() == current) break;
            current = current.getCause();
        }
        return stage + "/" + root.getClass().getSimpleName()
                + (connectionAttempts.isEmpty() ? "" : "/attempts=" + connectionAttempts);
    }
}
