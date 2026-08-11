package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 项目品牌上下文 DTO（阶段三：项目品牌记忆）。
 * <p>
 * 由 ProjectProfileTool 加载项目品牌画像后组装，
 * 写入 Blackboard 供 DesignRetrieveTool、CoderAgent、StyleAgent 消费。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectBrandContext {

    /** 项目ID。 */
    private Long projectId;

    /** 品牌名称。 */
    private String brandName;

    /** 行业。 */
    private String industry;

    /** 目标用户群体。 */
    private String targetAudience;

    /** 品牌调性列表。 */
    private List<String> brandTone;

    /** 品牌颜色配置。 */
    private Map<String, String> brandColors;

    /** 排版偏好。 */
    private List<String> typographyPreference;

    /** 布局偏好。 */
    private List<String> layoutPreference;

    /** 组件偏好。 */
    private List<String> componentPreference;

    /** 设计指南。 */
    private List<String> designGuidelines;

    /** 禁用规则列表。 */
    private List<String> forbiddenRules;

    /** 品牌画像摘要。 */
    private String profileSummary;

    /**
     * 是否有效（项目有品牌画像）。
     */
    public boolean hasBrandProfile() {
        return hasText(brandName) || hasText(industry) || hasText(targetAudience) || hasText(profileSummary)
                || hasValues(brandTone) || hasValues(typographyPreference) || hasValues(layoutPreference)
                || hasValues(componentPreference) || hasValues(designGuidelines) || hasValues(forbiddenRules)
                || (brandColors != null && !brandColors.isEmpty());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasValues(List<String> values) {
        return values != null && !values.isEmpty();
    }

    /** 空上下文工厂方法。 */
    public static ProjectBrandContext empty() {
        return ProjectBrandContext.builder().build();
    }
}
