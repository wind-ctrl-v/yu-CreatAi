package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 向量表门面 Repository（A 改造：向量存储后端可选）。
 * <p>
 * 保留原有 public 方法签名（11 参 upsert、6 参 upsert、5 参 / 4 参 search、deactivate），
 * 并补充 5 参 upsert 兼容重载；内部委托给 {@link VectorStore}，调用方
 * （{@code DesignKnowledgeIndex} / {@code VectorSyncWorker}）零改动。
 * 具体后端由 {@code config.VectorStoreConfiguration} 按
 * {@code agent.retrieve.vector.store} 装配：
 * <ul>
 *   <li>{@code pg}（默认）：{@link PgVectorStore}，PGvector 原生 SQL + {@code <=>} 语义检索，行为不变；</li>
 *   <li>{@code mysql}：{@link MysqlVectorStore}，MySQL 持久化 + 内存快照余弦 TopK，无需 PostgreSQL。</li>
 * </ul>
 * <p>
 * 历史说明：本类原直接持有 {@code pgVectorJdbcTemplate} 并内联 PG SQL；拆分后只保留门面职责，
 * 使 {@link #search(float[], String, int, double, Long)} 的语义在不同存储后端下保持一致
 * （cosine similarity、按相似度降序）。
 * <p>
 * 注意：整体行为受 {@code agent.retrieve.vector.enabled=true} 开关控制（关闭时本 Bean 不存在，
 * 依赖它的 {@code VectorSyncWorker} / {@code DesignKnowledgeIndex} 同时不装配）。
 * <p>
 * ★ 包结构说明（包结构优化计划问题 5）：本类原位于 {@code service} 包，与普通业务 Service 混放；
 * 现独立到 {@code repository} 包，聚合"直连数据源的原生 SQL 访问层"这一类职责，
 * 与 {@code mapper}（MyBatis-Flex 映射器）、{@code service}（业务逻辑）形成三层清晰划分。
 *
 * @author yu
 */
@Repository
@Slf4j
@ConditionalOnProperty(name = "agent.retrieve.vector.enabled", havingValue = "true")
public class DesignFingerprintVectorRepository {

    private final VectorStore vectorStore;

    public DesignFingerprintVectorRepository(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 插入或更新向量（幂等），含阶段二权限字段。
     * <p>
     * 指纹 APPROVED 时调用；若 fingerprint_id 已存在则更新 embedding 及元数据。
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
    public void upsert(Long fingerprintId, String styleProfile,
                       Double confidence, Integer priority,
                       String embeddingText, float[] embedding,
                       String sourceScope, Long ownerUserId, Long projectId,
                       String visibility, String sourceType) {
        vectorStore.upsert(fingerprintId, styleProfile, confidence, priority,
                embeddingText, embedding, sourceScope, ownerUserId, projectId,
                visibility, sourceType);
    }

    /**
     * 插入或更新向量（向后兼容，无权限字段版本）。
     */
    public void upsert(Long fingerprintId, String styleProfile,
                       Double confidence, Integer priority,
                       String embeddingText, float[] embedding) {
        vectorStore.upsert(fingerprintId, styleProfile, confidence, priority,
                embeddingText, embedding);
    }

    /**
     * 插入或更新向量（兼容重载：不传 embeddingText，语义文本留空）。
     */
    public void upsert(Long fingerprintId, String styleProfile,
                       Double confidence, Integer priority, float[] embedding) {
        vectorStore.upsert(fingerprintId, styleProfile, confidence, priority, embedding);
    }

    /**
     * 向量相似度检索（cosine similarity），含阶段二权限过滤。
     *
     * @param queryEmbedding 查询向量（由检索端 embedding 生成）
     * @param styleProfile   风格画像硬过滤（可空，null 则不限制）
     * @param topK           返回条数上限
     * @param minSimilarity  cosine similarity 最低阈值（如 0.7）
     * @param userId         当前用户ID（可空，null 时不加权限过滤）
     * @return 按 similarity DESC 排序的向量记录列表
     */
    public List<DesignFingerprintVector> search(
            float[] queryEmbedding, String styleProfile, int topK, double minSimilarity, Long userId) {
        return vectorStore.search(queryEmbedding, styleProfile, topK, minSimilarity, userId);
    }

    /**
     * 向量相似度检索（向后兼容，无权限过滤）。
     */
    public List<DesignFingerprintVector> search(
            float[] queryEmbedding, String styleProfile, int topK, double minSimilarity) {
        return vectorStore.search(queryEmbedding, styleProfile, topK, minSimilarity);
    }

    /**
     * 标记向量失效（软删除）。
     * <p>
     * 指纹 REJECTED / ARCHIVED 时调用，仅置 {@code is_active = false}，
     * 不物理删除以保留审计记录。
     *
     * @param fingerprintId MySQL 指纹 ID
     */
    public void deactivate(Long fingerprintId) {
        vectorStore.deactivate(fingerprintId);
    }
}
