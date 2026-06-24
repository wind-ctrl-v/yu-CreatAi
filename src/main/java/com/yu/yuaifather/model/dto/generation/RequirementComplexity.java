package com.yu.yuaifather.model.dto.generation;

/**
 * 需求复杂度。由 {@code RequirementComplexityService} 基于用户消息与代码生成类型启发式评估，
 * 用于在套餐允许的上限内动态升降级生成模式。
 */
public enum RequirementComplexity {
    /** 简单修改，如改颜色、改标题。 */
    SIMPLE,
    /** 普通需求，如生成单页官网。 */
    NORMAL,
    /** 复杂需求，如完整多页面项目、响应式、复杂动效。 */
    COMPLEX
}
