package com.yu.yuaifather.orchestrator;

import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.core.Blackboard;
import com.yu.yuaifather.agent.workers.*;
import com.yu.yuaifather.model.dto.agent.CriticReport;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.generation.GenerationPolicy;
import com.yu.yuaifather.model.enums.GenerationMode;
import com.yu.yuaifather.model.enums.PipelineType;
import com.yu.yuaifather.service.UserFingerprintIngestService;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.system.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GenerationOrchestrator：新版生成编排器（V2 主链路），按 {@link GenerationPolicy} 编排，
 * 自身不做任何用户身份/套餐/额度判断。
 *
 * <h3>三种模式（由 policy.generationMode 决定）</h3>
 * <ul>
 *   <li><b>low_cost</b>：PlanningAgent → DesignContextAssembler(精简,evidence=1) → PageGenerateAgent(单遍) → RuleCriticTool</li>
 *   <li><b>standard</b>：PlanningAgent → DesignContextAssembler → (并发开关)CoderAgent∥StyleAgent / PageGenerateAgent → RuleCriticTool →（规则失败且启用 LLM Critic 才）1 轮 Critic</li>
 *   <li><b>high_quality</b>：PlanningAgent → DesignContextAssembler(完整,evidence=N) → CoderAgent∥StyleAgent → RuleCriticTool → Critic 多轮(≤maxCriticRounds)</li>
 * </ul>
 */
@Slf4j
@Component
public class GenerationOrchestrator {

    public static final String NAME = "GenerationOrchestrator";

    @Value("${agent.orchestrator.v2.enabled:false}")
    private boolean v2Enabled;

    /** 兼容入口的默认模式（无 policy 时使用）。 */
    @Value("${generation.mode:standard}")
    private String defaultGenerationMode;

    /** 兼容入口的默认 critic 轮次。 */
    @Value("${agent.critic.max-rounds:3}")
    private int defaultCriticMaxRounds;

    @Value("${agent.deploy.enabled:false}")
    private boolean deployEnabled;

    private final PlanningAgent planningAgent;
    private final PageGenerateAgent pageGenerateAgent;
    private final CoderAgent coderAgent;
    private final StyleAgent styleAgent;
    private final CriticAgent criticAgent;
    private final DesignContextAssembler assembler;
    private final RuleCriticTool ruleCriticTool;
    private final DeployTool deployTool;
    private final GenerationRecordTool generationRecordTool;
    private final RagTraceTool ragTraceTool;
    private final UserFingerprintIngestService fingerprintIngestService;

    @Autowired
    public GenerationOrchestrator(PlanningAgent planningAgent,
                                   PageGenerateAgent pageGenerateAgent,
                                   CoderAgent coderAgent,
                                   StyleAgent styleAgent,
                                   CriticAgent criticAgent,
                                   DesignContextAssembler assembler,
                                   RuleCriticTool ruleCriticTool,
                                   DeployTool deployTool,
                                   GenerationRecordTool generationRecordTool,
                                   RagTraceTool ragTraceTool,
                                   UserFingerprintIngestService fingerprintIngestService) {
        this.planningAgent = planningAgent;
        this.pageGenerateAgent = pageGenerateAgent;
        this.coderAgent = coderAgent;
        this.styleAgent = styleAgent;
        this.criticAgent = criticAgent;
        this.assembler = assembler;
        this.ruleCriticTool = ruleCriticTool;
        this.deployTool = deployTool;
        this.generationRecordTool = generationRecordTool;
        this.ragTraceTool = ragTraceTool;
        this.fingerprintIngestService = fingerprintIngestService;
    }

    /**
     * 兼容入口：无 policy 时按 {@code @Value} 默认值构造策略后执行。
     */
    public Flux<AgentEvent> generate(AgentContext ctx) {
        return generate(ctx, defaultPolicy());
    }

