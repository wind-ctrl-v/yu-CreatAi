package com.yu.yuaifather.agent.workers;

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
 * DesignRetrieveAgent：运行时召回 Worker。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.tool.system.DesignRetrieveTool}，请使用 DesignRetrieveTool 替代。
 */
@Deprecated
@Slf4j
@Component
@ConditionalOnBean(DesignKnowledgeIndex.class)
public class DesignRetrieveAgent implements Agent {

    public static final String AGENT_NAME = "DesignRetrieveAgent";

    @Value("${agent.retrieve.enabled:false}")
    private boolean retrieveEnabled;

    @Value("${agent.retrieve.top-k:2}")
    private int topK;

    private final DesignKnowledgeIndex knowledgeIndex;

    @Autowired
    public DesignRetrieveAgent(DesignKnowledgeIndex knowledgeIndex) {
        this.knowledgeIndex = knowledgeIndex;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!retrieveEnabled) {
                sink.next(AgentEvent.status(AGENT_NAME, "运行时检索未启用，使用静态规范"));
                sink.complete();
                return;
            }
            sink.next(AgentEvent.status(AGENT_NAME, "运行时召回设计证据"));
            try {
                DesignSpec staticSpec = ctx.getBlackboard().getDesignSpec();
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
                UserDesignContext userContext = ctx.getBlackboard().getUserDesignContext();

                String profile = resolveProfile(plan, staticSpec);
                List<String> queryTags = buildQueryTags(ctx, plan, staticSpec);

                // 阶段一：传递用户偏好上下文给 DesignKnowledgeIndex
                DesignEvidence evidence = knowledgeIndex.retrieve(profile, queryTags, staticSpec,
                        requirement, plan, userContext, topK);
                if (evidence != null && !evidence.isEmpty()) {
                    ctx.getBlackboard().putDesignEvidence(evidence);
                    sink.next(AgentEvent.status(AGENT_NAME,
                            "召回完成 channel=" + evidence.getChannel() + " items=" + evidence.getItems().size()));
                } else {
                    sink.next(AgentEvent.status(AGENT_NAME, "召回为空，降级使用静态规范"));
                }
            } catch (Exception e) {
                // 检索阶段任何异常都降级，不影响后续生成（见 3.7）
                log.warn("DesignRetrieveAgent 检索异常，降级静态规范 appId={} msg={}", ctx.getAppId(), e.getMessage());
                sink.next(AgentEvent.status(AGENT_NAME, "召回异常，降级使用静态规范"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String resolveProfile(GenerationPlan plan, DesignSpec staticSpec) {
        if (staticSpec != null && StrUtil.isNotBlank(staticSpec.getStyleProfile())) {
            return staticSpec.getStyleProfile();
        }
        return plan == null ? null : plan.getStyleProfile();
    }

    /**
     * 构造软匹配标签：静态规范调性 + 需求摘要关键词（小规模、去重）。
     */
    private List<String> buildQueryTags(AgentContext ctx, GenerationPlan plan, DesignSpec staticSpec) {
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
