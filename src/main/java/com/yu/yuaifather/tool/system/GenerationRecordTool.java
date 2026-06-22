package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GenerationRecordTool：记录页面生成结果（System Tool，零 LLM）。
 * <p>
 * 负责将每次生成请求的元数据、DesignContext 快照、生成结果持久化到 generated_page 表。
 * <p>
 * 当前为占位实现，后续关联具体的 Mapper 完成落地。
 */
@Slf4j
@Component
public class GenerationRecordTool implements SystemTool<GenerationRecordTool.RecordInput, Boolean> {

    public static final String TOOL_NAME = "GenerationRecordTool";

    public record RecordInput(
            Long requestId,
            Long userId,
            Long projectId,
            String generationMode,
            String requirement,
            String designContextSnapshot,
            String htmlContent,
            String cssContent,
            String jsContent,
            String deployUrl
    ) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Boolean execute(RecordInput input, ToolContext context) {
        if (input == null) {
            return false;
        }
        try {
            // TODO: 关联 GeneratedPageMapper 持久化
            log.info("GenerationRecordTool 记录生成结果 userId={} projectId={} mode={}",
                    input.userId(), input.projectId(), input.generationMode());
            return true;
        } catch (Exception e) {
            log.error("GenerationRecordTool 记录失败", e);
            return false;
        }
    }
}
