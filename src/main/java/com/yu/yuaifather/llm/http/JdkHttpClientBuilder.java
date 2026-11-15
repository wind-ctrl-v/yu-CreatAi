package com.yu.yuaifather.llm.http;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import java.time.Duration;

/**
 * 构建基于 JDK {@link java.net.http.HttpClient} 的 LangChain4j HTTP 客户端。
 * <p>
 * 不使用 Spring RestClient + Apache HttpClient 5：后者在读取 DeepSeek
 * chunked {@code application/json} 响应时会截断为单个字符 {@code {}。
 */
public final class JdkHttpClientBuilder implements HttpClientBuilder {

    private Duration connectTimeout;
    private Duration readTimeout;

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        Duration connect = connectTimeout != null ? connectTimeout : Duration.ofSeconds(15);
        Duration read = readTimeout != null ? readTimeout : Duration.ofSeconds(15);
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(connect)
                .build();
        return new JdkHttpClient(jdk, read);
    }
}
