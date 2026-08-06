package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.Socket;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.minecraft.client.Minecraft;

/** 纯客户端 HTTPS 下载与缓存。每一跳都重新解析并把已校验公网 IP 固定到 TLS 连接，避免 DNS 重绑定。 */
public final class RemoteAudioDownload {
    public static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final long STALE_PART_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final ConcurrentHashMap<String, CompletableFuture<CachedAudio>> IN_FLIGHT = new ConcurrentHashMap<>();
    /** 原生 DNS 无可用连接超时参数；单个 daemon 解析器超时后失效关闭，避免任意 URL 堆积请求线程。 */
    private static final ExecutorService DNS_RESOLVER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-audio-dns");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService DEADLINE_ENFORCER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-audio-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private static final SSLSocketFactory TLS_FACTORY = (SSLSocketFactory) SSLSocketFactory.getDefault();

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
            try (DownloadResponse response = openPinned(current, deadlineNanos)) {
                int status = response.status();
                ensureBeforeDeadline(deadlineNanos);
                if (isRedirect(status)) {
                    if (redirects == MAX_REDIRECTS) throw new IOException("在线音频重定向超过上限");
                    String location = response.header("location");
                    if (location == null || location.isBlank()) throw new IOException("重定向缺少 Location");
                    current = current.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("在线音频 HTTP 状态不允许：" + status);
                String encoding = response.header("content-encoding");
                if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("不允许压缩传输编码");
                }
                String contentType = response.header("content-type");
                if (!isAudioContentType(contentType)) throw new IOException("响应 Content-Type 不是 OGG 或 MP3");
                String contentLength = response.header("content-length");
                if (contentLength != null && !contentLength.isBlank()) {
                    try {
                        long declaredLength = Long.parseLong(contentLength.trim());
                        if (declaredLength < 0L) throw new IOException("在线音频 Content-Length 非法");
                        if (declaredLength > MAX_DOWNLOAD_BYTES) throw new IOException("在线音频超过 16 MiB 上限");
                    } catch (NumberFormatException exception) {
                        throw new IOException("在线音频 Content-Length 非法", exception);
                    }
                }
                return saveResponse(response.body(), cache, urlHash, deadlineNanos);
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

    /**
     * 不使用 HttpsURLConnection：其无参 SocketFactory 回退会重新按域名建连，且全局 CookieHandler/
     * Authenticator 可悄然插入身份头。这里直接向已验证 IP 的 TLS socket 写固定 GET，因而既无
     * DNS 重绑定窗口，也绝不继承客户端其它网页会话的 Cookie、认证或代理配置。
     */
    private static DownloadResponse openPinned(URI uri, long deadlineNanos) throws IOException {
        InetAddress[] addresses = resolvePublicAddresses(uri.getHost(), deadlineNanos);
        IOException failure = null;
        for (InetAddress address : addresses) {
            try {
                return openPinnedAtAddress(uri, address, deadlineNanos);
            } catch (IOException exception) {
                failure = exception;
                ensureBeforeDeadline(deadlineNanos);
            }
        }
        throw new IOException("在线音频所有已验证公网地址均无法连接", failure);
    }

    private static DownloadResponse openPinnedAtAddress(URI uri, InetAddress address, long deadlineNanos) throws IOException {
        Socket plain = new Socket(Proxy.NO_PROXY);
        SSLSocket tls = null;
        ScheduledFuture<?> closeAtDeadline = null;
        try {
            plain.connect(new InetSocketAddress(address, 443), remainingMillis(deadlineNanos));
            plain.setSoTimeout(remainingMillis(deadlineNanos));
            tls = (SSLSocket) TLS_FACTORY.createSocket(plain, uri.getHost(), 443, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(java.util.List.of(new SNIHostName(uri.getHost())));
            tls.setSSLParameters(parameters);
            long closeDelay = remainingNanos(deadlineNanos);
            SSLSocket pinnedSocket = tls;
            closeAtDeadline = DEADLINE_ENFORCER.schedule(() -> closeQuietly(pinnedSocket), closeDelay, TimeUnit.NANOSECONDS);
            tls.setSoTimeout(remainingMillis(deadlineNanos));
            tls.startHandshake();
            ensureBeforeDeadline(deadlineNanos);
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.getHost(), tls.getSession())) {
                throw new IOException("在线音频 TLS 证书主机名不匹配");
            }
            writeRequest(tls.getOutputStream(), uri);
            InputStream input = tls.getInputStream();
            int status = readStatus(input, deadlineNanos);
            Map<String, String> headers = readHeaders(input, deadlineNanos);
            String transfer = headers.get("transfer-encoding");
            if (transfer != null && !transfer.isBlank() && !"chunked".equalsIgnoreCase(transfer.trim())) {
                throw new IOException("在线音频不允许 Transfer-Encoding：" + transfer);
            }
            InputStream body = "chunked".equalsIgnoreCase(transfer == null ? "" : transfer.trim())
                    ? new ChunkedInputStream(input, deadlineNanos) : input;
            return new DownloadResponse(status, headers, body, tls, closeAtDeadline);
        } catch (IOException | RuntimeException exception) {
            if (closeAtDeadline != null) closeAtDeadline.cancel(false);
            if (tls != null) closeQuietly(tls);
            else closeQuietly(plain);
            throw exception;
        }
    }

