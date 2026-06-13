package com.yu.yuaifather.agent.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出 JSON 的兜底解析器。
 * <p>
 * 即使 prompt 要求"仅输出 JSON"，模型仍可能附带 markdown 围栏或说明文字。
 * 该工具按以下顺序尝试解析：
 * <ol>
 *   <li>原始内容直接 readValue。</li>
 *   <li>提取首个 ```...``` 围栏内的内容。</li>
 *   <li>提取首个 { 到最后一个 } 之间的内容。</li>
 * </ol>
 */
@Slf4j
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\s*([\\s\\S]*?)```");

    private JsonExtractor() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T parse(String raw, Class<T> type) {
        String json = extract(raw);
        if (StrUtil.isBlank(json)) {
            throw new IllegalArgumentException("LLM 返回内容为空，无法解析 JSON");
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
     * 从 LLM 输出中提取最可能的 JSON 文本。
     */
    public static String extract(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        // 1) 已经是合法 JSON 对象/数组开头
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // 2) markdown 围栏
        Matcher m = FENCE.matcher(trimmed);
        if (m.find()) {
            String inner = m.group(1).trim();
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return inner;
            }
        }
        // 3) 首个 { 到最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }
}
