package com.yu.yuaifather.config;

import com.yu.yuaifather.llm.http.JdkHttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 覆盖 LangChain4j OpenAI starter 的同步 ChatModel HTTP 客户端。
 * <p>
 * starter 默认使用 Spring RestClient，而本项目 classpath 上存在 Apache HttpClient 5
 * （来自 WebDriver 等依赖），{@code ClientHttpRequestFactories} 会优先选用它。
 * 该工厂读取 DeepSeek 的 chunked JSON 时会把响应体截断为 {@code {}，
 * 导致 {@code routeCodeGenType} 与 PlanningAgent 等同步调用在解析 HTTP 响应时失败。
 * 使用 JDK HTTP/1.1 客户端可完整读取响应体。
 */
@Configuration
public class OpenAiChatHttpClientConfig {

    @Bean(name = "openAiChatModelHttpClientBuilder")
    public HttpClientBuilder openAiChatModelHttpClientBuilder() {
        return new JdkHttpClientBuilder();
    }
}
