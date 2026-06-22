package com.yu.yuaifather.tool.agent;

import com.yu.yuaifather.tool.core.AgentTool;
import com.yu.yuaifather.tool.core.ToolContext;
import com.yu.yuaifather.tool.core.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BrandExtractTool：从描述中提取品牌画像（Agent Tool）。
 * <p>
 * 项目首次创建时，PlanningAgent 可调用此工具从用户描述中自动提取品牌色、调性等。
 */
@Slf4j
@Component
public class BrandExtractTool implements AgentTool<BrandExtractTool.Input, BrandExtractTool.Result> {

    public static final String TOOL_NAME = "BrandExtractTool";

    public record Input(String description) {}
    public record Result(String brandName, java.util.List<String> colors, java.util.List<String> tones, String industry) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of("从用户描述中提取品牌画像（品牌名、颜色、调性、行业），项目首次创建时使用。");
    }

    @Override
    public Result execute(Input input, ToolContext context) {
        log.info("BrandExtractTool 提取 description={}", input.description());
        // TODO: 调用 LLM 完成品牌提取
        return new Result(null, java.util.List.of(), java.util.List.of(), null);
    }
}
