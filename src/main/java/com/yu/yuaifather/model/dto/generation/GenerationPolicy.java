package com.yu.yuaifather.model.dto.generation;

import com.yu.yuaifather.model.enums.GenerationMode;
import com.yu.yuaifather.model.enums.PipelineType;
import lombok.Builder;
import lombok.Value;

/**
 * 生成策略（不可变）。
 * <p>由 {@code GenerationModeResolver} 综合用户身份、套餐、额度、需求复杂度与前端意图算出，
 * 是 {@code GenerationPipelineRouter} 与 {@code GenerationOrchestrator} 唯一消费的决策对象。</p>
 *
 * <p><b>核心原则</b>：身份/额度判断只在 Resolver 收敛，编排器只按本对象执行，不再做任何身份判断。
 * 对象不可变，可安全跨线程（响应式流）传递。</p>
 */
@Value
@Builder
public class GenerationPolicy {

    /** 首选链路（通常为 V2）。 */
    PipelineType preferredPipeline;

    /** 最终生成模式（已封顶 + 复杂度调整 + 额度限制后的结果）。 */
    GenerationMode generationMode;

    /** 是否启用 V2 主链路。 */
    boolean v2Enabled;

    /** 是否允许降级到经典链路兜底。 */
    boolean allowClassicFallback;

    /** 是否启用 LLM Critic（非纯规则检查）。 */
    boolean enableLlmCritic;

    /** 是否启用 CoderAgent ∥ StyleAgent 并发拆分。 */
    boolean enableCoderStyleParallel;

    /** Critic 最大轮次（0 表示不做 LLM Critic 轮次）。 */
    int maxCriticRounds;

    /** 注入的设计 evidence 最大条数。 */
    int maxEvidenceCount;

    /** 规划模型 key。 */
    String planningModel;

    /** 生成模型 key。 */
    String generateModel;

    /** Critic 模型 key。 */
    String criticModel;

    /** 整体超时（毫秒）。 */
    long timeoutMs;

    /** 用户前端请求的原始意图模式（不可信，仅用于可观测/记录）。 */
    String requestedModeRaw;
}
