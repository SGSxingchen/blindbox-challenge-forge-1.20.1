package cn.blindboxchallenge.client.audio;

import com.mojang.blaze3d.audio.OggAudioStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** 通过 Forge 公开的 SoundInstance#getStream 扩展点接入原版 SoundEngine，不直接控制 OpenAL。 */
final class RemoteMusicSoundInstance extends AbstractSoundInstance {
    private final RemoteAudioDownload.CachedAudio audio;

    RemoteMusicSoundInstance(RemoteAudioDownload.CachedAudio audio, net.minecraft.core.BlockPos source) {
        super(SoundEvents.MUSIC_DISC_13, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.audio = audio;
        x = source.getX() + 0.5D;
        y = source.getY() + 0.5D;
        z = source.getZ() + 0.5D;
        volume = 1.0F;
        pitch = 1.0F;
        looping = false;
        attenuation = Attenuation.LINEAR;
        relative = false;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return audio.kind() == RemoteAudioDownload.Kind.OGG
                        ? new OggAudioStream(Files.newInputStream(audio.path()))
                        : new Mp3AudioStream(Files.newInputStream(audio.path()));
            } catch (IOException exception) {
                throw new IllegalStateException("无法从客户端缓存打开在线音频", exception);
            }
        }, ClientMusicService.AUDIO_EXECUTOR);
    }
}
