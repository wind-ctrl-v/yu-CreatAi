package com.yu.yuaifather.observability;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class TokenUsageAttributionTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
        if (LlmCallAttribution.current() != null) {
            throw new AssertionError("attribution leaked: " + LlmCallAttribution.current());
        }
    }

    @Test
    void responseUsesAttributesNotMdcAndAggregatesTheStep() throws Exception {
        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        TokenUsageChatModelListener listener = new TokenUsageChatModelListener(new SimpleMeterRegistry(), usage);
        String generationId = usage.newGenerationId();
        usage.open(generationId, 42L);

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("plan"))
                .tokenUsage(new TokenUsage(11, 7, 18))
                .build();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public List<ChatModelListener> listeners() {
                return List.of(listener);
            }

            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                Thread callback = new Thread(() -> handler.onCompleteResponse(response));
                callback.start();
                try {
                    callback.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };

        MDC.put("generationId", "from-mdc");
        MDC.put("appId", "999");
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("做一个主页"))).build();
        try (var ignored = LlmCallAttribution.open(generationId, 42L, "PlanningAgent")) {
            model.chat(request, noopHandler());
        }
        MDC.put("generationId", "still-mdc");

        TokenUsageReport report = usage.snapshot(generationId);
        assertEquals(generationId, report.generationId());
        assertEquals(42L, report.appId());
        assertEquals("PlanningAgent", report.step());
        assertEquals(11, report.stepInputTokens());
        assertEquals(7, report.stepOutputTokens());
        assertEquals(18, report.totalTokens());
        assertEquals(1, report.callCount());
        assertEquals(0, report.unknownUsageCalls());
        assertEquals(1, report.steps().size());
    }

    @Test
    void missingUsageIsKeptAsUnknownInsteadOfDropped() {
        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        String generationId = usage.newGenerationId();
        usage.open(generationId, 5L);
        usage.record(generationId, 5L, "CoderAgent", "deepseek-chat", new TokenUsage(100, 40, 140));
        usage.record(generationId, 5L, "CoderAgent", "deepseek-chat", null);
        usage.record(generationId, 5L, "StyleAgent", "deepseek-chat", new TokenUsage(10, 4, 14));

        TokenUsageReport report = usage.snapshot(generationId);
        assertEquals(2, report.steps().size());
        assertEquals("CoderAgent", report.steps().get(0).step());
        assertEquals(100, report.steps().get(0).inputTokens());
        assertEquals(2, report.steps().get(0).callCount());
        assertEquals(1, report.steps().get(0).unknownUsageCalls());
        assertEquals("StyleAgent", report.steps().get(1).step());
        assertEquals(3, report.callCount());
        assertEquals(1, report.unknownUsageCalls());
        assertEquals(154, report.totalTokens());
    }

    @Test
    void unattributedCallWithoutGenerationIsLabeledAndNotStored() {
        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        TokenUsageChatModelListener listener = new TokenUsageChatModelListener(new SimpleMeterRegistry(), usage);
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        LlmCallAttribution.stamp(attributes);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("x"))
                .tokenUsage(new TokenUsage(1, 1, 2))
                .build();
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OTHER, attributes));
        assertEquals(LlmSteps.UNATTRIBUTED, LlmCallAttribution.fromAttributes(attributes).step());
        assertNull(usage.liveForApp(1L));
    }

    @Test
    void listenerFailureDoesNotThrow() {
        GenerationTokenUsageService exploding = mock(GenerationTokenUsageService.class);
        doThrow(new IllegalStateException("ledger down")).when(exploding)
                .record(any(), any(), any(), any(), any());
        TokenUsageChatModelListener listener = new TokenUsageChatModelListener(new SimpleMeterRegistry(), exploding);
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(LlmCallAttribution.ATTR_GENERATION_ID, "gen_x");
        attributes.put(LlmCallAttribution.ATTR_APP_ID, 1L);
        attributes.put(LlmCallAttribution.ATTR_STEP, "CriticAgent");
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("x"))
                .tokenUsage(new TokenUsage(2, 3, 5))
                .build();
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
        assertDoesNotThrow(() -> listener.onRequest(null));
        assertDoesNotThrow(() -> listener.onResponse(null));
        assertDoesNotThrow(() -> listener.onError(null));
        assertDoesNotThrow(() -> listener.onResponse(
                new ChatModelResponseContext(response, request, ModelProvider.OTHER, attributes)));
        assertDoesNotThrow(() -> listener.onError(new ChatModelErrorContext(
                new IllegalStateException("upstream"), request, ModelProvider.OTHER, attributes)));
        assertDoesNotThrow(() -> listener.onRequest(
                new ChatModelRequestContext(request, ModelProvider.OTHER, new ConcurrentHashMap<>() {
                    @Override
                    public Object put(Object key, Object value) {
                        throw new IllegalStateException("no attributes");
                    }
                })));
    }

    @Test
    void sseEventCarriesStepCountsTotalsAndFullBreakdown() {
        GenerationTokenUsageService usage = new GenerationTokenUsageService();
        String generationId = usage.newGenerationId();
        usage.open(generationId, 7L);
        usage.record(generationId, 7L, "PlanningAgent", "deepseek-chat", new TokenUsage(3, 5, 8));
        TokenUsageReport report = usage.snapshot(generationId);

        var event = TokenUsageSse.toEvent(report);
        assertEquals(TokenUsageSse.EVENT_NAME, event.event());
        JSONObject json = JSONUtil.parseObj(event.data());
        assertEquals("token_usage", json.getStr("event"));
        assertEquals(generationId, json.getStr("generationId"));
        assertEquals(7L, json.getLong("appId"));
        assertEquals("PlanningAgent", json.getStr("step"));
        assertEquals(3, json.getInt("inputTokens"));
        assertEquals(5, json.getInt("outputTokens"));
        assertEquals(8, json.getInt("totalTokens"));
        assertEquals(1, json.getInt("callCount"));
        assertEquals(8, json.getJSONObject("totals").getInt("totalTokens"));
        assertEquals("PlanningAgent", json.getJSONArray("steps").getJSONObject(0).getStr("step"));
    }

    @Test
    void followUpStreamingCallOnAnotherThreadKeepsTheStep() throws Exception {
        AtomicReference<LlmCallScope> during = new AtomicReference<>();
        AtomicReference<LlmCallScope> after = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel[] holder = new StreamingChatModel[1];
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("继续"))).build();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                if (calls.incrementAndGet() == 1) {
                    Thread callback = new Thread(() -> {
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("tool"))
                                .build());
                        after.set(LlmCallAttribution.current());
                    });
                    callback.start();
                    try {
                        callback.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                } else {
                    during.set(LlmCallAttribution.current());
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("done"))
                            .build());
                }
            }
        };
        holder[0] = AttributingStreamingChatModel.wrap(delegate);
        try (var ignored = LlmCallAttribution.open("gen_follow", 9L, "CoderAgent")) {
            holder[0].chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    holder[0].chat(request, noopHandler());
                }

                @Override
                public void onError(Throwable error) {
                }
            });
        }
        assertEquals("CoderAgent", during.get().step());
        assertEquals("gen_follow", during.get().generationId());
        assertEquals(9L, during.get().appId());
        assertNull(after.get());
    }

    private static StreamingChatResponseHandler noopHandler() {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
    }
}
