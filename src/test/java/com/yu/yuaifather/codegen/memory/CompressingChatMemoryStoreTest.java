package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompressingChatMemoryStore} 包装器单测：mock delegate 验证拦截与委托行为。
 */
class CompressingChatMemoryStoreTest {

    private RedisChatMemoryStore delegate;
    private CompressingChatMemoryStore store;

    @BeforeEach
    void setUp() {
        delegate = mock(RedisChatMemoryStore.class);
        ChatMemoryCompressor compressor = new ChatMemoryCompressor();
        ReflectionTestUtils.setField(compressor, "compressThreshold", 1500);
        ReflectionTestUtils.setField(compressor, "explainMaxChars", 500);
        store = new CompressingChatMemoryStore(delegate, compressor, new SimpleMeterRegistry());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateMessages_compressesBeforeDelegating() {
        String bigText = "说明\n\n```html\n<head><title>T</title></head>\n" + "x".repeat(2000) + "\n```";
        List<ChatMessage> messages = List.of(AiMessage.from(bigText), AiMessage.from("好的"));

        store.updateMessages(1L, messages);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(delegate).updateMessages(eq(1L), captor.capture());
        List<ChatMessage> persisted = captor.getValue();
        // 写入 delegate 的应是压缩后的形态：大消息变占位卡，最新一条保留全文窗口
        assertTrue(((AiMessage) persisted.get(0)).text().contains("[html index.html"),
                ((AiMessage) persisted.get(0)).text());
        assertEquals("好的", ((AiMessage) persisted.get(1)).text());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateMessages_shortMessages_passedThroughUnchanged() {
        List<ChatMessage> messages = List.of(UserMessage.from("你好"), AiMessage.from("短回复"));

        store.updateMessages(2L, messages);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(delegate).updateMessages(eq(2L), captor.capture());
        // 短消息低于阈值，内容原样透传
        assertEquals(messages, captor.getValue());
    }

    @Test
    void getMessages_directlyDelegates() {
        List<ChatMessage> stored = List.of(UserMessage.from("历史消息"));
        when(delegate.getMessages(3L)).thenReturn(stored);

        List<ChatMessage> result = store.getMessages(3L);

        assertEquals(stored, result);
        verify(delegate).getMessages(3L);
    }

    @Test
    void deleteMessages_directlyDelegates() {
        store.deleteMessages(4L);
        verify(delegate).deleteMessages(4L);
    }
}
