package com.yu.yuaifather.codegen.routing;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import com.yu.yuaifather.utils.JsonExtractor;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 代码生成类型路由：走普通 Chat + {@code json_object}，再用 {@link JsonExtractor} 解析。
 * <p>
 * 不再使用 LangChain4j AiServices 的枚举结构化输出。DeepSeek 不支持
 * {@code response_format=json_schema}，而 AiServices 在具备该能力时会走 schema，
 * 即使退化为提示词约束，也仍依赖同步 HTTP 客户端完整取回响应。
 */
@Slf4j
public class AiCodeGenTypeRoutingServiceImpl implements AiCodeGenTypeRoutingService {

    private static final String OUTPUT_INSTRUCTION = """

            请严格只输出一个 JSON 对象，格式必须是：
            {"codeGenType":"HTML"}
            或 {"codeGenType":"MULTI_FILE"}
            或 {"codeGenType":"VUE_PROJECT"}
            不要输出解释文字、不要使用 markdown 围栏。
            """;

    private static final int PARSE_ATTEMPTS = 2;

    private final ChatModel chatModel;
    private final String systemPrompt;

    public AiCodeGenTypeRoutingServiceImpl(ChatModel chatModel, String systemPrompt) {
        this.chatModel = chatModel;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public CodeGenTypeEnum routeCodeGenType(String userPrompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(systemPrompt + OUTPUT_INSTRUCTION),
                        UserMessage.from(userPrompt)))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .build())
                .build();

        RuntimeException last = null;
        for (int i = 1; i <= PARSE_ATTEMPTS; i++) {
            ChatResponse response = chatModel.chat(request);
            String raw = response != null && response.aiMessage() != null
                    ? response.aiMessage().text()
                    : null;
            CodeGenTypeEnum type = parseCodeGenType(raw);
            if (type != null) {
                log.info("代码生成类型路由结果 type={} attempt={}", type.getValue(), i);
                return type;
            }
            last = new IllegalArgumentException(
                    "无法从模型输出解析代码生成类型: " + StrUtil.subPre(raw, 200));
            log.warn("代码生成类型路由解析失败 attempt={}/{} raw_head={}",
                    i, PARSE_ATTEMPTS, StrUtil.subPre(raw, 200));
        }
        throw last;
    }

    /**
     * 从模型输出中解析 {@link CodeGenTypeEnum}。支持纯枚举名、value，以及
     * {@code {"codeGenType":"VUE_PROJECT"}} 这类 JSON。
     */
    public static CodeGenTypeEnum parseCodeGenType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String json = JsonExtractor.extract(raw);
        if (json != null) {
            try {
                JsonNode node = JsonExtractor.mapper().readTree(json);
                if (node.isTextual()) {
                    CodeGenTypeEnum matched = fromToken(node.asText());
                    if (matched != null) {
                        return matched;
                    }
                } else if (node.isObject()) {
                    String field = JsonExtractor.textField(
                            node, "codeGenType", "type", "value", "result", "name");
                    CodeGenTypeEnum matched = fromToken(field);
                    if (matched != null) {
                        return matched;
                    }
                }
            } catch (Exception e) {
                log.debug("路由 JSON 解析回退到文本匹配: {}", e.getMessage());
            }
        }
        return fromToken(raw);
    }

    static CodeGenTypeEnum fromToken(String raw) {
        if (raw == null) {
            return null;
        }
        String token = raw.trim().replace("`", "").replace("\"", "").replace("'", "").trim();
        if (token.isEmpty()) {
            return null;
        }
        for (CodeGenTypeEnum type : CodeGenTypeEnum.values()) {
            if (type.name().equalsIgnoreCase(token) || type.getValue().equalsIgnoreCase(token)) {
                return type;
            }
        }
        String upper = token.toUpperCase(Locale.ROOT);
        String lower = token.toLowerCase(Locale.ROOT);
        if (upper.contains("VUE_PROJECT") || lower.contains("vue_project")) {
            return CodeGenTypeEnum.VUE_PROJECT;
        }
        if (upper.contains("MULTI_FILE") || lower.contains("multi_file")) {
            return CodeGenTypeEnum.MULTI_FILE;
        }
        if (upper.matches("(?s).*\\bHTML\\b.*") || lower.matches("(?s).*\\bhtml\\b.*")) {
            return CodeGenTypeEnum.HTML;
        }
        return null;
    }
}
