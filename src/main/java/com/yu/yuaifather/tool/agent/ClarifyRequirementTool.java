package com.yu.yuaifather.tool.agent;

import com.yu.yuaifather.tool.core.AgentTool;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.core.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ClarifyRequirementTool：生成需求澄清问题（Agent Tool）。
 * <p>
 * 当用户需求过于模糊时，PlanningAgent 可调用此工具生成澄清问题向用户提问。
 */
@Slf4j
@Component
public class ClarifyRequirementTool implements AgentTool<ClarifyRequirementTool.Input, ClarifyRequirementTool.Result> {

    public static final String TOOL_NAME = "ClarifyRequirementTool";

    public record Input(String rawRequirement) {}
    public record Result(java.util.List<String> questions) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("需求过于模糊时生成澄清问题，帮助确定页面类型、风格、功能范围等。");
    }

    @Override
    public Result execute(Input input, ToolContext context) {
        log.info("ClarifyRequirementTool 生成澄清问题 requirement={}", input.rawRequirement());
        // TODO: 调用 LLM 生成澄清问题
        return new Result(java.util.List.of());
    }
}
