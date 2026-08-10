package com.yu.yuaifather.codegen.memory;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * 内存优先的窗口对话记忆。
 *
 * <h3>三层职责</h3>
 * <ul>
 *   <li><b>JVM 内存</b>：保存未压缩的真实消息，是本进程内的事实源，保证 readFile / writeFile 的内容
 *   不会被压缩占位符污染（旧 {@code MessageWindowChatMemory} 每轮从 store 回读压缩历史，
 *   模型会把占位符当成文件真实内容写回磁盘）。</li>
 *   <li><b>{@link #messages()} 渲染</b>：按轮次边界分层——最近 N 轮原样全文，更早历史过
 *   {@link ChatMemoryCompressor}，再按字符预算从最早处裁剪，保证上下文体积不随轮次增长。</li>
 *   <li><b>Redis 快照</b>：仅作备份，不回读、不进入模型上下文；跨请求恢复走 MySQL 冷加载
 *   （{@code ChatHistoryService#loadChatHistoryToMemory}）。</li>
 * </ul>
 *
 * <h3>协议保护</h3>
 * <ul>
 *   <li>SystemMessage 去重：与官方 {@code MessageWindowChatMemory} 对齐，同一份系统提示词只保留一条，
 *   否则框架每轮都会追加一份，窗口里堆积多份长提示词。</li>
 *   <li>工具消息配对：淘汰带 tool_calls 的 AiMessage 时连带淘汰其后的 ToolExecutionResultMessage，
 *   渲染出口再兜底清理孤儿 tool 消息，否则 OpenAI 兼容接口直接 400。</li>
 * </ul>
 */
@Slf4j
public class CompressingWindowChatMemory implements ChatMemory {

    private static final String TRUNCATED_SUFFIX = "...[单条消息超长，已截断]";

    private final Object id;
    private final CompressingChatMemoryStore store;
    private final ChatMemoryCompressor compressor;
    private final MeterRegistry meterRegistry;
    private final ChatMemorySettings settings;

    /** 未压缩的真实消息窗口 */
    private final LinkedList<ChatMessage> messages = new LinkedList<>();

    /** 渲染结果缓存，任何写操作后置空，避免每轮重复跑压缩正则 */
    private List<ChatMessage> renderedCache;

    /** 距上次Redis 快照累积的消息条数 */
    private int messagesSinceSnapshot;

    public CompressingWindowChatMemory(Object id, CompressingChatMemoryStore store,
                                       ChatMemoryCompressor compressor, MeterRegistry meterRegistry,
                                       ChatMemorySettings settings) {
        this.id = id;
        this.store = store;
        this.compressor = compressor;
        this.meterRegistry = meterRegistry;
        this.settings = settings;
    }

    @Override
    public Object id() {
        return id;
    }

    /**
     * 返回发给模型的上下文：最近 N 轮全文 + 更早历史压缩 + 字符预算裁剪。
     */
    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> rendered;
        synchronized (this) {
            if (renderedCache == null) {
                renderedCache = render();
            }
            rendered = new ArrayList<>(renderedCache);
        }
        recordContextChars(rendered);
        return rendered;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> toPersist;
        synchronized (this) {
            addInternal(message);
            toPersist = pollSnapshot(false);
        }
        // 快照涉及压缩与 Redis 写，放到锁外执行，避免持锁做 CPU/网络 IO
        persist(toPersist);
    }

    @Override
    public void add(Iterable<ChatMessage> messagesToAdd) {
        List<ChatMessage> toPersist;
        synchronized (this) {
            for (ChatMessage message : messagesToAdd) {
                addInternal(message);
            }
            // 批量写入（如冷启动加载）结束后强制快照一次，避免逐条触发全窗口压缩
            toPersist = pollSnapshot(true);
        }
        persist(toPersist);
    }

    /**
     * 清空记忆并删除 Redis 快照（{@link ChatMemory} 的标准语义）。
     */
    @Override
    public void clear() {
        clearMemoryOnly();
        try {
            store.deleteMessages(id);
        } catch (Exception e) {
            log.warn("清空 Redis 记忆快照失败 memoryId={} msg={}", id, e.getMessage());
        }
    }

    /**
     * 只清空 JVM 内存窗口，保留 Redis 备份。
     *
     * <p>冷启动加载前的清理、上下文超限后的自愈都应走这里：备份是排障资产，
     * 没有理由在每次实例重建时被顺手删掉。</p>
     */
    public synchronized void clearMemoryOnly() {
        messages.clear();
        renderedCache = null;
        messagesSinceSnapshot = 0;
    }

    // ==================== 写入 ====================

    private void addInternal(ChatMessage message) {
        if (message == null) {
            return;
        }
        if (message instanceof SystemMessage systemMessage && !acceptSystemMessage(systemMessage)) {
            return;
        }
        messages.addLast(truncateIfHuge(message));
        trimToCapacity();
        renderedCache = null;
    }

    /**
     * SystemMessage 去重：内容一致则丢弃，内容变更则替换旧的。
     *
     * @return true 表示需要继续追加
     */
    private boolean acceptSystemMessage(SystemMessage systemMessage) {
        Optional<SystemMessage> existing = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .findAny();
        if (existing.isEmpty()) {
            return true;
        }
        if (existing.get().equals(systemMessage)) {
            return false;
        }
        messages.remove(existing.get());
        return true;
    }

    /**
     * 单条消息硬上限：防止一次超大工具结果 / 超长回复直接把窗口顶爆。
     * 只截断文本载荷，不动工具调用参数（那是模型自己刚写出的内容，需保持可核对）。
     */
    private ChatMessage truncateIfHuge(ChatMessage message) {
        int limit = settings.hardMaxMessageChars();
        if (limit <= 0) {
            return message;
        }
        if (message instanceof ToolExecutionResultMessage result
                && result.text() != null && result.text().length() > limit) {
            return new ToolExecutionResultMessage(result.id(), result.toolName(),
                    StrUtil.subPre(result.text(), limit) + TRUNCATED_SUFFIX);
        }
        if (message instanceof AiMessage aiMessage
                && aiMessage.text() != null && aiMessage.text().length() > limit) {
            String text = StrUtil.subPre(aiMessage.text(), limit) + TRUNCATED_SUFFIX;
            return aiMessage.hasToolExecutionRequests()
                    ? AiMessage.from(text, aiMessage.toolExecutionRequests())
                    : AiMessage.from(text);
        }
        return message;
    }

    /**
     * 窗口淘汰：SystemMessage 永不淘汰；淘汰带 tool_calls 的 AiMessage 时连带淘汰其后的工具结果消息，
     * 否则窗口首部会留下孤儿 role=tool 消息，服务端直接 400。
     */
    private void trimToCapacity() {
        while (messages.size() > settings.maxMessages()) {
            ChatMessage evicted = removeFirstEvictable();
            if (evicted == null) {
                // 只剩系统提示词，停止淘汰
                break;
            }
            if (evicted instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                while (!messages.isEmpty() && messages.getFirst() instanceof ToolExecutionResultMessage) {
                    messages.removeFirst();
                }
            }
        }
    }

    private ChatMessage removeFirstEvictable() {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessage)) {
                return messages.remove(i);
            }
        }
        return null;
    }

    // ==================== 渲染 ====================

    private List<ChatMessage> render() {
        List<ChatMessage> snapshot = new ArrayList<>(messages);
        if (snapshot.isEmpty()) {
            return snapshot;
        }
        int fullTextFrom = resolveFullTextFrom(snapshot);
        List<ChatMessage> rendered = new ArrayList<>(snapshot.size());
        if (fullTextFrom > 0) {
            // 远端历史过压缩器：compressAll 保持工具调用的 id / name 结构不变，配对关系不受影响
            rendered.addAll(compressor.compressAll(new ArrayList<>(snapshot.subList(0, fullTextFrom))));
        }
        rendered.addAll(snapshot.subList(fullTextFrom, snapshot.size()));

        // 系统提示词单独摘出：不参与预算裁剪，最后归位到首条
        SystemMessage systemMessage = extractSystemMessage(rendered);
        rendered = applyCharBudget(rendered, systemMessage == null ? 0 : ChatMessageChars.of(systemMessage));
        rendered = dropLeadingOrphanToolResults(rendered);
        if (systemMessage != null) {
            rendered.add(0, systemMessage);
        }
        return rendered;
    }

    /**
     * 按轮次边界定位全文段起点：从尾部回溯第 {@code fullTextTurns} 个 UserMessage。
     *
     * <p>不能按固定条数切分——VUE 单轮的工具消息常达十几条，按条数会把本轮早期的 readFile 结果
     * 压成占位符，模型据此写文件就会产出残缺内容。</p>
     */
    private int resolveFullTextFrom(List<ChatMessage> snapshot) {
        int turns = 0;
        int from = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i) instanceof UserMessage && ++turns >= settings.fullTextTurns()) {
                from = i;
                break;
            }
        }
        //兜底：单轮消息过多时收缩全文段，收缩点必须对齐消息对边界
        int maxFullText = settings.fullTextMaxMessages();
        if (snapshot.size() - from > maxFullText) {
            from = alignToPairBoundary(snapshot, snapshot.size() - maxFullText);
        }
        return from;
    }

    /**
     * 全文段起点不得落在工具结果消息上，否则该结果对应的 tool_calls 请求会被压缩段与全文段割裂。
     * 向前回退到其所属的 AiMessage(tool_calls) 处即可保证配对完整。
     */
    private int alignToPairBoundary(List<ChatMessage> snapshot, int from) {
        int aligned = Math.max(0, from);
        while (aligned > 0 && snapshot.get(aligned) instanceof ToolExecutionResultMessage) {
            aligned--;
        }
        return aligned;
    }

    private SystemMessage extractSystemMessage(List<ChatMessage> rendered) {
        for (int i = 0; i < rendered.size(); i++) {
            if (rendered.get(i) instanceof SystemMessage systemMessage) {
                rendered.remove(i);
                return systemMessage;
            }
        }
        return null;
    }

    /**
     * 字符预算裁剪：从尾部向前累加，超预算即停止，最新一条始终保留。
     *
     * @param reservedChars 系统提示词等必留内容已占用的字符数
     */
    private List<ChatMessage> applyCharBudget(List<ChatMessage> rendered, int reservedChars) {
        int budget = settings.charBudget();
        if (budget <= 0 || rendered.isEmpty()) {
            return rendered;
        }
        int used = reservedChars;
        int keepFrom = rendered.size() - 1;
        for (int i = rendered.size() - 1; i >= 0; i--) {
            int chars = ChatMessageChars.of(rendered.get(i));
            if (i < rendered.size() - 1 && used + chars > budget) {
                break;
            }
            used += chars;
            keepFrom = i;
        }
        if (keepFrom == 0) {
            return rendered;
        }
        log.info("上下文超预算，丢弃最早 {} 条 memoryId={} budget={}", keepFrom, id, budget);
        return new ArrayList<>(rendered.subList(keepFrom, rendered.size()));
    }

    /**
     * 兜底清理首部孤儿工具结果消息：其对应的 tool_calls 请求已被淘汰或裁剪掉。
     */
    private List<ChatMessage> dropLeadingOrphanToolResults(List<ChatMessage> rendered) {
        int from = 0;
        while (from < rendered.size() && rendered.get(from) instanceof ToolExecutionResultMessage) {
            from++;
        }
        if (from == 0) {
            return rendered;
        }
        log.warn("丢弃 {} 条孤儿工具结果消息 memoryId={}", from, id);
        return new ArrayList<>(rendered.subList(from, rendered.size()));
    }

    private void recordContextChars(List<ChatMessage> rendered) {
        try {
            DistributionSummary.builder("memory.context.chars")
                    .register(meterRegistry)
                    .record(ChatMessageChars.total(rendered));
        } catch (Exception e) {
            log.warn("上下文体积打点失败: {}", e.getMessage());
        }
    }

    // ==================== 快照 ====================

    /**
     * 判断是否到达快照时机，到达则返回内存窗口副本（供锁外持久化）。
     *
     * @param force 是否强制快照（批量写入结束、显式收尾等场景）
     */
    private List<ChatMessage> pollSnapshot(boolean force) {
        messagesSinceSnapshot++;
        if (!force && messagesSinceSnapshot < settings.snapshotEvery()) {
            return null;
        }
        messagesSinceSnapshot = 0;
        return new ArrayList<>(messages);
    }

    /**
     * 写Redis 压缩快照。仅持久化备份，失败不影响生成。
     */
    private void persist(List<ChatMessage> toPersist) {
        if (toPersist == null) {
            return;
        }
        try {
            if (toPersist.isEmpty()) {
                store.deleteMessages(id);
            } else {
                store.updateMessages(id, toPersist);
            }
        } catch (Exception e) {
            log.warn("记忆快照写 Redis 失败 memoryId={} msg={}", id, e.getMessage());
        }
    }
}
