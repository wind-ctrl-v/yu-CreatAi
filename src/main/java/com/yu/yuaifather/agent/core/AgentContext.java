package com.yu.yuaifather.agent.core;

import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.model.entity.User;
import lombok.Builder;
import lombok.Data;

/**
 * Agent 请求上下文。
 * <p>
 * 在整条 Agent 流水线中传递，承载本次代码生成请求的不可变输入，
 * 以及线程安全的共享黑板 {@link Blackboard}（各 Agent 通过黑板读写中间产物）。
 */
@Data
@Builder
public class AgentContext {

    /**
     * 应用 ID（同时作为 LangChain4j 的 MemoryId 与项目工作目录标识）。
     */
    private Long appId;

    /**
     * 发起请求的登录用户。
     */
    private User loginUser;

    /**
     * 用户原始需求 prompt。
     */
    private String userPrompt;

    /**
     * 由路由器决定的代码生成类型。
     */
    private CodeGenTypeEnum codeGenType;

    /**
     * 共享黑板，用于在各 Agent 之间传递中间状态（requirementDoc / plan / imageMap / criticReport 等）。
     */
    @Builder.Default
    private Blackboard blackboard = new Blackboard();
}