    private static void writeRequest(OutputStream output, URI uri) throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + "\r\n"
                + "Accept: audio/ogg,audio/mpeg\r\n"
                + "Accept-Encoding: identity\r\n"
                + "User-Agent: BlindBoxChallenge/1.20.1\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private static int readStatus(InputStream input, long deadlineNanos) throws IOException {
        String line = readHttpLine(input, deadlineNanos, MAX_HEADER_BYTES);
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("在线音频 HTTP 状态行非法");
        try { return Integer.parseInt(parts[1]); }
        catch (NumberFormatException exception) { throw new IOException("在线音频 HTTP 状态码非法", exception); }
    }

    private static Map<String, String> readHeaders(InputStream input, long deadlineNanos) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int total = 0;
        for (int lines = 0; lines < 100; lines++) {
            String line = readHttpLine(input, deadlineNanos, MAX_HEADER_BYTES - total);
            total += line.length() + 2;
            if (line.isEmpty()) return headers;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("在线音频 HTTP 响应头非法");
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (headers.putIfAbsent(name, value) != null) throw new IOException("在线音频响应头重复：" + name);
        }
        throw new IOException("在线音频响应头过多");
    }

    private static String readHttpLine(InputStream input, long deadlineNanos, int limit) throws IOException {
        if (limit <= 0) throw new IOException("在线音频响应头超过上限");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (;;) {
            ensureBeforeDeadline(deadlineNanos);
            int value = input.read();
            if (value < 0) throw new IOException("在线音频 HTTP 响应意外结束");
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > limit) throw new IOException("在线音频响应头超过上限");
        }
        return bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
    }

    private record DownloadResponse(int status, Map<String, String> headers, InputStream body, SSLSocket socket,
                                    ScheduledFuture<?> closeAtDeadline) implements AutoCloseable {
        String header(String name) { return headers.get(name); }

        @Override
        public void close() {
            closeAtDeadline.cancel(false);
            closeQuietly(socket);
        }
    }

    /** 仅接受标准 HTTP/1.1 分块传输；分块大小仍由 saveResponse 的 16 MiB 总量限制。 */
    private static final class ChunkedInputStream extends InputStream {
        private final InputStream input;
        private final long deadlineNanos;
        private long remaining;
        private boolean finished;

        private ChunkedInputStream(InputStream input, long deadlineNanos) {
            this.input = input;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            byte[] byteValue = new byte[1];
            return read(byteValue, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(byteValue[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            prepareChunk();
            if (finished) return -1;
            ensureBeforeDeadline(deadlineNanos);
            int read = input.read(target, offset, (int) Math.min(length, remaining));
            if (read < 0) throw new IOException("在线音频分块响应意外结束");
            remaining -= read;
            if (remaining == 0L) afterChunkData = true;
            return read;
        }

        @Override
        public void close() throws IOException { input.close(); }

        private void prepareChunk() throws IOException {
            while (!finished && remaining == 0L) {
                if (afterChunkData) consumeChunkTerminator();
                String line = readHttpLine(input, deadlineNanos, 1024);
                int extension = line.indexOf(';');
                String sizeText = (extension < 0 ? line : line.substring(0, extension)).trim();
                final long size;
                try { size = Long.parseLong(sizeText, 16); }
                catch (NumberFormatException exception) { throw new IOException("在线音频分块长度非法", exception); }
                if (size < 0L) throw new IOException("在线音频分块长度非法");
                if (size == 0L) {
                    for (int lines = 0; lines < 32; lines++) {
                        if (readHttpLine(input, deadlineNanos, 1024).isEmpty()) {
                            finished = true;
                            return;
                        }
                    }
                    throw new IOException("在线音频分块尾部过长");
                }
                remaining = size;
            }
        }

        private boolean afterChunkData;

        private void consumeChunkTerminator() throws IOException {
            ensureBeforeDeadline(deadlineNanos);
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("在线音频分块结尾非法");
            afterChunkData = false;
        }
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
        return new CachedAudio(target, kind, contentHash, false);
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
        return new CachedAudio(cached, kind, namedHash, true);
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
        long remaining = remainingNanos(deadlineNanos);
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis + 1L));
    }
    private static long remainingNanos(long deadlineNanos) throws IOException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) throw new IOException("在线音频总下载超时");
        return remaining;
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
    public record CachedAudio(Path path, Kind kind, String contentHash, boolean cacheHit) {}
}
