package com.yu.yuaifather.tool.engine;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.yu.yuaifather.config.ImageProperties;
import com.yu.yuaifather.media.storage.LocalImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 把搜图结果下载到本机静态目录。
 * <p>
 * Pixabay、Unsplash、Pexels 返回的图片地址会过期或拒绝外链。生成页如果直接写这些地址，
 * 过一段时间封面截图和页面里的图片都会失效。这里在写入代码前把字节落到
 * {@code /api/static/_generated_images}，页面只引用自己的地址。
 */
@Slf4j
@Component
public class SearchImageArchiver {

    static final int MAX_REDIRECTS = 3;

    private final LocalImageStorage localImageStorage;
    private final RemoteImageClient client;
    private final Predicate<String> embeddable;

    public SearchImageArchiver(LocalImageStorage localImageStorage, ImageProperties properties) {
        this(localImageStorage,
                new HutoolRemoteImageClient(10_000, byteLimit(properties)),
                ImageHostPolicy::isEmbeddable);
    }

    private static long byteLimit(ImageProperties properties) {
        long limit = properties.getFallback().getMaxBytes();
        return limit > 0 ? limit : 20L * 1024 * 1024;
    }

    SearchImageArchiver(LocalImageStorage localImageStorage,
                        RemoteImageClient client,
                        Predicate<String> embeddable) {
        this.localImageStorage = localImageStorage;
        this.client = client;
        this.embeddable = embeddable;
    }

    /**
     * 下载白名单内的远程图片并返回本机静态 URL。失败返回 null，调用方继续换图源。
     */
    public String archive(String remoteUrl) {
        String current = remoteUrl;
        Set<String> seen = new HashSet<>();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (StrUtil.isBlank(current) || !seen.add(current) || !embeddable.test(current)) {
                return null;
            }
            RemoteImageResponse response = client.fetch(current);
            if (response == null) {
                return null;
            }
            if (response.redirect()) {
                current = resolve(current, response.location());
                continue;
            }
            if (response.status() != 200 || response.body() == null || response.body().length == 0) {
                return null;
            }
            String ext = imageExtension(response.body());
            if (ext == null) {
                log.warn("远程图片不是可识别的位图，已丢弃 host={}", hostOf(current));
                return null;
            }
            return store(response.body(), ext, hostOf(current));
        }
        return null;
    }

    private String store(byte[] body, String ext, String host) {
        File temp = null;
        try {
            temp = File.createTempFile("img_", "." + ext);
            FileUtil.writeBytes(body, temp);
            String localUrl = localImageStorage.save(temp, ext);
            if (StrUtil.isBlank(localUrl)) {
                return null;
            }
            log.info("搜图结果已转存本地 host={} bytes={}", host, body.length);
            return localUrl;
        } catch (Exception ex) {
            log.warn("搜图结果转存失败 host={} msg={}", host, ex.getMessage());
            return null;
        } finally {
            if (temp != null) {
                FileUtil.del(temp);
            }
        }
    }

    static String resolve(String current, String location) {
        if (StrUtil.isBlank(location)) {
            return null;
        }
        try {
            return URI.create(current).resolve(location.trim()).toString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String imageExtension(byte[] data) {
        if (data == null || data.length < 3) {
            return null;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (data.length >= 8
                && data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "png";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }

    interface RemoteImageClient {
        RemoteImageResponse fetch(String url);
    }

    record RemoteImageResponse(int status, String location, byte[] body) {
        boolean redirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }

    /**
     * 不自动跟随重定向。每一跳都要重新过白名单，避免图床把请求转到内网。
     */
    static final class HutoolRemoteImageClient implements RemoteImageClient {
        private final int timeoutMs;
        private final long maxBytes;

        HutoolRemoteImageClient(int timeoutMs, long maxBytes) {
            this.timeoutMs = timeoutMs;
            this.maxBytes = maxBytes;
        }

        @Override
        public RemoteImageResponse fetch(String url) {
            try (HttpResponse response = HttpRequest.get(url)
                    .timeout(timeoutMs)
                    .setFollowRedirects(false)
                    .execute()) {
                int status = response.getStatus();
                if (status >= 300 && status < 400) {
                    return new RemoteImageResponse(status, response.header("Location"), null);
                }
                if (status != 200) {
                    return new RemoteImageResponse(status, null, null);
                }
                byte[] body = readLimited(response, maxBytes);
                return new RemoteImageResponse(status, null, body);
            } catch (Exception ex) {
                log.warn("下载远程图片失败 host={} msg={}", hostOf(url), ex.getMessage());
                return null;
            }
        }

        private static byte[] readLimited(HttpResponse response, long maxBytes) throws java.io.IOException {
            try (InputStream in = response.bodyStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
    }
}
