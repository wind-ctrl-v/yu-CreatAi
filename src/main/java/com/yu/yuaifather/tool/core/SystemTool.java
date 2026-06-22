package com.yu.yuaifather.tool.core;

/**
 * System Tool 接口 —— 确定性工具，由系统固定调用，不暴露给 LLM。
 * <p>
 * System Tool 的实现特点是：
 * <ul>
 *   <li><b>零 Chat Token 消耗</b>：不调用 LLM，纯 Java 或调用外部 API；</li>
 *   <li><b>身份注入</b>：userId/projectId 从 {@link ToolContext} 获取，不由调用方传入；</li>
 *   <li><b>同步执行</b>：结果确定性，不需要异步/流式返回；</li>
 *   <li><b>失败即降级</b>：抛出异常时由调用方（Orchestrator/Assembler）统一处理。</li>
 * </ul>
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface SystemTool<I, O> {

    /** 工具名称，用于日志、事件标记和 trace。 */
    String name();

    /** 执行确定性操作，context 由系统注入。 */
    O execute(I input, ToolContext context);
}
