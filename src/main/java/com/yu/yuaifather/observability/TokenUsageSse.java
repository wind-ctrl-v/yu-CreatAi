package com.yu.yuaifather.observability;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.springframework.http.codec.ServerSentEvent;

/**
 * 把用量快照编成 SSE {@code token-usage} 事件。载荷是整次生成的步骤表，前端可以直接替换。
 */
public final class TokenUsageSse {

    public static final String EVENT_NAME = "token-usage";
    public static final String EVENT_TYPE = "token_usage";

    private TokenUsageSse() {
    }

    public static ServerSentEvent<String> toEvent(TokenUsageReport report) {
        return ServerSentEvent.<String>builder()
                .event(EVENT_NAME)
                .data(toJson(report))
                .build();
    }

    public static String toJson(TokenUsageReport report) {
        JSONObject body = new JSONObject(true);
        body.set("event", EVENT_TYPE);
        body.set("generationId", report.generationId());
        body.set("appId", report.appId());
        body.set("step", report.step());
        body.set("inputTokens", report.stepInputTokens());
        body.set("outputTokens", report.stepOutputTokens());
        body.set("totalTokens", report.stepTotalTokens());
        body.set("callCount", report.stepCallCount());
        body.set("unknownUsageCalls", report.stepUnknownUsageCalls());
        if (report.model() != null) {
            body.set("model", report.model());
        }
        JSONObject totals = new JSONObject(true);
        totals.set("inputTokens", report.inputTokens());
        totals.set("outputTokens", report.outputTokens());
        totals.set("totalTokens", report.totalTokens());
        totals.set("callCount", report.callCount());
        totals.set("unknownUsageCalls", report.unknownUsageCalls());
        body.set("totals", totals);
        body.set("steps", stepsJson(report));
        return body.toString();
    }

    private static JSONArray stepsJson(TokenUsageReport report) {
        JSONArray array = new JSONArray();
        if (report.steps() == null) {
            return array;
        }
        for (StepTokenUsage step : report.steps()) {
            JSONObject item = new JSONObject(true);
            item.set("step", step.step());
            item.set("inputTokens", step.inputTokens());
            item.set("outputTokens", step.outputTokens());
            item.set("totalTokens", step.totalTokens());
            item.set("callCount", step.callCount());
            item.set("unknownUsageCalls", step.unknownUsageCalls());
            if (step.model() != null) {
                item.set("model", step.model());
            }
            array.add(item);
        }
        return array;
    }
}
