package cn.blindboxchallenge.client.audio;

import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.Socket;
import java.nio.channels.SocketChannel;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
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
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.minecraft.client.Minecraft;

/** 纯客户端 HTTPS 下载与缓存。每一跳都重新解析并把已校验公网 IP 固定到 TLS 连接，避免 DNS 重绑定。 */
public final class RemoteAudioDownload {
    /** 仅客户端日志使用的失败阶段；不含 URL、响应头、路径或异常消息。 */
    public enum FailureStage {
        DNS, PINNED_CONNECT_IPV4, PINNED_CONNECT_IPV6,
        TLS_SOCKET_WRAP, TLS_PARAMETERS, TLS_DEADLINE_ARM, TLS_HANDSHAKE, TLS_POST_HANDSHAKE_DEADLINE,
        HTTP_HEADERS, BODY, CACHE, DECODE, UNKNOWN
    }

    /** 保留实际 cause 供本地链路处理；对外诊断只允许读取无敏感数据的阶段枚举。 */
    public static final class AudioFailureException extends IOException {
        private final FailureStage stage;
        private final String connectionAttemptSummary;

        public AudioFailureException(FailureStage stage, Throwable cause) {
            this(stage, cause, "");
        }

        /** 只含失败阶段和异常简单类名，用于客户端本地定位；绝不包含地址、URL、端口或异常消息。 */
        public AudioFailureException(FailureStage stage, Throwable cause, String connectionAttemptSummary) {
            super(cause);
            this.stage = stage;
            this.connectionAttemptSummary = connectionAttemptSummary;
        }

        public FailureStage stage() { return stage; }
        public String connectionAttemptSummary() { return connectionAttemptSummary; }
    }

    public static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int TIMEOUT_MILLIS = (int) Duration.ofSeconds(10).toMillis();
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final long STALE_PART_MILLIS = Duration.ofMinutes(1).toMillis();
    /**
     * 同 URL 的单飞结果只保存不可变的缓存条目；每个等待者都必须取得自己的短租约，不能共享一个
     * 可关闭的 {@link CachedAudio}。否则第一个完成解码的播放会错误释放另一个等待者仍在读取的文件。
     */
    private static final ConcurrentHashMap<String, CompletableFuture<StoredAudio>> IN_FLIGHT = new ConcurrentHashMap<>();
    /** 只串行缓存复验、落盘提交和 LRU；网络传输与音频解码绝不持有此锁。 */
    private static final Object CACHE_LOCK = new Object();
    /** 在 decode 打开缓存文件前暂时钉住它，LRU 不得删除仍被异步工作线程读取的文件。 */
    private static final Map<Path, Integer> CACHE_REFERENCES = new HashMap<>();
    /** 文件系统时间精度可能低于连续请求速度；此值令持久 mtime 仍保持严格的本进程 LRU 顺序。 */
    private static long lastCacheAccessMillis;
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
        try { Files.createDirectories(cache); }
        catch (IOException exception) { throw new AudioFailureException(FailureStage.CACHE, exception); }
        String urlHash = sha256(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StoredAudio cached;
        try { cached = findCached(cache, urlHash); }
        catch (IOException exception) { throw new AudioFailureException(FailureStage.CACHE, exception); }
        if (cached != null) return leaseOrCancel(cached);

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        CompletableFuture<StoredAudio> ownDownload = new CompletableFuture<>();
        CompletableFuture<StoredAudio> activeDownload = IN_FLIGHT.putIfAbsent(urlHash, ownDownload);
        if (activeDownload != null) return lease(waitForDownload(activeDownload, deadlineNanos), false, true);
        try {
            cleanupStaleParts(cache);
            // 进入单飞区后再次复检，避免刚完成的同 URL 下载仍被当成未命中。
            cached = findCached(cache, urlHash);
            StoredAudio result = cached != null ? cached : fetchUncached(normalized, cache, urlHash, deadlineNanos);
            // 新文件已由 saveResponse 在 CACHE_LOCK 内预留；先把预留转交给本调用者的租约，再唤醒
            // 其它同 URL 等待者，保证提交→解码打开之间没有可被另一个 URL 的 LRU 删除的窗口。
            CachedAudio leased = leaseOrCancel(result);
            ownDownload.complete(result);
            return leased;
        } catch (AudioFailureException exception) {
            ownDownload.completeExceptionally(exception);
            throw exception;
        } catch (IOException exception) {
            AudioFailureException staged = new AudioFailureException(FailureStage.CACHE, exception);
            ownDownload.completeExceptionally(staged);
            throw staged;
        } catch (RuntimeException exception) {
            ownDownload.completeExceptionally(exception);
            throw exception;
        } finally {
            IN_FLIGHT.remove(urlHash, ownDownload);
        }
    }

