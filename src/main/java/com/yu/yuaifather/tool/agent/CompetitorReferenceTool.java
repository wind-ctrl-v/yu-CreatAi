package com.yu.yuaifather.tool.agent;

import com.yu.yuaifather.tool.core.AgentTool;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.core.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CompetitorReferenceTool：查询平台已有参考风格（Agent Tool）。
 * <p>
 * 当用户说"类似 Linear / Stripe 风格"时，PlanningAgent 可调用此工具查找匹配的风格模板。
 */
@Slf4j
@Component
public class CompetitorReferenceTool implements AgentTool<CompetitorReferenceTool.Input, CompetitorReferenceTool.Result> {

    public static final String TOOL_NAME = "CompetitorReferenceTool";

    public record Input(String styleKeyword) {}
    public record Result(String styleProfile, java.util.List<String> matchedPatterns) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("查询平台已有参考风格，用户说'类似Linear风格'时使用。");
    }

    @Override
    public Result execute(Input input, ToolContext context) {
        log.info("CompetitorReferenceTool 查询 keyword={}", input.styleKeyword());
        // TODO: 关联模板库查询
        return new Result("default", java.util.List.of());
    }
}
