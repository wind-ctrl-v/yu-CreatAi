-- ============================================================
-- PGvector 向量存储建表脚本
-- 用途：为阶段三 3B 语义检索提供向量索引
-- 数据库：PostgreSQL（与 MySQL 主库并存）
-- 执行前提：PostgreSQL 已安装 PGvector 扩展
-- ============================================================

-- 1. 安装 PGvector 扩展（需 superuser，首次执行）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建向量表
CREATE TABLE IF NOT EXISTS design_fingerprint_vector (
    id              BIGSERIAL       PRIMARY KEY,
    fingerprint_id  BIGINT          NOT NULL UNIQUE,        -- 对应 MySQL design_fingerprint.id
    style_profile   VARCHAR(32)     NOT NULL,               -- 风格画像（冗余，硬过滤用）
    status          VARCHAR(16)     NOT NULL DEFAULT 'APPROVED', -- 治理状态
    confidence      DOUBLE PRECISION,                       -- 置信度（冗余，辅助排序）
    priority        INTEGER         DEFAULT 30,             -- 优先级（指纹默认 30，低于静态规范 50）
    embedding_text  TEXT,                                   -- 原始语义文本（调试/审计用）
    embedding       vector(1536),                           -- 向量（维度与 embedding 模型一致）
    created_at      TIMESTAMP       DEFAULT now(),
    updated_at      TIMESTAMP       DEFAULT now(),
    is_active       BOOLEAN         DEFAULT true            -- 软开关，REJECTED/ARCHIVED 时置 false
);

-- 3. HNSW 索引（比 IVFFlat 更快，PGvector 0.5+ 支持）
-- m=16：每个节点 16 个邻居，平衡精度/构建时间
-- ef_construction=200：构建时搜索范围，越大精度越高但构建越慢
CREATE INDEX IF NOT EXISTS idx_fv_embedding_hnsw
    ON design_fingerprint_vector
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- 4. 业务索引：按 style_profile + is_active 过滤加速
CREATE INDEX IF NOT EXISTS idx_fv_style_active
    ON design_fingerprint_vector (style_profile, is_active)
    WHERE is_active = true;

-- 5. 联合查询索引
CREATE INDEX IF NOT EXISTS idx_fv_fingerprint_id
    ON design_fingerprint_vector (fingerprint_id);

-- ============================================================
-- 运维命令参考
-- ============================================================
-- 查看扩展版本：
--   SELECT * FROM pg_extension WHERE extname = 'vector';

-- 查看索引使用情况：
--   EXPLAIN ANALYZE
--   SELECT fingerprint_id, 1 - (embedding <=> '[0.01, ...]'::vector) AS sim
--   FROM design_fingerprint_vector
--   WHERE is_active = true
--   ORDER BY embedding <=> '[0.01, ...]'::vector
--   LIMIT 10;

-- 全量同步历史已 APPROVED 指纹（在应用层通过 VectorSyncJob 执行）：
--   1. 从 MySQL 查询所有 status='APPROVED' 的 DesignFingerprint
--   2. 逐条调用 VectorSyncService.syncOnApprove(fp)
--   3. 建议分批处理，每批 100 条
