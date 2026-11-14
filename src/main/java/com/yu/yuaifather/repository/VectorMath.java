package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 向量数学工具（纯函数，无 Spring 依赖，便于单测）。
 * <p>
 * A 改造（向量存储可选后端）中，MySQL 后端不依赖 PGvector，余弦相似度与 TopK
 * 全部在 JVM 内存中完成，本类承载 {@link #norm(float[])}、{@link #cosine(float[], float[])}
 * 与 {@link #topK(float[], List, int, double)} 三个核心能力。
 * <p>
 * 约定：
 * <ul>
 *   <li>零向量 / null / 维度不一致均视为无效，cosine 返回 {@code 0}；</li>
 *   <li>TopK 返回<b>新的</b> {@link DesignFingerprintVector} 副本（含 similarity），
 *       不修改快照中的共享对象，保证并发检索安全；</li>
 *   <li>排序口径：similarity DESC → priority DESC → confidence DESC → fingerprintId ASC，
 *       保证相同相似度下结果稳定可测。</li>
 * </ul>
 *
 * @author yu
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * 检索排序口径（自然序 = 由差到优，供最小堆取最差项；输出时整体 reversed）：
     * similarity 升序 → priority 升序 → confidence 升序 → fingerprintId 降序，
     * 保证相同相似度下 TopK 入选与顺序完全确定（可测）。
     */
    private static final Comparator<Scored> WORST_FIRST = (x, y) -> {
        int cmp = Double.compare(x.similarity(), y.similarity());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(nz(x.vector().getPriority()), nz(y.vector().getPriority()));
        if (cmp != 0) {
            return cmp;
        }
        cmp = Double.compare(nzd(x.vector().getConfidence()), nzd(y.vector().getConfidence()));
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(nz(y.vector().getFingerprintId()), nz(x.vector().getFingerprintId()));
    };

    /**
     * 计算 L2 范数；null/空向量返回 0。
     */
    public static double norm(float[] vector) {
        if (vector == null || vector.length == 0) {
            return 0d;
        }
        double sum = 0d;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * 计算 cosine 相似度；null / 维度不一致 / 零向量返回 0。
     *
     * @return 取值范围理论为 [-1, 1]，无效输入统一返回 0
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Double.isNaN(similarity) ? 0d : similarity;
    }

    /**
     * 内存余弦 TopK。
     * <p>
     * 对候选集逐个计算 cosine（O(N*D)），用大小为 topK 的最小堆裁剪，
     * 再按相似度降序输出副本；低于 {@code minSimilarity} 的候选被淘汰。
     *
     * @param queryEmbedding 查询向量（null/空返回空列表）
     * @param candidates     候选向量集（可含 null / 空向量 / 维度不一致项，自动跳过）
     * @param topK           返回条数上限（&le;0 返回空列表）
     * @param minSimilarity  cosine 相似度下限（含等于）
     * @return 按 similarity DESC 排序的结果副本（非空，元素 similarity 已填充）
     */
    public static List<DesignFingerprintVector> topK(float[] queryEmbedding,
                                                     List<DesignFingerprintVector> candidates,
                                                     int topK,
                                                     double minSimilarity) {
        if (queryEmbedding == null || queryEmbedding.length == 0
                || candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        PriorityQueue<Scored> heap = new PriorityQueue<>(
                Math.min(topK, candidates.size()) + 1, WORST_FIRST);
        for (DesignFingerprintVector candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            float[] embedding = candidate.getEmbedding();
            if (embedding == null || embedding.length != queryEmbedding.length) {
                continue;
            }
            double similarity = cosine(queryEmbedding, embedding);
            if (similarity < minSimilarity) {
                continue;
            }
            Scored scored = new Scored(candidate, similarity);
            if (heap.size() < topK) {
                heap.offer(scored);
            } else if (WORST_FIRST.compare(scored, heap.peek()) > 0) {
                heap.poll();
                heap.offer(scored);
            }
        }
        List<Scored> scored = new ArrayList<>(heap);
        scored.sort(WORST_FIRST.reversed());
        List<DesignFingerprintVector> result = new ArrayList<>(scored.size());
        for (Scored item : scored) {
            result.add(copyWithSimilarity(item.vector(), item.similarity()));
        }
        return result;
    }

    /**
     * 复制向量记录并写入本次检索得分（不改动入参对象）。
     */
    public static DesignFingerprintVector copyWithSimilarity(DesignFingerprintVector src, double similarity) {
        if (src == null) {
            return null;
        }
        return DesignFingerprintVector.builder()
                .id(src.getId())
                .fingerprintId(src.getFingerprintId())
                .styleProfile(src.getStyleProfile())
                .status(src.getStatus())
                .confidence(src.getConfidence())
                .priority(src.getPriority())
                .embeddingText(src.getEmbeddingText())
                .embedding(src.getEmbedding())
                .createdAt(src.getCreatedAt())
                .updatedAt(src.getUpdatedAt())
                .isActive(src.getIsActive())
                .sourceScope(src.getSourceScope())
                .ownerUserId(src.getOwnerUserId())
                .projectId(src.getProjectId())
                .visibility(src.getVisibility())
                .sourceType(src.getSourceType())
                .embeddingModel(src.getEmbeddingModel())
                .embeddingVersion(src.getEmbeddingVersion())
                .semanticTextHash(src.getSemanticTextHash())
                .similarity(similarity)
                .build();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static double nzd(Double value) {
        return value == null ? 0d : value;
    }

    /** 检索临时得分（最小堆 + 稳定排序用）。 */
    private record Scored(DesignFingerprintVector vector, double similarity) {
    }
}
