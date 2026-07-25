package com.yu.yuaifather.config;

import com.yu.yuaifather.model.enums.GenerationMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 生成体系配置。绑定 {@code application.yml} 的 {@code generation.*}。
 * <p>所有项均内置合理默认值，未在 yml 配置时仍可工作（yml 完整配置在后续阶段补齐）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "generation")
public class GenerationProperties {

    /** 链路级配置。 */
    private Pipeline pipeline = new Pipeline();

    /** 各模式配置，key 为模式 value（low_cost/standard/high_quality）。 */
    private Map<String, ModeConfig> modes = new HashMap<>();

    /** 各套餐配置，key 为套餐名（FREE/PRO/...）。 */
    private Map<String, EntitlementConfig> entitlements = new HashMap<>();

    @Data
    public static class Pipeline {
        /** 首选链路。 */
        private String preferred = "V2";
        /** 是否允许降级 Classic。 */
        private boolean allowClassicFallback = true;
    }

    @Data
    public static class ModeConfig {
        private String planningModel;
        private String generateModel;
        private String criticModel;
        private boolean enableCoderStyleParallel;
        private boolean enableLlmCritic;
        /** Critic 触发策略：on_rule_failure / always / never。 */
        private String criticTrigger = "never";
        private int maxCriticRounds;
        private int maxEvidenceCount = 1;
        private long timeoutMs = 180000L;
    }

    @Data
    public static class EntitlementConfig {
        /** 默认模式 value。 */
        private String defaultMode;
        /** 允许的最高模式 value（封顶）。 */
        private String maxMode;
        private Integer dailyLowCostLimit;
        private Integer dailyStandardLimit;
        private Integer dailyHighQualityLimit;
    }

    /**
     * 取模式配置，未配置时返回内置默认值，保证编排可用。
     */
    public ModeConfig getModeOrDefault(GenerationMode mode) {
        ModeConfig cfg = modes.get(mode.getValue());
        if (cfg != null) {
            return cfg;
        }
        return defaultModeConfig(mode);
    }

    private ModeConfig defaultModeConfig(GenerationMode mode) {
        ModeConfig cfg = new ModeConfig();
        switch (mode) {
            case LOW_COST -> {
                cfg.setEnableCoderStyleParallel(false);
                cfg.setEnableLlmCritic(false);
                cfg.setCriticTrigger("never");
                cfg.setMaxCriticRounds(0);
                cfg.setMaxEvidenceCount(1);
                cfg.setTimeoutMs(180000L);
            }
            case STANDARD -> {
                cfg.setEnableCoderStyleParallel(true);
                cfg.setEnableLlmCritic(true);
                cfg.setCriticTrigger("on_rule_failure");
                cfg.setMaxCriticRounds(1);
                cfg.setMaxEvidenceCount(3);
                cfg.setTimeoutMs(300000L);
            }
            case HIGH_QUALITY -> {
                cfg.setEnableCoderStyleParallel(true);
                cfg.setEnableLlmCritic(true);
                cfg.setCriticTrigger("always");
                cfg.setMaxCriticRounds(3);
                cfg.setMaxEvidenceCount(5);
                cfg.setTimeoutMs(600000L);
            }
            default -> { /* keep defaults */ }
        }
        return cfg;
    }

    /**
     * 取套餐配置，未配置时按套餐返回内置默认值。
     */
    public EntitlementConfig getEntitlementOrDefault(String planName) {
        EntitlementConfig cfg = entitlements.get(planName);
        if (cfg != null) {
            return cfg;
        }
        return defaultEntitlement(planName);
    }

    private EntitlementConfig defaultEntitlement(String planName) {
        EntitlementConfig cfg = new EntitlementConfig();
        switch (planName == null ? "FREE" : planName.toUpperCase()) {
            case "PRO" -> {
                cfg.setDefaultMode("standard");
                cfg.setMaxMode("standard");
                cfg.setDailyStandardLimit(50);
            }
            case "PREMIUM" -> {
                cfg.setDefaultMode("standard");
                cfg.setMaxMode("high_quality");
                cfg.setDailyStandardLimit(100);
                cfg.setDailyHighQualityLimit(10);
            }
            case "ENTERPRISE" -> {
                cfg.setDefaultMode("high_quality");
                cfg.setMaxMode("high_quality");
                cfg.setDailyHighQualityLimit(100);
            }
            case "ADMIN" -> {
                cfg.setDefaultMode("high_quality");
                cfg.setMaxMode("high_quality");
            }
            default -> {
                // FREE
                cfg.setDefaultMode("low_cost");
                cfg.setMaxMode("low_cost");
                cfg.setDailyLowCostLimit(10);
            }
        }
        return cfg;
    }
}
