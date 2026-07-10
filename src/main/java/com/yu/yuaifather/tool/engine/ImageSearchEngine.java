package com.yu.yuaifather.tool.engine;

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
 * 图片搜索引擎：Pixabay → Unsplash → Pexels → picsum 兜底。
 * <p>
 * 原名 ImageSearchTool，重命名为 ImageSearchEngine 以区别于 tool/system/ImageSearchTool（System Tool 包装层）。
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
public class ImageSearchEngine {

    private static final Set<String> HOST_WHITELIST = Set.of(
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

    private final ImageProperties properties;

    private Cache<String, String> cache;

    @Autowired
    public ImageSearchEngine(ImageProperties properties) {
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
        url = searchPixabay(keyword);
        if (isSafe(url)) {
            return url;
        }
        url = searchUnsplash(keyword);
        if (isSafe(url)) {
            return url;
        }
        url = searchPexels(keyword);
        if (isSafe(url)) {
            return url;
        }
        return picsumFallback(keyword);
    }

    /**
     * Pixabay 搜图（首选源）。
     * <p>
     * 注意：key 作为 URL 查询参数传递；per_page 最小值为 3，取首条结果。
     */
    private String searchPixabay(String keyword) {
        String apiKey = properties.getPixabay().getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            log.debug("Pixabay api key 未配置，跳过");
            return null;
        }
        String api = properties.getPixabay().getBaseUrl()
                + "/?key=" + cn.hutool.core.net.URLEncodeUtil.encodeQuery(apiKey)
                + "&image_type=photo&safesearch=true&per_page=3&q="
                + cn.hutool.core.net.URLEncodeUtil.encodeQuery(keyword);
        try (HttpResponse resp = HttpRequest.get(api)
                .timeout(properties.getPixabay().getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("Pixabay 搜图失败 status={} keyword={}", resp.getStatus(), keyword);
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray hits = json.getJSONArray("hits");
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            JSONObject hit = hits.getJSONObject(0);
            // 优先大图，回退到 webformat
            String url = hit.getStr("largeImageURL");
            return StrUtil.isBlank(url) ? hit.getStr("webformatURL") : url;
        } catch (Exception e) {
            log.warn("Pixabay 搜图异常 keyword={} msg={}", keyword, e.getMessage());
            return null;
        }
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
        String seed = Integer.toHexString(keyword.hashCode());
        return properties.getFallback().getPicsumUrl().replace("{seed}", seed);
    }

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
