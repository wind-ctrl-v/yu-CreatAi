package com.yu.yuaifather.agent.orchestrator;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.workers.ArchitectAgent;
import com.yu.yuaifather.agent.workers.CoderAgent;
import com.yu.yuaifather.agent.workers.CriticAgent;
import com.yu.yuaifather.agent.workers.DeployAgent;
import com.yu.yuaifather.agent.workers.ImageAgent;
import com.yu.yuaifather.agent.workers.ProductAgent;
import com.yu.yuaifather.agent.workers.StyleAgent;
import com.yu.yuaifather.ai.model.dto.CriticReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OrchestratorAgent：编排多 Agent 流水线。
 * <p>
 * 顺序：Product → Architect → Image → (Coder + Style 并发) → Critic（最多 3 轮反馈）→ Deploy
 * <p>
 * 任一阶段失败时返回错误事件并提前终止；Critic 未通过则回到 Coder 修复，最多重试 {@link #criticMaxRounds} 轮，
 * 超限后降级返回当前最佳结果，不阻塞主流程。
 */
@Slf4j
@Component
public class OrchestratorAgent implements Agent {

    public static final String AGENT_NAME = "Orchestrator";

    @Value("${agent.critic.max-rounds:3}")
    private int criticMaxRounds;

    @Value("${agent.deploy.enabled:false}")
    private boolean deployEnabled;

    private final ProductAgent productAgent;
    private final ArchitectAgent architectAgent;
    private final ImageAgent imageAgent;
    private final CoderAgent coderAgent;
    private final StyleAgent styleAgent;
    private final CriticAgent criticAgent;
    private final DeployAgent deployAgent;

    @Autowired
    public OrchestratorAgent(ProductAgent productAgent,
                             ArchitectAgent architectAgent,
                             ImageAgent imageAgent,
                             CoderAgent coderAgent,
                             StyleAgent styleAgent,
                             CriticAgent criticAgent,
                             DeployAgent deployAgent) {
        this.productAgent = productAgent;
        this.architectAgent = architectAgent;
        this.imageAgent = imageAgent;
        this.coderAgent = coderAgent;
        this.styleAgent = styleAgent;
        this.criticAgent = criticAgent;
        this.deployAgent = deployAgent;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        Flux<AgentEvent> pipeline = Flux.just(AgentEvent.status(AGENT_NAME, "流水线启动"))
                .concatWith(productAgent.handle(ctx))
                .concatWith(architectAgent.handle(ctx))
                .concatWith(imageAgent.handle(ctx))
                // Coder 与 Style 并发执行
                .concatWith(Flux.merge(coderAgent.handle(ctx), styleAgent.handle(ctx)))
                // Critic 反馈循环
                .concatWith(runCriticLoop(ctx));
        if (deployEnabled) {
            pipeline = pipeline.concatWith(deployAgent.handle(ctx));
        }
        return pipeline
                .concatWith(Flux.just(AgentEvent.status(AGENT_NAME, "流水线完成")))
                .onErrorResume(err -> {
                    log.error("Orchestrator 流水线异常 appId={}", ctx.getAppId(), err);
                    return Flux.just(AgentEvent.error(AGENT_NAME, "流水线失败: " + err.getMessage()));
                });
    }

    /**
     * Critic 反馈循环：最多 {@link #criticMaxRounds} 轮，未通过则交回 Coder 修复。
     */
    private Flux<AgentEvent> runCriticLoop(AgentContext ctx) {
        return Flux.defer(() -> doCriticRound(ctx, 1));
    }

    private Flux<AgentEvent> doCriticRound(AgentContext ctx, int round) {
        Flux<AgentEvent> step = Flux.just(AgentEvent.status(AGENT_NAME, "质检第 " + round + " 轮"))
                .concatWith(criticAgent.handle(ctx));
        return step.concatWith(Flux.defer(() -> {
            CriticReport report = ctx.getBlackboard().getCriticReport();
            if (report == null || report.isPassed()) {
                return Flux.empty();
            }
            if (round >= criticMaxRounds) {
                return Flux.just(AgentEvent.status(AGENT_NAME,
                        "质检超出最大轮次（" + criticMaxRounds + "），降级返回当前结果"));
            }
            return Flux.just(AgentEvent.status(AGENT_NAME, "质检未通过，进入第 " + (round + 1) + " 轮修复"))
                    .concatWith(coderAgent.handle(ctx))
                    .concatWith(doCriticRound(ctx, round + 1));
        }));
    }
}
