package com.yu.yuaifather.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Agent 事件模型。
 * <p>
 * 统一各 Agent 在执行过程中向外输出的事件，便于 Orchestrator 透传到 SSE，
 * 前端可据此展示各 Agent 的实时进度与产物。
 */
@Data
@Builder
@AllArgsConstructor
public class AgentEvent {

    /**
     * 事件类型。
     */
    private EventType type;

    /**
     * 产生该事件的 Agent 名称。
     */
    private String agentName;

    /**
     * 文本内容（TEXT_CHUNK / STATUS_CHANGE / ERROR 时使用）。
     */
    private String content;

    /**
     * 附加结构化数据（如工具名、工具参数、阶段产物摘要等）。
     */
    private Object data;

    /**
     * 事件类型枚举。
     */
    public enum EventType {
        /** 文本增量片段（LLM 流式输出）。 */
        TEXT_CHUNK,
        /** 工具调用请求。 */
        TOOL_CALL,
        /** 工具执行结果。 */
        TOOL_RESULT,
        /** Agent 阶段状态变更（开始 / 完成）。 */
        STATUS_CHANGE,
        /** 错误事件。 */
        ERROR
    }

    /**
     * 构造文本增量事件。
     */
    public static AgentEvent text(String agentName, String content) {
        return AgentEvent.builder()
                .type(EventType.TEXT_CHUNK)
                .agentName(agentName)
                .content(content)
                .build();
    }

    /**
     * 构造状态变更事件。
     */
    public static AgentEvent status(String agentName, String content) {
        return AgentEvent.builder()
                .type(EventType.STATUS_CHANGE)
                .agentName(agentName)
                .content(content)
                .build();
    }

    /**
     * 构造错误事件。
     */
    public static AgentEvent error(String agentName, String content) {
        return AgentEvent.builder()
                .type(EventType.ERROR)
                .agentName(agentName)
                .content(content)
                .build();
    }
}
