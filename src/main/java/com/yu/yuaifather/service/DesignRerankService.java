package com.yu.yuaifather.service;

import cn.hutool.core.collection.CollUtil;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.ProjectBrandContext;
import com.yu.yuaifather.model.dto.agent.UserDesignContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 综合重排服务（阶段三：项目品牌记忆 + 评估治理）。
 * <p>
 * 实现 9 项加权计分公式：
 * <pre>
 * final_score =
 *   0.25 * semantic_similarity
 * + 0.15 * style_profile_score
 * + 0.15 * component_match_score
 * + 0.10 * layout_match_score
 * + 0.10 * design_quality_score
 * + 0.15 * user_preference_score
 * + 0.15 * project_brand_score
 * - 0.20 * user_dislike_penalty
 * - 0.20 * project_forbidden_penalty
 * - 0.10 * duplicate_penalty
 * </pre>
 * <p>
 * 各项权重可配置。
 *
 * @author yu
 */
@Slf4j
@Service
public class DesignRerankService {

    @Value("${agent.rerank.weights.semantic:0.25}")
    private double wSemantic;

    @Value("${agent.rerank.weights.style:0.15}")
    private double wStyle;

    @Value("${agent.rerank.weights.component:0.15}")
    private double wComponent;

    @Value("${agent.rerank.weights.layout:0.10}")
    private double wLayout;

    @Value("${agent.rerank.weights.quality:0.10}")
    private double wQuality;

    @Value("${agent.rerank.weights.user-pref:0.15}")
    private double wUserPref;

    @Value("${agent.rerank.weights.project-brand:0.15}")
    private double wProjectBrand;

    @Value("${agent.rerank.weights.dislike-penalty:-0.20}")
    private double wDislikePenalty;

    @Value("${agent.rerank.weights.forbidden-penalty:-0.20}")
    private double wForbiddenPenalty;

    @Value("${agent.rerank.weights.duplicate-penalty:-0.10}")
    private double wDuplicatePenalty;

    @Value("${agent.rerank.enabled:false}")
    private boolean rerankEnabled;

    private final UserPreferenceService userPreferenceService;

    public DesignRerankService(UserPreferenceService userPreferenceService) {
        this.userPreferenceService = userPreferenceService;
    }

