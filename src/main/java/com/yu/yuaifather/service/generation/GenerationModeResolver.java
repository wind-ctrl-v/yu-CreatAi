package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.config.GenerationProperties;
import com.yu.yuaifather.model.dto.generation.GenerationPolicy;
import com.yu.yuaifather.model.dto.generation.RequirementComplexity;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.enums.GenerationMode;
import com.yu.yuaifather.model.enums.PipelineType;
import com.yu.yuaifather.model.enums.PlanType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 生成模式决策器。
 * <p>把"用户身份/套餐/额度/复杂度判断"统一收敛于此，产出不可变 {@link GenerationPolicy}。
 * 编排器只消费 policy，不再做任何身份判断。</p>
 *
 * <h3>决策顺序</h3>
 * <ol>
 *   <li>解析前端 {@code requestedMode}（<b>不可信意图</b>）；</li>
 *   <li>按套餐取默认模式与最高模式（{@code maxAllowedMode} 封顶）；</li>
 *   <li>按需求复杂度在上限内动态升/降级；</li>
 *   <li>按额度限制（阶段2 no-op）；</li>
 *   <li>读取配置组装 {@link GenerationPolicy}。</li>
 * </ol>
 */
@Slf4j
@Service
public class GenerationModeResolver {

    private final GenerationProperties properties;
    private final UserEntitlementService userEntitlementService;
    private final QuotaService quotaService;
    private final RequirementComplexityService complexityService;

    @Value("${agent.orchestrator.v2.enabled:false}")
    private boolean v2Enabled;

    public GenerationModeResolver(GenerationProperties properties,
                                  UserEntitlementService userEntitlementService,
                                  QuotaService quotaService,
                                  RequirementComplexityService complexityService) {
        this.properties = properties;
        this.userEntitlementService = userEntitlementService;
        this.quotaService = quotaService;
        this.complexityService = complexityService;
    }

    /**
     * 解析生成策略。
     *
     * @param requestedMode 前端请求模式（不可信，仅作上限内意图），可为 null
     * @param loginUser     当前登录用户
     * @param message       用户消息（用于复杂度分析）
     * @param codeGenType   代码生成类型
     * @return 不可变策略
     */
    public GenerationPolicy resolve(String requestedMode, User loginUser, String message, CodeGenTypeEnum codeGenType) {
        PlanType plan = userEntitlementService.getPlan(loginUser);
        GenerationProperties.EntitlementConfig ent = properties.getEntitlementOrDefault(plan.name());

        GenerationMode defaultMode = parseMode(ent.getDefaultMode(), GenerationMode.STANDARD);
        GenerationMode maxAllowedMode = parseMode(ent.getMaxMode(), defaultMode);

        // 1. 前端意图（不可信），无效则用套餐默认模式
        GenerationMode intended = GenerationMode.getEnumByValue(requestedMode);
        GenerationMode candidate = intended != null ? intended : defaultMode;

        // 2. 复杂度动态调整（仍受上限约束）
        RequirementComplexity complexity = complexityService.analyze(message, codeGenType);
        candidate = applyComplexity(candidate, complexity);

        // 3. 套餐封顶：最终模式不得超过 maxAllowedMode
        GenerationMode capped = GenerationMode.min(candidate, maxAllowedMode);

        // 4. 额度限制（阶段2 no-op）
        GenerationMode finalMode = quotaService.applyQuotaLimit(loginUser, capped);

        if (log.isDebugEnabled()) {
            log.debug("模式决策 plan={} requested={} default={} max={} complexity={} -> final={}",
                    plan, requestedMode, defaultMode, maxAllowedMode, complexity, finalMode);
        }

        return buildPolicy(finalMode, requestedMode);
    }

    /**
     * 复杂度调整：简单需求降到低成本；复杂需求升到高质量（后续仍由套餐封顶）。
     */
    private GenerationMode applyComplexity(GenerationMode candidate, RequirementComplexity complexity) {
        return switch (complexity) {
            case SIMPLE -> GenerationMode.LOW_COST;
            case COMPLEX -> GenerationMode.HIGH_QUALITY;
            case NORMAL -> candidate;
        };
    }

    /**
     * 读取配置组装策略。
     */
    private GenerationPolicy buildPolicy(GenerationMode mode, String requestedModeRaw) {
        GenerationProperties.ModeConfig cfg = properties.getModeOrDefault(mode);
        GenerationProperties.Pipeline pipeline = properties.getPipeline();

        return GenerationPolicy.builder()
                .preferredPipeline(parsePipeline(pipeline.getPreferred()))
                .generationMode(mode)
                .v2Enabled(v2Enabled)
                .allowV1Fallback(pipeline.isAllowV1Fallback())
                .allowClassicFallback(pipeline.isAllowClassicFallback())
                .enableLlmCritic(cfg.isEnableLlmCritic())
                .enableCoderStyleParallel(cfg.isEnableCoderStyleParallel())
                .maxCriticRounds(cfg.getMaxCriticRounds())
                .maxEvidenceCount(cfg.getMaxEvidenceCount())
                .planningModel(cfg.getPlanningModel())
                .generateModel(cfg.getGenerateModel())
                .criticModel(cfg.getCriticModel())
                .timeoutMs(cfg.getTimeoutMs())
                .requestedModeRaw(requestedModeRaw)
                .build();
    }

    private GenerationMode parseMode(String value, GenerationMode fallback) {
        GenerationMode m = GenerationMode.getEnumByValue(value);
        return m != null ? m : fallback;
    }

    private PipelineType parsePipeline(String value) {
        if (value == null) {
            return PipelineType.V2;
        }
        try {
            return PipelineType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PipelineType.V2;
        }
    }
}
