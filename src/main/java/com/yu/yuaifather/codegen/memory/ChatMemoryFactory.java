package com.yu.yuaifather.codegen.memory;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对话记忆实例的统一装配出口。
 *
 * <p>主生成链路与增量编辑链路都从这里取记忆实例，避免三处各自 new一遍、
 * 参数扩展时漏改其中一处。</p>
 */
@Component
public class ChatMemoryFactory {

    private final CompressingChatMemoryStore store;
    private final ChatMemoryCompressor compressor;
    private final MeterRegistry meterRegistry;

    /** JVM 内存窗口最大条数 */
    @Value("${agent.memory.max-messages:60}")
    private int maxMessages;

    /** 发给模型时保持全文的最近轮次数 */
    @Value("${agent.memory.full-text-turns:2}")
    private int fullTextTurns;

    /** 全文段条数兜底上限 */
    @Value("${agent.memory.full-text-max-messages:24}")
    private int fullTextMaxMessages;

    /** 上下文字符预算 */
    @Value("${agent.memory.char-budget:80000}")
    private int charBudget;

    /** 单条消息硬截断上限 */
    @Value("${agent.memory.hard-max-message-chars:200000}")
    private int hardMaxMessageChars;

    /** Redis 快照落盘的消息间隔 */
    @Value("${agent.memory.snapshot-every:5}")
    private int snapshotEvery;

    public ChatMemoryFactory(CompressingChatMemoryStore store, ChatMemoryCompressor compressor,
                             MeterRegistry meterRegistry) {
        this.store = store;
        this.compressor = compressor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 创建内存优先的窗口记忆实例
     *
     * @param memoryId 记忆标识，统一由 {@link ChatMemoryIds} 生成
     */
    public CompressingWindowChatMemory create(Object memoryId) {
        ChatMemorySettings settings = new ChatMemorySettings(maxMessages, fullTextTurns, fullTextMaxMessages,
                charBudget, hardMaxMessageChars, snapshotEvery);
        return new CompressingWindowChatMemory(memoryId, store, compressor, meterRegistry, settings);
    }
}
