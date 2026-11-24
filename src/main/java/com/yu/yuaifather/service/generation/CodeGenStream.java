package com.yu.yuaifather.service.generation;

import com.yu.yuaifather.observability.TokenUsageReport;
import reactor.core.publisher.Flux;

/**
 * 代码生成内容流，以及与之并行的 token 用量流。
 * 用量走独立的 SSE 事件，避免写进 HTML / 多文件产物或对话历史。
 */
public record CodeGenStream(Flux<String> content, Flux<TokenUsageReport> tokenUsage) {

    public CodeGenStream {
        content = content == null ? Flux.empty() : content;
        tokenUsage = tokenUsage == null ? Flux.empty() : tokenUsage;
    }
}
