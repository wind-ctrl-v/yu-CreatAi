package com.yu.yuaifather.codegen.memory;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatMemoryCompressor} 规则单测：R1 代码块占位 / R2 工具参数截断 / R3 工具结果截断 /
 * 全文窗口=1 / 阈值 / 降级安全。
 */
class ChatMemoryCompressorTest {

    private ChatMemoryCompressor compressor;

    @BeforeEach
    void setUp() {
        compressor = new ChatMemoryCompressor();
        ReflectionTestUtils.setField(compressor, "compressThreshold", 1500);
        ReflectionTestUtils.setField(compressor, "explainMaxChars", 500);
    }

    // ---------- 通用行为 ----------

    @Test
    void nullAndEmptyList_returnedAsIs() {
        assertNull(compressor.compressAll(null));
        List<ChatMessage> empty = List.of();
        assertSame(empty, compressor.compressAll(empty));
    }

    @Test
    void userMessage_alwaysPreserved() {
        String longUserText = pad(3000);
        List<ChatMessage> result = compressor.compressAll(List.of(
                UserMessage.from(longUserText), AiMessage.from("好的")));
        assertEquals(longUserText, ((UserMessage) result.get(0)).singleText());
    }

    @Test
    void shortAiMessage_belowThreshold_untouched() {
        AiMessage shortMessage = AiMessage.from("这是一条很短的回复");
        List<ChatMessage> result = compressor.compressAll(List.of(
                shortMessage, AiMessage.from("好的")));
        assertSame(shortMessage, result.get(0));
    }

    // ---------- 全文窗口 = 1 ----------

    @Test
    void lastAiMessage_keptFull_earlierOneCompressed() {
        String a1Text = bigHtmlAiText("旧版页面");
        String a2Text = bigHtmlAiText("新版页面");
        List<ChatMessage> result = compressor.compressAll(List.of(
                UserMessage.from("做一个咖啡店落地页"),
                AiMessage.from(a1Text),
                UserMessage.from("改成暗色风格"),
                AiMessage.from(a2Text)));
        // 较早的 A1 被压缩为占位卡
        String a1Compressed = ((AiMessage) result.get(1)).text();
        assertTrue(a1Compressed.contains("[html index.html"), a1Compressed);
        assertTrue(a1Compressed.length() < a1Text.length() / 2);
        // 最新一条 A2 保留全文（全文窗口=1，MULTI_FILE/HTML 修改轮依赖它）
        assertEquals(a2Text, ((AiMessage) result.get(3)).text());
    }

    // ---------- R1 代码块占位 ----------

    @Test
    void htmlBlock_extractsTitleAndSkeleton() {
        String compressed = compressSingleBigAi(bigHtmlAiText("说明文字"));
        assertTrue(compressed.contains("[html index.html"), compressed);
        assertTrue(compressed.contains("title=「隅咖啡」"), compressed);
        assertTrue(compressed.contains("骨架: header.nav-sticky"), compressed);
        assertTrue(compressed.contains("section#hero.split-layout"), compressed);
        // 代码块本体已被移除
        assertTrue(!compressed.contains("```"), compressed);
    }

    @Test
    void cssBlock_extractsVarsAndSelectors() {
        String cssCode = ":root {\n--bg: #12100e;\n--primary: #c8956c;\n}\n"
                + ".hero { color: red; }\n.menu-card { color: blue; }\n" + pad(1600);
        String compressed = compressSingleBigAi("样式说明\n\n```css\n" + cssCode + "\n```");
        assertTrue(compressed.contains("[css style.css"), compressed);
        assertTrue(compressed.contains("--bg:#12100e"), compressed);
        assertTrue(compressed.contains("--primary:#c8956c"), compressed);
        assertTrue(compressed.contains(".hero"), compressed);
        assertTrue(compressed.contains(".menu-card"), compressed);
    }

    @Test
    void jsBlock_extractsFunctionNames() {
        String jsCode = "function initCarousel() {}\nfunction validateForm() {}\nconst appConfig = {};\n" + pad(1600);
        String compressed = compressSingleBigAi("脚本说明\n\n```js\n" + jsCode + "\n```");
        assertTrue(compressed.contains("[js script.js"), compressed);
        assertTrue(compressed.contains("initCarousel"), compressed);
        assertTrue(compressed.contains("validateForm"), compressed);
    }

