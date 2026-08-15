package com.yu.yuaifather.config;

import com.yu.yuaifather.observability.TokenUsageChatModelListener;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    private String baseUrl;

    private String apiKey;

    /**
     * 推理流式模型（用于 Vue 项目生成，带工具调用）
     */
    @Bean
    public StreamingChatModel reasoningStreamingChatModel(TokenUsageChatModelListener tokenUsageListener) {
        // 生产环境（deepseek-reasoner 不支持 function calling，改用 deepseek-chat 保证 V2 工具调用可用）
        final String modelName = "deepseek-chat";
        final int maxTokens = 8192;
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .logRequests(true)
                .logResponses(true)
                // 手工构建的实例不经 starter 自动收集，显式注册 token 观测监听器
                .listeners(List.of(tokenUsageListener))
                .build();
    }
}
