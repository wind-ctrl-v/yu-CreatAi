package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量内存快照（MySQL 后端检索的核心数据结构，无 Spring 依赖，便于单测）。
 * <p>
 * 职责：
 * <ul>
 *   <li>持有 {@code fingerprintId → 向量记录} 的并发映射，支持启动加载 / 定时重载整表替换；</li>
 *   <li>{@link #put(DesignFingerprintVector)} / {@link #remove(Long)} 供 upsert / deactivate 写穿；</li>
 *   <li>{@link #search} 先做 styleProfile 与权限（GLOBAL 或本人私有）过滤，再交给
 *       {@link VectorMath#topK} 做余弦 TopK。</li>
 * </ul>
 * 并发约定：映射字段 volatile，重载时整体替换（检索方拿到的是替换前/后其一，不会看到半张表）；
 * 单条增删直接在当前映射上操作，{@link ConcurrentHashMap} 保证弱一致遍历安全。
 *
 * @author yu
 */
public class VectorSnapshot {

    /** 当前快照映射；重载时整体替换，volatile 保证检索线程可见性。 */
    private volatile Map<Long, DesignFingerprintVector> entries = new ConcurrentHashMap<>();

    public VectorSnapshot() {
    }

    /**
     * 以给定向量集构建快照（null / 无 fingerprintId / 空向量自动跳过）。
     */
    public VectorSnapshot(Collection<DesignFingerprintVector> vectors) {
        replaceAll(vectors);
    }

    /**
     * 空快照（启动加载失败时退化使用，检索返回空列表 → 上层回退 3A 结构化召回）。
     */
    public static VectorSnapshot empty() {
        return new VectorSnapshot();
    }

    /**
     * 整表替换（启动加载 / 定时重载使用）。构建新映射后一次性替换，避免检索读到半成品。
     */
    public void replaceAll(Collection<DesignFingerprintVector> vectors) {
        Map<Long, DesignFingerprintVector> next = new ConcurrentHashMap<>();
        if (vectors != null) {
            for (DesignFingerprintVector vector : vectors) {
                putIfValid(next, vector);
            }
        }
        this.entries = next;
    }

    /**
     * 写入单条向量（upsert 写穿）。无效记录（null / 无 fingerprintId / 空向量）忽略。
     */
    public void put(DesignFingerprintVector vector) {
        putIfValid(this.entries, vector);
    }

    /**
     * 移除单条向量（deactivate 写穿）。
     */
    public void remove(Long fingerprintId) {
        if (fingerprintId == null) {
            return;
        }
        this.entries.remove(fingerprintId);
    }

    /**
     * 当前快照条目数。
     */
    public int size() {
        return this.entries.size();
    }

    /**
     * 是否包含指定指纹向量。
     */
    public boolean contains(Long fingerprintId) {
        return fingerprintId != null && this.entries.containsKey(fingerprintId);
    }

    /**
     * 是否为空快照。
     */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * 过滤 + 余弦 TopK。
     * <p>
     * 过滤口径与原 PG SQL 对齐：{@code is_active} 为 false 的排除；{@code styleProfile}
     * 非空时精确匹配；{@code userId} 非空时仅保留 {@code source_scope='GLOBAL'} 或
     * {@code owner_user_id=userId} 的记录。
     *
     * @param queryEmbedding 查询向量
     * @param styleProfile   风格画像过滤（可空）
     * @param topK           返回条数上限
     * @param minSimilarity  cosine 相似度下限
     * @param userId         当前用户ID（可空）
     * @return 按 similarity DESC 排序的向量副本列表
     */
    public List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                                int topK, double minSimilarity, Long userId) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }
        List<DesignFingerprintVector> candidates = new ArrayList<>(this.entries.size());
        for (DesignFingerprintVector vector : this.entries.values()) {
            if (vector == null || Boolean.FALSE.equals(vector.getIsActive())) {
                continue;
            }
            if (vector.getStatus() != null && !"APPROVED".equals(vector.getStatus())) {
                continue;
            }
            if (styleProfile != null && !styleProfile.isBlank()
                    && !styleProfile.equals(vector.getStyleProfile())) {
                continue;
            }
            if (userId != null) {
                boolean global = "GLOBAL".equals(vector.getSourceScope());
                boolean owner = userId.equals(vector.getOwnerUserId());
                if (!global && !owner) {
                    continue;
                }
            }
            candidates.add(vector);
        }
        return VectorMath.topK(queryEmbedding, candidates, topK, minSimilarity);
    }

    /**
     * 过滤 + 余弦 TopK（向后兼容重载，无权限过滤）。
     */
    public List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                                int topK, double minSimilarity) {
        return search(queryEmbedding, styleProfile, topK, minSimilarity, null);
    }

    private void putIfValid(Map<Long, DesignFingerprintVector> target, DesignFingerprintVector vector) {
        if (vector == null || vector.getFingerprintId() == null) {
            return;
        }
        float[] embedding = vector.getEmbedding();
        if (embedding == null || embedding.length == 0) {
            return;
        }
        target.put(vector.getFingerprintId(), vector);
    }
}
