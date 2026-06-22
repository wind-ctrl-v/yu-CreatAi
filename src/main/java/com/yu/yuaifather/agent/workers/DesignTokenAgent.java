package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.agent.support.DesignProfileResolver;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * DesignTokenAgent：阶段一「先验规则」加载器（新 Worker）。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.tool.system.DesignTokenTool}，请使用 DesignTokenTool 替代。
 */
@Deprecated
@Slf4j
@Component
public class DesignTokenAgent implements Agent {

    public static final String AGENT_NAME = "DesignTokenAgent";

    private final DesignProfileResolver profileResolver;

    @Autowired
    public DesignTokenAgent(DesignProfileResolver profileResolver) {
        this.profileResolver = profileResolver;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "加载设计规范"));
            DesignSpec spec;
            try {
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                String rawProfile = plan == null ? null : plan.getStyleProfile();
                Double confidence = plan == null ? null : plan.getStyleProfileConfidence();
                String profile = profileResolver.normalizeProfile(rawProfile, confidence);
                spec = profileResolver.loadDesignSpec(profile);
                log.info("DesignTokenAgent 规范加载完成 appId={} rawProfile={} confidence={} -> profile={} sourceType={} forbidden={} tokenGroups={}",
                        ctx.getAppId(), rawProfile, confidence, profile, spec.getSourceType(),
                        spec.getForbiddenPatterns() == null ? 0 : spec.getForbiddenPatterns().size(),
                        spec.getTokenGroups() == null ? 0 : spec.getTokenGroups().size());
            } catch (Exception e) {
                // 兜底兜底：即使 resolver 出现意外异常，也用内置默认规范，绝不中断流水线
                log.error("DesignTokenAgent 异常，使用内置默认规范 appId={}", ctx.getAppId(), e);
                spec = profileResolver.buildBuiltinDefaultSpec();
            }
            ctx.getBlackboard().putDesignSpec(spec);
            sink.next(AgentEvent.builder()
                    .type(AgentEvent.EventType.STATUS_CHANGE)
                    .agentName(AGENT_NAME)
                    .content("设计规范就绪：profile=" + spec.getStyleProfile()
                            + " source=" + spec.getSourceType())
                    .data(spec.getStyleProfile())
                    .build());
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
