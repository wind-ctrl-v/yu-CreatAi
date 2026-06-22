package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.service.design.DesignProfileResolver;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * DesignTokenTool：阶段一「先验规则」加载器（System Tool，零 LLM）。
 * <p>
 * 从 DesignTokenAgent 改名重构而来。
 * <p>
 * <b>不调用 LLM</b>，纯 Java 根据 {@code GenerationPlan.styleProfile} 从 {@code skill/designs/*.md}
 * 解析出结构化 {@link DesignSpec} 写入 Blackboard，供并发分支的 StyleAgent / CoderAgent 消费。
 */
@Slf4j
@Component
public class DesignTokenTool implements Agent, SystemTool<String, DesignSpec> {

    public static final String TOOL_NAME = "DesignTokenTool";

    private final DesignProfileResolver profileResolver;

    @Autowired
    public DesignTokenTool(DesignProfileResolver profileResolver) {
        this.profileResolver = profileResolver;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(TOOL_NAME, "加载设计规范"));
            DesignSpec spec;
            try {
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                String rawProfile = plan == null ? null : plan.getStyleProfile();
                Double confidence = plan == null ? null : plan.getStyleProfileConfidence();
                String profile = profileResolver.normalizeProfile(rawProfile, confidence);
                spec = profileResolver.loadDesignSpec(profile);
                log.info("DesignTokenTool 规范加载完成 appId={} rawProfile={} confidence={} -> profile={}",
                        ctx.getAppId(), rawProfile, confidence, profile);
            } catch (Exception e) {
                log.error("DesignTokenTool 异常，使用内置默认规范 appId={}", ctx.getAppId(), e);
                spec = profileResolver.buildBuiltinDefaultSpec();
            }
            ctx.getBlackboard().putDesignSpec(spec);
            sink.next(AgentEvent.builder()
                    .type(AgentEvent.EventType.STATUS_CHANGE)
                    .agentName(TOOL_NAME)
                    .content("设计规范就绪：profile=" + spec.getStyleProfile()
                            + " source=" + spec.getSourceType())
                    .data(spec.getStyleProfile())
                    .build());
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * SystemTool 同步执行：根据 styleProfile 加载 DesignSpec。
     *
     * @param styleProfile 风格标识（如 "saas"、"default"）
     * @param context      工具调用上下文（未使用，仅满足接口契约）
     * @return 解析后的设计规范
     */
    @Override
    public DesignSpec execute(String styleProfile, ToolContext context) {
        try {
            String profile = profileResolver.normalizeProfile(styleProfile, null);
            return profileResolver.loadDesignSpec(profile);
        } catch (Exception e) {
            log.error("DesignTokenTool 同步执行异常 profile={}", styleProfile, e);
            return profileResolver.buildBuiltinDefaultSpec();
        }
    }
}
