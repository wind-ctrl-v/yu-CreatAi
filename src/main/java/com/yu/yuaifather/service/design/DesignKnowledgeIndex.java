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
import com.yu.yuaifather.model.entity.DesignMissLog;
import com.yu.yuaifather.service.DesignFingerprintService;
import com.yu.yuaifather.service.DesignMissLogService;
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
import java.util.HashSet;
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

    /** 覆盖度优化（阶段一）：召回候选中与目标 profile 一致的证据加分（profile 由硬过滤改为加权项）。 */
    private static final int PROFILE_MATCH_BONUS = 2;

    private final DesignFingerprintService fingerprintService;
    private final FingerprintProjector fingerprintProjector;
    private final DesignFingerprintVectorRepository vectorRepo;
    private final EmbeddingService embeddingService;
    private final PersonalizedQueryBuilder queryBuilder;
    private final UserPreferenceService userPreferenceService;
    private final DesignMissLogService missLogService;
    private final DynamicSpecSynthesizer synthesizer;

    @Value("${agent.retrieve.vector.enabled:false}")
    private boolean vectorEnabled;

    @Value("${agent.retrieve.vector.min-similarity:0.7}")
    private double minSimilarity;

    @Value("${agent.personalization.enabled:false}")
    private boolean personalizationEnabled;

    @Value("${agent.retrieve.curated-min-ratio:0.3}")
    private double curatedMinRatio;

    /** 动态合成兜底开关（覆盖度优化 阶段二，默认关闭）。 */
    @Value("${agent.retrieve.synth.enabled:false}")
    private boolean synthEnabled;

    /** 动态合成触发阈值：同一 profile 的 miss 次数达到该值才触发合成（默认 2，防一次性长尾浪费）。 */
    @Value("${agent.retrieve.synth.miss-threshold:2}")
    private int synthMissThreshold;

    public DesignKnowledgeIndex(DesignFingerprintService fingerprintService,
                                FingerprintProjector fingerprintProjector,
                                DesignFingerprintVectorRepository vectorRepo,
                                @Autowired(required = false) EmbeddingService embeddingService,
                                @Autowired(required = false) PersonalizedQueryBuilder queryBuilder,
                                @Autowired(required = false) UserPreferenceService userPreferenceService,
                                @Autowired(required = false) DesignMissLogService missLogService,
                                @Autowired(required = false) DynamicSpecSynthesizer synthesizer) {
        this.fingerprintService = fingerprintService;
        this.fingerprintProjector = fingerprintProjector;
        this.vectorRepo = vectorRepo;
        this.embeddingService = embeddingService;
        this.queryBuilder = queryBuilder;
        this.userPreferenceService = userPreferenceService;
        this.missLogService = missLogService;
        this.synthesizer = synthesizer;
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
                if (appendStructuralCandidates(styleProfile, queryTags, k, null, ranked)) {
                    channel = "STRUCTURAL_3A";
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
        String queryText = embeddingService != null ? embeddingService.buildQueryText(styleProfile, queryTags) : null;
        // 覆盖度优化（阶段一/二）：无指纹证据命中 → 记 miss；达阈值 → 动态合成（第四档降级）
        if (items.stream().noneMatch(this::isFingerprintSource)) {
            recordMiss(styleProfile, queryTags, channel, queryText, null);
            DesignSpec synthesized = tryDynamicSynthesize(styleProfile, queryTags, null, null);
            if (synthesized != null) {
                items.add(0, specToItem(synthesized, DynamicSpecSynthesizer.SOURCE_TYPE));
                channel = "DYNAMIC_SYNTH";
            }
        }
        return DesignEvidence.builder()
                .styleProfile(styleProfile)
                .channel(channel)
                .queryText(queryText)
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
                if (appendStructuralCandidates(styleProfile, queryTags, k, currentUserId, ranked)) {
                    channel = "STRUCTURAL_3A";
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
        // 覆盖度优化（阶段一/二）：无指纹证据命中 → 记 miss；达阈值 → 动态合成（第四档降级）
        if (items.stream().noneMatch(this::isFingerprintSource)) {
            Long missUserId = (userContext != null) ? userContext.getUserId() : null;
            recordMiss(styleProfile, queryTags, channel, queryText, missUserId);
            DesignSpec synthesized = tryDynamicSynthesize(styleProfile, queryTags, requirement, plan);
            if (synthesized != null) {
                items.add(0, specToItem(synthesized, DynamicSpecSynthesizer.SOURCE_TYPE));
                channel = "DYNAMIC_SYNTH";
            }
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
        // 覆盖度优化（阶段一）：styleProfile 传 null 解除硬过滤，全局语义召回（min-similarity 兜底），
        // profile 一致性改由 addRankedItem 加分表达
        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, null, k * 3, minSimilarity, queryUserId);
        if (CollUtil.isEmpty(vResults)) {
            return false;
        }
        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) continue;
            if (addRankedItem(fp, styleProfile, queryTags, ranked)) {
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
        // 覆盖度优化（阶段一）：styleProfile 传 null 解除硬过滤，全局语义召回（min-similarity 兜底）
        List<DesignFingerprintVector> vResults =
                vectorRepo.search(queryEmbedding, null, k * 3, minSimilarity);
        if (CollUtil.isEmpty(vResults)) {
            return false;
        }
        int added = 0;
        for (DesignFingerprintVector vr : vResults) {
            DesignFingerprint fp = fingerprintService.getById(vr.getFingerprintId());
            if (fp == null) continue;
            if (addRankedItem(fp, styleProfile, queryTags, ranked)) {
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

    /**
     * 结构化降级召回（覆盖度优化 阶段一：profile 从硬过滤改为加权）。
     * <p>
     * 先按目标 profile 召回（一致者加分）；不足 k 条时全局补充跨类目 APPROVED 指纹
     * （不加分），交由 softMatchScore 与统一排序竞争。
     * 权限过滤（visibility / ownerUserId）在 listApproved 内保持不变。
     *
     * @return 是否召回了至少一条候选
     */
    private boolean appendStructuralCandidates(String styleProfile, List<String> queryTags, int k,
                                               Long currentUserId, List<Ranked> ranked) {
        int limit = Math.max(k * 3, 9);
        Set<Long> seen = new HashSet<>();
        int added = 0;
        List<DesignFingerprint> sameProfile = fingerprintService.listApproved(styleProfile, limit, currentUserId);
        for (DesignFingerprint fp : sameProfile) {
            if (addRankedItem(fp, styleProfile, queryTags, ranked)) {
                if (fp.getId() != null) {
                    seen.add(fp.getId());
                }
                added++;
            }
        }
        // 覆盖度优化：同类目不足时跨类目补充（profile 仅作加分项，不作准入门槛）
        if (added < k) {
            List<DesignFingerprint> global = fingerprintService.listApproved(null, limit, currentUserId);
            for (DesignFingerprint fp : global) {
                if (fp.getId() != null && seen.contains(fp.getId())) {
                    continue;
                }
                if (addRankedItem(fp, styleProfile, queryTags, ranked)) {
                    added++;
                }
            }
        }
        return added > 0;
    }

    /**
     * 指纹转证据并加入候选集；与目标 profile 一致时附加 {@link #PROFILE_MATCH_BONUS}。
     *
     * @return 是否成功加入（投影失败返回 false）
     */
    private boolean addRankedItem(DesignFingerprint fp, String styleProfile,
                                  List<String> queryTags, List<Ranked> ranked) {
        DesignEvidence.Item item = fingerprintToItem(fp);
        if (item == null) {
            return false;
        }
        int matchScore = softMatchScore(item, queryTags);
        if (StrUtil.isNotBlank(styleProfile) && styleProfile.equalsIgnoreCase(fp.getStyleProfile())) {
            matchScore += PROFILE_MATCH_BONUS;
        }
        ranked.add(toRanked(item, fp.getUpdateTime(), matchScore));
        return true;
    }

    /**
     * 第四档降级（覆盖度优化 阶段二）：同一 profile 的 miss 次数达到阈值时，
     * 调用 LLM 动态合成六层设计规范；产物同步入库 CANDIDATE（signature 去重，
     * 人工审核 APPROVED 后才进入向量召回池，未审核不污染召回）。
     */
    private DesignSpec tryDynamicSynthesize(String styleProfile, List<String> queryTags,
                                            RequirementDoc requirement, GenerationPlan plan) {
        if (!synthEnabled || synthesizer == null || missLogService == null) {
            return null;
        }
        String profile = StrUtil.blankToDefault(styleProfile, "default");
        // missCount 含本次 miss（recordMiss 先于本方法执行），达到阈值即触发
        long missCount = missLogService.countMissByProfile(profile);
        if (missCount < synthMissThreshold) {
            log.info("动态合成未触发：miss 次数不足 profile={} missCount={} threshold={}",
                    profile, missCount, synthMissThreshold);
            return null;
        }
        try {
            DesignFingerprintDTO dto = synthesizer.synthesizeDto(profile, queryTags, requirement, plan);
            if (dto == null) {
                return null;
            }
            // 入库候选（命中重复签名返回 null 不影响本次生成使用）
            fingerprintService.saveSynthesizedCandidate(dto);
            return fingerprintProjector.project(dto, dto.getSourceId(),
                    DynamicSpecSynthesizer.SOURCE_TYPE,
                    DynamicSpecSynthesizer.SYNTHESIZED_PRIORITY,
                    DynamicSpecSynthesizer.SYNTHESIZED_CONFIDENCE);
        } catch (Exception e) {
            log.warn("动态合成异常，降级静态规范 profile={} msg={}", profile, e.getMessage());
            return null;
        }
    }

    /**
     * 记录检索 miss 现场（阶段一埋点；写入失败不影响检索主链路，由 service 内部兜底）。
     */
    private void recordMiss(String styleProfile, List<String> queryTags, String channel,
                            String queryText, Long userId) {
        if (missLogService == null) {
            return;
        }
        String tags = (queryTags == null) ? null
                : String.join(",", queryTags.stream().limit(20).toList());
        missLogService.recordMiss(DesignMissLog.builder()
                .styleProfile(StrUtil.blankToDefault(styleProfile, "default"))
                .queryText(queryText)
                .queryTags(tags)
                .channel(channel)
                .userId(userId)
                .build());
    }

    /**
     * 判断证据条目是否来自指纹库（区别于静态规范 / 内置默认 / 动态合成）。
     */
    private boolean isFingerprintSource(DesignEvidence.Item item) {
        String st = item.getSourceType();
        return "EXTRACTED_FINGERPRINT".equals(st) || "SELF_FED_FINGERPRINT".equals(st);
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
        // ★ 统一排序权重（P0-5 修复，2026-07-04）：与 DesignRerankService 的 10 项公式保持一致，
        // 避免双轨权重不一致导致"开 rerank 和不开 rerank 效果完全不同"的问题。
        // 两套开关（personalization.enabled / rerank.enabled）的联动：
        //   - personalization=true, rerank=false → 用本方法（10项简化版，无 brandContext/duplicate）
        //   - personalization=true, rerank=true  → 本方法先排序，再由 DesignRerankService 做完整 10 项重排
        //   - personalization=false              → 用默认 priority/confidence/updatedAt 排序
        double semanticScore = (queryTags != null && !queryTags.isEmpty())
                ? Math.min(1.0, (double) r.matchScore / Math.max(1, queryTags.size()))
                : 0.5;
        double styleScore = (item.getTone() != null && userContext.getPreferredStyles() != null)
                ? overlapRatio(item.getTone(), userContext.getPreferredStyles())
                : 0.5;
        double componentScore = (item.getPreferredPatterns() != null && userContext.getPreferredComponents() != null)
                ? overlapRatio(item.getPreferredPatterns(), userContext.getPreferredComponents())
                : 0.5;
        double layoutScore = (item.getLayoutPatterns() != null && userContext.getPreferredLayouts() != null)
                ? overlapRatio(item.getLayoutPatterns(), userContext.getPreferredLayouts())
                : 0.5;
        double qualityScore = nzd(item.getConfidence());
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
        double conf = qualityScore;
        // 与 DesignRerankService 保持一致的权重（无 projectBrand / forbidden / duplicate，因为此处无 brandContext）
        return 0.25 * semanticScore
                + 0.15 * styleScore
                + 0.15 * componentScore
                + 0.10 * layoutScore
                + 0.10 * conf
                + 0.15 * Math.max(0, Math.min(1, userPrefScore + 0.5))
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
