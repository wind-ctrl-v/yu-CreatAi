package com.yu.yuaifather.observability;

/**
 * 一次模型调用的归因。由调用线程放入 {@link LlmCallAttribution}，
 * 再在 {@code ChatModelListener.onRequest} 中复制到 listener attributes，
 * 供异步 / 流式回调线程读取。不要把这些字段放进 MDC。
 */
public record LlmCallScope(String generationId, Long appId, String step) {
}
