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
        if (value.length == 4) return isPublicIpv4(value, 0);
        if (value.length != 16) return false;

        // 这些 IPv6 形式最后四字节实际决定 IPv4 目标。若不展开，NAT64 或 IPv4-mapped 地址可把
        // 127.0.0.1 / 192.168.0.0 等危险目标伪装成 IPv6，从而绕过 DNS 结果的逐地址拒绝。
        if (isIpv4Mapped(value) || isIpv4Compatible(value) || isWellKnownNat64(value)) return isPublicIpv4(value, 12);

        int first = unsigned(value, 0);
        int second = unsigned(value, 1);
        // 只接受 IANA 当前可公开路由的 global-unicast 范围 2000::/3，再排除其中已保留的特殊用途块。
        if ((first & 0xe0) != 0x20) return false;
        if (first == 0x20 && second == 0x01 && (unsigned(value, 2) & 0xfe) == 0x00) return false; // 2001::/23
        if (first == 0x20 && second == 0x01 && unsigned(value, 2) == 0x00 && unsigned(value, 3) == 0x02
                && unsigned(value, 4) == 0x00 && unsigned(value, 5) == 0x00) return false; // 2001:2::/48
        if (first == 0x20 && second == 0x01 && unsigned(value, 2) == 0x00
                && ((unsigned(value, 3) & 0xf0) == 0x10 || (unsigned(value, 3) & 0xf0) == 0x20)) return false; // 2001:10::/28、2001:20::/28
        return !(first == 0x20 && second == 0x01 && unsigned(value, 2) == 0x0d && unsigned(value, 3) == 0xb8); // 2001:db8::/32
    }

    private static boolean isPublicIpv4(byte[] value, int offset) {
        int a = unsigned(value, offset);
        int b = unsigned(value, offset + 1);
        int c = unsigned(value, offset + 2);
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && b == 0 && c == 0)
                && !(a == 192 && b == 0 && c == 2)
                && !(a == 192 && b == 31 && c == 196)
                && !(a == 192 && b == 52 && c == 193)
                && !(a == 192 && b == 88 && c == 99)
                && !(a == 192 && b == 168)
                && !(a == 192 && b == 175 && c == 48)
                && !(a == 198 && (b == 18 || b == 19 || b == 51))
                && !(a == 203 && b == 0 && c == 113);
    }

    private static boolean isIpv4Mapped(byte[] value) {
        for (int index = 0; index < 10; index++) if (value[index] != 0) return false;
        return value[10] == (byte) 0xff && value[11] == (byte) 0xff;
    }

    private static boolean isIpv4Compatible(byte[] value) {
        for (int index = 0; index < 12; index++) if (value[index] != 0) return false;
        return true;
    }

    private static boolean isWellKnownNat64(byte[] value) {
        return value[0] == 0x00 && value[1] == 0x64 && value[2] == (byte) 0xff && value[3] == (byte) 0x9b
                && value[4] == 0 && value[5] == 0 && value[6] == 0 && value[7] == 0
                && value[8] == 0 && value[9] == 0 && value[10] == 0 && value[11] == 0;
    }

    private static int unsigned(byte[] value, int index) { return Byte.toUnsignedInt(value[index]); }

    private static boolean looksLikeIpv4(String value) {
        // 该函数会在服务端 C2S 提交时调用；数字/点主机名本身没有合法域名用途，直接拒绝而不做 DNS，
        // 避免模糊数字表示触发网络解析、阻塞服务端 tick 或绕过 IP 字面量策略。
        return value.matches("[0-9.]+");
    }
}
