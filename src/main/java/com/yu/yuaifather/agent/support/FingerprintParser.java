package com.yu.yuaifather.agent.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FingerprintParser：六层指纹的解析、schema 校验、置信度计算与去重签名（阶段二）。
 * <p>
 * 职责（计划 2.7 / 1.6.0 / 2.2.1）：
 * <ul>
 *   <li>把 LLM 输出的原始文本解析为 {@link DesignFingerprintDTO}（复用 {@link JsonExtractor} 容错）；</li>
 *   <li>schema 校验：六层核心字段是否齐全、类型是否合法；</li>
 *   <li>按 1.6.0 客观信号计算 {@code confidence}（<b>不</b>由 LLM 自报）；</li>
 *   <li>生成稳定去重签名；</li>
 *   <li>版本路由：仅支持 {@code v1}，无法识别的高版本跳过（返回 null），不报错阻断。</li>
 * </ul>
 * confidence 公式集中在本类，便于统一调参与回归。
 */
@Slf4j
@Component
public class FingerprintParser {

    /** 当前支持的 schema 版本。 */
    public static final String SUPPORTED_SCHEMA_VERSION = "v1";

    // 1.6.0 权重
    private static final double W_SCHEMA_COMPLETE = 0.3;
    private static final double W_FIELD_NONEMPTY = 0.3;
    private static final double W_ANTIPATTERN = 0.2;
    private static final double W_SOURCE_TRUST = 0.2;

    /**
     * 解析原始 LLM 输出为指纹 DTO，并补齐 schemaVersion / sourceType 默认值。
     *
     * @return 解析成功的 DTO；解析失败抛出异常由调用方处理
     */
    public DesignFingerprintDTO parse(String raw) {
        DesignFingerprintDTO dto = JsonExtractor.parse(raw, DesignFingerprintDTO.class);
        if (StrUtil.isBlank(dto.getSchemaVersion())) {
            dto.setSchemaVersion(SUPPORTED_SCHEMA_VERSION);
        }
        if (StrUtil.isBlank(dto.getSourceType())) {
            dto.setSourceType("EXTRACTED_FINGERPRINT");
        }
        return dto;
    }

    /**
     * 版本路由：是否为当前可识别的 schema 版本。
     * 无法识别的高版本应跳过（不报错，呼应 2.2.1「召回兜底」）。
     */
    public boolean isSupportedVersion(DesignFingerprintDTO dto) {
        return dto != null && SUPPORTED_SCHEMA_VERSION.equalsIgnoreCase(dto.getSchemaVersion());
    }

    /**
     * schema 校验：六层核心字段是否齐全、类型合法。
     *
     * @return 校验结果，含失败原因
     */
    public Validation validate(DesignFingerprintDTO dto) {
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            errors.add("指纹为空");
            return new Validation(false, errors);
        }
        if (StrUtil.isBlank(dto.getStyleProfile())) {
            errors.add("缺少 styleProfile");
        }
        if (dto.getTokens() == null) {
            errors.add("缺少 tokens 层");
        }
        if (dto.getLayout() == null || StrUtil.isBlank(dto.getLayout().getSkeleton())) {
            errors.add("缺少 layout.skeleton");
        }
        if (dto.getComponents() == null || dto.getComponents().isEmpty()) {
            errors.add("缺少 components 层");
        }
        if (dto.getInteraction() == null) {
            errors.add("缺少 interaction 层");
        }
        if (dto.getAntiPatterns() == null) {
            errors.add("缺少 antiPatterns 层");
        }
        return new Validation(errors.isEmpty(), errors);
    }

    /**
     * 按 1.6.0 客观信号计算置信度（0~1）。
     * <ul>
     *   <li>schema 完整度（0.3）：六层必填字段是否齐全；</li>
     *   <li>字段非空率（0.3）：tokens / layout / components 等关键字段的非空比例；</li>
     *   <li>反模式覆盖（0.2）：是否记录了负样本（antiPatterns 非空为正向信号）；</li>
     *   <li>来源可信度（0.2）：人工精选 > 自产反哺（由 trusted 入参体现）。</li>
     * </ul>
     *
     * @param dto     指纹
     * @param trusted 是否来自可信来源（人工精选 true / 自产反哺 false）
     */
    public double computeConfidence(DesignFingerprintDTO dto, boolean trusted) {
        if (dto == null) {
            return 0;
        }
        double schemaComplete = validate(dto).isValid() ? 1.0 : 0.5;

        int total = 6;
        int filled = 0;
        if (dto.getTokens() != null) filled++;
        if (dto.getLayout() != null && StrUtil.isNotBlank(dto.getLayout().getSkeleton())) filled++;
        if (dto.getComponents() != null && !dto.getComponents().isEmpty()) filled++;
        if (dto.getInteraction() != null) filled++;
        if (dto.getAntiPatterns() != null && !dto.getAntiPatterns().isEmpty()) filled++;
        if (dto.getTone() != null && !dto.getTone().isEmpty()) filled++;
        double nonEmptyRate = (double) filled / total;

        double antiPatternSignal = (dto.getAntiPatterns() != null && !dto.getAntiPatterns().isEmpty()) ? 1.0 : 0.3;
        double sourceTrust = trusted ? 1.0 : 0.6;

        double score = W_SCHEMA_COMPLETE * schemaComplete
                + W_FIELD_NONEMPTY * nonEmptyRate
                + W_ANTIPATTERN * antiPatternSignal
                + W_SOURCE_TRUST * sourceTrust;
        // 收敛到 [0,1]
        return Math.max(0, Math.min(1, Math.round(score * 100) / 100.0));
    }

    /**
     * 生成稳定去重签名：styleProfile + layout.skeleton + token signature + components。
     * 用于精确去重与近邻相似度比较的基线。
     */
    public String signature(DesignFingerprintDTO dto) {
        if (dto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.nullToEmpty(dto.getStyleProfile())).append('|');
        if (dto.getLayout() != null) {
            sb.append(StrUtil.nullToEmpty(dto.getLayout().getSkeleton())).append('|');
        }
        if (dto.getTokens() != null) {
            DesignFingerprintDTO.Tokens t = dto.getTokens();
            sb.append(StrUtil.nullToEmpty(t.getColorRatio())).append('/')
                    .append(StrUtil.nullToEmpty(t.getRadii())).append('/')
                    .append(StrUtil.nullToEmpty(t.getShadow())).append('|');
        }
        if (dto.getComponents() != null) {
            List<String> comps = new ArrayList<>(dto.getComponents());
            comps.sort(String::compareTo);
            sb.append(String.join(",", comps));
        }
        return SecureUtil.md5(sb.toString());
    }

    /**
     * 校验结果。
     */
    @Getter
    public static class Validation {
        private final boolean valid;
        private final List<String> errors;

        public Validation(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }
    }
}
