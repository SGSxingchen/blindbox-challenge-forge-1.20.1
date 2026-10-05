package cn.blindboxchallenge.service;

/** 每次播放固定的下载限制；服务端配置和客户端网络解码共用硬边界。 */
public record AudioDownloadLimits(int maxBytes, int connectTimeoutMillis, int readTimeoutMillis, int totalTimeoutMillis) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final AudioDownloadLimits DEFAULTS = new AudioDownloadLimits(MAX_BYTES, 10_000, 10_000, 60_000);

    public AudioDownloadLimits {
        if (maxBytes < 1024 * 1024 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("在线音频大小限制必须在 1 到 16 MiB 之间");
        }
        if (connectTimeoutMillis < 1000 || connectTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("在线音频连接超时必须在 1 到 60 秒之间");
        }
        if (readTimeoutMillis < 1000 || readTimeoutMillis > 60_000) {
            throw new IllegalArgumentException("在线音频读取超时必须在 1 到 60 秒之间");
        }
        if (totalTimeoutMillis < 1000 || totalTimeoutMillis > 120_000) {
            throw new IllegalArgumentException("在线音频总下载时限必须在 1 到 120 秒之间");
        }
    }
}
