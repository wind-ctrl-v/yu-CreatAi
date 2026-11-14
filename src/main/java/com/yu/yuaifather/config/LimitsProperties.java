package com.yu.yuaifather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 并发保护配置。绑定 {@code app.limits.*}，按 key 限制同时执行数。
 * <p>
 * 例：{@code app.limits.generation.max-concurrent=2}、{@code app.limits.deploy.max-concurrent=1}。
 * 默认 0 = 不限制，保持改造前行为。
 * <p>
 * 注意：前缀必须是 {@code app}（而不是 {@code app.limits}），这样字段 {@link #limits}
 * 才能接到 {@code app.limits.<key>.max-concurrent} 的 map 键上；{@code app} 下的其它配置项
 * （deploy/screenshot）不在本类声明，由 Spring Boot 忽略未知字段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class LimitsProperties {

    /** 限制 key -> 并发上限。 */
    private Map<String, Limit> limits = new HashMap<>();

    @Data
    public static class Limit {
        /** 最大并发数，0=不限制。 */
        private int maxConcurrent = 0;
    }
}
