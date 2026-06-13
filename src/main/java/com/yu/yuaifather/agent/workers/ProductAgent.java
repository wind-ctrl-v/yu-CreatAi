package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.agent.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.JsonExtractor;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * ProductAgent：用户 prompt → 结构化 RequirementDoc。
 */
@Slf4j
@Component
public class ProductAgent implements Agent {

    public static final String AGENT_NAME = "ProductAgent";

    private final AgentAiService agentAiService;

    @Autowired
    public ProductAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "解析需求中"));
            try {
                String raw = agentAiService.generateRequirementDoc(ctx.getUserPrompt());
                RequirementDoc doc = JsonExtractor.parse(raw, RequirementDoc.class);
                ctx.getBlackboard().putRequirementDoc(doc);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("需求文档生成完成：" + doc.getProjectName())
                        .data(doc)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("ProductAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "需求解析失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