    private static StoredAudio fetchUncached(String normalized, Path cache, String urlHash, long deadlineNanos) throws IOException {
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
                boolean chunked = "chunked".equalsIgnoreCase(response.header("transfer-encoding"));
                if (chunked && contentLength != null && !contentLength.isBlank()) {
                    // 分块体由 ChunkedInputStream 定界；同时接受 Content-Length 会让两个长度语义竞争。
                    throw new IOException("在线音频响应同时声明 Content-Length 与 chunked");
                }
                long declaredContentLength = -1L;
                if (contentLength != null && !contentLength.isBlank()) {
                    try {
                        declaredContentLength = Long.parseLong(contentLength.trim());
                        if (declaredContentLength < 0L) throw new IOException("在线音频 Content-Length 非法");
                        if (declaredContentLength > MAX_DOWNLOAD_BYTES) throw new IOException("在线音频超过 16 MiB 上限");
                    } catch (NumberFormatException exception) {
                        throw new IOException("在线音频 Content-Length 非法", exception);
                    }
                }
                return saveResponse(response.body(), cache, urlHash, deadlineNanos, declaredContentLength);
            } catch (AudioFailureException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new AudioFailureException(FailureStage.HTTP_HEADERS, exception);
            }
        }
        throw new IOException("在线音频重定向流程异常");
    }

    private static StoredAudio waitForDownload(CompletableFuture<StoredAudio> activeDownload, long deadlineNanos) throws IOException {
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
        InetAddress[] addresses;
        try { addresses = resolvePublicAddresses(uri.getHost(), deadlineNanos); }
        catch (IOException exception) { throw new AudioFailureException(FailureStage.DNS, exception); }
        IOException failure = null;
        Map<FailureStage, String> attempts = new LinkedHashMap<>();
        for (InetAddress address : addresses) {
            try {
                return openPinnedAtAddress(uri, address, deadlineNanos);
            } catch (IOException exception) {
                FailureStage attemptStage = exception instanceof AudioFailureException staged ? staged.stage() : connectStage(address);
                attempts.put(attemptStage, rootExceptionType(exception));
                failure = exception instanceof AudioFailureException ? exception : new AudioFailureException(attemptStage, exception);
                try {
                    ensureBeforeDeadline(deadlineNanos);
                } catch (IOException deadlineException) {
                    if (failure instanceof AudioFailureException staged) {
                        throw new AudioFailureException(staged.stage(), staged, connectionAttemptSummary(attempts));
                    }
                    throw new AudioFailureException(connectStage(address), deadlineException);
                }
            }
        }
        if (failure instanceof AudioFailureException staged) {
            throw new AudioFailureException(staged.stage(), staged, connectionAttemptSummary(attempts));
        }
        throw new AudioFailureException(FailureStage.UNKNOWN, failure);
    }

    private static DownloadResponse openPinnedAtAddress(URI uri, InetAddress address, long deadlineNanos) throws IOException {
        Socket plain = directSocketFor(address);
        SSLSocket tls = null;
        ScheduledFuture<?> closeAtDeadline = null;
        FailureStage stage = connectStage(address);
        try {
            plain.connect(new InetSocketAddress(address, 443), remainingMillis(deadlineNanos));
            plain.setSoTimeout(remainingMillis(deadlineNanos));
            stage = FailureStage.TLS_SOCKET_WRAP;
            tls = (SSLSocket) TLS_FACTORY.createSocket(plain, uri.getHost(), 443, true);
            stage = FailureStage.TLS_PARAMETERS;
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(java.util.List.of(new SNIHostName(uri.getHost())));
            tls.setSSLParameters(parameters);
            stage = FailureStage.TLS_DEADLINE_ARM;
            long closeDelay = remainingNanos(deadlineNanos);
            SSLSocket pinnedSocket = tls;
            closeAtDeadline = DEADLINE_ENFORCER.schedule(() -> closeQuietly(pinnedSocket), closeDelay, TimeUnit.NANOSECONDS);
            tls.setSoTimeout(remainingMillis(deadlineNanos));
            stage = FailureStage.TLS_HANDSHAKE;
            tls.startHandshake();
            stage = FailureStage.TLS_POST_HANDSHAKE_DEADLINE;
            ensureBeforeDeadline(deadlineNanos);
            stage = FailureStage.HTTP_HEADERS;
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
            if (exception instanceof AudioFailureException staged) throw staged;
            throw new AudioFailureException(stage, exception);
        }
    }

    /** 只暴露已尝试目标的地址族，不输出地址、主机、端口或异常消息。 */
    private static FailureStage connectStage(InetAddress address) {
        return address.getAddress().length == 4 ? FailureStage.PINNED_CONNECT_IPV4 : FailureStage.PINNED_CONNECT_IPV6;
    }

    private static String connectionAttemptSummary(Map<FailureStage, String> attempts) {
        StringJoiner summary = new StringJoiner(",");
        attempts.forEach((stage, type) -> summary.add(stage + "/" + type));
        return summary.toString();
    }

    private static String rootExceptionType(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName();
    }

    /**
     * 对已经逐个通过公网策略的候选建立无代理、地址族精确的 TCP socket。不能让 JVM 的双栈默认选择
     * 把 IPv4 字面量重新放进不可达 IPv6 路径；IPv6 候选仍在 IPv4 候选全部失败后原样回退。
     */
    private static Socket directSocketFor(InetAddress address) throws IOException {
        return SocketChannel.open(address instanceof Inet4Address ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6).socket();
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
            // Hosted Runner 已证实存在无 IPv6 路由的环境；所有答案都已先完整通过公网校验，故只改变
            // 连接尝试顺序：优先可达 IPv4，IPv4 全部失败时仍严格尝试每个已校验 IPv6 答案。
            return Arrays.stream(addresses).sorted(Comparator.comparingInt(RemoteAudioDownload::addressFamilyRank)).toArray(InetAddress[]::new);
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

    private static int addressFamilyRank(InetAddress address) { return address instanceof Inet4Address ? 0 : 1; }

    /**
     * HTTP/1.1 的非分块响应一旦声明 Content-Length，就必须按它完成消息体定界；不能等连接 EOF，
     * 因为合法保活响应会在完整音频后继续保持 TLS socket 打开。未声明长度的响应才读到 EOF。
     */
    private static StoredAudio saveResponse(InputStream input, Path cache, String urlHash, long deadlineNanos,
                                            long declaredContentLength) throws IOException {
        final Path temporary;
        try { temporary = Files.createTempFile(cache, urlHash + "-", ".part"); }
        catch (IOException exception) { throw new AudioFailureException(FailureStage.CACHE, exception); }
        MessageDigest digest = digest();
        byte[] first = new byte[10];
        int firstLength = 0;
        int total = 0;
        long remainingContentLength = declaredContentLength;
        try (input; OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            for (;;) {
                ensureBeforeDeadline(deadlineNanos);
                if (remainingContentLength == 0L) break;
                int requested = remainingContentLength < 0L ? buffer.length : (int) Math.min(buffer.length, remainingContentLength);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    if (remainingContentLength > 0L) throw new IOException("在线音频在 Content-Length 前意外结束");
                    break;
                }
                if (read == 0) continue;
                if (remainingContentLength > 0L) remainingContentLength -= read;
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
            throw new AudioFailureException(FailureStage.BODY, exception);
        } catch (RuntimeException exception) {
            Files.deleteIfExists(temporary);
            throw new AudioFailureException(FailureStage.BODY, exception);
        }
        Kind kind = detect(first, firstLength);
        if (kind == null) {
            Files.deleteIfExists(temporary);
            throw new AudioFailureException(FailureStage.BODY, new IOException("在线音频文件头不是 OGG 或 MP3"));
        }
        String contentHash = hex(digest.digest());
        Path target = cache.resolve(urlHash + "-" + contentHash + kind.extension);
        try {
            // 不同 URL 可并行下载；只有提交和随后的 LRU 必须原子化，才不会共同越过 64 MiB 上限。
            synchronized (CACHE_LOCK) {
                try {
                    moveNewFile(temporary, target);
                } catch (java.nio.file.FileAlreadyExistsException exception) {
                    Files.deleteIfExists(temporary);
                    StoredAudio existing = findCachedLocked(cache, urlHash);
                    if (existing != null) return existing;
                    throw new AudioFailureException(FailureStage.CACHE, exception);
                }
                // 新条目在本调用者把租约交给解码器前不能被并发 LRU 淘汰；否则 Windows 等平台会在
                // Files.newInputStream 前删掉刚下载的文件。预留会在 fetch 的 lease(...) 中原子转交。
                touchCacheEntryLocked(target);
                retainCacheReferenceLocked(target);
                try {
                    trimCacheLocked(cache);
                } catch (IOException exception) {
                    releaseCacheReferenceLocked(target);
                    throw exception;
                }
            }
        } catch (AudioFailureException exception) {
            throw exception;
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw new AudioFailureException(FailureStage.CACHE, exception);
        }
        return new StoredAudio(target, kind, contentHash, false, true);
    }

    private static void moveNewFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        }
    }

    private static StoredAudio findCached(Path cache, String urlHash) throws IOException {
        synchronized (CACHE_LOCK) {
            return findCachedLocked(cache, urlHash);
        }
    }

    /** 调用方必须持有 {@link #CACHE_LOCK}，避免复验删坏文件时与 LRU 淘汰并发。 */
    private static StoredAudio findCachedLocked(Path cache, String urlHash) throws IOException {
        try (var files = Files.list(cache)) {
            for (Path cached : files.filter(path -> isCacheFileName(path.getFileName().toString(), urlHash))
                    .sorted(Comparator.comparingLong(RemoteAudioDownload::lastModified).reversed()).toList()) {
                StoredAudio verified = verifyCached(cache, cached, urlHash);
                if (verified != null) {
                    // findCached 的锁释放到 lease 再次取得锁之间也不能让新提交的 LRU 删除命中项。
                    // 这份预留和 saveResponse 的新文件预留走同一条原子转交流程。
                    retainCacheReferenceLocked(verified.path());
                    return new StoredAudio(verified.path(), verified.kind(), verified.contentHash(), verified.cacheHit(), true);
                }
            }
        }
        return null;
    }

    private static StoredAudio verifyCached(Path cache, Path cached, String urlHash) throws IOException {
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
        touchCacheEntryLocked(cached);
        return new StoredAudio(cached, kind, namedHash, true, false);
    }

    private static boolean isCacheFileName(String name, String urlHash) {
        String extension = name.endsWith(Kind.OGG.extension) ? Kind.OGG.extension : name.endsWith(Kind.MP3.extension) ? Kind.MP3.extension : null;
        if (extension == null || !name.startsWith(urlHash + "-") || name.length() != urlHash.length() + 1 + 64 + extension.length()) return false;
        String contentHash = name.substring(urlHash.length() + 1, name.length() - extension.length());
        return contentHash.matches("[0-9a-f]{64}");
    }

    private static int kindExtensionLength(String name) { return name.endsWith(Kind.OGG.extension) ? Kind.OGG.extension.length() : Kind.MP3.extension.length(); }

    /** 调用方必须持有 {@link #CACHE_LOCK}；仍有 fetch→decode 租约的文件绝不允许被删除。 */
    private static void trimCacheLocked(Path cache) throws IOException {
        cleanupStaleParts(cache);
        try (var files = Files.list(cache)) {
            var entries = files.filter(path -> path.toString().endsWith(Kind.OGG.extension) || path.toString().endsWith(Kind.MP3.extension))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparingLong(RemoteAudioDownload::lastModified)
                            .thenComparing(path -> path.getFileName().toString())).toList();
            long total = 0L;
            for (Path entry : entries) total += Files.size(entry);
            for (Path entry : entries) {
                if (total <= MAX_CACHE_BYTES) break;
                if (CACHE_REFERENCES.containsKey(entry)) continue;
                long size = Files.size(entry);
                Files.deleteIfExists(entry);
                total -= size;
            }
        }
    }

    /** 为调用者分配独立租约；同 URL 单飞的每个 future 等待者都要各自持有一次。 */
    private static CachedAudio lease(StoredAudio stored, boolean transferReservation, boolean singleFlightFollower) throws IOException {
        synchronized (CACHE_LOCK) {
            if (!Files.isRegularFile(stored.path(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stored.path())) {
                throw new AudioFailureException(FailureStage.CACHE, new IOException("在线音频缓存条目在租约前消失"));
            }
            retainCacheReferenceLocked(stored.path());
            if (transferReservation) {
                if (!stored.reserved()) {
                    releaseCacheReferenceLocked(stored.path());
                    throw new AudioFailureException(FailureStage.CACHE, new IOException("在线音频缓存预留状态不一致"));
                }
                releaseCacheReferenceLocked(stored.path());
            }
            return new CachedAudio(stored.path(), stored.kind(), stored.contentHash(), stored.cacheHit(), singleFlightFollower);
        }
    }

    private static CachedAudio leaseOrCancel(StoredAudio stored) throws IOException {
        try {
            return lease(stored, stored.reserved(), false);
        } catch (IOException | RuntimeException exception) {
            if (stored.reserved()) cancelReservation(stored.path());
            throw exception;
        }
    }

    private static void retainCacheReferenceLocked(Path path) {
        CACHE_REFERENCES.merge(path, 1, Integer::sum);
    }

    private static void cancelReservation(Path path) {
        synchronized (CACHE_LOCK) {
            releaseCacheReferenceLocked(path);
        }
    }

    private static void releaseCacheReferenceLocked(Path path) {
        Integer references = CACHE_REFERENCES.get(path);
        if (references == null || references <= 0) throw new IllegalStateException("在线音频缓存租约计数非法");
        if (references == 1) CACHE_REFERENCES.remove(path);
        else CACHE_REFERENCES.put(path, references - 1);
    }

    /** mtime 是跨重启保留的 LRU 账本；同一毫秒内也必须严格递增，不能依赖 Files.list 的任意顺序。 */
    private static void touchCacheEntryLocked(Path path) throws IOException {
        long existing = lastModified(path);
        long now = System.currentTimeMillis();
        long next = Math.max(now, Math.max(existing + 1L, lastCacheAccessMillis + 1L));
        lastCacheAccessMillis = next;
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(next));
    }

    /**
     * 解码只在客户端工作线程内发生；该租约只覆盖 fetch 返回至 {@code prepare} 打开并读完文件的短窗口，
     * 不会把网络、完整 PCM 解码或 SoundEngine 播放串行到 {@link #CACHE_LOCK}。
     */
    public static final class CachedAudio implements AutoCloseable {
        private final Path path;
        private final Kind kind;
        private final String contentHash;
        private final boolean cacheHit;
        /** 仅表示本次真实 fetch 等待了同 JVM 内已有下载，不代表缓存命中或 PCM 成功。 */
        private final boolean singleFlightFollower;
        private boolean closed;

        private CachedAudio(Path path, Kind kind, String contentHash, boolean cacheHit, boolean singleFlightFollower) {
            this.path = path;
            this.kind = kind;
            this.contentHash = contentHash;
            this.cacheHit = cacheHit;
            this.singleFlightFollower = singleFlightFollower;
        }

        public Path path() { return path; }
        public Kind kind() { return kind; }
        public String contentHash() { return contentHash; }
        public boolean cacheHit() { return cacheHit; }
        public boolean singleFlightFollower() { return singleFlightFollower; }

        @Override
        public void close() throws IOException {
            synchronized (CACHE_LOCK) {
                if (closed) return;
                closed = true;
                releaseCacheReferenceLocked(path);
                // 并发 decode 完成后立即收敛临时超过上限的缓存；若文件系统拒绝该维护，不能静默
                // 当成成功，prepare 会把 IOException 明确归入 CACHE/DECODE 失败链。
                Path parent = path.getParent();
                if (parent != null && Files.isDirectory(parent)) trimCacheLocked(parent);
            }
        }
    }

    /** 内部单飞值没有可关闭状态；只有每位调用者得到的 {@link CachedAudio} 才拥有租约。 */
    private record StoredAudio(Path path, Kind kind, String contentHash, boolean cacheHit, boolean reserved) {}

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
}
