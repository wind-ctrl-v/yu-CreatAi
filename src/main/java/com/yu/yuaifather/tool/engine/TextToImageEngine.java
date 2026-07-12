package com.yu.yuaifather.tool.engine;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yu.yuaifather.config.ImageProperties;
import com.yu.yuaifather.manager.LocalImageStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 文生图引擎：调用 OpenAI 兼容的 {@code /images/generations} 生成图片，并转存到本地静态目录。
 * <p>
 * 作为搜图全部未命中时的语义兜底，彻底消除「找不到贴切图」的问题。
 * 仅在 {@code image.fallback.enabled=true} 且配置了 api-key 时生效。
 * <p>
 * 生成图片的第三方 URL 通常有时效，故下载后经 {@link LocalImageStorage} 落地为本机静态资源，
 * 由 {@code /api/static/**} 长期提供访问（本地存储形态，无需 COS）。
 * <p>
 * 安全要点（SSRF 防护）：
 * <ul>
 *   <li>下载生成图片前校验 URL 必须为 https；</li>
 *   <li>解析域名后拒绝私有网段；</li>
 *   <li>限制下载文件大小，防止超大响应打爆磁盘。</li>
 * </ul>
 */
@Slf4j
@Component
public class TextToImageEngine {

    private final ImageProperties properties;
    private final LocalImageStorage localImageStorage;

    @Autowired
    public TextToImageEngine(ImageProperties properties, LocalImageStorage localImageStorage) {
        this.properties = properties;
        this.localImageStorage = localImageStorage;
    }

    /**
     * 根据关键词生成图片并转存本地静态目录，返回可访问 URL；不可用或失败返回 null。
     *
     * @param keyword 已清洗的英文关键词
     */
    public String generate(String keyword) {
        ImageProperties.Fallback f = properties.getFallback();
        if (!f.isEnabled()) {
            return null;
        }
        if (StrUtil.isBlank(f.getApiKey()) || StrUtil.isBlank(f.getBaseUrl())) {
            log.debug("文生图兜底已启用但未配置 api-key/base-url，跳过");
            return null;
        }
        if (StrUtil.isBlank(keyword) || "default".equals(keyword)) {
            return null;
        }
        String remoteUrl = requestGeneration(keyword, f);
        if (StrUtil.isBlank(remoteUrl)) {
            return null;
        }
        return transferToLocal(remoteUrl, f);
    }

    /**
     * 调用 OpenAI 兼容图片生成接口，返回远端图片 URL。
     */
    private String requestGeneration(String keyword, ImageProperties.Fallback f) {
        String base = f.getBaseUrl().endsWith("/") ? f.getBaseUrl() : f.getBaseUrl() + "/";
        String api = base + "images/generations";
        String prompt = buildPrompt(keyword);
        JSONObject body = new JSONObject()
                .set("model", f.getModel())
                .set("prompt", prompt)
                .set("size", f.getSize());
        try (HttpResponse resp = HttpRequest.post(api)
                .header("Authorization", "Bearer " + f.getApiKey())
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(f.getTimeoutMs())
                .execute()) {
            if (!resp.isOk()) {
                log.warn("文生图请求失败 status={} keyword={}", resp.getStatus(), keyword);
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.warn("文生图返回空 data keyword={}", keyword);
                return null;
            }
            String url = data.getJSONObject(0).getStr("url");
            if (StrUtil.isBlank(url)) {
                log.warn("文生图返回未包含 url keyword={}", keyword);
                return null;
            }
            return url;
        } catch (Exception e) {
            log.warn("文生图异常 keyword={} msg={}", keyword, e.getMessage());
            return null;
        }
    }

    /**
     * 将生成图片下载后转存本地静态目录，返回可访问 URL。
     */
    private String transferToLocal(String remoteUrl, ImageProperties.Fallback f) {
        if (!isSafeRemote(remoteUrl)) {
            return null;
        }
        File temp = null;
        try {
            temp = File.createTempFile("t2i_", ".png");
            long size = downloadWithLimit(remoteUrl, temp, f.getMaxBytes(), f.getTimeoutMs());
            if (size <= 0) {
                return null;
            }
            return localImageStorage.save(temp, "png");
        } catch (Exception e) {
            log.warn("文生图转存本地失败 url={} msg={}", remoteUrl, e.getMessage());
            return null;
        } finally {
            if (temp != null) {
                FileUtil.del(temp);
            }
        }
    }

    /**
     * 带大小上限的下载，超限即中止并删除，返回写入字节数（失败返回 -1）。
     */
    private long downloadWithLimit(String url, File dest, long maxBytes, int timeoutMs) {
        try (HttpResponse resp = HttpRequest.get(url).timeout(timeoutMs).executeAsync()) {
            if (!resp.isOk()) {
                log.warn("下载生成图片失败 status={} url={}", resp.getStatus(), url);
                return -1;
            }
            try (java.io.InputStream in = resp.bodyStream();
                 java.io.OutputStream out = FileUtil.getOutputStream(dest)) {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        log.warn("生成图片超过大小上限 {} bytes，已中止 url={}", maxBytes, url);
                        return -1;
                    }
                    out.write(buf, 0, n);
                }
                return total;
            }
        } catch (Exception e) {
            log.warn("下载生成图片异常 url={} msg={}", url, e.getMessage());
            return -1;
        }
    }

    /**
     * 把英文关键词扩展为更完整的文生图提示词，提高画面质量与语义贴合度。
     */
    private String buildPrompt(String keyword) {
        String k = keyword.toLowerCase();
        if (k.contains("dashboard") || k.contains("ui ") || k.contains("interface") || k.contains("admin") || k.contains("panel")) {
            return keyword + ", modern UI design, clean interface, professional dashboard, high quality, screenshot, 4k";
        }
        if (k.contains("product") || k.contains("device") || k.contains("phone") || k.contains("laptop") || k.contains("bottle") || k.contains("package") || k.contains("shoe")) {
            return keyword + ", product photography, studio lighting, clean white background, high detail, professional, 4k";
        }
        if (k.contains("person") || k.contains("team") || k.contains("people") || k.contains("portrait") || k.contains("avatar") || k.contains("user")) {
            return keyword + ", professional portrait, natural lighting, high quality, realistic, sharp focus";
        }
        if (k.contains("landscape") || k.contains("nature") || k.contains("city") || k.contains("building") || k.contains("mountain") || k.contains("sky")) {
            return keyword + ", landscape photography, golden hour, high resolution, scenic, professional, 4k";
        }
        if (k.contains("food") || k.contains("cake") || k.contains("coffee") || k.contains("dish") || k.contains("restaurant") || k.contains("fruit")) {
            return keyword + ", food photography, appetizing, warm lighting, shallow depth of field, professional, top-down";
        }
        if (k.contains("office") || k.contains("workspace") || k.contains("room") || k.contains("interior")) {
            return keyword + ", interior photography, wide angle, natural light, professional, architectural, 4k";
        }
        return keyword + ", high quality, professional, clean composition, realistic lighting, 4k";
    }

    /**
     * SSRF 校验：仅允许 https，且解析后非私有网段。
     */
    private boolean isSafeRemote(String url) {
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
            log.warn("拒绝非 https 的生成图片 URL: {}", url);
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                log.warn("拒绝私有网段生成图片 URL: {} -> {}", host, addr.getHostAddress());
                return false;
            }
        } catch (UnknownHostException e) {
            log.warn("解析生成图片域名失败: {}", host);
            return false;
        }
        return true;
    }
}
