package com.yu.yuaifather.orchestrator;

import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import lombok.Builder;
import lombok.Data;

/**
 * DesignContextAssembler 输入参数。
 */
@Data
@Builder
public class DesignContextInput {

    /** 用户 ID。 */
    private Long userId;

    /** 项目 ID（当前架构中与 appId 一一对应）。 */
    private Long projectId;

    /** 需求文档（由 PlanningAgent 产出）。 */
    private RequirementDoc requirementDoc;

    /** 生成计划（由 PlanningAgent 产出）。 */
    private GenerationPlan generationPlan;

    /** 生成模式：standard / high_quality / low_cost。 */
    private String generationMode;
}
