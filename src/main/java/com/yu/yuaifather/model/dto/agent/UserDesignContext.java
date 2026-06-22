package com.yu.yuaifather.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 用户设计上下文 DTO（阶段一：用户偏好闭环）。
 * <p>
 * 由 UserPreferenceTool 加载用户偏好数据后组装，
 * 写入 Blackboard 供 DesignRetrieveTool、CoderAgent、StyleAgent 消费。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDesignContext {

    /** 用户ID。 */
    private Long userId;

    /** 用户偏好摘要（自然语言）。 */
    private String profileSummary;

    /** 偏好的设计风格列表。 */
    private List<String> preferredStyles;

    /** 不喜欢的风格列表。 */
    private List<String> dislikedStyles;

    /** 偏好的颜色系列。 */
    private List<String> preferredColors;

    /** 不喜欢的颜色系列。 */
    private List<String> dislikedColors;

    /** 偏好的布局类型。 */
    private List<String> preferredLayouts;

    /** 不喜欢的布局类型。 */
    private List<String> dislikedLayouts;

    /** 偏好的组件类型。 */
    private List<String> preferredComponents;

    /** 不喜欢的组件类型。 */
    private List<String> dislikedComponents;

    /** 偏好的交互模式。 */
    private List<String> preferredInteractions;

    /** 不喜欢的交互模式。 */
    private List<String> dislikedInteractions;

    /**
     * 偏好权重表，key 为 weightKey（如 "style:minimal", "color:blue"），value 为权重。
     * 正值为喜欢，负值为反感。
     */
    private Map<String, Double> preferenceWeights;

    /**
     * 是否有效（用户有实际偏好数据）。
     */
    public boolean hasPreferences() {
        return (preferredStyles != null && !preferredStyles.isEmpty())
                || (preferredColors != null && !preferredColors.isEmpty())
                || (preferredComponents != null && !preferredComponents.isEmpty())
                || (preferenceWeights != null && !preferenceWeights.isEmpty());
    }

    /** 空上下文工厂方法。 */
    public static UserDesignContext empty() {
        return UserDesignContext.builder().build();
    }
}
