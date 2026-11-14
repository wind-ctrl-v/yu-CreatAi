package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;

import java.util.List;

/**
 * 向量存储后端统一接口（A 改造：PG → MySQL 内存余弦，保留语义检索）。
 * <p>
 * 由 {@code config.VectorStoreConfiguration} 按 {@code agent.retrieve.vector.store}
 * 装配具体实现：
 * <ul>
 *   <li>{@code pg}（默认，matchIfMissing）：{@link PgVectorStore}，迁移自原
 *       {@link DesignFingerprintVectorRepository} 的 PGvector 原生 SQL，行为不变；</li>
 *   <li>{@code mysql}：{@link MysqlVectorStore}，MySQL 持久化 + JVM 内存快照 + Java 余弦 TopK，
 *       无需部署 PostgreSQL（生产 2C4G 单机推荐）。</li>
 * </ul>
 * <p>
 * 方法签名与历史 {@link DesignFingerprintVectorRepository} 的 public 方法一一对应，
 * 门面保留原签名后调用方（{@code DesignKnowledgeIndex} / {@code VectorSyncWorker}）零改动。
 *
 * @author yu
 */
public interface VectorStore {

    /**
     * 插入或更新向量（幂等），含阶段二权限字段。
     *
     * @param fingerprintId MySQL 指纹 ID
     * @param styleProfile  风格画像
     * @param confidence    置信度
     * @param priority      优先级
     * @param embeddingText 语义摘要文本（调试/审计）
     * @param embedding     向量 float[]
     * @param sourceScope   来源范围（GLOBAL/USER/PROJECT）
     * @param ownerUserId   归属用户ID
     * @param projectId     归属项目ID
     * @param visibility    可见性
     * @param sourceType    来源类型
     */
    void upsert(Long fingerprintId, String styleProfile,
                Double confidence, Integer priority,
                String embeddingText, float[] embedding,
                String sourceScope, Long ownerUserId, Long projectId,
                String visibility, String sourceType);

    /**
     * 插入或更新向量（向后兼容，无权限字段版本，取 GLOBAL / PUBLIC 默认值）。
     */
    default void upsert(Long fingerprintId, String styleProfile,
                        Double confidence, Integer priority,
                        String embeddingText, float[] embedding) {
        upsert(fingerprintId, styleProfile, confidence, priority,
                embeddingText, embedding, "GLOBAL", null, null, "PUBLIC", null);
    }

    /**
     * 插入或更新向量（兼容重载：不传 embeddingText，语义文本留空）。
     */
    default void upsert(Long fingerprintId, String styleProfile,
                        Double confidence, Integer priority, float[] embedding) {
        upsert(fingerprintId, styleProfile, confidence, priority, null, embedding);
    }

    /**
     * 向量相似度检索（cosine similarity），含权限过滤。
     *
     * @param queryEmbedding 查询向量
     * @param styleProfile   风格画像过滤（可空，null 则不限制）
     * @param topK           返回条数上限
     * @param minSimilarity  cosine similarity 最低阈值
     * @param userId         当前用户ID（可空，null 时不加权限过滤）
     * @return 按 similarity DESC 排序的向量记录列表
     */
    List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                         int topK, double minSimilarity, Long userId);

    /**
     * 向量相似度检索（向后兼容，无权限过滤）。
     */
    default List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                                 int topK, double minSimilarity) {
        return search(queryEmbedding, styleProfile, topK, minSimilarity, null);
    }

    /**
     * 标记向量失效（软删除）。
     *
     * @param fingerprintId MySQL 指纹 ID
     */
    void deactivate(Long fingerprintId);
}
