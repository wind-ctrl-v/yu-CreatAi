package com.yu.yuaifather.tool.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ToolCallTraceService：异步记录每次 Tool 调用的追踪信息。
 * <p>
 * 落表到 tool_call_trace，记录输入/输出摘要、耗时、成败，
 * 与 generation_rag_trace 通过 request_id 关联形成完整追踪链路。
 * <p>
 * 写库失败不影响主业务流程。
 */
@Slf4j
@Service
public class ToolCallTraceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ToolCallTraceService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步记录一次 Tool 调用。
     */
    @Async
    public void recordTrace(TraceRecord record) {
        if (record == null) return;
        try {
            String inputJson = safeJson(record.inputSummary());
            String outputJson = safeJson(record.outputSummary());
            jdbc.update(
                    "INSERT INTO tool_call_trace " +
                    "(request_id, generation_id, caller_type, caller_name, tool_type, tool_name, " +
                    "input_summary, output_summary, success, error_message, latency_ms, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    record.requestId(), record.generationId(),
                    record.callerType(), record.callerName(),
                    record.toolType(), record.toolName(),
                    inputJson, outputJson,
                    record.success(), record.errorMessage(),
                    record.latencyMs(), LocalDateTime.now()
            );
        } catch (Exception e) {
            log.error("ToolCallTraceService 记录失败 tool={}", record != null ? record.toolName() : null, e);
        }
    }

    /**
     * 记录一次成功的 System Tool 调用。
     */
    public void traceSystemTool(String requestId, Long generationId,
                                 String callerName, String toolName,
                                 Object input, Object output, long latencyMs) {
        recordTrace(new TraceRecord(
                requestId, generationId,
                "ORCHESTRATOR", callerName,
                "SYSTEM_TOOL", toolName,
                input, output, true, null, latencyMs
        ));
    }

    /**
     * 记录一次失败的调用。
     */
    public void traceFailure(String requestId, Long generationId,
                              String callerName, String toolName,
                              Object input, String errorMessage, long latencyMs) {
        recordTrace(new TraceRecord(
                requestId, generationId,
                "ORCHESTRATOR", callerName,
                "SYSTEM_TOOL", toolName,
                input, null, false, errorMessage, latencyMs
        ));
    }

    private String safeJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            String json = objectMapper.writeValueAsString(obj);
            return json.length() > 2000 ? json.substring(0, 2000) : json;
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    /**
     * 追踪记录值对象。
     */
    @Builder
    public record TraceRecord(
            String requestId,
            Long generationId,
            String callerType,
            String callerName,
            String toolType,
            String toolName,
            Object inputSummary,
            Object outputSummary,
            Boolean success,
            String errorMessage,
            Long latencyMs
    ) {}
}
