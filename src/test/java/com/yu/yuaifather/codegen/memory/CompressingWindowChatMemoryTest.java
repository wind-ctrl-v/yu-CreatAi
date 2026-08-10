package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CompressingWindowChatMemory} 单测：协议保护（SystemMessage 去重、工具消息配对）
 * 与分层渲染（轮次边界全文、字符预算裁剪）。
 */
class CompressingWindowChatMemoryTest {

    private static final String MEMORY_ID = "1#vue_project";
    /** 超过压缩阈值 1500，用于验证远端历史确实被压缩 */
    private static final String BIG_FILE_CONTENT = "x".repeat(2000);

    private CompressingChatMemoryStore store;
    private ChatMemoryCompressor compressor;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        store = mock(CompressingChatMemoryStore.class);
        compressor = new ChatMemoryCompressor();
        ReflectionTestUtils.setField(compressor, "compressThreshold", 1500);
        ReflectionTestUtils.setField(compressor, "explainMaxChars", 500);
        meterRegistry = new SimpleMeterRegistry();
    }

    private CompressingWindowChatMemory memory(ChatMemorySettings settings) {
        return new CompressingWindowChatMemory(MEMORY_ID, store, compressor, meterRegistry, settings);
    }

    /** 默认：不硬截断、几乎不快照，便于聚焦窗口与渲染行为 */
    private ChatMemorySettings settings(int maxMessages, int fullTextTurns, int charBudget) {
        return new ChatMemorySettings(maxMessages, fullTextTurns, 100, charBudget, 0, 100);
    }

    private AiMessage readFileRequest(String toolId, String path) {
        return AiMessage.from("", List.of(ToolExecutionRequest.builder()
                .id(toolId)
                .name("readFile")
                .arguments("{\"relativeFilePath\":\"" + path + "\"}")
                .build()));
    }

    @Test
    void add_sameSystemMessage_keptOnlyOnce() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        SystemMessage system = SystemMessage.from("你是代码生成器");

        for (int i = 0; i < 3; i++) {
            memory.add(system);
            memory.add(UserMessage.from("第" + i + "轮"));
        }

        long systemCount = memory.messages().stream().filter(SystemMessage.class::isInstance).count();
        assertEquals(1, systemCount, "重复的系统提示词必须去重，否则每轮堆积一份");
    }

    @Test
    void add_changedSystemMessage_replacesOldOne() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        memory.add(SystemMessage.from("旧提示词"));
        memory.add(UserMessage.from("你好"));
        memory.add(SystemMessage.from("新提示词"));

        List<SystemMessage> systems = memory.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .toList();
        assertEquals(1, systems.size());
        assertEquals("新提示词", systems.get(0).text());
    }

    @Test
    void trim_evictingToolCallMessage_alsoEvictsItsResult() {
        CompressingWindowChatMemory memory = memory(settings(3, 1, 0));
        memory.add(UserMessage.from("请修改首页"));
        memory.add(readFileRequest("t1", "src/App.vue"));
        memory.add(new ToolExecutionResultMessage("t1", "readFile", "文件内容"));
        memory.add(AiMessage.from("已完成"));
        // 再来一条，淘汰点正好落在 tool_calls 与 tool 结果之间
        memory.add(UserMessage.from("再改一次"));

        List<ChatMessage> rendered = memory.messages();
        assertFalse(rendered.stream().anyMatch(ToolExecutionResultMessage.class::isInstance),
                "tool_calls 被淘汰后，其结果消息必须一起淘汰，否则请求里会出现孤儿 role=tool 消息");
    }

    @Test
    void trim_neverEvictsSystemMessage() {
        CompressingWindowChatMemory memory = memory(settings(2, 1, 0));
        memory.add(SystemMessage.from("系统提示词"));
        memory.add(UserMessage.from("u1"));
        memory.add(UserMessage.from("u2"));
        memory.add(UserMessage.from("u3"));

        List<ChatMessage> rendered = memory.messages();
        assertTrue(rendered.stream().anyMatch(SystemMessage.class::isInstance), "系统提示词不可被窗口淘汰");
        assertTrue(rendered.stream().anyMatch(m -> m instanceof UserMessage u && "u3".equals(u.singleText())));
    }

    @Test
    void messages_currentTurnKeepsFullText_olderTurnCompressed() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        // 第1 轮
        memory.add(UserMessage.from("生成首页"));
        memory.add(readFileRequest("t1", "src/Old.vue"));
        memory.add(new ToolExecutionResultMessage("t1", "readFile", BIG_FILE_CONTENT));
        memory.add(AiMessage.from("首页已生成"));
        // 第 2 轮：同轮内先readFile 再写文件，早期的 readFile 结果不能被压缩
        memory.add(UserMessage.from("改标题"));
        memory.add(readFileRequest("t2", "src/New.vue"));
        memory.add(new ToolExecutionResultMessage("t2", "readFile", BIG_FILE_CONTENT));
        memory.add(AiMessage.from("标题已修改"));

        List<ToolExecutionResultMessage> results = memory.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
        assertEquals(2, results.size());
        assertTrue(results.get(0).text().contains("内容已省略"), "上一轮的 readFile 结果应被压缩");
        assertEquals(BIG_FILE_CONTENT, results.get(1).text(),
                "本轮的 readFile 结果必须保持全文，否则模型会照着占位符写出残缺文件");
    }

    @Test
    void messages_overCharBudget_dropsEarliestMessages() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 100));
        memory.add(UserMessage.from("a".repeat(60)));
        memory.add(UserMessage.from("b".repeat(60)));
        memory.add(UserMessage.from("c".repeat(60)));

        List<ChatMessage> rendered = memory.messages();
        assertTrue(ChatMessageChars.total(rendered) <= 100, "渲染结果必须落在字符预算内");
        assertEquals("c".repeat(60), ((UserMessage) rendered.get(rendered.size() - 1)).singleText());
    }

    @Test
    void messages_systemMessageMovedToFirst() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        memory.add(UserMessage.from("你好"));
        memory.add(AiMessage.from("在的"));
        memory.add(SystemMessage.from("系统提示词"));

        assertInstanceOf(SystemMessage.class, memory.messages().get(0), "渲染出口应把系统提示词归位到首条");
    }

    @Test
    void clearMemoryOnly_keepsRedisSnapshot_whileClearDeletesIt() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        memory.add(UserMessage.from("你好"));

        memory.clearMemoryOnly();
        assertTrue(memory.messages().isEmpty());
        verify(store, never()).deleteMessages(any());

        memory.clear();
        verify(store).deleteMessages(MEMORY_ID);
    }

    @Test
    void add_snapshotFailure_doesNotBreakGeneration() {
        // snapshotEvery = 1：每条消息都尝试落快照
        CompressingWindowChatMemory memory = memory(new ChatMemorySettings(50, 1, 100, 0, 0, 1));
        doThrow(new RuntimeException("redis down")).when(store).updateMessages(any(), any());

        memory.add(UserMessage.from("你好"));

        assertEquals(1, memory.messages().size(), "快照失败只应记日志，不能阻断生成");
    }

    @Test
    void messages_renderedCacheInvalidatedAfterAdd() {
        CompressingWindowChatMemory memory = memory(settings(50, 1, 0));
        memory.add(UserMessage.from("第一条"));
        assertEquals(1, memory.messages().size());

        memory.add(AiMessage.from("第二条"));
        assertEquals(2, memory.messages().size(), "add 之后渲染缓存必须失效");
    }
}
