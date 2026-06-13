package com.yu.yuaifather.ai.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CriticAgent 输出的质检报告。
 * <p>
 * 评分规则：满分 100，P0 问题每条扣 30，P1 每条扣 15，P2 每条扣 5；
 * score &ge; 80 视为通过（passed=true）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticReport {

    /** 是否通过质检。 */
    private boolean passed;

    /** 最终评分（0-100）。 */
    private int score;

    /** 总体评价。 */
    private String summary;

    /** 问题清单。 */
    private List<Issue> issues;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {
        /** 严重级别：P0 / P1 / P2。 */
        private String severity;
        /** 问题类别：build / placeholder / style / a11y / content 等。 */
        private String category;
        /** 涉及文件相对路径（可空）。 */
        private String file;
        /** 问题描述。 */
        private String description;
        /** 修复建议。 */
        private String suggestion;
    }
}
