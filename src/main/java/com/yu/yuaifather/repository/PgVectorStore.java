package com.yu.yuaifather.repository;

import com.yu.yuaifather.model.dto.agent.DesignFingerprintVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * PGvector 向量存储实现（默认后端 {@code agent.retrieve.vector.store=pg}）。
 * <p>
 * 迁移自原 {@link DesignFingerprintVectorRepository}：所有 SQL 与行为保持不变，
 * 仅把"存储实现"从门面类中抽出，实现 {@link VectorStore} 接口。
 * <p>
 * 不使用 MyBatis-Flex（PGvector {@code vector} 类型和 {@code <=>} 操作符不被支持），
 * 所有操作均为原生 SQL + {@link JdbcTemplate}，与 MySQL 主链路隔离：
 * <ul>
 *   <li>入参 {@code float[]} 由 PGvector 自动映射为 {@code vector} 类型；</li>
 *   <li>相似度检索用 {@code embedding <=> ?::vector}（cosine distance），
 *       {@code 1 - distance} 得到 cosine similarity；</li>
 *   <li>UPSERT 用 {@code INSERT ... ON CONFLICT ... DO UPDATE} 保证幂等。</li>
 * </ul>
 *
 * @author yu
 */
@Slf4j
public class PgVectorStore implements VectorStore {

    private final JdbcTemplate jdbc;

    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
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

    @Override
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

    @Override
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
