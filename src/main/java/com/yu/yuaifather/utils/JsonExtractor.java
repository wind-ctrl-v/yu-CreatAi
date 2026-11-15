package com.yu.yuaifather.utils;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出 JSON 的兜底解析器。
 * <p>
 * 即使 prompt 要求"仅输出 JSON"，模型仍可能附带 markdown 围栏或说明文字，
 * 也可能返回截断的不完整 JSON。该工具按以下顺序尝试解析：
 * <ol>
 *   <li>原始内容若已是合法 JSON，直接使用。</li>
 *   <li>提取首个 ```...``` 围栏内的合法 JSON。</li>
 *   <li>提取括号配对完整且可解析的 JSON 对象/数组。</li>
 * </ol>
 * 截断输入（例如单独的 {@code {}）不会被当成合法 JSON。
 */
@Slf4j
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\s*([\\s\\S]*?)```");

    private static final int DEFAULT_PARSE_ATTEMPTS = 2;

    private JsonExtractor() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T parse(String raw, Class<T> type) {
        String json = extract(raw);
        if (StrUtil.isBlank(json)) {
            throw new IllegalArgumentException(
                    "LLM 返回内容为空或 JSON 不完整，无法解析: " + StrUtil.subPre(raw, 200));
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.warn("JSON 解析失败 type={} raw_head={} msg={}",
                    type.getSimpleName(), StrUtil.subPre(raw, 200), e.getMessage());
            throw new IllegalArgumentException("LLM 输出无法解析为 " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 调用 LLM 并解析为指定类型；若输出不是完整 JSON，则再调用一次。
     * HTTP/传输层重试仍由 ChatModel 自己负责，这里只覆盖“响应已取回但 JSON 不完整”的情况。
     */
    public static <T> T parseWithRetry(Supplier<String> call, Class<T> type) {
        return parseWithRetry(call, type, DEFAULT_PARSE_ATTEMPTS);
    }

    public static <T> T parseWithRetry(Supplier<String> call, Class<T> type, int maxAttempts) {
        int attempts = Math.max(1, maxAttempts);
        RuntimeException last = null;
        for (int i = 1; i <= attempts; i++) {
            String raw = call.get();
            try {
                return parse(raw, type);
            } catch (RuntimeException e) {
                last = e;
                log.warn("JSON 解析失败 attempt={}/{} type={} raw_head={} msg={}",
                        i, attempts, type.getSimpleName(), StrUtil.subPre(raw, 200), e.getMessage());
            }
        }
        throw last;
    }

    /**
     * 从 LLM 输出中提取最可能的、可被 Jackson 解析的 JSON 文本。
     * 不完整片段（如单独的 {@code {}）返回 {@code null}。
     */
    public static String extract(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (isValidJson(trimmed)) {
            return trimmed;
        }
        Matcher m = FENCE.matcher(trimmed);
        if (m.find()) {
            String inner = m.group(1).trim();
            if (isValidJson(inner)) {
                return inner;
            }
            String fromFence = extractBalancedJson(inner);
            if (fromFence != null) {
                return fromFence;
            }
        }
        return extractBalancedJson(trimmed);
    }

    public static boolean isValidJson(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        try {
            MAPPER.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从文本中提取第一段括号配对完整且可解析的 JSON 对象或数组。
     */
    public static String extractBalancedJson(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        int start;
        char open;
        char close;
        if (obj >= 0 && (arr < 0 || obj < arr)) {
            start = obj;
            open = '{';
            close = '}';
        } else if (arr >= 0) {
            start = arr;
            open = '[';
            close = ']';
        } else {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    return isValidJson(candidate) ? candidate : null;
                }
            }
        }
        return null;
    }

    /**
     * 读取 JSON 对象上若干候选字段的文本值。
     */
    public static String textField(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject() || fieldNames == null) {
            return null;
        }
        for (String name : fieldNames) {
            if (name != null && node.has(name) && !node.get(name).isNull()) {
                String value = node.get(name).asText(null);
                if (StrUtil.isNotBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }
}
