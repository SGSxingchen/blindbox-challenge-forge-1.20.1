package cn.blindboxchallenge.client.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** 通过 Forge 公开的 SoundInstance#getStream 扩展点接入原版 SoundEngine，不直接控制 OpenAL。 */
final class RemoteMusicSoundInstance extends AbstractSoundInstance {
    private final BufferedAudioStream audio;
    private final UUID eventId;
    private final RemoteAudioDownload.Kind kind;
    private final boolean cacheHit;
    private final net.minecraft.core.BlockPos source;

    private RemoteMusicSoundInstance(BufferedAudioStream audio, net.minecraft.core.BlockPos source, UUID eventId,
                                     RemoteAudioDownload.Kind kind, boolean cacheHit) {
        super(SoundEvents.MUSIC_DISC_13, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.audio = audio;
        this.eventId = eventId;
        this.kind = kind;
        this.cacheHit = cacheHit;
        this.source = source.immutable();
        x = source.getX() + 0.5D;
        y = source.getY() + 0.5D;
        z = source.getZ() + 0.5D;
        volume = 1.0F;
        pitch = 1.0F;
        looping = false;
        attenuation = Attenuation.LINEAR;
        relative = false;
    }

    /** 下载、文件读取和 OGG/MP3 解码均在 AUDIO_EXECUTOR 完成；此后音频线程只消费 PCM 内存。 */
    static RemoteMusicSoundInstance prepare(RemoteAudioDownload.CachedAudio cached, net.minecraft.core.BlockPos source, UUID eventId,
                                            Runnable closeCallback) {
        try (var input = Files.newInputStream(cached.path())) {
            BufferedAudioStream audio = cached.kind() == RemoteAudioDownload.Kind.OGG
                    ? BufferedAudioStream.decodeOgg(input)
                    : new Mp3AudioStream(input);
            audio.setCloseCallback(closeCallback);
            return new RemoteMusicSoundInstance(audio, source, eventId, cached.kind(), cached.cacheHit());
        } catch (IOException exception) {
            throw new IllegalStateException("无法在客户端工作线程解码在线音频", exception);
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(audio);
    }

    UUID eventId() { return eventId; }
    RemoteAudioDownload.Kind kind() { return kind; }
    boolean cacheHit() { return cacheHit; }
    net.minecraft.core.BlockPos source() { return source; }
    void discard() { audio.close(); }
}
