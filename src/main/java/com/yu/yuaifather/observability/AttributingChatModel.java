package com.yu.yuaifather.observability;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/**
 * 同步模型包装：工具循环里的下一次 {@code chat()} 仍能看见外层归因。
 * 监听器仍挂在被包装的模型上，本类不重复打点。
 */
public final class AttributingChatModel implements ChatModel {

    private final ChatModel delegate;

    private AttributingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    public static ChatModel wrap(ChatModel delegate) {
        if (delegate == null || delegate instanceof AttributingChatModel) {
            return delegate;
        }
        return new AttributingChatModel(delegate);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return LlmCallAttribution.callWith(LlmCallAttribution.current(), () -> delegate.chat(chatRequest));
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
    public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
