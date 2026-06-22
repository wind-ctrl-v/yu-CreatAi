package com.yu.yuaifather.tool.system;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.DesignKnowledgeIndex;
import com.yu.yuaifather.ai.model.dto.DesignEvidence;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import com.yu.yuaifather.ai.model.dto.UserDesignContext;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DesignRetrieveTool：运行时指纹召回（System Tool，消耗 Embedding Token 但不消耗 Chat Token）。
 * <p>
 * 从 DesignRetrieveAgent 改名重构而来。
 * <p>
 * 通过 DesignKnowledgeIndex 合并先验静态规范与后验 APPROVED 指纹，
 * 召回 top-k 受控证据写入 Blackboard，供 Coder / Style 受控注入。
 */
@Slf4j
@Component
@ConditionalOnBean(DesignKnowledgeIndex.class)
public class DesignRetrieveTool implements Agent, SystemTool<DesignRetrieveTool.RetrieveInput, DesignEvidence> {

    /** 检索输入参数。 */
    public record RetrieveInput(String styleProfile,
                                 java.util.List<String> queryTags,
                                 DesignSpec staticSpec,
                                 RequirementDoc requirement,
                                 GenerationPlan plan,
                                 UserDesignContext userContext,
                                 int topK) {}

    public static final String TOOL_NAME = "DesignRetrieveTool";

    @Value("${agent.retrieve.enabled:false}")
    private boolean retrieveEnabled;

    @Value("${agent.retrieve.top-k:2}")
    private int topK;

    private final DesignKnowledgeIndex knowledgeIndex;

    @Autowired
    public DesignRetrieveTool(DesignKnowledgeIndex knowledgeIndex) {
        this.knowledgeIndex = knowledgeIndex;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!retrieveEnabled) {
                sink.next(AgentEvent.status(TOOL_NAME, "运行时检索未启用，使用静态规范"));
                sink.complete();
                return;
            }
            sink.next(AgentEvent.status(TOOL_NAME, "运行时召回设计证据"));
            try {
                DesignSpec staticSpec = ctx.getBlackboard().getDesignSpec();
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
                UserDesignContext userContext = ctx.getBlackboard().getUserDesignContext();

                String profile = resolveProfile(plan, staticSpec);
                List<String> queryTags = buildQueryTags(plan, staticSpec);

                DesignEvidence evidence = knowledgeIndex.retrieve(profile, queryTags, staticSpec,
                        requirement, plan, userContext, topK);
                if (evidence != null && !evidence.isEmpty()) {
                    ctx.getBlackboard().putDesignEvidence(evidence);
                    sink.next(AgentEvent.status(TOOL_NAME,
                            "召回完成 channel=" + evidence.getChannel() + " items=" + evidence.getItems().size()));
                } else {
                    sink.next(AgentEvent.status(TOOL_NAME, "召回为空，降级使用静态规范"));
                }
            } catch (Exception e) {
                log.warn("DesignRetrieveTool 检索异常，降级静态规范 appId={} msg={}", ctx.getAppId(), e.getMessage());
                sink.next(AgentEvent.status(TOOL_NAME, "召回异常，降级使用静态规范"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SystemTool 同步执行：根据检索参数召回设计证据。
     * 消耗 Embedding Token 但不消耗 Chat Token。
     */
    @Override
    public DesignEvidence execute(RetrieveInput input, ToolContext context) {
        if (input == null) {
            return DesignEvidence.empty();
        }
        try {
            return knowledgeIndex.retrieve(input.styleProfile(), input.queryTags(),
                    input.staticSpec(), input.requirement(), input.plan(),
                    input.userContext(), input.topK());
        } catch (Exception e) {
            log.error("DesignRetrieveTool 同步检索异常 profile={}", input.styleProfile(), e);
            return DesignEvidence.empty();
        }
    }

    private String resolveProfile(GenerationPlan plan, DesignSpec staticSpec) {
        if (staticSpec != null && StrUtil.isNotBlank(staticSpec.getStyleProfile())) {
            return staticSpec.getStyleProfile();
        }
        return plan == null ? null : plan.getStyleProfile();
    }

    private List<String> buildQueryTags(GenerationPlan plan, DesignSpec staticSpec) {
        Set<String> tags = new LinkedHashSet<>();
        if (staticSpec != null && staticSpec.getTone() != null) {
            tags.addAll(staticSpec.getTone());
        }
        if (plan != null && StrUtil.isNotBlank(plan.getRequirementSummary())) {
            for (String w : plan.getRequirementSummary().split("[\\s,，。、/]+")) {
                if (w.length() >= 2) {
                    tags.add(w);
                }
            }
        }
        return new ArrayList<>(tags);
    }
}
