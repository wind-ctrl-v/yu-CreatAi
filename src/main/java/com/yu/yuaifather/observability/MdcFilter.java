package com.yu.yuaifather.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * MDC 日志串联过滤器：为每个请求生成 requestId 写入 MDC 与响应头 X-Request-Id，
 * 使同一请求的日志可按 requestId 检索串联。
 *
 * <p>注意：reactive / 流式回调运行在异步线程，MDC 不自动跨线程传播，
 * 本过滤器仅保证 Servlet 请求线程内的日志串联（LLM listener 等异步打点不依赖 MDC）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MdcFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
