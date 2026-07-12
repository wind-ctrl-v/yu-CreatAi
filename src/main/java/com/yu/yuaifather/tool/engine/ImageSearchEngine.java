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
 * 图片搜索引擎：Pixabay → Unsplash → Pexels → 文生图(可选) → loremflickr → picsum 兜底。
 * <p>
 * 由代码生成后的占位符回填器 ImagePlaceholderReplacer 调用，按关键词搜图。
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
    private final TextToImageEngine textToImageEngine;

    private Cache<String, String> cache;

    // Pixabay 熔断：连续失败后短时间内跳过，避免网络不可达时每次搜图都等超时
    private static final int PIXABAY_FAILURE_THRESHOLD = 3;
    private static final long PIXABAY_CIRCUIT_OPEN_MS = 60_000;
    private final java.util.concurrent.atomic.AtomicInteger pixabayFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long pixabayCircuitOpenUntil = 0;

    @Autowired
    public ImageSearchEngine(ImageProperties properties, TextToImageEngine textToImageEngine) {
        this.properties = properties;
        this.textToImageEngine = textToImageEngine;
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
        String key = cleanKeyword(keyword);
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        String url = doSearch(key);
        // 仅缓存"高质"结果（真实搜图命中 / 文生图落地图）；
        // loremflickr、picsum 属降级兜底，不缓存，以便后续请求重试真实图源，避免图文不符被长期锁定。
        if (StrUtil.isNotBlank(url) && !isLowQualityFallback(url)) {
            cache.put(key, url);
        }
        return url;
    }

    /**
     * 判断是否为低质降级兜底（loremflickr / picsum）——这类结果不进缓存。
     */
    private boolean isLowQualityFallback(String url) {
        return url.contains("loremflickr.com") || url.contains("picsum.photos");
    }

    /**
     * 关键词清洗：仅保留英文字母、数字与空格，去除中文/标点/多余空白并转小写。
     * <p>防止中文或非法字符混入导致搜图 API 无结果、进而掉入随机兜底（图文不符主因之一）。
     */
    String cleanKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "default";
        }
        String cleaned = keyword.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return StrUtil.isBlank(cleaned) ? "default" : cleaned;
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
        // 文生图兜底（可选，需 image.fallback.enabled=true 且配置 api-key）：转存 COS 后返回自有域名 URL，可信直接使用
        url = textToImageEngine.generate(keyword);
        if (StrUtil.isNotBlank(url)) {
            log.info("文生图兜底命中 keyword={}", keyword);
            return url;
        }
        // 语义兜底：loremflickr 按关键词 tag 返回相关图片，避免 picsum 完全随机导致图文不符
        url = loremflickrFallback(keyword);
        if (isSafe(url)) {
            return url;
        }
        return picsumFallback(keyword);
    }

    /**
     * loremflickr 语义兜底：按关键词 tag 返回主题相关图片。
     * <p>使用 lock 参数固定 seed，保证同一关键词稳定复用同一张图。
     */
    private String loremflickrFallback(String keyword) {
        if (StrUtil.isBlank(keyword) || "default".equals(keyword)) {
            return null;
        }
        String tags = keyword.replace(" ", ",");
        int lock = Math.abs(keyword.hashCode()) % 1000 + 1;
        return "https://loremflickr.com/1200/800/"
                + cn.hutool.core.net.URLEncodeUtil.encodeQuery(tags) + "?lock=" + lock;
    }

    /**
     * Pixabay 搜图（首选源）。
     * <p>
     * 注意：key 作为 URL 查询参数传递；per_page 最小值为 3，取首条结果。
     */
    private String searchPixabay(String keyword) {
        // 熔断检查：连续失败后短时间内跳过，避免每次都等超时
        if (System.currentTimeMillis() < pixabayCircuitOpenUntil) {
            log.debug("Pixabay 熔断中，跳过");
            return null;
        }
        String apiKey = properties.getPixabay().getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            log.debug("Pixabay api key 未配置，跳过");
            return null;
        }
        String api = properties.getPixabay().getBaseUrl()
                + "/?key=" + cn.hutool.core.net.URLEncodeUtil.encodeQuery(apiKey)
                + "&image_type=photo&safesearch=true&per_page=5&order=popular&orientation=horizontal&q="
                + cn.hutool.core.net.URLEncodeUtil.encodeQuery(keyword);
        try (HttpResponse resp = HttpRequest.get(api)
                .timeout(properties.getPixabay().getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("Pixabay 搜图失败 status={} keyword={}", resp.getStatus(), keyword);
                onPixabayFailure();
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray hits = json.getJSONArray("hits");
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            JSONObject hit = hits.getJSONObject(0);
            String url = hit.getStr("largeImageURL");
            url = StrUtil.isBlank(url) ? hit.getStr("webformatURL") : url;
            if (StrUtil.isNotBlank(url)) {
                pixabayFailures.set(0);
            }
            return url;
        } catch (Exception e) {
            log.warn("Pixabay 搜图异常 keyword={} msg={}", keyword, e.getMessage());
            onPixabayFailure();
            return null;
        }
    }

    /** Pixabay 失败计数 + 熔断判定 */
    private void onPixabayFailure() {
        if (pixabayFailures.incrementAndGet() >= PIXABAY_FAILURE_THRESHOLD) {
            pixabayCircuitOpenUntil = System.currentTimeMillis() + PIXABAY_CIRCUIT_OPEN_MS;
            pixabayFailures.set(0);
            log.warn("Pixabay 连续失败达 {} 次，熔断 {} 秒", PIXABAY_FAILURE_THRESHOLD, PIXABAY_CIRCUIT_OPEN_MS / 1000);
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
