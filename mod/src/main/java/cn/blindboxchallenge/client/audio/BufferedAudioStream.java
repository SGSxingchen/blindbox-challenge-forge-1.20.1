package cn.blindboxchallenge.client.audio;

import com.mojang.blaze3d.audio.OggAudioStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** 已在客户端工作线程完整解码的 PCM 流；原版音频线程只复制内存缓冲，不再做网络、文件或编解码工作。 */
class BufferedAudioStream implements AudioStream {
    static final long MAX_BUFFERED_PCM_BYTES = 128L * 1024L * 1024L;
    private final AudioFormat format;
    private ByteBuffer remaining;
    private boolean closed;
    private Runnable closeCallback = () -> {};

    BufferedAudioStream(AudioFormat format, byte[] pcm) throws IOException {
        if (pcm.length > MAX_BUFFERED_PCM_BYTES) throw new IOException("在线音频解码 PCM 超过 128 MiB 上限");
        this.format = format;
        remaining = ByteBuffer.allocateDirect(pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        remaining.put(pcm);
        remaining.flip();
    }

    static BufferedAudioStream decodeOgg(InputStream input) throws IOException {
        try (OggAudioStream decoder = new OggAudioStream(input)) {
            return buffer(decoder);
        }
    }

    static BufferedAudioStream buffer(AudioStream decoder) throws IOException {
        AudioFormat format = decoder.getFormat();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            ByteBuffer frame = decoder.read(8192);
            if (frame == null || !frame.hasRemaining()) break;
            if ((long) output.size() + frame.remaining() > MAX_BUFFERED_PCM_BYTES) {
                throw new IOException("在线音频解码 PCM 超过 128 MiB 上限");
            }
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            output.write(bytes, 0, bytes.length);
        }
        return new BufferedAudioStream(format, output.toByteArray());
    }

    void setCloseCallback(Runnable closeCallback) { this.closeCallback = closeCallback; }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public ByteBuffer read(int requestedBytes) {
        if (closed || !remaining.hasRemaining()) return ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN);
        int length = Math.min(Math.max(1, requestedBytes), remaining.remaining());
        ByteBuffer output = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer slice = remaining.duplicate();
        slice.limit(slice.position() + length);
        output.put(slice);
        remaining.position(remaining.position() + length);
        output.flip();
        return output;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        remaining = ByteBuffer.allocateDirect(0);
        closeCallback.run();
    }
}
