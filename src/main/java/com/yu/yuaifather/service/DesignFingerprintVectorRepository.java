package com.yu.yuaifather.service;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量表 Repository：基于 {@link JdbcTemplate} 操作 PGvector。
 * <p>
 * 不使用 MyBatis-Flex（PGvector {@code vector} 类型和 {@code <=>} 操作符不被支持），
 * 所有操作均为原生 SQL + {@link JdbcTemplate}，与 MySQL 主链路隔离。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>入参 {@code float[]} 由 PGvector 自动映射为 {@code vector} 类型；</li>
 *   <li>相似度检索用 {@code embedding <=> ?::vector}（cosine distance），
 *       {@code 1 - distance} 得到 cosine similarity；</li>
 *   <li>UPSERT 用 {@code INSERT ... ON CONFLICT ... DO UPDATE} 保证幂等。</li>
 * </ul>
 *
 * @author yu
 */
@Repository
@Slf4j
public class DesignFingerprintVectorRepository {

    private final JdbcTemplate jdbc;

    public DesignFingerprintVectorRepository(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        if (embedding == null || embedding.length == 0) {
            log.warn("向量为空，跳过 upsert fingerprintId={}", fingerprintId);
            return;
        }
        String sql = """
                INSERT INTO design_fingerprint_vector
                    (fingerprint_id, style_profile, status, confidence, priority,
                     embedding_text, embedding, is_active,
                     source_scope, owner_user_id, project_id, visibility,
                     source_type)
                VALUES (?, ?, 'APPROVED', ?, ?, ?, ?::vector, true, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint_id)
                DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embedding_text = EXCLUDED.embedding_text,
                    confidence = EXCLUDED.confidence,
                    priority = EXCLUDED.priority,
                    source_scope = EXCLUDED.source_scope,
                    owner_user_id = EXCLUDED.owner_user_id,
                    project_id = EXCLUDED.project_id,
                    visibility = EXCLUDED.visibility,
                    source_type = EXCLUDED.source_type,
                    updated_at = now(),
                    is_active = true
                """;
        jdbc.update(sql, fingerprintId, styleProfile, confidence, priority,
                embeddingText, embedding,
                sourceScope, ownerUserId, projectId, visibility, sourceType);
    }

    /**
     * 插入或更新向量（向后兼容，无权限字段版本）。
     */
    public void upsert(Long fingerprintId, String styleProfile,
                       Double confidence, Integer priority,
                       String embeddingText, float[] embedding) {
        upsert(fingerprintId, styleProfile, confidence, priority,
                embeddingText, embedding, "GLOBAL", null, null, "PUBLIC", null);
    }

    /**
     * 向量相似度检索（cosine similarity），含阶段二权限过滤。
     * <p>
     * 使用 PGvector {@code <=>} 操作符计算 cosine distance，
     * {@code 1 - (embedding <=> query)} 得到 cosine similarity。
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

        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }

        StringBuilder sqlBuilder = new StringBuilder("""
                SELECT id, fingerprint_id, style_profile, status, confidence, priority,
                       embedding_text, source_scope, owner_user_id, project_id, visibility,
                       created_at, updated_at, is_active,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM design_fingerprint_vector
                WHERE is_active = true AND status = 'APPROVED'
                """);

        List<Object> params = new ArrayList<>();
        params.add(queryEmbedding);

        if (styleProfile != null && !styleProfile.isBlank()) {
            sqlBuilder.append(" AND style_profile = ?");
            params.add(styleProfile);
        }

        // ★ 阶段二：权限过滤（GLOBAL 或 当前用户私有）
        if (userId != null) {
            sqlBuilder.append(" AND (source_scope = 'GLOBAL' OR owner_user_id = ?)");
            params.add(userId);
        }

        // 用 HAVING 因为 similarity 是计算列
        sqlBuilder.append(" AND 1 - (embedding <=> ?::vector) >= ?");
        params.add(queryEmbedding);
        params.add(minSimilarity);

        // PGvector 的 <=> 可走 HNSW 索引
        sqlBuilder.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        params.add(queryEmbedding);
        params.add(topK);

        try {
            return jdbc.query(sqlBuilder.toString(),
                    new BeanPropertyRowMapper<>(DesignFingerprintVector.class),
                    params.toArray());
        } catch (Exception e) {
            log.error("向量检索失败 styleProfile={} topK={}", styleProfile, topK, e);
            return List.of();
        }
    }

    /**
     * 向量相似度检索（向后兼容，无权限过滤）。
     */
    public List<DesignFingerprintVector> search(
            float[] queryEmbedding, String styleProfile, int topK, double minSimilarity) {
        return search(queryEmbedding, styleProfile, topK, minSimilarity, null);
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
        if (fingerprintId == null) {
            return;
        }
        String sql = """
                UPDATE design_fingerprint_vector
                SET is_active = false, updated_at = now()
                WHERE fingerprint_id = ?
                """;
        int updated = jdbc.update(sql, fingerprintId);
        if (updated > 0) {
            log.info("向量标记失效 fingerprintId={}", fingerprintId);
        }
    }
}
