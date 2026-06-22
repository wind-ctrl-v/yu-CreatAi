package com.yu.yuaifather.service.design;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FingerprintProjector：六层指纹 → {@link DesignSpec} 的投影转换（阶段二，见计划 2.4）。
 */
@Component
public class FingerprintProjector {

    private static final int FINGERPRINT_BASE_PRIORITY = 30;

    public DesignSpec project(DesignFingerprintDTO dto, String sourceId) {
        if (dto == null) {
            return null;
        }
        return DesignSpec.builder()
                .schemaVersion(StrUtil.blankToDefault(dto.getSchemaVersion(), "v1"))
                .styleProfile(dto.getStyleProfile())
                .sourceType("EXTRACTED_FINGERPRINT")
                .sourceId(StrUtil.blankToDefault(sourceId, dto.getSourceId()))
                .confidence(dto.getConfidence())
                .priority(FINGERPRINT_BASE_PRIORITY)
                .tone(dto.getTone() == null ? new ArrayList<>() : new ArrayList<>(dto.getTone()))
                .tokenGroups(projectTokens(dto.getTokens()))
                .layoutPatterns(projectLayout(dto.getLayout()))
                .preferredPatterns(dto.getComponents() == null ? new ArrayList<>() : new ArrayList<>(dto.getComponents()))
                .interactionPatterns(projectInteraction(dto.getInteraction()))
                .forbiddenPatterns(dto.getAntiPatterns() == null ? new ArrayList<>() : new ArrayList<>(dto.getAntiPatterns()))
                .build();
    }

    private Map<String, Map<String, String>> projectTokens(DesignFingerprintDTO.Tokens tokens) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        if (tokens == null) {
            return groups;
        }
        Map<String, String> descriptor = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(tokens.getColorRatio())) {
            descriptor.put("colorRatio", tokens.getColorRatio());
        }
        if (tokens.getFontScale() != null && !tokens.getFontScale().isEmpty()) {
            descriptor.put("fontScale", String.join(", ", tokens.getFontScale()));
        }
        if (StrUtil.isNotBlank(tokens.getRadii())) {
            descriptor.put("radii", tokens.getRadii());
        }
        if (StrUtil.isNotBlank(tokens.getShadow())) {
            descriptor.put("shadow", tokens.getShadow());
        }
        if (!descriptor.isEmpty()) {
            groups.put("descriptor", descriptor);
        }
        return groups;
    }

    private List<String> projectLayout(DesignFingerprintDTO.Layout layout) {
        List<String> patterns = new ArrayList<>();
        if (layout == null) {
            return patterns;
        }
        if (StrUtil.isNotBlank(layout.getSkeleton())) {
            patterns.add("区块骨架：" + layout.getSkeleton());
        }
        if (StrUtil.isNotBlank(layout.getWhitespace())) {
            patterns.add("留白节奏：" + layout.getWhitespace());
        }
        if (StrUtil.isNotBlank(layout.getFocus())) {
            patterns.add("视觉重心：" + layout.getFocus());
        }
        return patterns;
    }

    private List<String> projectInteraction(DesignFingerprintDTO.Interaction interaction) {
        List<String> patterns = new ArrayList<>();
        if (interaction == null) {
            return patterns;
        }
        if (StrUtil.isNotBlank(interaction.getMotion())) {
            patterns.add("动效：" + interaction.getMotion());
        }
        if (StrUtil.isNotBlank(interaction.getTransition())) {
            patterns.add("过渡：" + interaction.getTransition());
        }
        return patterns;
    }
}
