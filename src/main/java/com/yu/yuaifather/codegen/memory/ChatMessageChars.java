package com.yu.yuaifather.codegen.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 消息字符数估算工具：上下文预算裁剪与压缩率打点的共同度量口径。
 *
 * <p>字符数不等于 token，但作为「同一口径下的相对体积」足够用；换算经验值：
 * 代码与英文约 3.5~4 字符/token，中文约 1.5~1.7 字符/token，混合内容保守按 2 字符/token 折算。</p>
 */
public final class ChatMessageChars {

    private ChatMessageChars() {
    }

    /**
     * 单条消息的字符数（AiMessage 计入工具调用参数，那才是代码生成场景的体积大头）
     */
    public static int of(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            try {
                String text = userMessage.singleText();
                return text == null ? 0 : text.length();
            } catch (Exception e) {
                // 多内容块（图片等）无法取单一文本，按0 计，不影响相对度量
                return 0;
            }
        }
        if (message instanceof AiMessage aiMessage) {
            int chars = aiMessage.text() == null ? 0 : aiMessage.text().length();
            if (aiMessage.toolExecutionRequests() != null) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (request != null && request.arguments() != null) {
                        chars += request.arguments().length();
                    }
                }
            }
            return chars;
        }
        if (message instanceof ToolExecutionResultMessage resultMessage) {
            return resultMessage.text() == null ? 0 : resultMessage.text().length();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text() == null ? 0 : systemMessage.text().length();
        }
        return 0;
    }

    /**
     * 消息列表的总字符数
     */
    public static int total(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += of(message);
        }
        return total;
    }
}
