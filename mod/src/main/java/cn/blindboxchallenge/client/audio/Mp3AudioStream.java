package cn.blindboxchallenge.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** JLayer 的纯客户端 MP3→16-bit little-endian PCM 适配器；不调用系统播放器或外部编解码器。 */
final class Mp3AudioStream implements net.minecraft.client.sounds.AudioStream {
    private static final long MAX_DECODED_BYTES = 128L * 1024L * 1024L;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private AudioFormat format;
    private ByteBuffer pending;
    private long decodedBytes;

    Mp3AudioStream(InputStream input) throws IOException {
        bitstream = new Bitstream(input);
        pending = decodeFrame();
        if (pending == null || format == null) throw new IOException("MP3 不含可解码音频帧");
    }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        int requested = Math.max(1, requestedBytes);
        ByteBuffer output = ByteBuffer.allocateDirect(requested).order(ByteOrder.LITTLE_ENDIAN);
        while (output.hasRemaining()) {
            if (pending == null || !pending.hasRemaining()) {
                pending = decodeFrame();
                if (pending == null) break;
            }
            int copied = Math.min(output.remaining(), pending.remaining());
            ByteBuffer slice = pending.duplicate();
            slice.limit(slice.position() + copied);
            output.put(slice);
            pending.position(pending.position() + copied);
        }
        output.flip();
        decodedBytes += output.remaining();
        if (decodedBytes > MAX_DECODED_BYTES) throw new IOException("MP3 解码 PCM 超过 128 MiB 上限");
        return output;
    }

    private ByteBuffer decodeFrame() throws IOException {
        try {
            Header header = bitstream.readFrame();
            if (header == null) return null;
            try {
                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (format == null) format = new AudioFormat(samples.getSampleFrequency(), 16, samples.getChannelCount(), true, false);
                short[] source = samples.getBuffer();
                int length = samples.getBufferLength();
                ByteBuffer pcm = ByteBuffer.allocateDirect(length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int index = 0; index < length; index++) pcm.putShort(source[index]);
                pcm.flip();
                return pcm;
            } finally {
                bitstream.closeFrame();
            }
        } catch (Exception exception) {
            throw new IOException("JLayer 无法解码 MP3 帧", exception);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (Exception exception) {
            throw new IOException("关闭 MP3 流失败", exception);
        }
    }
}
