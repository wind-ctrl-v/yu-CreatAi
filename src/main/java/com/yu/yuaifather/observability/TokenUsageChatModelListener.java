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
 * LLM 调用观测监听器：统一记录全部模型调用的 token 用量 / 耗时 / 模型名。
 *
 * <p>注册覆盖：{@code chatModel} 与 {@code openAiStreamingChatModel} 由 langchain4j starter
 * 自动收集本 Bean；{@code reasoningStreamingChatModel} 在
 * {@code ReasoningStreamingChatModelConfig} 中显式注册。</p>
 *
 * <p>约束：流式回调运行在 HTTP client 线程，<b>禁止依赖 MDC</b>；
 * 打点失败仅 log.warn，绝不阻断模型调用主流程。</p>
 */
@Slf4j
@Component
public class TokenUsageChatModelListener implements ChatModelListener {

    /**
     * 请求起始时间（nanoTime），存放在跨 request/response/error 共享的 attributes 中
     */
    private static final String START_NANO_KEY = "llmCallStartNano";

    private final MeterRegistry meterRegistry;

    public TokenUsageChatModelListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        try {
            requestContext.attributes().put(START_NANO_KEY, System.nanoTime());
        } catch (Exception e) {
            log.warn("LLM 请求打点失败: {}", e.getMessage());
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        try {
            ChatResponse response = responseContext.chatResponse();
            String model = response == null ? "unknown" : response.modelName();
            TokenUsage usage = response == null ? null : response.tokenUsage();
            long elapsedMs = elapsedMs(responseContext.attributes());
            if (usage == null) {
                log.info("LLM 调用成功 model={} token=未知(上游未返回) 耗时={}ms", model, elapsedMs);
            } else {
                log.info("LLM 调用成功 model={} input={} output={} total={} 耗时={}ms",
                        model, usage.inputTokenCount(), usage.outputTokenCount(),
                        usage.totalTokenCount(), elapsedMs);
                // 指标：llm.tokens{model, direction}
                if (usage.inputTokenCount() != null) {
                    Counter.builder("llm.tokens").tags("model", safeModel(model), "direction", "input")
                            .register(meterRegistry).increment(usage.inputTokenCount());
                }
                if (usage.outputTokenCount() != null) {
                    Counter.builder("llm.tokens").tags("model", safeModel(model), "direction", "output")
                            .register(meterRegistry).increment(usage.outputTokenCount());
                }
            }
            // 指标：llm.call.duration{model, result}
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
            long elapsedMs = elapsedMs(errorContext.attributes());
            Throwable error = errorContext.error();
            String model = modelName(errorContext.chatRequest());
            log.warn("LLM 调用失败 model={} type={} err={} 耗时={}ms",
                    model,
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

    private String modelName(ChatRequest chatRequest) {
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
}
