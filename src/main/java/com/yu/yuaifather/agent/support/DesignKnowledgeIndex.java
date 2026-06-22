package com.yu.yuaifather.agent.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.DesignEvidence;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintVector;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.ai.model.dto.GenerationPlan;
import com.yu.yuaifather.ai.model.dto.RequirementDoc;
import com.yu.yuaifather.ai.model.dto.UserDesignContext;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.service.DesignFingerprintService;
import com.yu.yuaifather.service.DesignFingerprintVectorRepository;
import com.yu.yuaifather.service.PersonalizedQueryBuilder;
import com.yu.yuaifather.service.UserPreferenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DesignKnowledgeIndex：统一知识层索引访问（阶段三，见计划 3.4 / 3.5）。
 * <p>
 * 合并<b>先验</b>（人工静态 {@link DesignSpec}）与<b>后验</b>（APPROVED 六层指纹）为统一证据集，
 * 按 {@code priority DESC → confidence DESC → updatedAt DESC → softMatchScore DESC} 排序取 top-k。
 *
 * <h3>渐进增强（3.1 / 3.6）</h3>
 * <ul>
 *   <li><b>3B 向量 RAG</b>（已接入）：PGvector 语义检索优先，失败自动回退 3A；</li>
 *   <li><b>3A 轻量结构化检索</b>：MySQL 结构化字段硬过滤 + Java 侧软匹配排序；</li>
 *   <li><b>静态规范兜底</b>：写入阶段一静态 {@link DesignSpec} 作为先验证据。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBean(EmbeddingService.class)
public class DesignKnowledgeIndex {

    /** 召回 topK 上限（见 3.2，最大不超过 3）。 */
    private static final int MAX_TOP_K = 3;

    private final DesignFingerprintService fingerprintService;
    private final FingerprintProjector fingerprintProjector;
    private final DesignFingerprintVectorRepository vectorRepo;
    private final EmbeddingService embeddingService;
    private final PersonalizedQueryBuilder queryBuilder;
    private final UserPreferenceService userPreferenceService;

    @Value("${agent.retrieve.vector.enabled:false}")
    private boolean vectorEnabled;

    @Value("${agent.retrieve.vector.min-similarity:0.7}")
    private double minSimilarity;

    @Value("${agent.personalization.enabled:false}")
    private boolean personalizationEnabled;

    public DesignKnowledgeIndex(DesignFingerprintService fingerprintService,
                                FingerprintProjector fingerprintProjector,
                                DesignFingerprintVectorRepository vectorRepo,
                                @Autowired(required = false) EmbeddingService embeddingService,
                                @Autowired(required = false) PersonalizedQueryBuilder queryBuilder,
                                @Autowired(required = false) UserPreferenceService userPreferenceService) {
        this.fingerprintService = fingerprintService;
        this.fingerprintProjector = fingerprintProjector;
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
        this.queryBuilder = queryBuilder;
        this.userPreferenceService = userPreferenceService;
    }

