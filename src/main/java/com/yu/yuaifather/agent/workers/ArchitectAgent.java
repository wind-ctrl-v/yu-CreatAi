package com.yu.yuaifather.agent.workers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yu.yuaifather.agent.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.JsonExtractor;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * ArchitectAgent：requirementDoc + techStack → GenerationPlan。
 *
 * @deprecated 已合并入 {@link PlanningAgent}，请使用 PlanningAgent 替代。
 */
@Deprecated
@Slf4j
@Component
public class ArchitectAgent implements Agent {

    public static final String AGENT_NAME = "ArchitectAgent";

    private final AgentAiService agentAiService;

    @Autowired
    public ArchitectAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "设计架构与文件结构"));
            try {
                RequirementDoc doc = ctx.getBlackboard().getRequirementDoc();
                if (doc == null) {
                    throw new IllegalStateException("缺少 requirementDoc，请先执行 ProductAgent");
                }
                ObjectNode input = JsonExtractor.mapper().createObjectNode();
                input.set("requirementDoc", JsonExtractor.mapper().valueToTree(doc));
                input.put("techStack", ctx.getCodeGenType() == null
                        ? "vue_project" : ctx.getCodeGenType().getValue());
                String raw = agentAiService.generatePlan(input.toString());
                GenerationPlan plan = JsonExtractor.parse(raw, GenerationPlan.class);
                ctx.getBlackboard().putPlan(plan);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("生成计划完成：" + (plan.getFileStructure() == null ? 0 : plan.getFileStructure().size()) + " 个文件")
                        .data(plan)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("ArchitectAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "架构设计失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
