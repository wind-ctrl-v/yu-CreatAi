package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RagTraceTool：记录每次 RAG 调用的完整追踪信息（System Tool，零 LLM）。
 * <p>
 * 记录检索输入、召回结果、重排结果、注入条目、质量评分，
 * 可通过 requestId 与 tool_call_trace 表联动。
 * <p>
 * 当前为占位实现，后续关联 GenerationRagTraceMapper 完成落地。
 */
@Slf4j
@Component
public class RagTraceTool implements SystemTool<RagTraceTool.TraceInput, Boolean> {

    public static final String TOOL_NAME = "RagTraceTool";

    public record TraceInput(
            Long requestId,
            Long generationId,
            String styleProfile,
            int retrieveCount,
            int injectedCount,
            String channel,
            String evidenceSnapshot,
            Double qualityScore
    ) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Boolean execute(TraceInput input, ToolContext context) {
        if (input == null) {
            return false;
        }
        try {
            // TODO: 关联 GenerationRagTraceMapper 持久化
            log.info("RagTraceTool 记录追踪 requestId={} retrieveCount={} injectedCount={} channel={}",
                    input.requestId(), input.retrieveCount(), input.injectedCount(), input.channel());
            return true;
        } catch (Exception e) {
            log.error("RagTraceTool 记录失败", e);
            return false;
        }
    }
}
