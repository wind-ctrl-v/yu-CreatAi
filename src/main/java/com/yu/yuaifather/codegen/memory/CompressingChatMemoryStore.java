package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 压缩型对话记忆存储：包装 {@link RedisChatMemoryStore} 的运行时拦截点。
 *
 * <p>AI 流式回复完成后，LangChain4j 会将新消息 add 到 MessageWindowChatMemory，
 * 内部回调 {@link #updateMessages}，在此拦截并压缩后再写入 Redis。
 * MySQL 入库路径（StreamHandler 的 doOnComplete）完全不经过这里，全文不受影响。</p>
 *
 * <p>压缩是幂等的：已压缩的消息不含代码块且低于阈值，再次压缩为无操作。</p>
 */
@Slf4j
@Component
public class CompressingChatMemoryStore implements ChatMemoryStore {

    private final RedisChatMemoryStore delegate;
    private final ChatMemoryCompressor compressor;

    public CompressingChatMemoryStore(RedisChatMemoryStore delegate, ChatMemoryCompressor compressor) {
        this.delegate = delegate;
        this.compressor = compressor;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return delegate.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        int beforeChars = totalChars(messages);
        List<ChatMessage> compressed = compressor.compressAll(messages);
        int afterChars = totalChars(compressed);
        if (afterChars < beforeChars) {
            log.info("记忆压缩 memoryId={} {}→{} 字符", memoryId, beforeChars, afterChars);
        }
        delegate.updateMessages(memoryId, compressed);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(memoryId);
    }

    /**
     * 估算消息列表的总字符数，用于压缩效果观测打点。
     */
    private int totalChars(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += messageChars(message);
        }
        return total;
    }

    private int messageChars(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            try {
                String text = userMessage.singleText();
                return text == null ? 0 : text.length();
            } catch (Exception e) {
                return 0;
            }
        }
        if (message instanceof AiMessage aiMessage) {
            int chars = aiMessage.text() == null ? 0 : aiMessage.text().length();
            if (aiMessage.toolExecutionRequests() != null) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (request != null && request.arguments() != null) {
                        chars += request.arguments().length();
                    }
                }
            }
            return chars;
        }
        if (message instanceof ToolExecutionResultMessage resultMessage) {
            return resultMessage.text() == null ? 0 : resultMessage.text().length();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text() == null ? 0 : systemMessage.text().length();
        }
        return 0;
    }
}
