package com.yu.yuaifather.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;

/**
 * 流式模型包装：在 HTTP 回调线程进入下一轮工具调用前，重新装上本次生成的归因。
 *
 * <p>{@code onRequest} 仍发生在 {@code delegate.chat()} 里，并把归因复制到 listener attributes。
 * 本包装不读取 MDC。</p>
 */
public final class AttributingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;

    private AttributingStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    public static StreamingChatModel wrap(StreamingChatModel delegate) {
        if (delegate == null || delegate instanceof AttributingStreamingChatModel) {
            return delegate;
        }
        return new AttributingStreamingChatModel(delegate);
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        LlmCallScope captured = LlmCallAttribution.current();
        delegate.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onPartialToolExecutionRequest(int index, ToolExecutionRequest partialToolExecutionRequest) {
                handler.onPartialToolExecutionRequest(index, partialToolExecutionRequest);
            }

            @Override
            public void onCompleteToolExecutionRequest(int index, ToolExecutionRequest completeToolExecutionRequest) {
                handler.onCompleteToolExecutionRequest(index, completeToolExecutionRequest);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                LlmCallAttribution.runWith(captured, () -> handler.onCompleteResponse(completeResponse));
            }

            @Override
            public void onError(Throwable error) {
                LlmCallAttribution.runWith(captured, () -> handler.onError(error));
            }
        });
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
