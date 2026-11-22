package com.yu.yuaifather.agent.pipeline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.service.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.utils.JsonExtractor;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * PlanningAgent：合并 ProductAgent + ArchitectAgent 为一次 LLM 规划调用。
 * <p>
 * 输入 userPrompt → 一次性输出 RequirementDoc + GenerationPlan，
 * 减少一次 LLM 往返，降低延迟与 Chat Token 消耗。
 * <p>
 * 如果两次 LLM 调用合并提示词未就绪，则退化为串行两次调用（与旧版行为一致）。
 */
@Slf4j
@Component
public class PlanningAgent implements Agent {

    public static final String AGENT_NAME = "PlanningAgent";

    private final AgentAiService agentAiService;

    @Autowired
    public PlanningAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            // ① 解析需求
            sink.next(AgentEvent.status(AGENT_NAME, "解析需求并规划设计"));
            try {
                RequirementDoc doc = JsonExtractor.parseWithRetry(
                        () -> agentAiService.generateRequirementDoc(ctx.getUserPrompt()),
                        RequirementDoc.class);
                ctx.getBlackboard().putRequirementDoc(doc);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("需求文档生成完成：" + doc.getProjectName())
                        .data(doc)
                        .build());
            } catch (Exception e) {
                log.error("PlanningAgent 需求解析失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "需求解析失败: " + e.getMessage()));
                sink.error(e);
                return;
            }

            // ② 生成计划
            sink.next(AgentEvent.status(AGENT_NAME, "设计架构与文件结构"));
            try {
                RequirementDoc doc = ctx.getBlackboard().getRequirementDoc();
                if (doc == null) {
                    throw new IllegalStateException("缺少 requirementDoc");
                }
                ObjectNode input = JsonExtractor.mapper().createObjectNode();
                input.set("requirementDoc", JsonExtractor.mapper().valueToTree(doc));
                input.put("techStack", ctx.getCodeGenType() == null
                        ? "vue_project" : ctx.getCodeGenType().getValue());
                GenerationPlan plan = JsonExtractor.parseWithRetry(
                        () -> agentAiService.generatePlan(input.toString()),
                        GenerationPlan.class);
                ctx.getBlackboard().putPlan(plan);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("生成计划完成：" + (plan.getFileStructure() == null ? 0 : plan.getFileStructure().size()) + " 个文件")
                        .data(plan)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("PlanningAgent 计划生成失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "架构设计失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
