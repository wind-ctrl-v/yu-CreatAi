package com.yu.yuaifather.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonExtractorTest {

    record Sample(String name, int count) {}

    @Test
    void extractReturnsPlainJsonObject() {
        String json = "{\"name\":\"dash\",\"count\":2}";
        assertEquals(json, JsonExtractor.extract(json));
        Sample sample = JsonExtractor.parse(json, Sample.class);
        assertEquals("dash", sample.name());
        assertEquals(2, sample.count());
    }

    @Test
    void extractStripsMarkdownFence() {
        String raw = """
                这是说明
                ```json
                {"name":"boxed","count":3}
                ```
                结束
                """;
        Sample sample = JsonExtractor.parse(raw, Sample.class);
        assertEquals("boxed", sample.name());
        assertEquals(3, sample.count());
    }

    @Test
    void extractFindsEmbeddedObjectAmongProse() {
        String raw = "模型说明如下：{\"name\":\"embedded\",\"count\":4} 请查收。";
        Sample sample = JsonExtractor.parse(raw, Sample.class);
        assertEquals("embedded", sample.name());
        assertEquals(4, sample.count());
    }

    @Test
    void extractRejectsTruncatedCurlyBrace() {
        assertNull(JsonExtractor.extract("{"));
        assertNull(JsonExtractor.extract("{\"name\":\"x\""));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> JsonExtractor.parse("{", Sample.class));
        assertTrue(ex.getMessage().contains("不完整") || ex.getMessage().contains("无法解析"));
    }

    @Test
    void extractRejectsUnbalancedArray() {
        assertNull(JsonExtractor.extract("[1, 2"));
    }

    @Test
    void parseWithRetrySucceedsOnSecondCompleteJson() {
        AtomicInteger calls = new AtomicInteger();
        Sample sample = JsonExtractor.parseWithRetry(() -> {
            if (calls.incrementAndGet() == 1) {
                return "{";
            }
            return "{\"name\":\"retried\",\"count\":5}";
        }, Sample.class);
        assertEquals(2, calls.get());
        assertEquals("retried", sample.name());
        assertEquals(5, sample.count());
    }

    @Test
    void parseWithRetryExhaustsAttempts() {
        AtomicInteger calls = new AtomicInteger();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                JsonExtractor.parseWithRetry(() -> {
                    calls.incrementAndGet();
                    return "{";
                }, Sample.class, 2));
        assertEquals(2, calls.get());
        assertTrue(ex.getMessage().contains("不完整") || ex.getMessage().contains("无法解析"));
    }
}
