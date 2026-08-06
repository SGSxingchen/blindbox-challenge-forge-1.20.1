package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import javax.net.ssl.HttpsURLConnection;
import net.minecraft.client.Minecraft;

/** 纯客户端 HTTPS 下载与缓存。每一跳都重新解析并把已校验公网 IP 固定到 TLS 连接，避免 DNS 重绑定。 */
public final class RemoteAudioDownload {
    public static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();
    private static final int MAX_REDIRECTS = 3;

    private RemoteAudioDownload() {}

    public static CachedAudio fetch(String requestedUrl) throws IOException {
        String normalized = AudioUrlPolicy.normalizeHttpsUrl(requestedUrl);
        Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("blindboxchallenge-audio-cache");
        Files.createDirectories(cache);
        String urlHash = sha256(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CachedAudio cached = findCached(cache, urlHash);
        if (cached != null) return cached;

        URI current = URI.create(normalized);
        long started = System.nanoTime();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            current = URI.create(AudioUrlPolicy.normalizeHttpsUrl(current.toString()));
            HttpsURLConnection connection = openPinned(current);
            try {
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    if (redirects == MAX_REDIRECTS) throw new IOException("在线音频重定向超过上限");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) throw new IOException("重定向缺少 Location");
                    current = current.resolve(location);
                    continue;
                }
                if (status != HttpsURLConnection.HTTP_OK) throw new IOException("在线音频 HTTP 状态不允许：" + status);
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("不允许压缩传输编码");
                }
                String contentType = connection.getContentType();
                if (!isAudioContentType(contentType)) throw new IOException("响应 Content-Type 不是 OGG 或 MP3");
                return saveResponse(connection.getInputStream(), cache, urlHash, started);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("在线音频重定向流程异常");
    }

    private static HttpsURLConnection openPinned(URI uri) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !AudioUrlPolicy.isPublicAddress(address))) {
            throw new IOException("在线音频域名解析到非公网地址");
        }
        URL url = uri.toURL();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(new PinnedTlsSocketFactory(uri.getHost(), addresses));
        connection.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "audio/ogg,audio/mpeg");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "BlindBoxChallenge/1.20.1");
        connection.setRequestProperty("Cookie", "");
        connection.setRequestProperty("Authorization", "");
        return connection;
    }

    private static CachedAudio saveResponse(InputStream input, Path cache, String urlHash, long started) throws IOException {
        Path temporary = Files.createTempFile(cache, urlHash + "-", ".part");
        MessageDigest digest = digest();
        byte[] first = new byte[10];
        int firstLength = 0;
        int total = 0;
        try (input; OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (System.nanoTime() - started > Duration.ofSeconds(10).toNanos()) throw new IOException("在线音频总下载超时");
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) throw new IOException("在线音频超过 16 MiB 上限");
                if (firstLength < first.length) {
                    int copied = Math.min(read, first.length - firstLength);
                    System.arraycopy(buffer, 0, first, firstLength, copied);
                    firstLength += copied;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            if (exception instanceof IOException io) throw io;
            throw new IOException("在线音频下载失败", exception);
        }
        Kind kind = detect(first, firstLength);
        if (kind == null) {
            Files.deleteIfExists(temporary);
            throw new IOException("在线音频文件头不是 OGG 或 MP3");
        }
        String contentHash = hex(digest.digest());
        Path target = cache.resolve(urlHash + "-" + contentHash + kind.extension);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        trimCache(cache);
        return new CachedAudio(target, kind, contentHash);
    }

    private static CachedAudio findCached(Path cache, String urlHash) throws IOException {
        try (var files = Files.list(cache)) {
            Path cached = files.filter(path -> path.getFileName().toString().startsWith(urlHash + "-")
                            && (path.toString().endsWith(".ogg") || path.toString().endsWith(".mp3")))
                    .findFirst().orElse(null);
            if (cached == null || Files.size(cached) <= 0 || Files.size(cached) > MAX_DOWNLOAD_BYTES) return null;
            byte[] first = new byte[10];
            int length;
            try (InputStream input = Files.newInputStream(cached)) { length = Math.max(0, input.read(first)); }
            Kind kind = detect(first, length);
            if (kind == null) {
                Files.deleteIfExists(cached);
                return null;
            }
            Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            String name = cached.getFileName().toString();
            return new CachedAudio(cached, kind, name.substring(urlHash.length() + 1, name.length() - kind.extension.length()));
        }
    }

    private static void trimCache(Path cache) throws IOException {
        try (var files = Files.list(cache)) {
            var entries = files.filter(path -> path.toString().endsWith(".ogg") || path.toString().endsWith(".mp3"))
                    .sorted(Comparator.comparingLong(RemoteAudioDownload::lastModified)).toList();
            long total = 0L;
            for (Path entry : entries) total += Files.size(entry);
            for (Path entry : entries) {
                if (total <= MAX_CACHE_BYTES) break;
                long size = Files.size(entry);
                Files.deleteIfExists(entry);
                total -= size;
            }
        }
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    private static boolean isRedirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static boolean isAudioContentType(String type) {
        if (type == null) return false;
        String base = type.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return base.equals("audio/ogg") || base.equals("application/ogg") || base.equals("audio/mpeg") || base.equals("audio/mp3");
    }
    private static Kind detect(byte[] first, int length) {
        if (length >= 4 && first[0] == 'O' && first[1] == 'g' && first[2] == 'g' && first[3] == 'S') return Kind.OGG;
        if (length >= 3 && first[0] == 'I' && first[1] == 'D' && first[2] == '3') return Kind.MP3;
        return length >= 2 && (first[0] & 0xff) == 0xff && (first[1] & 0xe0) == 0xe0 ? Kind.MP3 : null;
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
    private static String sha256(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }

    public enum Kind { OGG(".ogg"), MP3(".mp3"); private final String extension; Kind(String extension) { this.extension = extension; } }
    public record CachedAudio(Path path, Kind kind, String contentHash) {}
}
