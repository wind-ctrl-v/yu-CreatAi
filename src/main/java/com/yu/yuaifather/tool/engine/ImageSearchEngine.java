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

import java.util.concurrent.TimeUnit;

/**
 * 图片搜索引擎：Pixabay → Unsplash → Pexels → 文生图(可选) → loremflickr → picsum 兜底。
 * <p>
 * 由代码生成后的占位符回填器 ImagePlaceholderReplacer 调用，按关键词搜图。
 * 图床返回的地址（尤其是 Pixabay {@code /get/}）通常只有约 24 小时有效，过期后页面和封面截图都会裂图。
 * 命中的远程图片会先经 {@link SearchImageArchiver} 转存到本机静态目录，再写入生成代码。
 * <p>
 * 安全要点：
 * <ul>
 *   <li>远程地址必须为 https，且域名在白名单内；</li>
 *   <li>解析域名后拒绝私有网段（防 SSRF）；</li>
 *   <li>转存失败时不把会过期的外链写进页面，继续尝试下一个图源。</li>
 * </ul>
 * 性能：使用 Caffeine 对 keyword 做本地缓存，避免重复请求。只缓存已转存的高质量结果。
 */
@Slf4j
@Component
public class ImageSearchEngine {

    private final ImageProperties properties;
    private final TextToImageEngine textToImageEngine;
    private final SearchImageArchiver imageArchiver;

    private Cache<String, String> cache;

    // Pixabay 熔断：连续失败后短时间内跳过，避免网络不可达时每次搜图都等超时
    private static final int PIXABAY_FAILURE_THRESHOLD = 3;
    private static final long PIXABAY_CIRCUIT_OPEN_MS = 60_000;
    private final java.util.concurrent.atomic.AtomicInteger pixabayFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long pixabayCircuitOpenUntil = 0;

    @Autowired
    public ImageSearchEngine(ImageProperties properties,
                             TextToImageEngine textToImageEngine,
                             SearchImageArchiver imageArchiver) {
        this.properties = properties;
        this.textToImageEngine = textToImageEngine;
        this.imageArchiver = imageArchiver;
    }

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getReplace().getCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(1000)
                .build();
    }

    /**
     * 根据关键词搜索图片，优先返回已转存到本机静态目录的 URL。
     *
     * @param keyword 英文关键词
     * @return 图片 URL（必定非空；图床全部失败时仍返回 picsum 兜底）
     */
    public String search(String keyword) {
        String key = cleanKeyword(keyword);
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        SearchHit hit = doSearch(key);
        // 仅缓存高质量结果（真实搜图或文生图，且已经落到本机）。
        // loremflickr、picsum 属降级兜底，不缓存，以便后续请求重试真实图源。
        if (hit.cacheable() && StrUtil.isNotBlank(hit.url())) {
            cache.put(key, hit.url());
        }
        return hit.url();
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

    private SearchHit doSearch(String keyword) {
        SearchHit hit = host(searchPixabay(keyword), true);
        if (hit != null) {
            return hit;
        }
        hit = host(searchUnsplash(keyword), true);
        if (hit != null) {
            return hit;
        }
        hit = host(searchPexels(keyword), true);
        if (hit != null) {
            return hit;
        }
        // 文生图兜底（可选）：TextToImageEngine 自己转存到本机静态目录，不再走远程归档。
        String generated = textToImageEngine.generate(keyword);
        if (StrUtil.isNotBlank(generated)) {
            log.info("文生图兜底命中 keyword={}", keyword);
            return new SearchHit(generated, true);
        }
        // 语义兜底：先尝试转存。转存失败时不把会过期的外链写进页面，最后才退回 picsum 原地址。
        hit = host(loremflickrFallback(keyword), false);
        if (hit != null) {
            return hit;
        }
        hit = host(picsumFallback(keyword), false);
        if (hit != null) {
            return hit;
        }
        return new SearchHit(picsumFallback(keyword), false);
    }

    /**
     * 只接受白名单内的远程地址，并且必须成功转存到本机。转存失败返回 null，避免把临时外链写进生成代码。
     */
    SearchHit host(String remoteUrl, boolean cacheable) {
        if (!isSafe(remoteUrl)) {
            return null;
        }
        String localUrl = imageArchiver.archive(remoteUrl);
        if (StrUtil.isBlank(localUrl)) {
            log.warn("图片转存失败，改试下一个图源 host={}", hostOf(remoteUrl));
            return null;
        }
        return new SearchHit(localUrl, cacheable);
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }

    record SearchHit(String url, boolean cacheable) {
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
        boolean safe = ImageHostPolicy.isEmbeddable(url);
        if (!safe && StrUtil.isNotBlank(url)) {
            log.debug("拒绝不可嵌入的图片 URL");
        }
        return safe;
    }
}
