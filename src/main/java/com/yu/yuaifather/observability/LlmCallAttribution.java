package com.yu.yuaifather.observability;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 把 generationId / appId / 步骤名绑定到当前线程上的模型调用。
 *
 * <p>只在发起 {@code chat()} 的线程上读取。监听器必须在 {@code onRequest}
 * 里把本对象复制进 listener attributes，之后禁止再读本 ThreadLocal 或 MDC。</p>
 */
public final class LlmCallAttribution {

    public static final String ATTR_GENERATION_ID = "llm.generationId";
    public static final String ATTR_APP_ID = "llm.appId";
    public static final String ATTR_STEP = "llm.step";

    private static final int MAX_GENERATION_ID = 128;
    private static final int MAX_STEP = 64;

    private static final ThreadLocal<LlmCallScope> CURRENT = new ThreadLocal<>();

    private LlmCallAttribution() {
    }

    public static LlmCallScope current() {
        return CURRENT.get();
    }

    /**
     * 打开一层作用域。关闭时恢复外层作用域，避免线程池复用时串到下一次调用。
     */
    public static Scope open(String generationId, Long appId, String step) {
        LlmCallScope previous = CURRENT.get();
        CURRENT.set(new LlmCallScope(cleanId(generationId), appId, normalizeStep(step)));
        return new Scope(previous);
    }

    /**
     * 在订阅 {@code source} 期间打开作用域，使订阅线程上同步发起的 {@code chat()} 能被归因。
     * 作用域在订阅返回后关闭；流式后续轮次由 {@link AttributingStreamingChatModel} 传播。
     */
    public static <T> Flux<T> scopedFlux(Flux<T> source, String generationId, Long appId, String step) {
        Flux<T> upstream = source == null ? Flux.empty() : source;
        return Flux.create(sink -> {
            Disposable disposable = null;
            try (Scope ignored = open(generationId, appId, step)) {
                disposable = upstream.subscribe(sink::next, sink::error, sink::complete);
            }
            if (disposable != null) {
                Disposable toDispose = disposable;
                sink.onDispose(toDispose::dispose);
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public static void runWith(LlmCallScope scope, Runnable action) {
        if (scope == null) {
            action.run();
            return;
        }
        LlmCallScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callWith(LlmCallScope scope, Supplier<T> action) {
        if (scope == null) {
            return action.get();
        }
        LlmCallScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * 把当前线程的归因写入 listener attributes。没有作用域时显式标成 {@code unattributed}，不丢弃。
     */
    public static void stamp(Map<Object, Object> attributes) {
        if (attributes == null) {
            return;
        }
        LlmCallScope scope = CURRENT.get();
        if (scope == null) {
            attributes.put(ATTR_STEP, LlmSteps.UNATTRIBUTED);
            return;
        }
        if (scope.generationId() != null) {
            attributes.put(ATTR_GENERATION_ID, scope.generationId());
        }
        if (scope.appId() != null) {
            attributes.put(ATTR_APP_ID, scope.appId());
        }
        attributes.put(ATTR_STEP, scope.step());
    }

    /**
     * 只从 attributes 还原归因。调用方不得再查 ThreadLocal 或 MDC。
     */
    public static LlmCallScope fromAttributes(Map<Object, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return new LlmCallScope(null, null, LlmSteps.UNATTRIBUTED);
        }
        Object generation = attributes.get(ATTR_GENERATION_ID);
        Object app = attributes.get(ATTR_APP_ID);
        Object step = attributes.get(ATTR_STEP);
        String generationId = generation == null || String.valueOf(generation).isBlank()
                ? null : String.valueOf(generation);
        return new LlmCallScope(generationId, toLong(app), normalizeStep(step == null ? null : String.valueOf(step)));
    }

    private static void restore(LlmCallScope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static Long toLong(Object app) {
        if (app instanceof Long value) {
            return value;
        }
        if (app instanceof Number number) {
            return number.longValue();
        }
        if (app instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String cleanId(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            return null;
        }
        String trimmed = generationId.trim();
        return trimmed.length() > MAX_GENERATION_ID ? trimmed.substring(0, MAX_GENERATION_ID) : trimmed;
    }

    static String normalizeStep(String step) {
        if (step == null || step.isBlank()) {
            return LlmSteps.UNATTRIBUTED;
        }
        String normalized = step.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) {
            return LlmSteps.UNATTRIBUTED;
        }
        return normalized.length() > MAX_STEP ? normalized.substring(0, MAX_STEP) : normalized;
    }

    public static final class Scope implements AutoCloseable {
        private final LlmCallScope previous;
        private boolean closed;

        private Scope(LlmCallScope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            restore(previous);
        }
    }
}
