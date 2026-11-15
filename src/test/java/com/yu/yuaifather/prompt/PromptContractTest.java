package com.yu.yuaifather.prompt;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContractTest {

    private static final List<String> PROMPTS = List.of(
            "agent-architect-system-prompt.txt",
            "agent-coder-system-prompt.txt",
            "agent-critic-system-prompt.txt",
            "agent-design-synthesis-system-prompt.txt",
            "agent-extractor-system-prompt.txt",
            "agent-page-system-prompt.txt",
            "agent-product-system-prompt.txt",
            "agent-style-system-prompt.txt",
            "codegen-html-system-prompt.txt",
            "codegen-multi-file-system-prompt.txt",
            "codegen-routing-system-prompt.txt",
            "codegen-vue-project-system-prompt.txt"
    );

    @Test
    void promptResourcesContainNoHiddenFormatCharactersOrLinkPollution() {
        for (String name : PROMPTS) {
            String prompt = readPrompt(name);
            assertFalse(prompt.contains("<a href=\"http"), name + " 不应包含来源网页链接");
            assertFalse(prompt.codePoints().anyMatch(cp -> Character.getType(cp) == Character.FORMAT),
                    name + " 不应包含不可见格式字符");
        }
    }

    @Test
    void generationOutputContractsMatchParsers() {
        String html = readPrompt("codegen-html-system-prompt.txt");
        assertTrue(html.contains("只能包含一个 `html` 代码块"));
        assertFalse(html.contains("可以在代码块前后"));

        String multi = readPrompt("codegen-multi-file-system-prompt.txt");
        assertTrue(multi.contains("只能输出 3 个完整代码块"));
        assertTrue(multi.contains("```html"));
        assertTrue(multi.contains("```css"));
        assertTrue(multi.contains("```javascript"));
    }

    @Test
    void designExamplesDoNotAnchorToLegacyThreeCardTemplate() {
        assertFalse(readPrompt("agent-design-synthesis-system-prompt.txt")
                .contains("Hero + 3栏特性 + 定价"));
        assertFalse(readPrompt("agent-extractor-system-prompt.txt")
                .contains("Hero + 3栏特性 + 定价"));
    }

    @Test
    void criticContractContainsEvidenceAndSimilarityGates() {
        String critic = readPrompt("agent-critic-system-prompt.txt");
        assertTrue(critic.contains("sourceFiles"));
        assertTrue(critic.contains("[fixed-template]"));
        assertTrue(critic.contains("[component-repetition]"));
        assertTrue(critic.contains("[brand-identity]"));
        assertFalse(critic.contains("{{"),
                "Critic 系统提示词会被 LangChain4j 当模板渲染，不能引用未提供的变量");
        assertTrue(critic.contains("Vue 插值"));
    }

    private String readPrompt(String name) {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("prompt/" + name)) {
            assertNotNull(input, "缺少提示词资源: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取提示词失败: " + name, e);
        }
    }
}
