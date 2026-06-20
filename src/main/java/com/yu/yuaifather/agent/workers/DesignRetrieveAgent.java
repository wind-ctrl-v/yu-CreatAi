package com.yu.yuaifather.agent.workers;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.DesignKnowledgeIndex;
import com.yu.yuaifather.ai.model.dto.DesignEvidence;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
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
 * DesignRetrieveAgent：运行时召回 Worker（阶段三，见计划 3.4）。
 * <p>
 * 按 {@code styleProfile} + 需求语义，通过 {@link DesignKnowledgeIndex} 合并先验静态规范与
 * 后验 APPROVED 指纹，召回 top-k 受控证据写入 Blackboard，供 Coder / Style 受控注入。
 *
 * <h3>开关与降级（3.7）</h3>
 * <ul>
 *   <li>{@code agent.retrieve.enabled=false}（默认）：直接跳过，不查询 DB，
 *       保证阶段一/二在未建指纹表时仍可独立运行；</li>
 *   <li>启用后：检索失败 / 召回为空一律降级（仅状态事件），绝不 {@code sink.error()}，
 *       下游回退到阶段一静态 {@link DesignSpec}。</li>
 * </ul>
 *
 * <p>插入位置：DesignTokenAgent 之后（静态规范已就绪可作先验合并）、ImageAgent 之前
 * （并发分支之前，确保 Coder/Style 并发时证据已就绪）。
 */
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
                String profile = resolveProfile(plan, staticSpec);
                List<String> queryTags = buildQueryTags(ctx, plan, staticSpec);

                DesignEvidence evidence = knowledgeIndex.retrieve(profile, queryTags, staticSpec, topK);
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
