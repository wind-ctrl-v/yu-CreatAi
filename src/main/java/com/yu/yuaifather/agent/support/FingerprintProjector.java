package com.yu.yuaifather.agent.support;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FingerprintProjector：六层指纹 → {@link DesignSpec} 的投影转换（阶段二，见计划 2.4）。
 * <p>
 * 六层指纹与 DesignSpec 不是字段级完全同构，而是<b>语义同构、可投影转换</b>：
 * <pre>
 *  tokens               → tokenGroups（描述性证据，非确切 --var 名契约）
 *  layout               → layoutPatterns
 *  components           → preferredPatterns
 *  interaction          → interactionPatterns
 *  antiPatterns         → forbiddenPatterns
 *  styleProfile + tone  → styleProfile / tone
 * </pre>
 *
 * <p>注意：指纹 {@code tokens} 是描述性维度（如「8px 统一圆角」），<b>不是</b>确切的
 * {@code --xxx: value} 变量名清单。因此投影出的 {@code tokenGroups} 仅作为运行时<b>参考证据</b>，
 * 真正的变量名强契约仍来自阶段一静态规范（见 1.5.1 #1）；阶段三注入时以静态规范的 tokenGroups 为准。
 */
@Component
public class FingerprintProjector {

    /** 指纹默认优先级：低于人工静态规范基线（50），保证默认人工先验优先（见 3.5）。 */
    private static final int FINGERPRINT_BASE_PRIORITY = 30;

    /**
     * 把指纹投影为 DesignSpec。
     *
     * @param dto      指纹 DTO
     * @param sourceId 来源标识（通常用指纹实体 id）
     */
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
            // 描述性证据组，与静态规范的 color/font/space 等确切变量名组区分
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
