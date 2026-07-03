package com.yu.yuaifather.agent.workers;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.agent.AgentAiService;
import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.PromptContext;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import com.yu.yuaifather.service.design.DesignContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * PageGenerateAgent：低成本/标准模式下一次 LLM 调用生成完整的 HTML/CSS/JS。
 * <p>
 * 接收 PromptContext 中的 fullPrompt 作为输入，输出页面三件套。
 * 与 CoderAgent + StyleAgent 二段式架构互为替代：
 * <ul>
 *   <li>standard / low_cost 模式 → 使用 PageGenerateAgent（一次调用）</li>
 *   <li>high_quality 模式 → 使用 CoderAgent + StyleAgent（并发两次调用）</li>
 * </ul>
 */
@Slf4j
@Component
public class PageGenerateAgent implements Agent {

    public static final String AGENT_NAME = "PageGenerateAgent";

    private final AgentAiService agentAiService;

    @Autowired
    public PageGenerateAgent(AgentAiService agentAiService) {
        this.agentAiService = agentAiService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            sink.next(AgentEvent.status(AGENT_NAME, "一次性生成页面"));
            try {
                RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
                GenerationPlan plan = ctx.getBlackboard().getPlan();
                DesignSpec designSpec = ctx.getBlackboard().getDesignSpec();

                if (requirement == null && plan == null) {
                    throw new IllegalStateException("缺少 RequirementDoc 或 GenerationPlan");
                }

                // 使用 AgentAiService 的现有方法（复用 generatePlan 作为入口，本质上是通用生成）
                // 实际项目中应新增专用的 generatePage 方法
                String prompt = buildPrompt(ctx);
                String raw = agentAiService.generatePlan(prompt);

                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.STATUS_CHANGE)
                        .agentName(AGENT_NAME)
                        .content("页面生成完成")
                        .data(raw)
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("PageGenerateAgent 失败 appId={}", ctx.getAppId(), e);
                sink.next(AgentEvent.error(AGENT_NAME, "页面生成失败: " + e.getMessage()));
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildPrompt(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("原始需求：").append(ctx.getUserPrompt()).append("\n\n");

        RequirementDoc requirement = ctx.getBlackboard().getRequirementDoc();
        if (requirement != null) {
            sb.append("项目名称：").append(
                    StrUtil.isNotBlank(requirement.getProjectName())
                            ? requirement.getProjectName() : "未命名").append("\n");
        }

        DesignSpec designSpec = ctx.getBlackboard().getDesignSpec();
        if (designSpec != null) {
            sb.append("风格：").append(designSpec.getStyleProfile()).append("\n");
            if (designSpec.getForbiddenPatterns() != null) {
                sb.append("禁用：").append(String.join(",", designSpec.getForbiddenPatterns())).append("\n");
            }
        }

        GenerationPlan plan = ctx.getBlackboard().getPlan();
        if (plan != null) {
            sb.append("请严格按以下计划生成代码：\n")
                    .append(com.yu.yuaifather.utils.JsonExtractor.mapper()
                            .valueToTree(plan).toString()).append("\n");
        }

        return sb.toString();
    }
}
