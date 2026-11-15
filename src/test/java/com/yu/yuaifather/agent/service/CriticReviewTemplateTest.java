package com.yu.yuaifather.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticReviewTemplateTest {

    @Test
    void rawVueInterpolationIsRejectedAsMissingTemplateVariable() {
        String raw = "value {{ counts[cat.id] || 0 }} and {{ r.name }}";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PromptTemplate.from(raw).apply(Map.of()));
        assertTrue(error.getMessage().contains("counts[cat.id] || 0")
                || error.getMessage().contains("r.name"));
    }

    @Test
    void reviewKeepsVueInterpolationsLiteral() {
        AtomicReference<String> userText = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                chatRequest.messages().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .map(UserMessage::singleText)
                        .forEach(userText::set);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"passed\":true,\"score\":90}"))
                        .build();
            }
        };
        AgentAiService service = AiServices.builder(AgentAiService.class).chatModel(model).build();
        String payload = """
                {"sourceFiles":{"src/pages/Home.vue":"<template>{{ r.name }} {{ counts[cat.id] || 0 }}</template>"}}
                """;

        assertDoesNotThrow(() -> service.review(payload));
        assertTrue(userText.get().contains("{{ r.name }}"));
        assertTrue(userText.get().contains("{{ counts[cat.id] || 0 }}"));
    }
}
