package com.yu.yuaifather.codegen.memory;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话记忆压缩器（纯规则、无 LLM 调用、可单测）。
 *
 * <p>核心思路：记忆里只存"索引摘要"，不存全文。安全前提是两个事实源始终完整：
 * MySQL {@code chat_history} 保留全量历史（前端展示不受影响），磁盘产物保留当前代码
 * （AI 需要细节时用 FileReadTool 现读）。</p>
 *
 * <h3>压缩规则</h3>
 * <ul>
 *   <li><b>R1 代码块占位</b>：HTML / MULTI_FILE 链路的 {@code AiMessage.text}，
 *   代码块替换为占位卡（html 提取 title + 语义骨架，css 提取变量 + 选择器，js 提取函数名），
 *   块外解释文字保留并截断；无语言标记的代码块（VUE 修改轮的替换前/后片段）截断为首行 + 占位（R3'）。</li>
 *   <li><b>R2 工具参数截断</b>：writeFile 的 content 全省略；modifyFile 的 oldContent/newContent
 *   各留前 80 字符（old→new 片段是变更语义的最佳载体）。id / name 结构保持不变，保证工具调用与结果配对。</li>
 *   <li><b>R3 工具结果截断</b>：readFile 结果替换为"[已读取 path，N字符，内容已省略]"，
 *   磁盘是唯一事实源，记忆里的副本无保留意义。</li>
 * </ul>
 *
 * <h3>关键策略</h3>
 * <ul>
 *   <li><b>全文窗口 = 1</b>：跳过列表中最后一条含正文的 AiMessage 不做 R1 压缩
 *   （HTML / MULTI_FILE 的修改轮依赖记忆里的当前版本做全量重输出；VUE 靠读盘，可全量压缩，
 *   其正文通常为空，不受影响）。</li>
 *   <li><b>UserMessage 一律原样保留</b>：承载了"本轮想做什么"的意图，是多轮连贯性的关键。</li>
 *   <li><b>降级安全</b>：任何解析失败都不抛异常、不丢消息，退化为"前 300 字符 + 省略标记"。</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatMemoryCompressor {

    /** 单条消息超过该字符数才触发压缩，短消息原样保留 */
    @Value("${agent.memory.compress.threshold:1500}")
    private int compressThreshold;

    /** 代码块外解释文字保留的最大字符数（提示词本就允许 AI 写总结，这是现成的自摘要） */
    @Value("${agent.memory.compress.explain-max-chars:500}")
    private int explainMaxChars;

    /** 代码块切分：```lang\n...```，语言标记可缺省 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w*)\\s*\\n([\\s\\S]*?)```");
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SEMANTIC_TAG_PATTERN = Pattern.compile("<(header|nav|main|section|footer)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR_PATTERN = Pattern.compile("id=\"([^\"]*)\"");
    private static final Pattern CLASS_ATTR_PATTERN = Pattern.compile("class=\"([^\"]*)\"");
    private static final Pattern CSS_VAR_PATTERN = Pattern.compile("(--[\\w-]+)\\s*:\\s*([^;]+);");
    /** 顶层 class 选择器（行首无缩进，排除嵌套） */
    private static final Pattern CSS_CLASS_PATTERN = Pattern.compile("^\\.([\\w-]+)", Pattern.MULTILINE);
    private static final Pattern JS_FUNC_PATTERN = Pattern.compile("function\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_CONST_PATTERN = Pattern.compile("const\\s+(\\w+)\\s*=");

    private static final String TOOL_WRITE_FILE = "writeFile";
    private static final String TOOL_MODIFY_FILE = "modifyFile";
    private static final String TOOL_READ_FILE = "readFile";

    private static final int MAX_SKELETON_TAGS = 20;
    private static final int MAX_CSS_VARS = 50;
    private static final int MAX_CSS_CLASSES = 30;
    private static final int MAX_JS_SYMBOLS = 30;
    /** modifyFile 新旧片段各保留的前缀字符数 */
    private static final int TOOL_ARG_KEEP_CHARS = 80;
    /** 降级截断保留的字符数 */
    private static final int FALLBACK_KEEP_CHARS = 300;
    private static final int FIRST_LINE_KEEP_CHARS = 100;

    /**
     * 压缩消息列表。UserMessage / SystemMessage 原样保留，仅处理 AiMessage 与 ToolExecutionResultMessage。
     * 整体异常时返回原始列表，绝不让压缩失败阻断生成。
     *
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public List<ChatMessage> compressAll(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        try {
            int lastTextAiIndex = findLastTextAiMessageIndex(messages);
            Map<String, String> readFilePaths = collectReadFilePaths(messages);
            List<ChatMessage> result = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                result.add(compressOne(messages.get(i), i == lastTextAiIndex, readFilePaths));
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆压缩整体失败，返回原始消息列表: {}", e.getMessage());
            return messages;
        }
    }

    /**
     * 压缩单条消息，异常时保留原消息。
     *
     * @param keepFullText 是否为全文窗口内的最新一条 AI 消息（跳过 R1）
     */
    private ChatMessage compressOne(ChatMessage message, boolean keepFullText, Map<String, String> readFilePaths) {
        try {
            if (message instanceof AiMessage aiMessage) {
                return compressAiMessage(aiMessage, keepFullText);
            }
            if (message instanceof ToolExecutionResultMessage resultMessage) {
                return compressToolResult(resultMessage, readFilePaths);
            }
            return message;
        } catch (Exception e) {
            log.warn("单条消息压缩失败，保留原消息: type={} err={}", message.type(), e.getMessage());
            return message;
        }
    }

    /**
     * R1（代码块占位）+ R2（工具参数截断）。
     */
    private AiMessage compressAiMessage(AiMessage aiMessage, boolean keepFullText) {
        String text = aiMessage.text();
        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();

        // R1：全文窗口=1，跳过最新一条含正文的 AI 消息
        String newText = text;
        if (!keepFullText && text != null && text.length() > compressThreshold) {
            newText = compressTextWithCodeBlocks(text);
        }

        // R2：工具参数截断对全部消息生效（VUE 链路靠读盘，可全量压缩）
        List<ToolExecutionRequest> newRequests = requests;
        boolean requestsChanged = false;
        if (requests != null && !requests.isEmpty()) {
            newRequests = new ArrayList<>(requests.size());
            for (ToolExecutionRequest request : requests) {
                ToolExecutionRequest compressed = compressToolRequest(request);
                requestsChanged = requestsChanged || compressed != request;
                newRequests.add(compressed);
            }
        }

        if (Objects.equals(newText, text) && !requestsChanged) {
            return aiMessage;
        }
        // 重建 AiMessage，工具调用的 id / name 结构保持不变
        if (newRequests != null && !newRequests.isEmpty()) {
            return AiMessage.from(newText, newRequests);
        }
        return AiMessage.from(newText == null ? "" : newText);
    }

    /**
     * R1：代码块替换为占位卡，块外解释文字保留并截断。
     */
    private String compressTextWithCodeBlocks(String text) {
        try {
            Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            boolean foundBlock = false;
            while (matcher.find()) {
                foundBlock = true;
                appendExplainText(sb, text.substring(lastEnd, matcher.start()));
                sb.append(buildPlaceholderCard(matcher.group(1), matcher.group(2)));
                lastEnd = matcher.end();
            }
            if (!foundBlock) {
                // 无代码块的超长文本：降级截断
                return fallbackTruncate(text);
            }
            appendExplainText(sb, text.substring(lastEnd));
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("代码块压缩失败，降级为截断: {}", e.getMessage());
            return fallbackTruncate(text);
        }
    }

    /**
     * 保留块外解释文字，截断到前 explainMaxChars 字符。
     */
    private void appendExplainText(StringBuilder sb, String explainText) {
        String trimmed = explainText.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.length() > explainMaxChars) {
            trimmed = StrUtil.subPre(trimmed, explainMaxChars) + "...";
        }
        sb.append(trimmed).append("\n\n");
    }

    /**
     * 按语言构建代码块占位卡，附行数与字符数让 AI 知道规模。
     */
    private String buildPlaceholderCard(String lang, String code) {
        String normalizedLang = lang == null ? "" : lang.toLowerCase();
        long lines = code.lines().count();
        int chars = code.length();
        StringBuilder card = new StringBuilder();
        switch (normalizedLang) {
            case "html" -> {
                card.append("[html index.html, ").append(lines).append("行, ").append(chars).append("字符, 已省略]");
                String title = extractFirstGroup(TITLE_PATTERN, code);
                if (StrUtil.isNotBlank(title)) {
                    card.append(" title=「").append(title.trim()).append("」");
                }
                String skeleton = extractHtmlSkeleton(code);
                if (!skeleton.isEmpty()) {
                    card.append("\n骨架: ").append(skeleton);
                }
            }
            case "css" -> {
                card.append("[css style.css, ").append(lines).append("行, ").append(chars).append("字符, 已省略]");
                String vars = extractCssVars(code);
                if (!vars.isEmpty()) {
                    card.append(" 变量: ").append(vars);
                }
                String selectors = extractCssClasses(code);
                if (!selectors.isEmpty()) {
                    card.append("\n选择器: ").append(selectors);
                }
            }
            case "js", "javascript" -> {
                card.append("[js script.js, ").append(lines).append("行, ").append(chars).append("字符, 已省略]");
                String symbols = extractJsSymbols(code);
                if (!symbols.isEmpty()) {
                    card.append(" 函数: ").append(symbols);
                }
            }
            default -> {
                // R3'：无语言标记的代码块（VUE 修改轮的替换前/后片段）→ 首行 + 占位
                card.append("[代码块, ").append(lines).append("行, ").append(chars).append("字符, 已省略]");
                String firstLine = code.lines().findFirst().orElse("").trim();
                if (!firstLine.isEmpty()) {
                    card.append(" 首行: ").append(StrUtil.subPre(firstLine, FIRST_LINE_KEEP_CHARS));
                }
            }
        }
        return card.append("\n\n").toString();
    }

    /**
     * 提取 HTML 语义骨架：header/nav/main/section/footer 标签的 id 与 class，上限 20 个。
     */
    private String extractHtmlSkeleton(String html) {
        Matcher matcher = SEMANTIC_TAG_PATTERN.matcher(html);
        List<String> tags = new ArrayList<>();
        while (matcher.find() && tags.size() < MAX_SKELETON_TAGS) {
            String tag = matcher.group(1).toLowerCase();
            String attrs = matcher.group(2);
            StringBuilder sb = new StringBuilder(tag);
            String id = extractFirstGroup(ID_ATTR_PATTERN, attrs);
            if (StrUtil.isNotBlank(id)) {
                sb.append("#").append(id.trim());
            }
            String classes = extractFirstGroup(CLASS_ATTR_PATTERN, attrs);
            if (StrUtil.isNotBlank(classes)) {
                for (String c : classes.trim().split("\\s+")) {
                    if (!c.isBlank()) {
                        sb.append(".").append(c);
                    }
                }
            }
            tags.add(sb.toString());
        }
        return String.join(", ", tags);
    }

    /**
     * 提取 CSS 设计变量（主色 / 字号阶梯，即"风格约定"载体），上限 50 个。
     */
    private String extractCssVars(String css) {
        Matcher matcher = CSS_VAR_PATTERN.matcher(css);
        List<String> vars = new ArrayList<>();
        while (matcher.find() && vars.size() < MAX_CSS_VARS) {
            vars.add(matcher.group(1) + ":" + matcher.group(2).trim());
        }
        return String.join(", ", vars);
    }

    /**
     * 提取 CSS 顶层 class 名单（去重），上限 30 个。
     */
    private String extractCssClasses(String css) {
        Matcher matcher = CSS_CLASS_PATTERN.matcher(css);
        Set<String> classes = new LinkedHashSet<>();
        while (matcher.find() && classes.size() < MAX_CSS_CLASSES) {
            classes.add("." + matcher.group(1));
        }
        return String.join(", ", classes);
    }

    /**
     * 提取 JS 顶层函数名与常量名（去重），上限 30 个。
     */
    private String extractJsSymbols(String js) {
        Set<String> symbols = new LinkedHashSet<>();
        Matcher funcMatcher = JS_FUNC_PATTERN.matcher(js);
        while (funcMatcher.find() && symbols.size() < MAX_JS_SYMBOLS) {
            symbols.add(funcMatcher.group(1));
        }
        Matcher constMatcher = JS_CONST_PATTERN.matcher(js);
        while (constMatcher.find() && symbols.size() < MAX_JS_SYMBOLS) {
            symbols.add(constMatcher.group(1));
        }
        return String.join(", ", symbols);
    }

    /**
     * R2：工具参数截断。writeFile 的 content 全省略；modifyFile 的新旧片段各留前 80 字符。
     * 其他工具、短参数原样保留；非法 JSON 降级为原文截断。
     */
    private ToolExecutionRequest compressToolRequest(ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        String name = request.name();
        String arguments = request.arguments();
        if (arguments == null || arguments.length() <= compressThreshold) {
            return request;
        }
        if (!TOOL_WRITE_FILE.equals(name) && !TOOL_MODIFY_FILE.equals(name)) {
            return request;
        }
        try {
            JSONObject json = JSONUtil.parseObj(arguments);
            if (TOOL_WRITE_FILE.equals(name)) {
                String content = json.getStr("content");
                if (content != null && content.length() > compressThreshold) {
                    json.set("content", "[系统压缩省略(" + content.length() + "字符)，禁止作为文件内容写入，需修改请先 readFile]");
                }
            } else {
                // old→new 片段本身是变更语义的最佳载体，不完全省略
                for (String field : new String[]{"oldContent", "newContent"}) {
                    String value = json.getStr(field);
                    if (value != null && value.length() > TOOL_ARG_KEEP_CHARS) {
                        json.set(field, StrUtil.subPre(value, TOOL_ARG_KEEP_CHARS) + "...[共" + value.length() + "字符]");
                    }
                }
            }
            return rebuildRequest(request, json.toString());
        } catch (Exception e) {
            // 工具参数非法 JSON：降级为原文截断
            return rebuildRequest(request, fallbackTruncate(arguments));
        }
    }

    /**
     * 重建工具请求，保持 id / name 不变（LangChain4j 工具调用与结果需配对校验）。
     */
    private ToolExecutionRequest rebuildRequest(ToolExecutionRequest request, String newArguments) {
        return ToolExecutionRequest.builder()
                .id(request.id())
                .name(request.name())
                .arguments(newArguments)
                .build();
    }

    /**
     * R3：工具结果截断。readFile 结果全省略（磁盘是唯一事实源）；
     * writeFile / modifyFile 结果本就是一句话，低于阈值不动；其余超长结果降级截断。
     */
    private ChatMessage compressToolResult(ToolExecutionResultMessage message, Map<String, String> readFilePaths) {
        String text = message.text();
        if (text == null || text.length() <= compressThreshold) {
            return message;
        }
        String replacement;
        if (TOOL_READ_FILE.equals(message.toolName())) {
            String path = readFilePaths.getOrDefault(message.id(), "未知文件");
            replacement = "[已读取 " + path + "，" + text.length() + "字符，内容已省略，需真实内容请再次 readFile]";
        } else {
            replacement = fallbackTruncate(text);
        }
        return new ToolExecutionResultMessage(message.id(), message.toolName(), replacement);
    }

    /**
     * 全文窗口 = 1：找到列表中最后一条含正文的 AiMessage 下标。
     */
    private int findLastTextAiMessageIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage aiMessage && StrUtil.isNotBlank(aiMessage.text())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 收集 readFile 工具调用的 id → 文件路径映射，用于 R3 占位卡标注来源文件。
     */
    private Map<String, String> collectReadFilePaths(List<ChatMessage> messages) {
        Map<String, String> paths = new HashMap<>();
        for (ChatMessage message : messages) {
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                if (request == null || !TOOL_READ_FILE.equals(request.name()) || request.id() == null) {
                    continue;
                }
                try {
                    String path = JSONUtil.parseObj(request.arguments()).getStr("relativeFilePath");
                    if (StrUtil.isNotBlank(path)) {
                        paths.put(request.id(), path);
                    }
                } catch (Exception e) {
                    log.debug("readFile 参数解析失败，跳过路径标注: {}", e.getMessage());
                }
            }
        }
        return paths;
    }

    private String extractFirstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 降级截断：前 300 字符 + 省略标记。宁可摘要粗糙，绝不丢消息。
     */
    private String fallbackTruncate(String text) {
        if (text.length() <= FALLBACK_KEEP_CHARS) {
            return text;
        }
        return StrUtil.subPre(text, FALLBACK_KEEP_CHARS) + "...[已省略 " + (text.length() - FALLBACK_KEEP_CHARS) + " 字符]";
    }
}
