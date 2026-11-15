package com.yu.yuaifather.codegen.routing;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AI 代码生成类型路由服务工厂。
 * <p>
 * 使用普通 Chat + JSON 解析实现，而不是 AiServices 枚举结构化输出。
 */
@Slf4j
@Configuration
public class AiCodeGenTypeRoutingServiceFactory {

    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService(ChatModel chatModel) {
        String systemPrompt = loadSystemPrompt();
        log.info("初始化代码生成类型路由服务（普通 Chat + JsonExtractor）");
        return new AiCodeGenTypeRoutingServiceImpl(chatModel, systemPrompt);
    }

    private static String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource("prompt/codegen-routing-system-prompt.txt");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载代码生成类型路由提示词", e);
        }
    }
}