    /**
     * 主入口：按 {@link GenerationPolicy} 编排执行。
     *
     * @param ctx    多 Agent 上下文
     * @param policy 不可变生成策略（由 GenerationModeResolver 产出）
     * @return 生成事件流
     */
    public Flux<AgentEvent> generate(AgentContext ctx, GenerationPolicy policy) {
        if (policy == null) {
            policy = defaultPolicy();
        }
        if (!policy.isV2Enabled()) {
            return Flux.just(AgentEvent.status(NAME, "V2 编排器未启用"));
        }

        GenerationMode mode = policy.getGenerationMode();
        ToolContext toolContext = ToolContext.builder()
                .requestId(String.valueOf(ctx.getAppId()))
                .currentUserId(ctx.getLoginUser() != null ? ctx.getLoginUser().getId() : null)
                .currentProjectId(ctx.getAppId())
                .generationMode(mode.getValue())
                .build();

        final GenerationPolicy effectivePolicy = policy;
        Flux<AgentEvent> pipeline = switch (mode) {
            case LOW_COST -> generateLowCost(ctx, toolContext, effectivePolicy);
            case STANDARD -> generateStandard(ctx, toolContext, effectivePolicy);
            case HIGH_QUALITY -> generateHighQuality(ctx, toolContext, effectivePolicy);
        };

        return pipeline.onErrorResume(err -> {
            log.error("GenerationOrchestrator 异常 mode={}", mode, err);
            return Flux.just(AgentEvent.error(NAME, "生成失败: " + err.getMessage()));
        });
    }

    // ==================== 三模式编排 ====================

    /** low_cost：单遍生成 + 规则检查，不启用 LLM Critic。 */
    private Flux<AgentEvent> generateLowCost(AgentContext ctx, ToolContext tc, GenerationPolicy policy) {
        AtomicReference<RuleCriticTool.RuleCriticResult> ruleHolder = new AtomicReference<>();
        return Flux.just(AgentEvent.status(NAME, "V2 编排器启动 (low_cost)"))
                .concatWith(planningAgent.handle(ctx))
                .concatWith(assembleFlux(ctx, tc, policy, "PageGenerateAgent"))
                .concatWith(pageGenerateAgent.handle(ctx))
                .concatWith(runRuleCritic(ctx, tc, ruleHolder))
                .concatWith(deployStep(ctx))
                .concatWith(doRecord(ctx, tc))
                .concatWith(Flux.just(AgentEvent.status(NAME, "生成完成")));
    }

    /** standard：默认链路，规则失败且启用 LLM Critic 时才做 1~maxRounds 轮修复。 */
    private Flux<AgentEvent> generateStandard(AgentContext ctx, ToolContext tc, GenerationPolicy policy) {
        AtomicReference<RuleCriticTool.RuleCriticResult> ruleHolder = new AtomicReference<>();
        String targetAgent = policy.isEnableCoderStyleParallel() ? "CoderAgent" : "PageGenerateAgent";
        return Flux.just(AgentEvent.status(NAME, "V2 编排器启动 (standard)"))
                .concatWith(planningAgent.handle(ctx))
                .concatWith(assembleFlux(ctx, tc, policy, targetAgent))
                .concatWith(generatePage(ctx, policy))
                .concatWith(runRuleCritic(ctx, tc, ruleHolder))
                .concatWith(Flux.defer(() -> {
                    RuleCriticTool.RuleCriticResult r = ruleHolder.get();
                    boolean needLlm = r != null && (r.missingRequiredFiles() || r.hasPlaceholders());
                    if (policy.isEnableLlmCritic() && needLlm && policy.getMaxCriticRounds() > 0) {
                        return Flux.just(AgentEvent.status(NAME, "规则检查未通过，启用 LLM Critic 修复"))
                                .concatWith(runCriticLoop(ctx, policy.getMaxCriticRounds()));
                    }
                    return Flux.empty();
                }))
                .concatWith(deployStep(ctx))
                .concatWith(doRecord(ctx, tc))
                .concatWith(Flux.just(AgentEvent.status(NAME, "生成完成")));
    }

