package com.yu.yuaifather.llm.http;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * 使用 JDK HTTP/1.1 客户端完整读取响应体，避免 Apache HttpClient 5
 * 在 DeepSeek chunked JSON 上只读到截断内容。
 */
public final class JdkHttpClient implements HttpClient {

    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade"
    );

    private final java.net.http.HttpClient delegate;
    private final Duration readTimeout;

    public JdkHttpClient(java.net.http.HttpClient delegate, Duration readTimeout) {
        this.delegate = delegate;
        this.readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(15);
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        try {
            HttpResponse<String> response = delegate.send(
                    toJdkRequest(request), BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new HttpException(response.statusCode(), response.body());
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .body(response.body())
                    .build();
        } catch (HttpException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JDK HttpClient 请求被中断", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        try {
            HttpResponse<InputStream> response = delegate.send(
                    toJdkRequest(request), BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errBody;
                try (InputStream in = response.body()) {
                    errBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                listener.onError(new HttpException(response.statusCode(), errBody));
                return;
            }
            listener.onOpen(SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers().map())
                    .build());
            try (InputStream in = response.body()) {
                parser.parse(in, listener);
            }
            listener.onClose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(e);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private java.net.http.HttpRequest toJdkRequest(HttpRequest request) {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(request.url()))
                .timeout(readTimeout);
        if (request.headers() != null) {
            request.headers().forEach((name, values) -> {
                if (name == null || RESTRICTED_HEADERS.contains(name.toLowerCase()) || values == null) {
                    return;
                }
                for (String value : values) {
                    builder.header(name, value);
                }
            });
        }
        String method = request.method() == null ? "GET" : request.method().name();
        String body = request.body();
        if (body == null || body.isEmpty() || "GET".equals(method) || "DELETE".equals(method)) {
            builder.method(method, BodyPublishers.noBody());
        } else {
            builder.method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }
}
