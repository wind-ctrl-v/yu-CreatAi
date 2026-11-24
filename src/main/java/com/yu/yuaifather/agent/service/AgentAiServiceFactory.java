package com.yu.yuaifather.agent.service;

import com.yu.yuaifather.observability.AttributingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多 Agent 流水线 AI 服务工厂。
 */
@Configuration
public class AgentAiServiceFactory {

    @Resource
    private ChatModel chatModel;

    @Bean
    public AgentAiService agentAiService() {
        return AiServices.builder(AgentAiService.class)
                .chatModel(AttributingChatModel.wrap(chatModel))
                .build();
    }
}
