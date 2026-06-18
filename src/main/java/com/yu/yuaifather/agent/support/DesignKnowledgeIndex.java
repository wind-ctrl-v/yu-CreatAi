package com.yu.yuaifather.agent.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.yu.yuaifather.ai.model.dto.DesignEvidence;
import com.yu.yuaifather.ai.model.dto.DesignFingerprintDTO;
import com.yu.yuaifather.ai.model.dto.DesignSpec;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.service.DesignFingerprintService;
import lombok.extern.slf4j.Slf4j;
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
 * 按 {@code priority DESC → confidence DESC → updatedAt DESC}（软匹配作次级加权）排序取 top-k。
 *
 * <h3>渐进增强（3.1）</h3>
 * <ul>
 *   <li><b>3A 轻量结构化检索</b>（本类已实现）：MySQL 结构化字段硬过滤 + Java 侧软匹配排序；</li>
 *   <li><b>3B 向量 RAG</b>（预留 {@link #vectorRetrieve}）：在 3A 验证有效后接入 embedding，
 *       失败自动回退 3A，3A 为空再回退静态规范（见 3.7）。</li>
 * </ul>
 */
@Slf4j
@Component
public class DesignKnowledgeIndex {

    /** 召回 topK 上限（见 3.2，最大不超过 3）。 */
    private static final int MAX_TOP_K = 3;

    private final DesignFingerprintService fingerprintService;
    private final FingerprintProjector fingerprintProjector;

    public DesignKnowledgeIndex(DesignFingerprintService fingerprintService,
                                FingerprintProjector fingerprintProjector) {
        this.fingerprintService = fingerprintService;
        this.fingerprintProjector = fingerprintProjector;
    }

    /**
     * 统一检索：合并静态规范与 APPROVED 指纹，返回受控证据 top-k。
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

        // 先验：静态规范作为一条高优先级证据
        if (staticSpec != null) {
            ranked.add(toRanked(specToItem(staticSpec, staticSpec.getSourceType()),
                    LocalDateTime.now(), 0));
        }

        // 后验：3A 结构化召回 APPROVED 指纹（失败不阻断）
        String channel = "STATIC_FALLBACK";
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

        // 合并排序：priority DESC → confidence DESC → updatedAt DESC → 软匹配 DESC
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
     * 3B 向量召回扩展点（预留，当前返回空表示未启用，由调用方回退 3A）。
     */
    public DesignEvidence vectorRetrieve(String styleProfile, String querySemantics, int topK) {
        // 待 3A 验证有效后接入：embedding + 向量索引 + score threshold；失败/空回退 3A。
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
