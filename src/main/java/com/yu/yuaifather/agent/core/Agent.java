package com.yu.yuaifather.agent.core;

import reactor.core.publisher.Flux;

/**
 * Agent 统一接口。
 * <p>
 * 所有 Agent（Product / Architect / Image / Coder / Style / Critic / Deploy / Orchestrator）
 * 均实现该接口，以统一的事件流方式对外暴露执行过程，便于编排与 SSE 透传。
 */
public interface Agent {

    /**
     * 处理一次 Agent 任务。
     *
     * @param ctx 请求上下文（含共享黑板）
     * @return Agent 执行过程中产生的事件流
     */
    Flux<AgentEvent> handle(AgentContext ctx);

    /**
     * Agent 名称（用于日志、事件标记与前端进度展示）。
     *
     * @return Agent 名称
     */
    String name();
}
