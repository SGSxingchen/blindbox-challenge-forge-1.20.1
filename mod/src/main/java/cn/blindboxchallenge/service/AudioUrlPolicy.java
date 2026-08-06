package cn.blindboxchallenge.service;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 八音盒的公共 URL 规则。服务端仅做语法与危险字面量过滤、保存和广播，绝不建立 HTTP 连接；
 * 客户端下载器会在每个真实跳转上重新解析 DNS、校验公网地址并将已验证地址钉到 TLS 连接。
 */
public final class AudioUrlPolicy {
    public static final int MAX_URL_LENGTH = 2048;
    private static final int HTTPS_PORT = 443;

    private AudioUrlPolicy() {}

    public static String normalizeHttpsUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_URL_LENGTH) throw new IllegalArgumentException("URL 长度不合法");
        try {
            URI input = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(input.getScheme()) || input.getRawUserInfo() != null || input.getRawFragment() != null) {
                throw new IllegalArgumentException("仅允许无认证信息的 HTTPS URL");
            }
            String host = input.getHost();
            if (host == null || host.isBlank() || host.indexOf(':') >= 0) throw new IllegalArgumentException("主机名不合法或为 IP 字面量");
            String asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (asciiHost.equals("localhost") || asciiHost.endsWith(".localhost") || looksLikeIpv4(asciiHost)) {
                throw new IllegalArgumentException("不允许本机或 IP 字面量");
            }
            int port = input.getPort();
            if (port != -1 && port != HTTPS_PORT) throw new IllegalArgumentException("只允许标准 HTTPS 端口");
            String path = input.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            URI normalized = new URI("https", null, asciiHost, -1, path, input.getRawQuery(), null).normalize();
            return normalized.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("在线音频 URL 不安全或格式错误", exception);
        }
    }

    /** 客户端 DNS 解析结果必须全部是可公开路由地址；任一危险回答即拒绝，避免选择性绕过。 */
    public static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] value = address.getAddress();
        if (value.length == 4) {
            int a = Byte.toUnsignedInt(value[0]);
            int b = Byte.toUnsignedInt(value[1]);
            int c = Byte.toUnsignedInt(value[2]);
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 168)
                    && !(a == 192 && b == 0 && c <= 2)
                    && !(a == 198 && (b == 18 || b == 19 || b == 51))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (value.length == 16) {
            int first = Byte.toUnsignedInt(value[0]);
            int second = Byte.toUnsignedInt(value[1]);
            return (first & 0xfe) != 0xfc && first != 0xff
                    && !(first == 0x20 && second == 0x01 && Byte.toUnsignedInt(value[2]) == 0x0d && Byte.toUnsignedInt(value[3]) == 0xb8);
        }
        return false;
    }

    private static boolean looksLikeIpv4(String value) {
        if (!value.matches("[0-9.]+")) return false;
        try {
            return InetAddress.getByName(value).getAddress().length == 4;
        } catch (Exception ignored) {
            return true;
        }
    }
}
