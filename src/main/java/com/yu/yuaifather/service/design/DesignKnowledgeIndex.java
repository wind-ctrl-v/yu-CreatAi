package com.yu.yuaifather.service.design;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import com.yu.yuaifather.model.dto.agent.UserDesignContext;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.service.DesignFingerprintService;
import com.yu.yuaifather.repository.DesignFingerprintVectorRepository;
import com.yu.yuaifather.service.PersonalizedQueryBuilder;
import com.yu.yuaifather.service.UserPreferenceService;
import com.yu.yuaifather.utils.JsonExtractor;
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
 */
@Slf4j
@Component
@ConditionalOnBean(DesignFingerprintVectorRepository.class)
public class DesignKnowledgeIndex {

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

    @Value("${agent.retrieve.curated-min-ratio:0.3}")
    private double curatedMinRatio;

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

    public DesignEvidence retrieve(String styleProfile, List<String> queryTags, DesignSpec staticSpec, int topK) {
        int k = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<Ranked> ranked = new ArrayList<>();

        if (staticSpec != null) {
            ranked.add(toRanked(specToItem(staticSpec, staticSpec.getSourceType()),
                    LocalDateTime.now(), 0));
        }

        String channel = "STATIC_FALLBACK";
        boolean vectorHit = false;

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

        if (!vectorHit) {
            try {
                // 无用户上下文的重载入口，出于权限安全（修复计划 02）仅传 null，
                // listApproved 在 currentUserId=null 时只返回公共指纹，不会误召回私有指纹。
                List<DesignFingerprint> fps = fingerprintService.listApproved(styleProfile, Math.max(k * 3, 9), null);
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
                .queryText(embeddingService != null ? embeddingService.buildQueryText(styleProfile, queryTags) : null)
                .items(items)
                .build();
    }

    public DesignEvidence retrieve(String styleProfile, List<String> queryTags, DesignSpec staticSpec,
                                    RequirementDoc requirement, GenerationPlan plan,
                                    UserDesignContext userContext, int topK) {
        int k = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<Ranked> ranked = new ArrayList<>();

        if (staticSpec != null) {
            ranked.add(toRanked(specToItem(staticSpec, staticSpec.getSourceType()),
                    LocalDateTime.now(), 0));
        }

        String channel = "STATIC_FALLBACK";
        boolean vectorHit = false;
        // 用于回收本次检索实际使用的查询文本（修复计划 03），供 DesignEvidence 携带至 RagTraceTool 落库
        String[] queryTextHolder = new String[1];

        if (vectorEnabled) {
            try {
                vectorHit = tryVectorRetrieveWithPersonalization(
                        styleProfile, queryTags, requirement, plan, userContext, k, ranked, queryTextHolder);
                if (vectorHit) {
                    channel = "VECTOR_3B";
                }
            } catch (Exception e) {
                log.warn("3B 向量检索异常，回退 3A profile={} msg={}", styleProfile, e.getMessage());
            }
        }

        if (!vectorHit) {
            try {
                // 结构化降级通道仅按当前用户上下文过滤，杜绝跨用户私有指纹越权（修复计划 02）
                Long currentUserId = (userContext != null) ? userContext.getUserId() : null;
                List<DesignFingerprint> fps = fingerprintService.listApproved(styleProfile, Math.max(k * 3, 9), currentUserId);
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

        if (personalizationEnabled && userContext != null && userContext.hasPreferences()) {
            sortWithPersonalization(ranked, queryTags, userContext);
        } else {
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

        List<Ranked> diversified = applyDiversityFilter(ranked, k, userContext);

        List<DesignEvidence.Item> items = new ArrayList<>();
        for (int i = 0; i < diversified.size() && items.size() < k; i++) {
            items.add(diversified.get(i).item);
        }
        // 若未走向量通道（未开启/未命中），queryText 兜底为与 3A/3B 查询同源的文本，避免为空
        String queryText = queryTextHolder[0];
        if ((queryText == null || queryText.isBlank()) && embeddingService != null) {
            queryText = embeddingService.buildQueryText(styleProfile, queryTags);
        }
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel(channel)
                .queryText(queryText)
                .items(items)
                .build();
    }

    private boolean tryVectorRetrieveWithPersonalization(String styleProfile, List<String> queryTags,
                                                          RequirementDoc requirement, GenerationPlan plan,
                                                          UserDesignContext userContext,
                                                          int k, List<Ranked> ranked,
                                                          String[] queryTextOut) {
        if (embeddingService == null) {
            return false;
        }
        Long queryUserId = (userContext != null) ? userContext.getUserId() : null;
        String queryText;
        if (personalizationEnabled && queryBuilder != null && userContext != null) {
            queryText = queryBuilder.build(requirement, plan, userContext,
                    userPreferenceService != null
                            ? userPreferenceService.loadPreferenceWeights(queryUserId)
                            : null);
        } else {
            queryText = embeddingService.buildQueryText(styleProfile, queryTags);
        }
        if (queryTextOut != null && queryTextOut.length > 0) {
            queryTextOut[0] = queryText;
        }
        if (queryText == null || queryText.isBlank()) {
            return false;
        }
        float[] queryEmbedding = embeddingService.embed(queryText);
        if (queryEmbedding.length == 0) {
            return false;
        }
        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, styleProfile, k * 2, minSimilarity, queryUserId);
        if (CollUtil.isEmpty(vResults)) {
            return false;
        }
        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) continue;
            DesignEvidence.Item item = fingerprintToItem(fp);
            if (item != null) {
                ranked.add(toRanked(item, fp.getUpdateTime(), softMatchScore(item, queryTags)));
                added++;
            }
        }
        log.info("3B 向量检索完成(个性化) profile={} candidates={} added={} userId={}",
                styleProfile, vResults.size(), added, queryUserId);
        return added > 0;
    }

    private boolean tryVectorRetrieve(String styleProfile, List<String> queryTags,
                                      int k, List<Ranked> ranked) {
        if (embeddingService == null) {
            return false;
        }
        String queryText = embeddingService.buildQueryText(styleProfile, queryTags);
        if (queryText.isBlank()) {
            return false;
        }
        float[] queryEmbedding = embeddingService.embed(queryText);
        if (queryEmbedding.length == 0) {
            return false;
        }
        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, styleProfile, k * 2, minSimilarity);
        if (CollUtil.isEmpty(vResults)) {
            return false;
        }
        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) continue;
            DesignEvidence.Item item = fingerprintToItem(fp);
            if (item != null) {
                ranked.add(toRanked(item, fp.getUpdateTime(), softMatchScore(item, queryTags)));
                added++;
            }
        }
        log.info("3B 向量检索完成 profile={} candidates={} added={}", styleProfile, vResults.size(), added);
        return added > 0;
    }

    public DesignEvidence vectorRetrieve(String styleProfile, String querySemantics, int topK) {
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel("VECTOR_3B")
                .items(new ArrayList<>())
                .build();
    }

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
            if (StrUtil.isBlank(tag)) continue;
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

    private List<Ranked> applyDiversityFilter(List<Ranked> ranked, int k, UserDesignContext userContext) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        List<Ranked> filtered = new ArrayList<>();
        int globalCuratedCount = 0;
        int poolSize = Math.max(ranked.size(), k);
        int minCurated = (int) Math.ceil(poolSize * curatedMinRatio);

        for (Ranked r : ranked) {
            String sourceType = r.item.getSourceType();
            boolean isGlobalCurated = "EXTRACTED_FINGERPRINT".equals(sourceType);
            if (isGlobalCurated) globalCuratedCount++;
            filtered.add(r);
        }

        if (globalCuratedCount < minCurated && filtered.size() < ranked.size()) {
            for (Ranked r : ranked) {
                if ("EXTRACTED_FINGERPRINT".equals(r.item.getSourceType()) && !filtered.contains(r)) {
                    filtered.add(r);
                    globalCuratedCount++;
                    if (globalCuratedCount >= minCurated) break;
                }
            }
        }

        log.debug("多样性过滤 k={} original={} filtered={} globalCurated={}",
                k, ranked.size(), filtered.size(), globalCuratedCount);
        return filtered;
    }

    private void sortWithPersonalization(List<Ranked> ranked, List<String> queryTags,
                                          UserDesignContext userContext) {
        ranked.sort((a, b) -> {
            double scoreA = calculatePersonalizedScore(a, queryTags, userContext);
            double scoreB = calculatePersonalizedScore(b, queryTags, userContext);
            return Double.compare(scoreB, scoreA);
        });
    }

    private double calculatePersonalizedScore(Ranked r, List<String> queryTags,
                                               UserDesignContext userContext) {
        DesignEvidence.Item item = r.item;
        double semanticScore = (queryTags != null && !queryTags.isEmpty())
                ? Math.min(1.0, (double) r.matchScore / Math.max(1, queryTags.size()))
                : 0.5;
        double styleScore = (item.getTone() != null && userContext.getPreferredStyles() != null)
                ? overlapRatio(item.getTone(), userContext.getPreferredStyles())
                : 0.5;
        double componentScore = (item.getPreferredPatterns() != null && userContext.getPreferredComponents() != null)
                ? overlapRatio(item.getPreferredPatterns(), userContext.getPreferredComponents())
                : 0.5;
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
        double conf = nzd(item.getConfidence());
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
            if (sa != null && setB.contains(sa)) hits++;
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

    private static class Ranked {
        DesignEvidence.Item item;
        LocalDateTime updatedAt;
        int matchScore;
    }
}