    @Test
    void unlabeledBlock_keepsFirstLineAndPlaceholder() {
        // R3'：VUE 修改轮的替换前/后代码块（无语言标记）→ 首行 + 占位
        String blockContent = "<template><div>首行内容</div></template>\n" + pad(1600);
        String compressed = compressSingleBigAi("[工具调用] 修改文件 src/App.vue\n\n```\n" + blockContent + "\n```");
        assertTrue(compressed.contains("[代码块,"), compressed);
        assertTrue(compressed.contains("首行: <template><div>首行内容</div></template>"), compressed);
        assertTrue(compressed.contains("[工具调用] 修改文件 src/App.vue"), compressed);
    }

    @Test
    void explainText_overLimit_truncatedTo500() {
        String longExplain = pad(600);
        String compressed = compressSingleBigAi(longExplain + "\n\n```js\n" + pad(1600) + "\n```");
        // 解释文字被截断为 500 字符 + "..."
        assertTrue(compressed.startsWith(pad(500) + "..."), compressed);
    }

    // ---------- R1 降级与边界 ----------

    @Test
    void longTextWithoutCodeBlock_fallbackTruncated() {
        String longText = pad(2000);
        String compressed = compressSingleBigAi(longText);
        assertTrue(compressed.startsWith(pad(300)), compressed);
        assertTrue(compressed.contains("[已省略 1700 字符]"), compressed);
    }

    @Test
    void malformedCodeBlock_doesNotThrowAndFallback() {
        // 格式错乱：代码块未闭合
        String malformed = "```html\n" + pad(2000);
        String compressed = assertDoesNotThrow(() -> compressSingleBigAi(malformed));
        assertTrue(compressed.contains("[已省略"), compressed);
    }

    @Test
    void emptyCodeBlock_doesNotThrow() {
        String text = pad(1600) + "\n\n```html\n```";
        String compressed = assertDoesNotThrow(() -> compressSingleBigAi(text));
        assertTrue(compressed.contains("[html index.html, 0行"), compressed);
    }

    @Test
    void singleLineHugeCode_compressedToCard() {
        String text = "说明\n\n```js\n" + pad(5000) + "\n```";
        String compressed = assertDoesNotThrow(() -> compressSingleBigAi(text));
        assertTrue(compressed.contains("[js script.js, 1行"), compressed);
    }

    // ---------- R2 工具参数截断 ----------

    @Test
    void writeFileArgs_contentOmitted_structurePreserved() {
        String content = "c".repeat(2000);
        String args = "{\"relativeFilePath\":\"index.html\",\"content\":\"" + content + "\"}";
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t1").name("writeFile").arguments(args).build();
        List<ChatMessage> result = compressor.compressAll(List.of(
                AiMessage.from(List.of(request)), AiMessage.from("完成")));

        AiMessage compressed = (AiMessage) result.get(0);
        ToolExecutionRequest compressedReq = compressed.toolExecutionRequests().get(0);
        // id / name 结构不变（工具调用与结果需配对校验）
        assertEquals("t1", compressedReq.id());
        assertEquals("writeFile", compressedReq.name());
        assertEquals("[已省略, 2000字符]", JSONUtil.parseObj(compressedReq.arguments()).getStr("content"));
        assertEquals("index.html", JSONUtil.parseObj(compressedReq.arguments()).getStr("relativeFilePath"));
    }

    @Test
    void modifyFileArgs_keeps80CharPrefix() {
        String oldContent = "o".repeat(900);
        String newContent = "n".repeat(900);
        String args = "{\"relativeFilePath\":\"a.vue\",\"oldContent\":\"" + oldContent
                + "\",\"newContent\":\"" + newContent + "\"}";
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t2").name("modifyFile").arguments(args).build();
        List<ChatMessage> result = compressor.compressAll(List.of(
                AiMessage.from(List.of(request)), AiMessage.from("完成")));

        String compressedArgs = ((AiMessage) result.get(0)).toolExecutionRequests().get(0).arguments();
        // old→new 片段是变更语义载体，各留前 80 字符
        assertEquals("o".repeat(80) + "...[共900字符]", JSONUtil.parseObj(compressedArgs).getStr("oldContent"));
        assertEquals("n".repeat(80) + "...[共900字符]", JSONUtil.parseObj(compressedArgs).getStr("newContent"));
    }

    @Test
    void invalidJsonArgs_fallbackTruncated_idPreserved() {
        String args = "{\"relativeFilePath\":" + pad(2000);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t3").name("writeFile").arguments(args).build();
        List<ChatMessage> result = assertDoesNotThrow(() -> compressor.compressAll(List.of(
                AiMessage.from(List.of(request)), AiMessage.from("完成"))));

        ToolExecutionRequest compressedReq = ((AiMessage) result.get(0)).toolExecutionRequests().get(0);
        assertEquals("t3", compressedReq.id());
        assertTrue(compressedReq.arguments().contains("[已省略"), compressedReq.arguments());
        assertTrue(compressedReq.arguments().length() < args.length());
    }

