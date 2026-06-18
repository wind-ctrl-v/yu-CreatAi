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
 * <p>
 * <b>不调用 LLM</b>，纯 Java 根据 {@code GenerationPlan.styleProfile} 从 {@code skill/designs/*.md}
 * 解析出结构化 {@link DesignSpec} 写入 Blackboard，供并发分支的 StyleAgent / CoderAgent 消费。
 *
 * <h3>降级策略（绝不中断流水线）</h3>
 * <ul>
 *   <li>识别失败 / 不在白名单 / 低置信：归一为 {@code default} 规范（见计划 1.2.1）；</li>
 *   <li>md 读取或解析失败：回退内置默认规范，保证 tokenGroups 强契约始终存在；</li>
 *   <li>任意异常：仅推 status 事件 + {@code sink.complete()}，<b>绝不</b> {@code sink.error()}，
 *       保证后续 Image / Coder / Style 仍能运行。</li>
 * </ul>
 *
 * <p>插入位置：ArchitectAgent 之后、ImageAgent 之前（即并发分支之前），
 * 确保 Coder/Style 并发执行时 DesignSpec 已就绪。
 */
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
