package com.yu.yuaifather.tool.engine;

import com.yu.yuaifather.common.security.SafeUrlValidator;
import com.yu.yuaifather.exception.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 可嵌入页面的远程图片地址。只允许 https、白名单主机，且解析结果必须是公网地址。
 */
final class ImageHostPolicy {

    static final Set<String> HOSTS = Set.of(
            "pixabay.com",
            "cdn.pixabay.com",
            "images.unsplash.com",
            "images.pexels.com",
            "source.unsplash.com",
            "loremflickr.com",
            "images.pexels.com.cn",
            "picsum.photos",
            "fastly.picsum.photos",
            "i.picsum.photos"
    );

    private ImageHostPolicy() {
    }

    static boolean isEmbeddable(String url) {
        URI uri = parse(url);
        if (uri == null || uri.getRawUserInfo() != null) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return resolvesToPublicAddress(host);
    }

    private static boolean resolvesToPublicAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                SafeUrlValidator.requirePublicAddress(address);
            }
            return true;
        } catch (UnknownHostException | BusinessException ex) {
            return false;
        }
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
