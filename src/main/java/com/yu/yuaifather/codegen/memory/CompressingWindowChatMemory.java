package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 内存优先的窗口对话记忆：
 * <ul>
 *   <li>模型每轮读取的是 JVM 内存中【未压缩】的真实消息，保证 writeFile/readFile 工具内容完整可见；</li>
 *   <li>Redis 仅保存压缩快照用于持久化，快照不再被反向读入模型上下文（旧 MessageWindowChatMemory 每轮从 store 重读压缩历史的缺陷）；</li>
 *   <li>跨请求恢复改走 MySQL 冷加载（{@code loadChatHistoryToMemory}），Redis 快照只作备份。</li>
 * </ul>
 */
@Slf4j
public class CompressingWindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final CompressingChatMemoryStore store;

    private final LinkedList<ChatMessage> messages = new LinkedList<>();

    public CompressingWindowChatMemory(Object id, CompressingChatMemoryStore store, int maxMessages) {
        this.id = id;
        this.store = store;
        this.maxMessages = Math.max(1, maxMessages);
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return new ArrayList<>(messages);
    }

    @Override
    public synchronized void add(ChatMessage message) {
        messages.addLast(message);
        trimToCapacity();
        snapshot();
    }

    @Override
    public synchronized void add(Iterable<ChatMessage> messagesToAdd) {
        for (ChatMessage message : messagesToAdd) {
            messages.addLast(message);
        }
        trimToCapacity();
        snapshot();
    }

    @Override
    public synchronized void clear() {
        messages.clear();
        try {
            store.deleteMessages(id);
        } catch (Exception e) {
            log.warn("清空 Redis 记忆快照失败 memoryId={} msg={}", id, e.getMessage());
        }
    }

    private void trimToCapacity() {
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
    }

    /**
     * 将内存窗口压缩后写入 Redis 快照。仅持久化，模型不会反向读到压缩后的占位符。
     */
    private void snapshot() {
        try {
            if (!messages.isEmpty()) {
                store.updateMessages(id, new ArrayList<>(messages));
            }
        } catch (Exception e) {
            log.warn("记忆快照写 Redis 失败 memoryId={} msg={}", id, e.getMessage());
        }
    }
}
