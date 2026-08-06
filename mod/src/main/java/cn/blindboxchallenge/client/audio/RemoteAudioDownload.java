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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HttpsURLConnection;
import net.minecraft.client.Minecraft;

/** 纯客户端 HTTPS 下载与缓存。每一跳都重新解析并把已校验公网 IP 固定到 TLS 连接，避免 DNS 重绑定。 */
public final class RemoteAudioDownload {
    public static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();
    private static final int MAX_REDIRECTS = 3;
    private static final long STALE_PART_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final ConcurrentHashMap<String, CompletableFuture<CachedAudio>> IN_FLIGHT = new ConcurrentHashMap<>();
    /** 原生 DNS 无可用连接超时参数；单个 daemon 解析器超时后失效关闭，避免任意 URL 堆积请求线程。 */
    private static final ExecutorService DNS_RESOLVER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-audio-dns");
        thread.setDaemon(true);
        return thread;
    });

    private RemoteAudioDownload() {}

    public static CachedAudio fetch(String requestedUrl) throws IOException {
        String normalized = AudioUrlPolicy.normalizeHttpsUrl(requestedUrl);
        Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("blindboxchallenge-audio-cache");
        Files.createDirectories(cache);
        String urlHash = sha256(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CachedAudio cached = findCached(cache, urlHash);
        if (cached != null) return cached;

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        CompletableFuture<CachedAudio> ownDownload = new CompletableFuture<>();
        CompletableFuture<CachedAudio> activeDownload = IN_FLIGHT.putIfAbsent(urlHash, ownDownload);
        if (activeDownload != null) return waitForDownload(activeDownload, deadlineNanos);
        try {
            cleanupStaleParts(cache);
            // 进入单飞区后再次复检，避免刚完成的同 URL 下载仍被当成未命中。
            cached = findCached(cache, urlHash);
            CachedAudio result = cached != null ? cached : fetchUncached(normalized, cache, urlHash, deadlineNanos);
            ownDownload.complete(result);
            return result;
        } catch (IOException exception) {
            ownDownload.completeExceptionally(exception);
            throw exception;
        } catch (RuntimeException exception) {
            ownDownload.completeExceptionally(exception);
            throw exception;
        } finally {
            IN_FLIGHT.remove(urlHash, ownDownload);
        }
    }

    private static CachedAudio fetchUncached(String normalized, Path cache, String urlHash, long deadlineNanos) throws IOException {
        URI current = URI.create(normalized);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            ensureBeforeDeadline(deadlineNanos);
            current = URI.create(AudioUrlPolicy.normalizeHttpsUrl(current.toString()));
            HttpsURLConnection connection = openPinned(current, deadlineNanos);
            try {
                int status = connection.getResponseCode();
                ensureBeforeDeadline(deadlineNanos);
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
                connection.setReadTimeout(remainingMillis(deadlineNanos));
                return saveResponse(connection.getInputStream(), cache, urlHash, deadlineNanos);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("在线音频重定向流程异常");
    }

    private static CachedAudio waitForDownload(CompletableFuture<CachedAudio> activeDownload, long deadlineNanos) throws IOException {
        try {
            return activeDownload.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("等待同一在线音频下载时被中断", exception);
        } catch (TimeoutException exception) {
            throw new IOException("等待同一在线音频下载超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("同一在线音频下载失败", cause);
        }
    }

    private static HttpsURLConnection openPinned(URI uri, long deadlineNanos) throws IOException {
        InetAddress[] addresses = resolvePublicAddresses(uri.getHost(), deadlineNanos);
        URL url = uri.toURL();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(new PinnedTlsSocketFactory(uri.getHost(), addresses, deadlineNanos));
        connection.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(remainingMillis(deadlineNanos));
        connection.setReadTimeout(remainingMillis(deadlineNanos));
        connection.setRequestProperty("Accept", "audio/ogg,audio/mpeg");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "BlindBoxChallenge/1.20.1");
        connection.setRequestProperty("Cookie", "");
        connection.setRequestProperty("Authorization", "");
        return connection;
    }

    private static InetAddress[] resolvePublicAddresses(String host, long deadlineNanos) throws IOException {
        Future<InetAddress[]> resolution = DNS_RESOLVER.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] addresses = resolution.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !AudioUrlPolicy.isPublicAddress(address))) {
                throw new IOException("在线音频域名解析到非公网地址");
            }
            return addresses;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("在线音频 DNS 解析被中断", exception);
        } catch (TimeoutException exception) {
            resolution.cancel(true);
            throw new IOException("在线音频 DNS 解析超时", exception);
        } catch (ExecutionException exception) {
            throw new IOException("在线音频 DNS 解析失败", exception.getCause());
        }
    }

    private static CachedAudio saveResponse(InputStream input, Path cache, String urlHash, long deadlineNanos) throws IOException {
        Path temporary = Files.createTempFile(cache, urlHash + "-", ".part");
        MessageDigest digest = digest();
        byte[] first = new byte[10];
        int firstLength = 0;
        int total = 0;
        try (input; OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                ensureBeforeDeadline(deadlineNanos);
                if (read == 0) continue;
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
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        } catch (RuntimeException exception) {
            Files.deleteIfExists(temporary);
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
            moveNewFile(temporary, target);
        } catch (java.nio.file.FileAlreadyExistsException exception) {
            Files.deleteIfExists(temporary);
            CachedAudio existing = findCached(cache, urlHash);
            if (existing != null) return existing;
            throw exception;
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        trimCache(cache);
        return new CachedAudio(target, kind, contentHash);
    }

    private static void moveNewFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }

    private static CachedAudio findCached(Path cache, String urlHash) throws IOException {
        try (var files = Files.list(cache)) {
            for (Path cached : files.filter(path -> isCacheFileName(path.getFileName().toString(), urlHash))
                    .sorted(Comparator.comparingLong(RemoteAudioDownload::lastModified).reversed()).toList()) {
                CachedAudio verified = verifyCached(cache, cached, urlHash);
                if (verified != null) return verified;
            }
        }
        return null;
    }

    private static CachedAudio verifyCached(Path cache, Path cached, String urlHash) throws IOException {
        if (!Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cached)) {
            Files.deleteIfExists(cached);
            return null;
        }
        long expectedSize = Files.size(cached);
        if (expectedSize <= 0 || expectedSize > MAX_DOWNLOAD_BYTES) {
            Files.deleteIfExists(cached);
            return null;
        }
        byte[] first = new byte[10];
        int firstLength = 0;
        int total = 0;
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(cached)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) break;
                if (firstLength < first.length) {
                    int copied = Math.min(read, first.length - firstLength);
                    System.arraycopy(buffer, 0, first, firstLength, copied);
                    firstLength += copied;
                }
                digest.update(buffer, 0, read);
            }
        }
        Kind kind = detect(first, firstLength);
        String name = cached.getFileName().toString();
        String namedHash = name.substring(urlHash.length() + 1, name.length() - kindExtensionLength(name));
        if (total != expectedSize || total > MAX_DOWNLOAD_BYTES || kind == null || !name.endsWith(kind.extension)
                || !namedHash.equals(hex(digest.digest()))) {
            Files.deleteIfExists(cached);
            return null;
        }
        Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        return new CachedAudio(cached, kind, namedHash);
    }

    private static boolean isCacheFileName(String name, String urlHash) {
        String extension = name.endsWith(Kind.OGG.extension) ? Kind.OGG.extension : name.endsWith(Kind.MP3.extension) ? Kind.MP3.extension : null;
        if (extension == null || !name.startsWith(urlHash + "-") || name.length() != urlHash.length() + 1 + 64 + extension.length()) return false;
        String contentHash = name.substring(urlHash.length() + 1, name.length() - extension.length());
        return contentHash.matches("[0-9a-f]{64}");
    }

    private static int kindExtensionLength(String name) { return name.endsWith(Kind.OGG.extension) ? Kind.OGG.extension.length() : Kind.MP3.extension.length(); }

    private static void trimCache(Path cache) throws IOException {
        cleanupStaleParts(cache);
        try (var files = Files.list(cache)) {
            var entries = files.filter(path -> path.toString().endsWith(Kind.OGG.extension) || path.toString().endsWith(Kind.MP3.extension))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
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

    private static void cleanupStaleParts(Path cache) throws IOException {
        long cutoff = System.currentTimeMillis() - STALE_PART_MILLIS;
        try (var files = Files.list(cache)) {
            for (Path part : files.filter(path -> path.getFileName().toString().endsWith(".part")).toList()) {
                if (Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS) && lastModified(part) < cutoff) Files.deleteIfExists(part);
            }
        }
    }

    private static void ensureBeforeDeadline(long deadlineNanos) throws IOException { remainingMillis(deadlineNanos); }
    private static int remainingMillis(long deadlineNanos) throws IOException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) throw new IOException("在线音频总下载超时");
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis + 1L));
    }
    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
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
