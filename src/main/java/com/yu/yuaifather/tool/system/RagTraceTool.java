package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.mapper.GenerationRagTraceMapper;
import com.yu.yuaifather.model.entity.GenerationRagTrace;
import com.yu.yuaifather.tool.core.SystemTool;
import com.yu.yuaifather.tool.core.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * RagTraceTool：记录每次 RAG 调用的完整追踪信息到 generation_rag_trace 表。
 * <p>
 * 记录检索输入、召回结果、注入条目、质量评分，用于调试和评估召回质量。
 */
@Slf4j
@Component
public class RagTraceTool implements SystemTool<RagTraceTool.TraceInput, Long> {

    public static final String TOOL_NAME = "RagTraceTool";

    private final GenerationRagTraceMapper traceMapper;

    public RagTraceTool(GenerationRagTraceMapper traceMapper) {
        this.traceMapper = traceMapper;
    }

    public record TraceInput(
            Long requestId,
            Long generationId,
            Long userId,
            Long projectId,
            String styleProfile,
            String queryText,
            int retrieveCount,
            int injectedCount,
            String channel,
            String evidenceSnapshot,
            Double topSimilarity,
            Boolean usedUserProfile
    ) {}

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Long execute(TraceInput input, ToolContext context) {
        if (input == null) {
            return null;
        }
        try {
            GenerationRagTrace trace = GenerationRagTrace.builder()
                    .generationId(input.generationId())
                    .userId(input.userId())
                    .projectId(input.projectId())
                    .styleProfile(input.styleProfile())
                    .queryText(input.queryText())
                    .retrievedItems(String.valueOf(input.retrieveCount()) + " items")
                    .injectedItems(String.valueOf(input.injectedCount()) + " items")
                    .retrieveChannelStats("{\"channel\":\"" + input.channel() + "\"}")
                    .topSimilarity(input.topSimilarity())
                    .usedUserProfile(input.usedUserProfile())
                    .createTime(LocalDateTime.now())
                    .build();
            traceMapper.insert(trace);
            log.info("RagTraceTool 已保存检索追踪 id={} channel={} retrieved={} injected={}",
                    trace.getId(), input.channel(), input.retrieveCount(), input.injectedCount());
            return trace.getId();
        } catch (Exception e) {
            log.error("RagTraceTool 写入失败 requestId={}", input.requestId(), e);
            return null;
        }
    }
}
