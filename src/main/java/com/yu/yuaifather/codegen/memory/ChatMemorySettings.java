package com.yu.yuaifather.codegen.memory;

/**
 * 对话记忆窗口的容量与渲染参数。
 *
 * @param maxMessages          JVM 内存窗口保留的最大消息条数（内存存全文，不可沿用压缩形态下的大值）
 * @param fullTextTurns        发给模型时保持全文的最近轮次数，以 UserMessage 为轮次边界
 * @param fullTextMaxMessages  全文段条数兜底上限，防止单轮工具消息过多把预算吃光
 * @param charBudget           发给模型的上下文字符预算，超出则从最早消息开始丢弃；<=0 表示不限制
 * @param hardMaxMessageChars  单条消息入内存前的硬截断上限；<=0 表示不限制
 * @param snapshotEvery        每累积多少条消息向 Redis 落一次压缩快照（快照仅为备份，不参与模型上下文）
 */
public record ChatMemorySettings(int maxMessages,
                                 int fullTextTurns,
                                 int fullTextMaxMessages,
                                 int charBudget,
                                 int hardMaxMessageChars,
                                 int snapshotEvery) {

    public ChatMemorySettings {
        maxMessages = Math.max(1, maxMessages);
        fullTextTurns = Math.max(1, fullTextTurns);
        fullTextMaxMessages = Math.max(1, fullTextMaxMessages);
        snapshotEvery = Math.max(1, snapshotEvery);
    }
}