    /**
     * 统一检索：3B 向量检索优先 → 3A 结构化回退 → 静态规范兜底。
     * <p>
     * 检索链路：
     * <ol>
     *   <li>先验：静态规范始终入 ranked（priority=50，永远排最前）；</li>
     *   <li>3B（如开启）：构造查询文本 → embed → PGvector cosine 检索 →
     *       命中后回 MySQL 加载完整指纹 → softMatchScore 打分入 ranked；</li>
     *   <li>3A（3B 关闭/失败/无结果时）：MySQL 结构化查询 → softMatchScore 打分入 ranked；</li>
     *   <li>合并排序取 top-k。</li>
     * </ol>
     *
     * @param styleProfile 风格画像（硬过滤）
     * @param queryTags    需求语义标签（软匹配，可空）
     * @param staticSpec   阶段一静态规范（先验，可空）
     * @param topK         期望条数（自动收敛到 [1, {@link #MAX_TOP_K}]）
     * @return 受控证据；任何异常都降级为「仅静态规范」或空，不抛出中断
     */
    public DesignEvidence retrieve(String styleProfile, List<String> queryTags, DesignSpec staticSpec, int topK) {
        int k = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<Ranked> ranked = new ArrayList<>();

        // 先验：静态规范始终排第一（priority=50 > 指纹默认 30）
        if (staticSpec != null) {
            ranked.add(toRanked(specToItem(staticSpec, staticSpec.getSourceType()),
                    LocalDateTime.now(), 0));
        }

        String channel = "STATIC_FALLBACK";
        boolean vectorHit = false;

        // ★ 3B 向量检索优先（独立开关，关闭或失败自动跳过）
        if (vectorEnabled) {
            try {
                vectorHit = tryVectorRetrieve(styleProfile, queryTags, k, ranked);
                if (vectorHit) {
                    channel = "VECTOR_3B";
                }
            } catch (Exception e) {
                log.warn("3B 向量检索异常，回退 3A profile={} msg={}", styleProfile, e.getMessage());
            }
        }

        // ★ 3A 结构化回退（3B 未命中时启用）
        if (!vectorHit) {
            try {
                List<DesignFingerprint> fps = fingerprintService.listApproved(styleProfile, Math.max(k * 3, 9));
                if (CollUtil.isNotEmpty(fps)) {
                    channel = "STRUCTURAL_3A";
                    for (DesignFingerprint fp : fps) {
                        DesignEvidence.Item item = fingerprintToItem(fp);
                        if (item != null) {
                            int matchScore = softMatchScore(item, queryTags);
                            ranked.add(toRanked(item, fp.getUpdateTime(), matchScore));
                        }
                    }
                }
            } catch (Exception e) {
                // 检索失败（如表不存在 / DB 异常）→ 仅用静态规范，绝不中断生成（见 3.7）
                log.warn("结构化召回失败，降级为仅静态规范 profile={} msg={}", styleProfile, e.getMessage());
            }
        }

        // 合并排序：priority DESC → confidence DESC → updatedAt DESC → softMatchScore DESC
        ranked.sort((a, b) -> {
            int c = Integer.compare(nz(b.item.getPriority()), nz(a.item.getPriority()));
            if (c != 0) return c;
            c = Double.compare(nzd(b.item.getConfidence()), nzd(a.item.getConfidence()));
            if (c != 0) return c;
            c = b.updatedAt.compareTo(a.updatedAt);
            if (c != 0) return c;
            return Integer.compare(b.matchScore, a.matchScore);
        });

        List<DesignEvidence.Item> items = new ArrayList<>();
        for (int i = 0; i < ranked.size() && items.size() < k; i++) {
            items.add(ranked.get(i).item);
        }
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel(channel)
                .items(items)
                .build();
    }

