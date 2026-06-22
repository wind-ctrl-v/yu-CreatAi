package com.yu.yuaifather.orchestrator;

import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.workers.*;
import com.yu.yuaifather.ai.model.dto.CriticReport;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.system.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * GenerationOrchestrator：新版生成编排器，支持 3 种生成模式。
 * <p>
 * 替代旧版 OrchestratorAgent 的主流程，System Tool 由 DesignContextAssembler 统一装配，
 * Agent 专注 LLM 生成任务。
 *
 * <h3>三种模式</h3>
 * <ul>
 *   <li><b>standard</b>（默认）：PlanningAgent → DesignContextAssembler → PageGenerateAgent → RuleCriticTool → DeployTool</li>
 *   <li><b>high_quality</b>：PlanningAgent → DesignContextAssembler → CoderAgent∥StyleAgent → RuleCriticTool → (可选CriticAgent) → DeployTool</li>
 *   <li><b>low_cost</b>：PlanningAgent → DesignContextAssembler(精简) → PageGenerateAgent → RuleCriticTool → DeployTool</li>
 * </ul>
 */
@Slf4j
@Component
public class GenerationOrchestrator {

    public static final String NAME = "GenerationOrchestrator";

    @Value("${agent.orchestrator.v2.enabled:false}")
    private boolean v2Enabled;

    @Value("${generation.mode:standard}")
    private String generationMode;

    @Value("${agent.critic.max-rounds:3}")
    private int criticMaxRounds;

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
                                   RagTraceTool ragTraceTool) {
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
    }

    /**
     * 执行生成主流程。
     *
     * @param ctx      旧版 AgentContext（兼容阶段，后续迁移到 ToolContext）
     * @return         生成事件流
     */
    public Flux<AgentEvent> generate(AgentContext ctx) {
        if (!v2Enabled) {
            return Flux.just(AgentEvent.status(NAME, "V2 编排器未启用"));
        }

        ToolContext toolContext = ToolContext.builder()
                .requestId(String.valueOf(ctx.getAppId()))
                .currentUserId(ctx.getLoginUser() != null ? ctx.getLoginUser().getId() : null)
                .currentProjectId(ctx.getAppId())
                .generationMode(generationMode)
                .build();

        String targetAgent = "high_quality".equals(generationMode) ? "CoderAgent" : "PageGenerateAgent";

        Flux<AgentEvent> pipeline = Flux.just(AgentEvent.status(NAME, "V2 编排器启动 (" + generationMode + ")"))
                // Step 1: LLM 规划
                .concatWith(planningAgent.handle(ctx))
                // Step 2: 装配 DesignContext（同步，使用 Mono.just 包装）
                .concatWith(Mono.just(doAssemble(ctx, toolContext, targetAgent))
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
                        }))
                // Step 3: 页面生成
                .concatWith(generatePage(ctx))
                // Step 4: 规则检查
                .concatWith(runRuleCritic(ctx))
                // Step 5: 可选 LLM Critic（仅 high_quality 模式）
                .concatWith("high_quality".equals(generationMode)
                        ? runCriticLoop(ctx) : Flux.empty())
                // Step 6: 部署（可选）
                .concatWith(deployEnabled
                        ? deployTool.handle(ctx)
                        : Flux.just(AgentEvent.status(NAME, "部署未启用，跳过")))
                // Step 7: 记录
                .concatWith(doRecord(ctx, toolContext))
                // 完成
                .concatWith(Flux.just(AgentEvent.status(NAME, "生成完成")));

        return pipeline
                .onErrorResume(err -> {
                    log.error("GenerationOrchestrator 异常", err);
                    return Flux.just(AgentEvent.error(NAME, "生成失败: " + err.getMessage()));
                });
    }

    private DesignContext doAssemble(AgentContext ctx, ToolContext toolContext, String targetAgent) {
        return assembler.assemble(
                DesignContextInput.builder()
                        .userId(toolContext.getCurrentUserId())
                        .projectId(toolContext.getCurrentProjectId())
                        .requirementDoc(ctx.getBlackboard().getRequirementDoc())
                        .generationPlan(ctx.getBlackboard().getPlan())
                        .generationMode(generationMode)
                        .build(),
                toolContext,
                targetAgent);
    }

    private Flux<AgentEvent> generatePage(AgentContext ctx) {
        if ("high_quality".equals(generationMode)) {
            return Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx));
        }
        // standard / low_cost: 使用 PageGenerateAgent
        return pageGenerateAgent.handle(ctx);
    }

    private Flux<AgentEvent> runRuleCritic(AgentContext ctx) {
        // 简单规则检查（不消费 Chat Token）
        return Flux.just(AgentEvent.status(NAME, "执行规则检查（零 LLM）"));
    }

    private Flux<AgentEvent> runCriticLoop(AgentContext ctx) {
        return Flux.defer(() -> doCriticRound(ctx, 1));
    }

    private Flux<AgentEvent> doCriticRound(AgentContext ctx, int round) {
        return criticAgent.handle(ctx)
                .concatWith(Flux.defer(() -> {
                    CriticReport report = ctx.getBlackboard().getCriticReport();
                    if (report == null || report.isPassed()) {
                        return Flux.empty();
                    }
                    if (round >= criticMaxRounds) {
                        return Flux.just(AgentEvent.status(NAME,
                                "质检超出最大轮次（" + criticMaxRounds + "），降级返回"));
                    }
                    return Flux.just(AgentEvent.status(NAME, "质检未通过，第 " + (round + 1) + " 轮修复"))
                            .concatWith(coderAgent.handle(ctx))
                            .concatWith(doCriticRound(ctx, round + 1));
                }));
    }

    private Flux<AgentEvent> doRecord(AgentContext ctx, ToolContext toolContext) {
        return Flux.just(AgentEvent.status(NAME, "记录生成结果"));
    }
}