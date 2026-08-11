package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.model.dto.agent.CriticReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticAgentDesignGateTest {

    @Test
    void fixedTemplateAlwaysFailsEvenWhenModelMarksPassed() throws Exception {
        CriticReport report = reportWithIssues(List.of(issue("[fixed-template] 机械套用固定模板")));

        enforceGate(report);

        assertFalse(report.isPassed());
        assertTrue(report.getScore() <= 75);
    }

    @Test
    void repeatedComponentsAndMissingBrandTogetherFail() throws Exception {
        CriticReport report = reportWithIssues(List.of(
                issue("[component-repetition] 组件重复"),
                issue("[brand-identity] 品牌辨识度不足")
        ));

        enforceGate(report);

        assertFalse(report.isPassed());
    }

    private CriticReport reportWithIssues(List<CriticReport.Issue> issues) {
        return CriticReport.builder()
                .passed(true)
                .score(90)
                .issues(issues)
                .build();
    }

    private CriticReport.Issue issue(String description) {
        return CriticReport.Issue.builder()
                .severity("P1")
                .category("design_similarity")
                .description(description)
                .build();
    }

    private void enforceGate(CriticReport report) throws Exception {
        CriticAgent agent = new CriticAgent(null, null, null);
        Method method = CriticAgent.class.getDeclaredMethod(
                "enforceDesignSimilarityGate", CriticReport.class, Long.class);
        method.setAccessible(true);
        method.invoke(agent, report, 1L);
    }
}
