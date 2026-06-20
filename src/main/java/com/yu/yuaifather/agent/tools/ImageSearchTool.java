package com.yu.yuaifather.agent.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yu.yuaifather.config.ImageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 图片搜索工具：Unsplash → Pexels → picsum 兜底。
 * <p>
 * 安全要点：
 * <ul>
 *   <li>返回 URL 必须为 https，且域名在白名单内；</li>
 *   <li>解析域名后拒绝私有网段（防 SSRF）。</li>
 * </ul>
 * 性能：使用 Caffeine 对 keyword 做本地缓存，避免重复请求。
 */
@Slf4j
@Component
public class ImageSearchTool {

    /** 允许的图床域名白名单。 */
    private static final Set<String> HOST_WHITELIST = Set.of(
            "images.unsplash.com",
            "images.pexels.com",
            "source.unsplash.com",
            "loremflickr.com",
            // pexels 实际 CDN
            "images.pexels.com.cn",
            "picsum.photos",
            "fastly.picsum.photos",
            "i.picsum.photos"
    );

    private final ImageProperties properties;

    private Cache<String, String> cache;

    @Autowired
    public ImageSearchTool(ImageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getReplace().getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
    }

    /**
     * 根据关键词搜索图片，返回安全可用的 https URL。
     *
     * @param keyword 英文关键词
     * @return 图片 URL（必定非空，最终兜底 picsum）
     */
    public String search(String keyword) {
        String key = StrUtil.isBlank(keyword) ? "default" : keyword.trim().toLowerCase();
        return cache.get(key, this::doSearch);
    }

    private String doSearch(String keyword) {
        String url;
        // 1) Unsplash
        url = searchUnsplash(keyword);
        if (isSafe(url)) {
            return url;
        }
        // 2) Pexels
        url = searchPexels(keyword);
        if (isSafe(url)) {
            return url;
        }
        // 3) picsum 兜底（保证不留 @@IMG:xxx@@ 残留）
        return picsumFallback(keyword);
    }

    private String searchUnsplash(String keyword) {
        String accessKey = properties.getUnsplash().getAccessKey();
        if (StrUtil.isBlank(accessKey)) {
            log.debug("Unsplash access key 未配置，跳过");
            return null;
        }
        String api = properties.getUnsplash().getBaseUrl() + "/search/photos?per_page=1&query=" +
                cn.hutool.core.net.URLEncodeUtil.encodeQuery(keyword);
        try (HttpResponse resp = HttpRequest.get(api)
                .header("Authorization", "Client-ID " + accessKey)
                .header("Accept-Version", "v1")
                .timeout(properties.getUnsplash().getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("Unsplash 搜图失败 status={} keyword={}", resp.getStatus(), keyword);
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray results = json.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            JSONObject urls = results.getJSONObject(0).getJSONObject("urls");
            return urls == null ? null : urls.getStr("regular");
        } catch (Exception e) {
            log.warn("Unsplash 搜图异常 keyword={} msg={}", keyword, e.getMessage());
            return null;
        }
    }

    private String searchPexels(String keyword) {
        String apiKey = properties.getPexels().getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            log.debug("Pexels api key 未配置，跳过");
            return null;
        }
        String api = properties.getPexels().getBaseUrl() + "/search?per_page=1&query=" +
                cn.hutool.core.net.URLEncodeUtil.encodeQuery(keyword);
        try (HttpResponse resp = HttpRequest.get(api)
                .header("Authorization", apiKey)
                .timeout(properties.getPexels().getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("Pexels 搜图失败 status={} keyword={}", resp.getStatus(), keyword);
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray photos = json.getJSONArray("photos");
            if (photos == null || photos.isEmpty()) {
                return null;
            }
            JSONObject src = photos.getJSONObject(0).getJSONObject("src");
            return src == null ? null : src.getStr("large");
        } catch (Exception e) {
            log.warn("Pexels 搜图异常 keyword={} msg={}", keyword, e.getMessage());
            return null;
        }
    }

    private String picsumFallback(String keyword) {
        // 使用 keyword 哈希作为 seed，保证同关键词稳定返回同张图
        String seed = Integer.toHexString(keyword.hashCode());
        return properties.getFallback().getPicsumUrl().replace("{seed}", seed);
    }

    /**
     * 校验 URL：必须为 https，域名在白名单内，且解析后非私有网段。
     */
    boolean isSafe(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        if (!HOST_WHITELIST.contains(host.toLowerCase())) {
            log.warn("图片域名不在白名单内: {}", host);
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (isPrivateAddress(addr)) {
                log.warn("拒绝私有网段图片 URL: {} -> {}", host, addr.getHostAddress());
                return false;
            }
        } catch (UnknownHostException e) {
            log.warn("解析图片域名失败: {}", host);
            return false;
        }
        return true;
    }

    private boolean isPrivateAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress();
    }
}
