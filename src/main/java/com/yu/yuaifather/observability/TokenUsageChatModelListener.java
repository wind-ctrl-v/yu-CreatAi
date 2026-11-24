package com.yu.yuaifather.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * LLM 调用观测监听器：统一记录全部模型调用的 token 用量 / 耗时 / 模型名，
 * 并按 listener attributes 中的 generationId、appId、步骤名归到当次生成。
 *
 * <p>注册覆盖：{@code chatModel} 与 {@code openAiStreamingChatModel} 由 langchain4j starter
 * 自动收集本 Bean；{@code reasoningStreamingChatModel} 在
 * {@code ReasoningStreamingChatModelConfig} 中显式注册。</p>
 *
 * <p>约束：流式回调运行在 HTTP client 线程，<b>禁止依赖 MDC 或 ThreadLocal</b>。
 * {@code onRequest} 在发起调用的线程上把 {@link LlmCallAttribution} 复制进 attributes，
 * {@code onResponse} / {@code onError} 只读这份 attributes。打点失败仅 log.warn，绝不阻断模型调用。</p>
 */
@Slf4j
@Component
public class TokenUsageChatModelListener implements ChatModelListener {

    /**
     * 请求起始时间（nanoTime），存放在跨 request/response/error 共享的 attributes 中
     */
    private static final String START_NANO_KEY = "llmCallStartNano";

    private final MeterRegistry meterRegistry;
    private final GenerationTokenUsageService tokenUsageService;

    public TokenUsageChatModelListener(MeterRegistry meterRegistry,
                                       GenerationTokenUsageService tokenUsageService) {
        this.meterRegistry = meterRegistry;
        this.tokenUsageService = tokenUsageService;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        try {
            if (requestContext == null || requestContext.attributes() == null) {
                return;
            }
            requestContext.attributes().put(START_NANO_KEY, System.nanoTime());
            LlmCallAttribution.stamp(requestContext.attributes());
        } catch (Exception e) {
            log.warn("LLM 请求打点失败: {}", e.getMessage());
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        try {
            if (responseContext == null) {
                return;
            }
            ChatResponse response = responseContext.chatResponse();
            String model = modelOf(response, responseContext.chatRequest());
            TokenUsage usage = response == null ? null : response.tokenUsage();
            long elapsedMs = elapsedMs(responseContext.attributes());
            LlmCallScope scope = LlmCallAttribution.fromAttributes(responseContext.attributes());
            if (usage == null || isUnknown(usage)) {
                log.info("LLM 调用成功 model={} generationId={} appId={} step={} token=未知(上游未返回) 耗时={}ms",
                        model, dash(scope.generationId()), dash(scope.appId()), scope.step(), elapsedMs);
            } else {
                log.info("LLM 调用成功 model={} generationId={} appId={} step={} input={} output={} total={} 耗时={}ms",
                        model, dash(scope.generationId()), dash(scope.appId()), scope.step(),
                        usage.inputTokenCount(), usage.outputTokenCount(),
                        usage.totalTokenCount(), elapsedMs);
                if (usage.inputTokenCount() != null) {
                    Counter.builder("llm.tokens").tags("model", safeModel(model), "direction", "input")
                            .register(meterRegistry).increment(usage.inputTokenCount());
                }
                if (usage.outputTokenCount() != null) {
                    Counter.builder("llm.tokens").tags("model", safeModel(model), "direction", "output")
                            .register(meterRegistry).increment(usage.outputTokenCount());
                }
            }
            tokenUsageService.record(scope.generationId(), scope.appId(), scope.step(), safeModel(model), usage);
            if (elapsedMs >= 0) {
                Timer.builder("llm.call.duration").tags("model", safeModel(model), "result", "success")
                        .register(meterRegistry).record(Duration.ofMillis(elapsedMs));
            }
        } catch (Exception e) {
            log.warn("LLM 响应打点失败: {}", e.getMessage());
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        try {
            if (errorContext == null) {
                return;
            }
            long elapsedMs = elapsedMs(errorContext.attributes());
            Throwable error = errorContext.error();
            String model = modelName(errorContext.chatRequest());
            LlmCallScope scope = LlmCallAttribution.fromAttributes(errorContext.attributes());
            log.warn("LLM 调用失败 model={} generationId={} appId={} step={} type={} err={} 耗时={}ms",
                    model,
                    dash(scope.generationId()),
                    dash(scope.appId()),
                    scope.step(),
                    error == null ? "unknown" : error.getClass().getSimpleName(),
                    error == null ? null : error.getMessage(), elapsedMs);
            if (elapsedMs >= 0) {
                Timer.builder("llm.call.duration").tags("model", safeModel(model), "result", "error")
                        .register(meterRegistry).record(Duration.ofMillis(elapsedMs));
            }
        } catch (Exception e) {
            log.warn("LLM 错误打点失败: {}", e.getMessage());
        }
    }

    private static boolean isUnknown(TokenUsage usage) {
        return usage.inputTokenCount() == null
                && usage.outputTokenCount() == null
                && usage.totalTokenCount() == null;
    }

    private static String modelOf(ChatResponse response, ChatRequest chatRequest) {
        if (response != null && response.modelName() != null && !response.modelName().isBlank()) {
            return response.modelName();
        }
        return modelName(chatRequest);
    }

    /**
     * 从共享 attributes 中取出起始时间计算耗时，取不到返回 -1
     */
    private long elapsedMs(Map<Object, Object> attributes) {
        if (attributes == null) {
            return -1;
        }
        Object start = attributes.get(START_NANO_KEY);
        if (start instanceof Long startNano) {
            return (System.nanoTime() - startNano) / 1_000_000;
        }
        return -1;
    }

    private static String modelName(ChatRequest chatRequest) {
        if (chatRequest == null || chatRequest.parameters() == null
                || chatRequest.parameters().modelName() == null) {
            return "unknown";
        }
        return chatRequest.parameters().modelName();
    }

    /**
     * 指标标签值兜底，避免 null 导致指标注册异常
     */
    private String safeModel(String model) {
        return model == null || model.isBlank() ? "unknown" : model;
    }

    private static String dash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