    /**
     * 个性化检索（阶段一新增）：支持用户偏好注入的检索方法。
     * <p>
     * 在原有检索基础上，使用 PersonalizedQueryBuilder 构造个性化查询文本，
     * 并在排序阶段融入用户偏好得分和反感惩罚。
     *
     * @param styleProfile 风格画像
     * @param queryTags    需求语义标签
     * @param staticSpec   静态规范
     * @param requirement  需求文档（可空）
     * @param plan         生成计划（可空）
     * @param userContext  用户设计上下文（可空）
     * @param topK         期望条数
     * @return 受控证据
     */
    public DesignEvidence retrieve(String styleProfile, List<String> queryTags, DesignSpec staticSpec,
                                    RequirementDoc requirement, GenerationPlan plan,
                                    UserDesignContext userContext, int topK) {
        int k = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<Ranked> ranked = new ArrayList<>();

        // 先验：静态规范始终排第一
        if (staticSpec != null) {
            ranked.add(toRanked(specToItem(staticSpec, staticSpec.getSourceType()),
                    LocalDateTime.now(), 0));
        }

        String channel = "STATIC_FALLBACK";
        boolean vectorHit = false;

        // ★ 3B 向量检索优先（使用个性化查询文本）
        if (vectorEnabled) {
            try {
                vectorHit = tryVectorRetrieveWithPersonalization(
                        styleProfile, queryTags, requirement, plan, userContext, k, ranked);
                if (vectorHit) {
                    channel = "VECTOR_3B";
                }
            } catch (Exception e) {
                log.warn("3B 向量检索异常，回退 3A profile={} msg={}", styleProfile, e.getMessage());
            }
        }

        // ★ 3A 结构化回退
        if (!vectorHit) {
            try {
                List<DesignFingerprint> fps = fingerprintService.listApproved(styleProfile, Math.max(k * 3, 9));
                if (CollUtil.isNotEmpty(fps)) {
                    channel = "STRUCTURAL_3A";
                    for (DesignFingerprint fp : fps) {
                        DesignEvidence.Item item = fingerprintToItem(fp);
                        if (item != null) {
                            int matchScore = softMatchScore(item, queryTags);
                            ranked.add(toRanked(item, fp.getUpdateTime(), matchScore));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("结构化召回失败，降级为仅静态规范 profile={} msg={}", styleProfile, e.getMessage());
            }
        }

        // ★ 阶段一：个性化加权排序
        if (personalizationEnabled && userContext != null && userContext.hasPreferences()) {
            sortWithPersonalization(ranked, queryTags, userContext);
        } else {
            // 原有排序逻辑
            ranked.sort((a, b) -> {
                int c = Integer.compare(nz(b.item.getPriority()), nz(a.item.getPriority()));
                if (c != 0) return c;
                c = Double.compare(nzd(b.item.getConfidence()), nzd(a.item.getConfidence()));
                if (c != 0) return c;
                c = b.updatedAt.compareTo(a.updatedAt);
                if (c != 0) return c;
                return Integer.compare(b.matchScore, a.matchScore);
            });
        }

        List<DesignEvidence.Item> items = new ArrayList<>();
        for (int i = 0; i < ranked.size() && items.size() < k; i++) {
            items.add(ranked.get(i).item);
        }
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel(channel)
                .items(items)
                .build();
    }

    // ============ 3B 向量检索内部实现 ============

    /**
     * 尝试 3B 向量检索（个性化版本），将命中的指纹投影为 Item 并加入 ranked。
     *
     * @return true 表示有有效命中；false 表示需要回退 3A
     */
    private boolean tryVectorRetrieveWithPersonalization(String styleProfile, List<String> queryTags,
                                                          RequirementDoc requirement, GenerationPlan plan,
                                                          UserDesignContext userContext,
                                                          int k, List<Ranked> ranked) {
        if (embeddingService == null) {
            log.debug("EmbeddingService 未加载，跳过 3B 向量检索");
            return false;
        }

        // ★ 构造个性化查询文本
        String queryText;
        if (personalizationEnabled && queryBuilder != null && userContext != null) {
            queryText = queryBuilder.build(requirement, plan, userContext, null);
        } else {
            queryText = embeddingService.buildQueryText(styleProfile, queryTags);
        }

        if (queryText == null || queryText.isBlank()) {
            log.debug("查询语义文本为空，跳过 3B profile={}", styleProfile);
            return false;
        }
        float[] queryEmbedding = embeddingService.embed(queryText);
        if (queryEmbedding.length == 0) {
            log.debug("查询向量为空，跳过 3B profile={}", styleProfile);
            return false;
        }

        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, styleProfile, k * 2, minSimilarity);

        if (CollUtil.isEmpty(vResults)) {
            log.debug("3B 向量检索无命中 profile={} minSimilarity={}", styleProfile, minSimilarity);
            return false;
        }

        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) {
                log.debug("3B 命中指纹在 MySQL 中不存在 fingerprintId={}", vr.getFingerprintId());
                continue;
            }
            DesignEvidence.Item item = fingerprintToItem(fp);
            if (item != null) {
                int matchScore = softMatchScore(item, queryTags);
                ranked.add(toRanked(item, fp.getUpdateTime(), matchScore));
                added++;
            }
        }
        log.info("3B 向量检索完成(个性化) profile={} candidates={} added={}",
                styleProfile, vResults.size(), added);
        return added > 0;
    }

