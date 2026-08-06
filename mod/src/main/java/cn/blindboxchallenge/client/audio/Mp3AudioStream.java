package cn.blindboxchallenge.client.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** JLayer 的纯客户端 MP3→16-bit little-endian PCM 适配器；构造时已在客户端工作线程完成完整解码。 */
final class Mp3AudioStream extends BufferedAudioStream {
    Mp3AudioStream(InputStream input) throws IOException { this(decode(input)); }

    private Mp3AudioStream(Decoded decoded) throws IOException { super(decoded.format(), decoded.pcm()); }

    private static Decoded decode(InputStream input) throws IOException {
        Bitstream bitstream = new Bitstream(input);
        try {
            Decoder decoder = new Decoder();
            AudioFormat format = null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Header header; (header = bitstream.readFrame()) != null;) {
                try {
                    SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    AudioFormat frameFormat = new AudioFormat(samples.getSampleFrequency(), 16, samples.getChannelCount(), true, false);
                    if (format == null) format = frameFormat;
                    else if (format.getSampleRate() != frameFormat.getSampleRate() || format.getChannels() != frameFormat.getChannels()) {
                        throw new IOException("MP3 在播放中改变采样参数");
                    }
                    short[] source = samples.getBuffer();
                    int length = samples.getBufferLength();
                    if ((long) output.size() + (long) length * Short.BYTES > MAX_BUFFERED_PCM_BYTES) {
                        throw new IOException("MP3 解码 PCM 超过 128 MiB 上限");
                    }
                    for (int index = 0; index < length; index++) {
                        short sample = source[index];
                        output.write(sample & 0xff);
                        output.write((sample >>> 8) & 0xff);
                    }
                } finally {
                    bitstream.closeFrame();
                }
            }
            if (format == null) throw new IOException("MP3 不含可解码音频帧");
            return new Decoded(format, output.toByteArray());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("JLayer 无法解码 MP3 帧", exception);
        } finally {
            try { bitstream.close(); }
            catch (Exception exception) { throw new IOException("关闭 MP3 流失败", exception); }
        }
    }

    private record Decoded(AudioFormat format, byte[] pcm) {}
}
