package com.yu.yuaifather.tool.core;

/**
 * Agent Tool 接口 —— 允许 LLM Agent 在白名单内按需调用的工具。
 * <p>
 * 与 System Tool 的关键区别：
 * <ul>
 *   <li>暴露给 LLM Agent 通过 Function Calling 按需调用；</li>
 *   <li>需提供 {@link #schema()} 描述工具能力（名称、描述、参数定义）；</li>
 *   <li>身份信息（userId/projectId）从 {@link ToolContext} 注入，LLM 不可自由传入；</li>
 *   <li>受白名单、最大调用次数、超时等限制。</li>
 * </ul>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface AgentTool<I, O> {

    /** 工具名称，用于白名单注册和日志。 */
    String name();

    /** 返回 Tool Schema（名称、描述、参数定义），供 LLM Function Calling 使用。 */
    ToolSchema schema();

    /** 执行操作，context 由系统注入。 */
    O execute(I input, ToolContext context);
}
