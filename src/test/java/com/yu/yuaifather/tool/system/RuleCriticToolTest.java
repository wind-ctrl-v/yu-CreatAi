package com.yu.yuaifather.tool.system;

import com.yu.yuaifather.tool.core.ToolContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCriticToolTest {

    private final RuleCriticTool tool = new RuleCriticTool();
    private final ToolContext context = ToolContext.builder().build();

    @Test
    void detectsFixedHeroCardFooterTemplate() {
        String html = """
                <header><nav>导航</nav></header>
                <main>
                  <section class="hero">主视觉</section>
                  <section class="features feature-grid">
                    <article class="feature-card">一</article>
                    <article class="feature-card">二</article>
                    <article class="feature-card">三</article>
                  </section>
                </main>
                <footer>页脚</footer>
                """;

        RuleCriticTool.RuleCriticResult result = tool.execute(
                new RuleCriticTool.RuleCriticInput(null, html, ":root{--space:1rem}.x{gap:var(--space)}", ""),
                context);

        assertTrue(result.hasDesignSimilarityIssues());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("固定模板")));
    }

    @Test
    void acceptsDistinctEditorialStructureAndUnrelatedClassNames() {
        String html = """
                <main class="editorial-layout">
                  <aside class="issue-index">目录</aside>
                  <article class="lead-story scorecard">主文章</article>
                  <ol class="timeline"><li>阶段一</li><li>阶段二</li></ol>
                </main>
                """;

        RuleCriticTool.RuleCriticResult result = tool.execute(
                new RuleCriticTool.RuleCriticInput(null, html, ":root{--space:1rem}.x{gap:var(--space)}", ""),
                context);

        assertFalse(result.hasDesignSimilarityIssues());
    }
}
