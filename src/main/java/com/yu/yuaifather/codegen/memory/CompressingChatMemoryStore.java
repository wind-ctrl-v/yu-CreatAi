package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 压缩型对话记忆存储：包装 {@link RedisChatMemoryStore} 的快照落盘拦截点。
 *
 * <p>{@link CompressingWindowChatMemory} 按间隔把JVM 内存窗口交给{@link #updateMessages}，
 * 在此压缩后再写入 Redis。<b>该快照只是备份</b>：不回读、不进入模型上下文，
 * 模型看到的内容一律由内存窗口渲染而来，避免压缩占位符被当成文件真实内容写回磁盘。</p>
 *
 * <p>MySQL 入库路径（StreamHandler 的 doOnComplete）完全不经过这里，全文不受影响，
 * 跨请求恢复由 {@code ChatHistoryService#loadChatHistoryToMemory} 从 MySQL 冷加载。</p>
 *
 * <p>压缩是幂等的：已压缩的消息不含代码块且低于阈值，再次压缩为无操作。</p>
 */
@Slf4j
@Component
public class CompressingChatMemoryStore implements ChatMemoryStore {

    private final RedisChatMemoryStore delegate;
    private final ChatMemoryCompressor compressor;
    private final MeterRegistry meterRegistry;

    public CompressingChatMemoryStore(RedisChatMemoryStore delegate, ChatMemoryCompressor compressor,
                                      MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.compressor = compressor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 读取 Redis 压缩快照。
     *
     * <p>注意：当前不在生成热路径上——模型上下文来自 {@link CompressingWindowChatMemory} 的内存窗口，
     * 此方法仅供人工排障或未来的恢复策略使用。</p>
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return delegate.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        int beforeChars = ChatMessageChars.total(messages);
        List<ChatMessage> compressed = compressor.compressAll(messages);
        int afterChars = ChatMessageChars.total(compressed);
        if (afterChars < beforeChars) {
            log.info("记忆压缩 memoryId={} {}→{} 字符", memoryId, beforeChars, afterChars);
        }
        // 指标：memory.compress.chars{phase=before/after}，用于观测压缩率
        try {
            DistributionSummary.builder("memory.compress.chars").tags("phase", "before")
                    .register(meterRegistry).record(beforeChars);
            DistributionSummary.builder("memory.compress.chars").tags("phase", "after")
                    .register(meterRegistry).record(afterChars);
        } catch (Exception e) {
            log.warn("压缩指标打点失败: {}", e.getMessage());
        }
        delegate.updateMessages(memoryId, compressed);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(memoryId);
    }
}
