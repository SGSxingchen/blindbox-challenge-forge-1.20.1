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

    private ClientMusicService() {}

    @SubscribeEvent
    public static void play(MusicBoxPlaybackEvent event) {
        synchronized (PLAYED_EVENTS) {
            if (!PLAYED_EVENTS.add(event.eventId())) return;
            if (PLAYED_EVENTS.size() > 256) PLAYED_EVENTS.remove(PLAYED_EVENTS.iterator().next());
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
        }, AUDIO_EXECUTOR).thenAccept(audio -> Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().getSoundManager().play(new RemoteMusicSoundInstance(audio, event.source()))))
                .exceptionally(exception -> {
                    Minecraft.getInstance().execute(() -> clientMessage("message.blindboxchallenge.music_box_download_failed"));
                    return null;
                });
    }

    private static void clientMessage(String key) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.translatable(key), true);
    }
}
