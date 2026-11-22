package com.yu.yuaifather.common.security;

import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 外部 HTTP 地址的统一 SSRF 防护入口。
 */
public final class SafeUrlValidator {

    private static final Set<Integer> BLOCKED_FIRST_OCTETS = Set.of(9, 10, 11, 21, 30);

    private SafeUrlValidator() {
    }

    public static URI validate(String rawUrl) {
        final URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw rejected("URL 格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw rejected("仅允许 HTTP 或 HTTPS URL");
        }
        if (uri.getRawUserInfo() != null) {
            throw rejected("URL 不允许包含用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw rejected("URL 主机无效");
        }
        try {
            String asciiHost = IDN.toASCII(host);
            InetAddress[] addresses = InetAddress.getAllByName(asciiHost);
            if (addresses.length == 0) {
                throw rejected("URL 主机无法解析");
            }
            Arrays.stream(addresses).forEach(SafeUrlValidator::requirePublicAddress);
        } catch (UnknownHostException | IllegalArgumentException e) {
            throw rejected("URL 主机无法解析");
        }
        return uri.normalize();
    }

    static void requirePublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            throw rejected("禁止访问本地或内部网络地址");
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            if (BLOCKED_FIRST_OCTETS.contains(a)
                    || a == 0 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 0 || b == 168))
                    || (a == 198 && (b == 18 || b == 19))) {
                throw rejected("禁止访问内部或保留网络地址");
            }
        } else if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xFE) == 0xFC;
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D && Byte.toUnsignedInt(bytes[3]) == 0xB8;
            if (uniqueLocal || documentation || (first & 0xE0) != 0x20) {
                throw rejected("禁止访问内部或保留 IPv6 地址");
            }
        }
    }

    private static BusinessException rejected(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN_ERROR, message);
    }
}
