package com.yu.yuaifather.service.design;

import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import lombok.Builder;
import lombok.Data;

/**
 * DesignContextAssembler 输入参数。
 * <p>
 * ★ 包结构说明：本类原位于 {@code orchestrator} 包，现迁移到 {@code service.design}
 * （见包结构优化计划问题 3）。
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

    /** 注入的设计 evidence 最大条数（由 GenerationPolicy 决定，0 表示用装配器默认值）。 */
    private int maxEvidenceCount;

    /** 是否精简上下文（low_cost 模式下减少 token 占用）。 */
    private boolean compactContext;

    /** 是否注入完整项目品牌画像（high_quality 模式开启）。 */
    private boolean fullProjectProfile;
}
