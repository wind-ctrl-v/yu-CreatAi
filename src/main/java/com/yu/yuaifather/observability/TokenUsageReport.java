package com.yu.yuaifather.observability;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.List;

/**
 * 一次生成的 token 快照。顶层计数是刚完成的那个步骤；{@code steps} 与合计覆盖整次生成。
 */
public record TokenUsageReport(
        String generationId,
        Long appId,
        String step,
        long stepInputTokens,
        long stepOutputTokens,
        long stepTotalTokens,
        int stepCallCount,
        int stepUnknownUsageCalls,
        String model,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        int callCount,
        int unknownUsageCalls,
        List<StepTokenUsage> steps
) {

    public String toStepsJson() {
        JSONArray array = new JSONArray();
        if (steps != null) {
            for (StepTokenUsage stepUsage : steps) {
                JSONObject item = new JSONObject(true);
                item.set("step", stepUsage.step());
                item.set("inputTokens", stepUsage.inputTokens());
                item.set("outputTokens", stepUsage.outputTokens());
                item.set("totalTokens", stepUsage.totalTokens());
                item.set("callCount", stepUsage.callCount());
                item.set("unknownUsageCalls", stepUsage.unknownUsageCalls());
                if (stepUsage.model() != null) {
                    item.set("model", stepUsage.model());
                }
                array.add(item);
            }
        }
        JSONObject root = new JSONObject(true);
        root.set("steps", array);
        return root.toString();
    }
}
