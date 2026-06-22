package com.yu.yuaifather.service;

import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import com.yu.yuaifather.ai.model.dto.UserDesignContext;
import com.yu.yuaifather.model.entity.UserPreferenceWeight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 个性化查询文本构造器（阶段一：用户偏好闭环）。
 * <p>
 * 在 embedding 检索前，将用户偏好融入查询语义文本，
 * 使向量检索能捕捉用户的风格倾向和回避偏好。
 * <p>
 * 查询文本格式与 EmbeddingService.buildSemanticText 保持一致，
 * 确保检索端和存储端结构对齐。
 *
 * @author yu
 */
@Slf4j
@Component
public class PersonalizedQueryBuilder {

    /**
     * 构造个性化查询文本。
     * <p>
     * 格式：
     * <pre>
     * [页面类型: xxx]
     * [行业: xxx]
     * [功能模块: xxx]
     * [设计风格: xxx]
     * [期望调性: xxx]
     * [用户偏好: xxx]
     * [用户不喜欢: xxx]
     * </pre>
     *
     * @param requirement   需求文档
     * @param plan          生成计划
     * @param userContext   用户设计上下文（可空）
     * @param weights       用户偏好权重列表（可空）
     * @return 个性化查询文本
     */
    public String build(RequirementDoc requirement,
                        GenerationPlan plan,
                        UserDesignContext userContext,
                        List<UserPreferenceWeight> weights) {

        StringBuilder sb = new StringBuilder();

        // 页面类型/行业信息
        if (plan != null) {
            if (StrUtil.isNotBlank(plan.getStyleProfile())) {
                appendTag(sb, "设计风格", plan.getStyleProfile());
            }
            if (StrUtil.isNotBlank(plan.getRequirementSummary())) {
                appendTag(sb, "需求摘要", plan.getRequirementSummary());
            }
        }

        if (requirement != null) {
            if (StrUtil.isNotBlank(requirement.getSummary())) {
                appendTag(sb, "项目概述", requirement.getSummary());
            }
            if (StrUtil.isNotBlank(requirement.getTargetUser())) {
                appendTag(sb, "目标用户", requirement.getTargetUser());
            }
            if (requirement.getCoreFeatures() != null && !requirement.getCoreFeatures().isEmpty()) {
                appendTag(sb, "核心功能", String.join(", ", requirement.getCoreFeatures()));
            }
            if (requirement.getPages() != null && !requirement.getPages().isEmpty()) {
                List<String> pageNames = requirement.getPages().stream()
                        .map(RequirementDoc.PageSpec::getName)
                        .collect(Collectors.toList());
                appendTag(sb, "页面", String.join(", ", pageNames));
            }
        }

        // 用户偏好（如果存在）
        if (userContext != null && userContext.hasPreferences()) {
            List<String> positives = new ArrayList<>();
            addIfNotEmpty(positives, userContext.getPreferredStyles(), "preferredStyles");
            addIfNotEmpty(positives, userContext.getPreferredColors(), "preferredColors");
            addIfNotEmpty(positives, userContext.getPreferredLayouts(), "preferredLayouts");
            addIfNotEmpty(positives, userContext.getPreferredComponents(), "preferredComponents");
            if (!positives.isEmpty()) {
                appendTag(sb, "用户偏好", String.join(", ", positives));
            }

            List<String> negatives = new ArrayList<>();
            addIfNotEmpty(negatives, userContext.getDislikedStyles(), "dislikedStyles");
            addIfNotEmpty(negatives, userContext.getDislikedColors(), "dislikedColors");
            addIfNotEmpty(negatives, userContext.getDislikedLayouts(), "dislikedLayouts");
            addIfNotEmpty(negatives, userContext.getDislikedComponents(), "dislikedComponents");
            if (!negatives.isEmpty()) {
                appendTag(sb, "用户不喜欢", String.join(", ", negatives));
            }
        }

        // 权重信息（top positive 和 top negative）
        if (weights != null && !weights.isEmpty()) {
            List<String> topPositive = weights.stream()
                    .filter(w -> w.getWeight() > 0)
                    .sorted((a, b) -> Double.compare(Math.abs(b.getWeight()), Math.abs(a.getWeight())))
                    .limit(5)
                    .map(w -> w.getPreferenceKey())
                    .collect(Collectors.toList());
            if (!topPositive.isEmpty()) {
                appendTag(sb, "高频偏好", String.join(", ", topPositive));
            }

            List<String> topNegative = weights.stream()
                    .filter(w -> w.getWeight() < 0)
                    .sorted((a, b) -> Double.compare(Math.abs(b.getWeight()), Math.abs(a.getWeight())))
                    .limit(5)
                    .map(w -> w.getPreferenceKey())
                    .collect(Collectors.toList());
            if (!topNegative.isEmpty()) {
                appendTag(sb, "高频反感", String.join(", ", topNegative));
            }
        }

        return sb.toString().trim();
    }

    /**
     * 构造不包含个性化信息的查询文本（向后兼容）。
     */
    public String buildWithoutPersonalization(RequirementDoc requirement, GenerationPlan plan) {
        return build(requirement, plan, null, null);
    }

    private void addIfNotEmpty(List<String> target, List<String> source, String prefix) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    private void appendTag(StringBuilder sb, String tag, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("[").append(tag).append(": ").append(value).append("] ");
        }
    }
}