    /**
     * 尝试 3B 向量检索，将命中的指纹投影为 Item 并加入 ranked。
     *
     * @return true 表示有有效命中；false 表示需要回退 3A
     */
    private boolean tryVectorRetrieve(String styleProfile, List<String> queryTags,
                                      int k, List<Ranked> ranked) {
        if (embeddingService == null) {
            log.debug("EmbeddingService 未加载，跳过 3B 向量检索");
            return false;
        }
        // 构造查询语义文本并生成向量
        String queryText = embeddingService.buildQueryText(styleProfile, queryTags);
        if (queryText.isBlank()) {
            log.debug("查询语义文本为空，跳过 3B profile={}", styleProfile);
            return false;
        }
        float[] queryEmbedding = embeddingService.embed(queryText);
        if (queryEmbedding.length == 0) {
            log.debug("查询向量为空，跳过 3B profile={}", styleProfile);
            return false;
        }

        // PGvector 余弦检索（候选数 k*2，给排序留余地）
        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, styleProfile, k * 2, minSimilarity);

        if (CollUtil.isEmpty(vResults)) {
            log.debug("3B 向量检索无命中 profile={} minSimilarity={}", styleProfile, minSimilarity);
            return false;
        }

        // 回 MySQL 加载完整指纹并投影
        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) {
                log.debug("3B 命中指纹在 MySQL 中不存在 fingerprintId={}", vr.getFingerprintId());
                continue;
            }
            DesignEvidence.Item item = fingerprintToItem(fp);
            if (item != null) {
                int matchScore = softMatchScore(item, queryTags);
                ranked.add(toRanked(item, fp.getUpdateTime(), matchScore));
                added++;
            }
        }
        log.info("3B 向量检索完成 profile={} candidates={} added={}",
                styleProfile, vResults.size(), added);
        return added > 0;
    }

    /**
     * 3B 向量召回扩展点（保留兼容，实际逻辑已内聚到 {@link #retrieve}）。
     */
    public DesignEvidence vectorRetrieve(String styleProfile, String querySemantics, int topK) {
        if (!vectorEnabled) {
            return DesignEvidence.builder()
                    .styleProfile(styleProfile)
                    .channel("VECTOR_3B")
                    .items(new ArrayList<>())
                    .build();
        }
        // 实际检索已由 retrieve() 统一处理，此处仅保留接口兼容
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel("VECTOR_3B")
                .items(new ArrayList<>())
                .build();
    }

    // =================== 内部辅助 ===================

    private DesignEvidence.Item fingerprintToItem(DesignFingerprint fp) {
        try {
            DesignFingerprintDTO dto = JsonExtractor.parse(fp.getFingerprintJson(), DesignFingerprintDTO.class);
            DesignSpec spec = fingerprintProjector.project(dto, String.valueOf(fp.getId()));
            DesignEvidence.Item item = specToItem(spec, fp.getSourceType());
            item.setPriority(fp.getPriority());
            item.setConfidence(fp.getConfidence());
            item.setSourceId(String.valueOf(fp.getId()));
            return item;
        } catch (Exception e) {
            // 脏数据 / 无法识别版本 → 跳过该条，不报错（呼应 2.2.1 召回兜底）
            log.warn("指纹投影失败，跳过 id={} msg={}", fp.getId(), e.getMessage());
            return null;
        }
    }

    private DesignEvidence.Item specToItem(DesignSpec spec, String sourceType) {
        return DesignEvidence.Item.builder()
                .sourceType(sourceType)
                .sourceId(spec.getSourceId())
                .priority(spec.getPriority())
                .confidence(spec.getConfidence())
                .tone(safe(spec.getTone()))
                .layoutPatterns(safe(spec.getLayoutPatterns()))
                .preferredPatterns(safe(spec.getPreferredPatterns()))
                .interactionPatterns(safe(spec.getInteractionPatterns()))
                .forbiddenPatterns(safe(spec.getForbiddenPatterns()))
                .build();
    }

    /**
     * 软匹配打分：证据的 tone / preferred 与需求标签的命中条数。
     */
    private int softMatchScore(DesignEvidence.Item item, List<String> queryTags) {
        if (CollUtil.isEmpty(queryTags)) {
            return 0;
        }
        Set<String> bag = new LinkedHashSet<>();
        bag.addAll(safe(item.getTone()));
        bag.addAll(safe(item.getPreferredPatterns()));
        bag.addAll(safe(item.getLayoutPatterns()));
        int score = 0;
        for (String tag : queryTags) {
            if (StrUtil.isBlank(tag)) {
                continue;
            }
            String t = tag.toLowerCase();
            for (String b : bag) {
                if (b != null && b.toLowerCase().contains(t)) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    /**
     * 阶段一：个性化加权排序。
     * <p>
     * final_score = 0.40 * semantic_similarity + 0.20 * style_profile_score
     *             + 0.15 * component_match_score + 0.15 * user_preference_score
     *             + 0.10 * confidence - 0.20 * user_dislike_penalty
     * <p>
     * 初期简化：userPreferenceScore = 命中的正向权重之和；dislikePenalty = 命中的负向权重绝对值之和。
     */
    private void sortWithPersonalization(List<Ranked> ranked, List<String> queryTags,
                                          UserDesignContext userContext) {
        // 为每个候选计算综合得分
        ranked.sort((a, b) -> {
            double scoreA = calculatePersonalizedScore(a, queryTags, userContext);
            double scoreB = calculatePersonalizedScore(b, queryTags, userContext);
            return Double.compare(scoreB, scoreA); // DESC
        });
    }

    private double calculatePersonalizedScore(Ranked r, List<String> queryTags,
                                               UserDesignContext userContext) {
        DesignEvidence.Item item = r.item;

        // 语义相似度（用 matchScore 近似，/ 最大可能命中数归一化）
        double semanticScore = (queryTags != null && !queryTags.isEmpty())
                ? Math.min(1.0, (double) r.matchScore / Math.max(1, queryTags.size()))
                : 0.5;

        // 风格画像匹配
        double styleScore = (item.getTone() != null && userContext.getPreferredStyles() != null)
                ? overlapRatio(item.getTone(), userContext.getPreferredStyles())
                : 0.5;

        // 组件匹配
        double componentScore = (item.getPreferredPatterns() != null && userContext.getPreferredComponents() != null)
                ? overlapRatio(item.getPreferredPatterns(), userContext.getPreferredComponents())
                : 0.5;

        // 用户偏好得分（从 UserPreferenceService 计算）
        double userPrefScore = 0;
        double dislikePenalty = 0;
        if (userContext.getUserId() != null && userPreferenceService != null) {
            Long uid = userContext.getUserId();
            if (CollUtil.isNotEmpty(item.getTone())) {
                userPrefScore += userPreferenceService.calculatePreferenceScore(uid, "style", item.getTone());
                dislikePenalty += userPreferenceService.calculateDislikePenalty(uid, "style", item.getTone());
            }
            if (CollUtil.isNotEmpty(item.getLayoutPatterns())) {
                userPrefScore += userPreferenceService.calculatePreferenceScore(uid, "layout", item.getLayoutPatterns());
                dislikePenalty += userPreferenceService.calculateDislikePenalty(uid, "layout", item.getLayoutPatterns());
            }
            if (CollUtil.isNotEmpty(item.getPreferredPatterns())) {
                userPrefScore += userPreferenceService.calculatePreferenceScore(uid, "component", item.getPreferredPatterns());
                dislikePenalty += userPreferenceService.calculateDislikePenalty(uid, "component", item.getPreferredPatterns());
            }
        }

        // 置信度归一化
        double conf = nzd(item.getConfidence());

        // 综合得分公式
        return 0.40 * semanticScore
                + 0.20 * styleScore
                + 0.15 * componentScore
                + 0.15 * Math.max(0, Math.min(1, userPrefScore + 0.5))
                + 0.10 * conf
                - 0.20 * Math.min(1, dislikePenalty);
    }

    private double overlapRatio(List<String> a, List<String> b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0.5;
        Set<String> setB = new LinkedHashSet<>(b);
        int hits = 0;
        for (String sa : a) {
            if (sa != null && setB.contains(sa)) {
                hits++;
            }
        }
        return hits > 0 ? (double) hits / a.size() : 0;
    }

    private Ranked toRanked(DesignEvidence.Item item, LocalDateTime updatedAt, int matchScore) {
        Ranked r = new Ranked();
        r.item = item;
        r.updatedAt = updatedAt == null ? LocalDateTime.MIN : updatedAt;
        r.matchScore = matchScore;
        return r;
    }

    private List<String> safe(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private double nzd(Double v) {
        return v == null ? 0d : v;
    }

    /** 排序中间载体。 */
    private static class Ranked {
        DesignEvidence.Item item;
        LocalDateTime updatedAt;
        int matchScore;
    }
}