    /**
     * 综合重排：为每条证据计算综合得分并排序。
     *
     * @param items         检索结果条目列表
     * @param queryTags     查询标签
     * @param userContext   用户偏好上下文（可空）
     * @param brandContext  项目品牌上下文（可空）
     * @return 按 final_score DESC 排序的条目列表
     */
    public List<ScoredItem> rerank(List<DesignEvidence.Item> items,
                                    List<String> queryTags,
                                    UserDesignContext userContext,
                                    ProjectBrandContext brandContext) {
        if (CollUtil.isEmpty(items)) {
            return Collections.emptyList();
        }

        Long userId = userContext != null ? userContext.getUserId() : null;

        return items.stream()
                .map(item -> {
                    ScoredItem scored = new ScoredItem();
                    scored.item = item;

                    // 语义相似度（用 matchScore 近似）
                    scored.semanticScore = estimateSemanticScore(item, queryTags);

                    // 风格画像匹配
                    scored.styleScore = estimateStyleScore(item, brandContext);

                    // 组件匹配
                    scored.componentScore = estimateComponentScore(item, queryTags);

                    // 布局匹配
                    scored.layoutScore = estimateLayoutScore(item, brandContext);

                    // 设计质量（置信度）
                    scored.qualityScore = item.getConfidence() != null ? item.getConfidence() : 0.5;

                    // 用户偏好得分
                    scored.userPrefScore = userId != null
                            ? estimateUserPreference(item, userId) : 0.5;

                    // 项目品牌得分
                    scored.projectBrandScore = estimateProjectBrandScore(item, brandContext);

                    // 用户反感惩罚
                    scored.dislikePenalty = userId != null
                            ? estimateDislikePenalty(item, userId) : 0;

                    // 项目禁用惩罚
                    scored.forbiddenPenalty = estimateForbiddenPenalty(item, brandContext);

                    // 同风格去重惩罚（与已选条目高度相似时降权）
                    scored.duplicatePenalty = estimateDuplicatePenalty(items, item, items.indexOf(item));

                    // 综合得分（10项加权）
                    scored.finalScore = wSemantic * scored.semanticScore
                            + wStyle * scored.styleScore
                            + wComponent * scored.componentScore
                            + wLayout * scored.layoutScore
                            + wQuality * scored.qualityScore
                            + wUserPref * scored.userPrefScore
                            + wProjectBrand * scored.projectBrandScore
                            + wDislikePenalty * scored.dislikePenalty
                            + wForbiddenPenalty * scored.forbiddenPenalty
                            + wDuplicatePenalty * scored.duplicatePenalty;

                    return scored;
                })
                .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore))
                .collect(Collectors.toList());
    }

    // ============ 各维度得分估算 ============

    private double estimateSemanticScore(DesignEvidence.Item item, List<String> queryTags) {
        if (queryTags == null || queryTags.isEmpty()) return 0.5;
        int hits = 0;
        for (String tag : queryTags) {
            if (containsTag(item.getTone(), tag)
                    || containsTag(item.getPreferredPatterns(), tag)
                    || containsTag(item.getLayoutPatterns(), tag)) {
                hits++;
            }
        }
        return hits > 0 ? Math.min(1.0, (double) hits / queryTags.size()) : 0.3;
    }

    private double estimateStyleScore(DesignEvidence.Item item, ProjectBrandContext brandContext) {
        if (brandContext == null || !brandContext.hasBrandProfile()) return 0.5;
        if (brandContext.getBrandTone() == null) return 0.5;
        if (item.getTone() == null) return 0.3;
        int hits = (int) item.getTone().stream()
                .filter(t -> brandContext.getBrandTone().contains(t))
                .count();
        return hits > 0 ? Math.min(1.0, 0.5 + 0.1 * hits) : 0.5;
    }

    private double estimateComponentScore(DesignEvidence.Item item, List<String> queryTags) {
        if (queryTags == null || item.getPreferredPatterns() == null) return 0.5;
        int hits = (int) item.getPreferredPatterns().stream()
                .filter(p -> queryTags.stream().anyMatch(t -> t.equalsIgnoreCase(p)))
                .count();
        return hits > 0 ? Math.min(1.0, 0.5 + 0.1 * hits) : 0.5;
    }

    private double estimateLayoutScore(DesignEvidence.Item item, ProjectBrandContext brandContext) {
        if (brandContext == null || brandContext.getLayoutPreference() == null) return 0.5;
        if (item.getLayoutPatterns() == null) return 0.3;
        int hits = (int) item.getLayoutPatterns().stream()
                .filter(l -> brandContext.getLayoutPreference().contains(l))
                .count();
        return hits > 0 ? Math.min(1.0, 0.5 + 0.15 * hits) : 0.5;
    }

    private double estimateUserPreference(DesignEvidence.Item item, Long userId) {
        double score = 0;
        if (CollUtil.isNotEmpty(item.getTone())) {
            score += userPreferenceService.calculatePreferenceScore(userId, "style", item.getTone());
        }
        if (CollUtil.isNotEmpty(item.getPreferredPatterns())) {
            score += userPreferenceService.calculatePreferenceScore(userId, "component", item.getPreferredPatterns());
        }
        if (CollUtil.isNotEmpty(item.getLayoutPatterns())) {
            score += userPreferenceService.calculatePreferenceScore(userId, "layout", item.getLayoutPatterns());
        }
        return score == 0 ? 0.5 : Math.max(0, Math.min(1, score + 0.5));
    }

    private double estimateProjectBrandScore(DesignEvidence.Item item, ProjectBrandContext brandContext) {
        if (brandContext == null || !brandContext.hasBrandProfile()) return 0.5;
        // 命中品牌调性/行业/目标用户时加分
        double score = 0.5;
        if (brandContext.getBrandTone() != null && item.getTone() != null) {
            long hits = item.getTone().stream()
                    .filter(t -> brandContext.getBrandTone().contains(t))
                    .count();
            score += 0.1 * hits;
        }
        if (brandContext.getComponentPreference() != null && item.getPreferredPatterns() != null) {
            long hits = item.getPreferredPatterns().stream()
                    .filter(p -> brandContext.getComponentPreference().contains(p))
                    .count();
            score += 0.05 * hits;
        }
        return Math.min(1.0, score);
    }

    private double estimateDislikePenalty(DesignEvidence.Item item, Long userId) {
        double penalty = 0;
        if (CollUtil.isNotEmpty(item.getTone())) {
            penalty += userPreferenceService.calculateDislikePenalty(userId, "style", item.getTone());
        }
        if (CollUtil.isNotEmpty(item.getPreferredPatterns())) {
            penalty += userPreferenceService.calculateDislikePenalty(userId, "component", item.getPreferredPatterns());
        }
        return Math.min(1.0, penalty);
    }

    /**
     * 项目禁用规则惩罚：命中项目禁用项时强降权。
     */
    private double estimateForbiddenPenalty(DesignEvidence.Item item, ProjectBrandContext brandContext) {
        if (brandContext == null || brandContext.getForbiddenRules() == null) return 0;
        if (item.getForbiddenPatterns() == null && item.getTone() == null) return 0;

        Set<String> forbidden = Set.copyOf(brandContext.getForbiddenRules());
        int violations = 0;

        if (item.getTone() != null) {
            violations += (int) item.getTone().stream().filter(forbidden::contains).count();
        }
        if (item.getPreferredPatterns() != null) {
            violations += (int) item.getPreferredPatterns().stream().filter(forbidden::contains).count();
        }

        return violations > 0 ? Math.min(1.0, 0.2 * violations) : 0;
    }

    private boolean containsTag(List<String> list, String tag) {
        if (list == null || tag == null) return false;
        return list.stream().anyMatch(t -> t != null && t.toLowerCase().contains(tag.toLowerCase()));
    }

    /**
     * 同风格去重惩罚：与列表中已有条目风格高度重叠时降权，避免重复注入相似指纹。
     */
    private double estimateDuplicatePenalty(List<DesignEvidence.Item> allItems,
                                             DesignEvidence.Item current, int currentIndex) {
        if (allItems == null || currentIndex <= 0) return 0;
        double maxOverlap = 0;
        for (int j = 0; j < currentIndex; j++) {
            DesignEvidence.Item other = allItems.get(j);
            double overlap = 0;
            if (current.getTone() != null && other.getTone() != null) {
                long common = current.getTone().stream()
                        .filter(t -> other.getTone().contains(t))
                        .count();
                overlap += common > 0 ? (double) common / Math.max(1, current.getTone().size()) : 0;
            }
            if (current.getPreferredPatterns() != null && other.getPreferredPatterns() != null) {
                long common = current.getPreferredPatterns().stream()
                        .filter(p -> other.getPreferredPatterns().contains(p))
                        .count();
                overlap += common > 0 ? (double) common / Math.max(1, current.getPreferredPatterns().size()) : 0;
            }
            maxOverlap = Math.max(maxOverlap, overlap);
        }
        return Math.min(1.0, maxOverlap);
    }

    /**
     * 带评分的结果条目。
     */
    public static class ScoredItem {
        public DesignEvidence.Item item;
        public double semanticScore;
        public double styleScore;
        public double componentScore;
        public double layoutScore;
        public double qualityScore;
        public double userPrefScore;
        public double projectBrandScore;
        public double dislikePenalty;
        public double forbiddenPenalty;
        public double duplicatePenalty;
        public double finalScore;
    }
}
