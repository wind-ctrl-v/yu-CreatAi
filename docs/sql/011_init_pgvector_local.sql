-- ============================================================
-- 本地 PGvector 初始化脚本
-- 用途：在本地 PostgreSQL (yu_ai_vector) 中创建向量表及索引
-- 前置：pgvector 扩展已安装（CREATE EXTENSION vector）
-- 执行：psql -U fish -d yu_ai_vector -f docs/sql/011_init_pgvector_local.sql
-- ============================================================

-- ===================== 第一部分：扩展安装 =====================

-- 安装 pgvector（需 superuser，如未安装请先执行）
-- 手动安装 pgvector 后，以 postgres 用户执行：
--   psql -U postgres -d yu_ai_vector -c "CREATE EXTENSION IF NOT EXISTS vector;"

-- ===================== 第二部分：向量表 =====================

CREATE TABLE IF NOT EXISTS design_fingerprint_vector (
    id               BIGSERIAL       PRIMARY KEY,
    fingerprint_id   BIGINT          NOT NULL UNIQUE,
    style_profile    VARCHAR(32)     NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'APPROVED',
    confidence       DOUBLE PRECISION,
    priority         INTEGER         DEFAULT 30,
    embedding_text   TEXT,
    embedding        vector(1536),
    source_scope     VARCHAR(32)     NOT NULL DEFAULT 'GLOBAL',
    owner_user_id    BIGINT          NULL,
    visibility       VARCHAR(32)     NOT NULL DEFAULT 'PUBLIC',
    source_type      VARCHAR(64)     NULL,
    created_at       TIMESTAMP       DEFAULT now(),
    updated_at       TIMESTAMP       DEFAULT now(),
    is_active        BOOLEAN         DEFAULT true
);

-- ===================== 第三部分：索引 =====================

-- HNSW 向量索引（余弦相似度检索）
CREATE INDEX IF NOT EXISTS idx_fv_embedding_hnsw
    ON design_fingerprint_vector
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- 业务过滤索引
CREATE INDEX IF NOT EXISTS idx_fv_style_active
    ON design_fingerprint_vector (style_profile, is_active)
    WHERE is_active = true;

-- 指纹关联索引
CREATE INDEX IF NOT EXISTS idx_fv_fingerprint_id
    ON design_fingerprint_vector (fingerprint_id);

-- 权限过滤索引
CREATE INDEX IF NOT EXISTS idx_fv_scope_owner
    ON design_fingerprint_vector (source_scope, owner_user_id, is_active);

-- ===================== 第四部分：向量检索函数 =====================

-- 按语义检索（外层应用调用）
-- 示例：
--   SELECT fingerprint_id, style_profile,
--          1 - (embedding <=> ?::vector) AS similarity
--   FROM design_fingerprint_vector
--   WHERE is_active = true
--     AND (source_scope = 'GLOBAL' OR owner_user_id = ?)
--   ORDER BY embedding <=> ?::vector
--   LIMIT 10;

-- 按风格过滤检索
-- 示例：
--   SELECT fingerprint_id, style_profile, confidence,
--          1 - (embedding <=> ?::vector) AS similarity
--   FROM design_fingerprint_vector
--   WHERE is_active = true AND style_profile = ?
--   ORDER BY embedding <=> ?::vector
--   LIMIT 5;

-- ===================== 第五部分：运维 =====================

-- 查看表大小
-- SELECT pg_size_pretty(pg_total_relation_size('design_fingerprint_vector'));

-- 查看向量索引状态
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'design_fingerprint_vector';

-- 清空向量表（谨慎）
-- TRUNCATE TABLE design_fingerprint_vector RESTART IDENTITY;
