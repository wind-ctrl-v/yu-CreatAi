package com.yu.yuaifather.observability;

/**
 * 单个步骤的累计 token。多次模型调用（规划的两次请求、质检多轮、工具往返）合并在同一行。
 */
public record StepTokenUsage(
        String step,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        int callCount,
        int unknownUsageCalls,
        String model
) {
}
