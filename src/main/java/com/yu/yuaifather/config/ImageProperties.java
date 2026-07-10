package com.yu.yuaifather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 图片服务相关配置。
 * <p>
 * 所有密钥仅通过环境变量注入，禁止在 yml 中明文写入。
 */
@Configuration
@ConfigurationProperties(prefix = "image")
@Data
public class ImageProperties {

    private Pixabay pixabay = new Pixabay();
    private Unsplash unsplash = new Unsplash();
    private Pexels pexels = new Pexels();
    private Fallback fallback = new Fallback();
    private Replace replace = new Replace();

    @Data
    public static class Pixabay {
        /** API key（通过环境变量 PIXABAY_API_KEY 注入）。 */
        private String apiKey;
        private String baseUrl = "https://pixabay.com/api";
        /** 请求超时（毫秒）。 */
        private int timeoutMs = 5000;
    }

    @Data
    public static class Unsplash {
        /** 访问 key（通过环境变量 UNSPLASH_ACCESS_KEY 注入）。 */
        private String accessKey;
        private String baseUrl = "https://api.unsplash.com";
        /** 请求超时（毫秒）。 */
        private int timeoutMs = 5000;
    }

    @Data
    public static class Pexels {
        /** API key（通过环境变量 PEXELS_API_KEY 注入）。 */
        private String apiKey;
        private String baseUrl = "https://api.pexels.com/v1";
        private int timeoutMs = 5000;
    }

    @Data
    public static class Fallback {
        /** 搜图无结果时是否启用文生图兜底。 */
        private boolean enabled = false;
        /** 最终兜底图（picsum），确保不留占位符残留。 */
        private String picsumUrl = "https://picsum.photos/seed/{seed}/1200/800";
    }

    @Data
    public static class Replace {
        /** 占位符替换并发度。 */
        private int concurrency = 4;
        /** 单 keyword 搜图缓存时长（秒），默认 1 小时。 */
        private int cacheTtlSeconds = 3600;
    }
}
