package cn.blindboxchallenge.client.audio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 将已验证的 DNS 结果固定到真正 TCP 连接，同时保留原主机名 SNI 与 HTTPS 证书主机名校验。 */
final class PinnedTlsSocketFactory extends SSLSocketFactory {
    private final String hostname;
    private final InetAddress[] addresses;
    private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
    private int nextAddress;

    PinnedTlsSocketFactory(String hostname, InetAddress[] addresses) {
        this.hostname = hostname;
        this.addresses = addresses.clone();
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
        SSLSocket socket = local == null ? (SSLSocket) delegate.createSocket(address, port)
                : (SSLSocket) delegate.createSocket(address, port, local, localPort);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(hostname)));
        socket.setSSLParameters(parameters);
        return socket;
    }
}