    @Test
    void shortToolArgs_untouched() {
        String args = "{\"relativeFilePath\":\"a.txt\",\"content\":\"小文件\"}";
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t4").name("writeFile").arguments(args).build();
        List<ChatMessage> result = compressor.compressAll(List.of(
                AiMessage.from(List.of(request)), AiMessage.from("完成")));
        assertSame(request, ((AiMessage) result.get(0)).toolExecutionRequests().get(0));
    }

    // ---------- R3 工具结果截断 ----------

    @Test
    void readFileResult_replacedWithPath() {
        ToolExecutionRequest readRequest = ToolExecutionRequest.builder()
                .id("r1").name("readFile").arguments("{\"relativeFilePath\":\"src/App.vue\"}").build();
        ToolExecutionResultMessage readResult = new ToolExecutionResultMessage("r1", "readFile", pad(3000));
        List<ChatMessage> result = compressor.compressAll(List.of(
                AiMessage.from(List.of(readRequest)), readResult, AiMessage.from("完成")));

        ToolExecutionResultMessage compressed = (ToolExecutionResultMessage) result.get(1);
        assertEquals("[已读取 src/App.vue，3000字符，内容已省略]", compressed.text());
        assertEquals("r1", compressed.id());
        assertEquals("readFile", compressed.toolName());
    }

    @Test
    void readFileResult_withoutPairingRequest_unknownFile() {
        ToolExecutionResultMessage readResult = new ToolExecutionResultMessage("r9", "readFile", pad(3000));
        List<ChatMessage> result = compressor.compressAll(List.of(
                readResult, AiMessage.from("完成")));
        assertTrue(((ToolExecutionResultMessage) result.get(0)).text().contains("未知文件"));
    }

    @Test
    void shortToolResult_untouched() {
        ToolExecutionResultMessage writeResult = new ToolExecutionResultMessage("t1", "writeFile", "文件写入成功: a.html");
        List<ChatMessage> result = compressor.compressAll(List.of(
                writeResult, AiMessage.from("完成")));
        assertSame(writeResult, result.get(0));
    }

    // ---------- 组合策略 ----------

    @Test
    void lastAiMessage_textKeptFull_butToolArgsStillCompressed() {
        // VUE 链路：最新一条 AI 消息正文保留全文，但工具参数仍按 R2 压缩
        String content = "c".repeat(2000);
        String args = "{\"relativeFilePath\":\"a.vue\",\"content\":\"" + content + "\"}";
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("t5").name("writeFile").arguments(args).build();
        String bigText = bigHtmlAiText("最新生成");
        List<ChatMessage> result = compressor.compressAll(List.of(
                UserMessage.from("生成页面"),
                AiMessage.from(bigText, List.of(request))));

        AiMessage last = (AiMessage) result.get(1);
        assertEquals(bigText, last.text());
        assertEquals("[已省略, 2000字符]",
                JSONUtil.parseObj(last.toolExecutionRequests().get(0).arguments()).getStr("content"));
    }

    @Test
    void compression_isIdempotent() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("做一个页面"),
                AiMessage.from(bigHtmlAiText("说明")),
                AiMessage.from("好的"));
        List<ChatMessage> once = compressor.compressAll(messages);
        List<ChatMessage> twice = compressor.compressAll(once);
        assertEquals(once.size(), twice.size());
        for (int i = 0; i < once.size(); i++) {
            assertEquals(once.get(i).toString(), twice.get(i).toString());
        }
    }

    // ---------- 工具方法 ----------

    /**
     * 压缩"一条大 AI 消息 + 一条短尾随 AI 消息"的列表，返回大消息的压缩文本。
     * 尾随消息用于让被测消息让出"全文窗口"的最新身份。
     */
    private String compressSingleBigAi(String bigText) {
        List<ChatMessage> result = compressor.compressAll(List.of(
                AiMessage.from(bigText), AiMessage.from("好的")));
        return ((AiMessage) result.get(0)).text();
    }

    private static String bigHtmlAiText(String explain) {
        String htmlCode = "<!DOCTYPE html>\n<html>\n<head><title>隅咖啡</title></head>\n<body>\n"
                + "<header class=\"nav-sticky\">导航</header>\n"
                + "<main><section id=\"hero\" class=\"split-layout\">横幅</section></main>\n"
                + pad(1600) + "\n</body>\n</html>";
        return explain + "\n\n```html\n" + htmlCode + "\n```";
    }

    private static String pad(int n) {
        return "x".repeat(n);
    }
}
