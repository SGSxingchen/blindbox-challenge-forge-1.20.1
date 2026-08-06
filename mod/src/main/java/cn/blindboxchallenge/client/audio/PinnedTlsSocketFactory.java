package cn.blindboxchallenge.client.audio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 将已验证的 DNS 结果固定到真正 TCP 连接，同时保留原主机名 SNI 与 HTTPS 证书主机名校验。 */
final class PinnedTlsSocketFactory extends SSLSocketFactory {
    private static final ScheduledExecutorService DEADLINE_ENFORCER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blindboxchallenge-audio-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final String hostname;
    private final InetAddress[] addresses;
    private final long deadlineNanos;
    private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
    private int nextAddress;

    PinnedTlsSocketFactory(String hostname, InetAddress[] addresses, long deadlineNanos) {
        this.hostname = hostname;
        this.addresses = addresses.clone();
        this.deadlineNanos = deadlineNanos;
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(String host, int port) throws IOException { return pinned(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return pinned(host, port, local, localPort); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return pinned(hostname, port); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return pinned(hostname, port, local, localPort); }
    @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        if (autoClose) socket.close();
        return pinned(host, port);
    }

    private synchronized SSLSocket pinned(String host, int port) throws IOException { return pinned(host, port, null, 0); }

    private synchronized SSLSocket pinned(String host, int port, InetAddress local, int localPort) throws IOException {
        if (!hostname.equalsIgnoreCase(host) || port != 443) throw new IOException("TLS 主机或端口与已验证 URL 不一致");
        InetAddress address = addresses[nextAddress++ % addresses.length];
        Socket plain = new Socket();
        try {
            if (local != null) plain.bind(new InetSocketAddress(local, localPort));
            plain.connect(new InetSocketAddress(address, port), remainingMillis());
            plain.setSoTimeout(remainingMillis());
            SSLSocket socket = (SSLSocket) delegate.createSocket(plain, hostname, port, true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(hostname)));
            socket.setSSLParameters(parameters);
            long remaining = remainingNanos();
            socket.setSoTimeout(toTimeoutMillis(remaining));
            // HttpsURLConnection 的 read timeout 不能可靠地覆盖已经建立的 TLS 流；到截止点主动关闭
            // 已钉住的 socket，确保 DNS、TCP、握手、响应头和响应体共用同一 10 秒预算。
            DEADLINE_ENFORCER.schedule(() -> closeQuietly(socket), remaining, TimeUnit.NANOSECONDS);
            return socket;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(plain);
            throw exception;
        }
    }

    private long remainingNanos() throws IOException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) throw new IOException("在线音频总下载超时");
        return remaining;
    }

    private int remainingMillis() throws IOException { return toTimeoutMillis(remainingNanos()); }

    private static int toTimeoutMillis(long nanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        if (millis == 0L) return 1;
        return (int) Math.min(Integer.MAX_VALUE, millis + 1L);
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
    }
}
