package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.utils.JsonExtractor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FingerprintParser：六层指纹的解析、schema 校验、置信度计算与去重签名（阶段二）。
 */
@Slf4j
@Component
public class FingerprintParser {

    public static final String SUPPORTED_SCHEMA_VERSION = "v1";

    private static final double W_SCHEMA_COMPLETE = 0.3;
    private static final double W_FIELD_NONEMPTY = 0.3;
    private static final double W_ANTIPATTERN = 0.2;
    private static final double W_SOURCE_TRUST = 0.2;

    /**
     * 系统禁用模式关键词清单（来自 {@link DesignLintChecker} 的 P_* 常量）。
     * <p>
     * 用于反模式质量判定：如果指纹的 antiPatterns 命中这些禁用模式，
     * 说明指纹本身就在描述系统禁止的写法，应严重扣分。
     */
    private static final List<String> SYSTEM_FORBIDDEN_PATTERNS = List.of(
            "backdrop-filter",
            "decorative-gradient",
            "hardcoded-px",
            "overscale-hover",
            "hardcoded-color",
            "dangling-var",
            "missing-global-style-import"
    );

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

    public boolean isSupportedVersion(DesignFingerprintDTO dto) {
        return dto != null && SUPPORTED_SCHEMA_VERSION.equalsIgnoreCase(dto.getSchemaVersion());
    }

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

        // ★ P0-残留-4 修复：antiPatternSignal → antiPatternQuality
        // 旧公式：有反模式=1.0，无=0.3（问题：鼓励 LLM 编造反模式刷分，且"无=粗糙"语义过强）
        // 新公式：区分"未填写/合法填写/命中禁用"三态
        double antiPatternQuality = calculateAntiPatternQuality(dto);
        double sourceTrust = trusted ? 1.0 : 0.6;

        double score = W_SCHEMA_COMPLETE * schemaComplete
                + W_FIELD_NONEMPTY * nonEmptyRate
                + W_ANTIPATTERN * antiPatternQuality
                + W_SOURCE_TRUST * sourceTrust;
        return Math.max(0, Math.min(1, Math.round(score * 100) / 100.0));
    }

    /**
     * 计算反模式质量分（P0-残留-4 修复）。
     * <p>
     * 三态判定：
     * <ul>
     *   <li>antiPatterns 为空/null → 0.7（提炼简单但合法，不惩罚也不奖励）</li>
     *   <li>antiPatterns 非空且不命中系统禁用模式 → 1.0（提炼细致，奖励）</li>
     *   <li>antiPatterns 命中系统禁用模式 → 0.2（指纹自身就有问题，严重扣分）</li>
     * </ul>
     */
    private double calculateAntiPatternQuality(DesignFingerprintDTO dto) {
        List<String> antiPatterns = dto.getAntiPatterns();
        if (antiPatterns == null || antiPatterns.isEmpty()) {
            return 0.7;  // 未填写反模式：视为提炼简单但合法
        }
        // 检查是否命中系统禁用模式
        boolean hitForbidden = antiPatterns.stream()
                .anyMatch(p -> p != null && SYSTEM_FORBIDDEN_PATTERNS.stream()
                        .anyMatch(f -> p.toLowerCase().contains(f.toLowerCase())));
        return hitForbidden ? 0.2 : 1.0;
    }

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
