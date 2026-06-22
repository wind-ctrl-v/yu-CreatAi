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

        double antiPatternSignal = (dto.getAntiPatterns() != null && !dto.getAntiPatterns().isEmpty()) ? 1.0 : 0.3;
        double sourceTrust = trusted ? 1.0 : 0.6;

        double score = W_SCHEMA_COMPLETE * schemaComplete
                + W_FIELD_NONEMPTY * nonEmptyRate
                + W_ANTIPATTERN * antiPatternSignal
                + W_SOURCE_TRUST * sourceTrust;
        return Math.max(0, Math.min(1, Math.round(score * 100) / 100.0));
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