    /** high_quality：Coder∥Style 并发 + 多轮 Critic。 */
    private Flux<AgentEvent> generateHighQuality(AgentContext ctx, ToolContext tc, GenerationPolicy policy) {
        AtomicReference<RuleCriticTool.RuleCriticResult> ruleHolder = new AtomicReference<>();
        int maxRounds = Math.max(1, policy.getMaxCriticRounds());
        return Flux.just(AgentEvent.status(NAME, "V2 编排器启动 (high_quality)"))
                .concatWith(planningAgent.handle(ctx))
                .concatWith(assembleFlux(ctx, tc, policy, "CoderAgent"))
                .concatWith(Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx)))
                .concatWith(runRuleCritic(ctx, tc, ruleHolder))
                .concatWith(runCriticLoop(ctx, maxRounds))
                .concatWith(deployStep(ctx))
                .concatWith(doRecord(ctx, tc))
                .concatWith(Flux.just(AgentEvent.status(NAME, "生成完成")));
    }

    // ==================== 步骤组件 ====================

    /** 页面生成：并发开关决定 Coder∥Style 或单 PageGenerateAgent。 */
    private Flux<AgentEvent> generatePage(AgentContext ctx, GenerationPolicy policy) {
        if (policy.isEnableCoderStyleParallel()) {
            return Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx));
        }
        return pageGenerateAgent.handle(ctx);
    }

    /** 装配 DesignContext（同步），按 policy 调节 evidence 数与精简程度，失败降级继续。 */
    private Flux<AgentEvent> assembleFlux(AgentContext ctx, ToolContext tc, GenerationPolicy policy, String targetAgent) {
        return Mono.fromCallable(() -> doAssemble(ctx, tc, policy, targetAgent))
                .flatMapMany(dc -> {
                    ctx.getBlackboard().putDesignSpec(dc.getDesignSpec());
                    if (dc.getUserContext() != null) {
                        ctx.getBlackboard().putUserDesignContext(dc.getUserContext());
                    }
                    if (dc.getProjectContext() != null) {
                        ctx.getBlackboard().putProjectBrandContext(dc.getProjectContext());
                    }
                    if (dc.getDesignEvidence() != null) {
                        ctx.getBlackboard().putDesignEvidence(dc.getDesignEvidence());
                    }
                    if (dc.getImageMap() != null) {
                        ctx.getBlackboard().putImageMap(dc.getImageMap());
                    }
                    return Flux.just(AgentEvent.status(NAME,
                            "DesignContext 装配完成 evidence=" + dc.getFingerprintHitCount()));
                })
                .onErrorResume(e -> {
                    log.warn("DesignContext 装配异常，降级继续", e);
                    return Flux.just(AgentEvent.status(NAME, "DesignContext 装配失败，降级继续"));
                });
    }

    private DesignContext doAssemble(AgentContext ctx, ToolContext toolContext, GenerationPolicy policy, String targetAgent) {
        return assembler.assemble(
                DesignContextInput.builder()
                        .userId(toolContext.getCurrentUserId())
                        .projectId(toolContext.getCurrentProjectId())
                        .requirementDoc(ctx.getBlackboard().getRequirementDoc())
                        .generationPlan(ctx.getBlackboard().getPlan())
                        .generationMode(policy.getGenerationMode().getValue())
                        .maxEvidenceCount(policy.getMaxEvidenceCount())
                        .compactContext(policy.getGenerationMode() == GenerationMode.LOW_COST)
                        .fullProjectProfile(policy.getGenerationMode() == GenerationMode.HIGH_QUALITY)
                        .build(),
                toolContext,
                targetAgent);
    }

    /** 规则检查（零 LLM）：调用真实 {@link RuleCriticTool}，结果写入 holder 供后续决策。 */
    private Flux<AgentEvent> runRuleCritic(AgentContext ctx, ToolContext tc,
                                           AtomicReference<RuleCriticTool.RuleCriticResult> holder) {
        return Flux.defer(() -> {
            try {
                RuleCriticTool.RuleCriticResult result = ruleCriticTool.execute(
                        new RuleCriticTool.RuleCriticInput(ctx.getAppId(), null, null, null), tc);
                holder.set(result);
                int issueCount = result.issues() == null ? 0 : result.issues().size();
                return Flux.just(AgentEvent.status(NAME, String.format(
                        "规则检查完成 score=%d passed=%s issues=%d", result.score(), result.passed(), issueCount)));
            } catch (Exception e) {
                log.warn("规则检查异常，跳过", e);
                return Flux.just(AgentEvent.status(NAME, "规则检查异常，跳过"));
            }
        });
    }

    private Flux<AgentEvent> deployStep(AgentContext ctx) {
        return deployEnabled
                ? deployTool.handle(ctx)
                : Flux.just(AgentEvent.status(NAME, "部署未启用，跳过"));
    }

    private Flux<AgentEvent> runCriticLoop(AgentContext ctx, int maxRounds) {
        return Flux.defer(() -> doCriticRound(ctx, 1, null, 0, maxRounds));
    }

    // 修复分支标识
    private static final String BRANCH_STYLE = "STYLE";
    private static final String BRANCH_CODER = "CODER";
    private static final String BRANCH_BOTH = "BOTH";

    /**
     * 单轮 Critic 质检 + 按 issue 分类的修复分支（对齐 V1 OrchestratorAgent）。
     */
    private Flux<AgentEvent> doCriticRound(AgentContext ctx, int round, String lastBranch, int pingpongCount, int maxRounds) {
        return criticAgent.handle(ctx)
                .concatWith(Flux.defer(() -> {
                    CriticReport report = ctx.getBlackboard().getCriticReport();
                    if (report == null || report.isPassed()) {
                        return Flux.empty();
                    }
                    if (round >= maxRounds) {
                        return Flux.just(AgentEvent.status(NAME,
                                "质检超出最大轮次（" + maxRounds + "），降级返回"));
                    }

                    // 按 issue 分类决定本轮修复分支
                    List<CriticReport.Issue> issues = report.getIssues();
                    boolean hasStyle = issues != null && issues.stream().anyMatch(i ->
                            "style".equalsIgnoreCase(i.getCategory()));
                    boolean hasCoder = issues == null || issues.isEmpty()
                            || issues.stream().anyMatch(i -> !"style".equalsIgnoreCase(i.getCategory()));
                    String thisBranch = (hasStyle && hasCoder) ? BRANCH_BOTH
                            : hasStyle ? BRANCH_STYLE : BRANCH_CODER;

                    // 乒乓抑制
                    boolean alternation = (BRANCH_STYLE.equals(lastBranch) && BRANCH_CODER.equals(thisBranch))
                            || (BRANCH_CODER.equals(lastBranch) && BRANCH_STYLE.equals(thisBranch));
                    int newPingpong = alternation ? pingpongCount + 1 : 0;
                    if (newPingpong >= 2) {
                        return Flux.just(AgentEvent.status(NAME,
                                "检测到 Style↔Coder 反复修复未收敛，强制收敛降级"));
                    }

                    Flux<AgentEvent> repair;
                    if (BRANCH_BOTH.equals(thisBranch)) {
                        repair = Flux.just(AgentEvent.status(NAME,
                                        "质检未通过，第 " + (round + 1) + " 轮修复（先样式后代码）"))
                                .concatWith(styleAgent.handle(ctx))
                                .concatWith(coderAgent.handle(ctx));
                    } else if (BRANCH_STYLE.equals(thisBranch)) {
                        repair = Flux.just(AgentEvent.status(NAME,
                                        "质检未通过，第 " + (round + 1) + " 轮修复（样式）"))
                                .concatWith(styleAgent.handle(ctx));
                    } else {
                        repair = Flux.just(AgentEvent.status(NAME,
                                        "质检未通过，第 " + (round + 1) + " 轮修复（代码）"))
                                .concatWith(coderAgent.handle(ctx));
                    }
                    return repair.concatWith(doCriticRound(ctx, round + 1, thisBranch, newPingpong, maxRounds));
                }));
    }

    private Flux<AgentEvent> doRecord(AgentContext ctx, ToolContext toolContext) {
        return Flux.defer(() -> {
            try {
                String requirement = ctx.getUserPrompt();
                String mode = toolContext.getGenerationMode();
                Long userId = toolContext.getCurrentUserId();
                Long projectId = toolContext.getCurrentProjectId();

                // 从黑板获取当前设计上下文信息
                Blackboard board = ctx.getBlackboard();
                DesignSpec spec = board != null ? board.getDesignSpec() : null;
                String styleProfile = spec != null ? spec.getStyleProfile() : null;
                DesignEvidence evidence = board != null ? board.getDesignEvidence() : null;

                // 保存生成产物快照到 generated_page
                Long pageId = generationRecordTool.execute(
                    new GenerationRecordTool.RecordInput(
                        ctx.getAppId(), userId, projectId, mode,
                        requirement, styleProfile, null, null, null, null, null
                    ), toolContext
                );

                // 保存 RAG 检索追踪（修复计划 03：queryText 位需传入真实查询文本，而非 channel 值）
                if (evidence != null && evidence.getItems() != null && !evidence.getItems().isEmpty()) {
                    ragTraceTool.execute(new RagTraceTool.TraceInput(
                        ctx.getAppId(), pageId, userId, projectId,
                        evidence.getStyleProfile(), evidence.getQueryText(),
                        evidence.getItems().size(), evidence.getItems().size(),
                        evidence.getChannel() != null ? evidence.getChannel() : "STRUCTURAL_3A",
                        null, null, userId != null
                    ), toolContext);
                }

                // Stage 2: 异步提取用户私有设计指纹（不阻塞主流程）
                if (pageId != null && userId != null) {
                    fingerprintIngestService.ingestUserFingerprint(pageId, userId, projectId);
                }

                return Flux.just(AgentEvent.status(NAME,
                    pageId != null ? "生成快照已保存 pageId=" + pageId : "生成快照已记录"));
            } catch (Exception e) {
                log.warn("记录生成结果失败 appId={}", ctx.getAppId(), e);
                return Flux.just(AgentEvent.status(NAME, "生成快照已记录"));
            }
        });
    }

    /**
     * 兼容入口的默认策略：按 {@code @Value} 配置推断，保证旧调用方行为基本一致。
     */
    private GenerationPolicy defaultPolicy() {
        GenerationMode mode = GenerationMode.getEnumByValue(defaultGenerationMode);
        if (mode == null) {
            mode = GenerationMode.STANDARD;
        }
        return GenerationPolicy.builder()
                .preferredPipeline(PipelineType.V2)
                .generationMode(mode)
                .v2Enabled(v2Enabled)
                .allowV1Fallback(true)
                .allowClassicFallback(true)
                .enableLlmCritic(mode != GenerationMode.LOW_COST)
                .enableCoderStyleParallel(mode == GenerationMode.HIGH_QUALITY)
                .maxCriticRounds(mode == GenerationMode.LOW_COST ? 0 : defaultCriticMaxRounds)
                .maxEvidenceCount(mode == GenerationMode.HIGH_QUALITY ? 5 : (mode == GenerationMode.STANDARD ? 3 : 1))
                .timeoutMs(300000L)
                .requestedModeRaw(defaultGenerationMode)
                .build();
    }
}
