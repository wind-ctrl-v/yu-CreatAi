-- ============================================================
-- 021 设计指纹向量表（MySQL 后端，A 改造：向量存储可选）
-- 用途：agent.retrieve.vector.store=mysql 时，为 3B 语义检索提供向量持久化
-- 数据库：MySQL（与主库同实例，无需 PostgreSQL / PGvector）
-- 执行前提：agent.retrieve.vector.enabled=true 且 store=mysql
--
-- 与 PG 脚本 docs/sql/postgresql/design_fingerprint_vector.sql 的关系：
--   * store=pg（默认）→ 只执行 PG 脚本，不执行本脚本；
--   * store=mysql     → 只执行本脚本，不需要部署 PostgreSQL。
--
-- 存储格式约定（与 MysqlVectorStore 一致）：
--   embedding = float[] 小端序（LITTLE_ENDIAN）字节矩阵，长度 = dim * 4；
--   norm      = L2 范数（冗余，便于审计；检索时仍按实际向量计算）；
--   检索为 JVM 内存快照 + Java 余弦 TopK，本表只需普通索引，无需向量索引。
-- ============================================================

CREATE TABLE IF NOT EXISTS design_fingerprint_vector (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    fingerprint_id  BIGINT          NOT NULL COMMENT '对应 MySQL design_fingerprint.id',
    style_profile   VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '风格画像（冗余，过滤用）',
    status          VARCHAR(16)     NOT NULL DEFAULT 'APPROVED' COMMENT '治理状态',
    confidence      DOUBLE          NULL COMMENT '置信度（冗余，辅助排序）',
    priority        INT             NULL DEFAULT 30 COMMENT '优先级（指纹默认 30，低于静态规范 50）',
    embedding_text  TEXT            NULL COMMENT '原始语义文本（调试/审计用）',
    embedding       BLOB            NOT NULL COMMENT '向量 float[] 小端序字节（dim*4 字节；dim>16383 需改 MEDIUMBLOB）',
    dim             SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '向量维度（启动加载时校验 expected-dim）',
    norm            DOUBLE          NULL COMMENT '向量 L2 范数（冗余，便于审计）',
    is_active       TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '软开关，REJECTED/ARCHIVED 时置 0',
    source_scope    VARCHAR(16)     NULL COMMENT '来源范围：GLOBAL/USER/PROJECT',
    owner_user_id   BIGINT          NULL COMMENT '归属用户ID',
    project_id      BIGINT          NULL COMMENT '归属项目ID',
    visibility      VARCHAR(16)     NULL COMMENT '可见性：PUBLIC/PRIVATE/TEAM',
    source_type     VARCHAR(32)     NULL COMMENT '来源类型（EXTRACTED_FINGERPRINT/SELF_FED_FINGERPRINT/...）',
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fingerprint_id (fingerprint_id),
    KEY idx_active_status_priority (is_active, status, priority),
    KEY idx_style_active (style_profile, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设计指纹向量索引（MySQL 后端，内存快照检索）';

-- ============================================================
-- 运维提示
-- ============================================================
-- 1) 历史 APPROVED 指纹补建向量：
--    应用启动后由管理员调用 POST /api/fingerprint/admin/vector/resync
--    （limit 默认且最大为 agent.retrieve.vector.resync-max=100，可重复调用直到补完）；
--    或等待指纹重新 APPROVED / 定时快照重载（reload-interval-ms 默认 5 分钟）。
-- 2) 检查向量规模与维度：
--    SELECT COUNT(*) AS total, MAX(dim) AS max_dim
--    FROM design_fingerprint_vector WHERE is_active = 1 AND status = 'APPROVED';
-- 3) 更换 embedding 模型（维度变化）时：先清空本表再重同步，或显式设置
--    agent.retrieve.vector.expected-dim 为新维度，避免旧维度向量被跳过。
-- 4) 清理软删除记录（可选，确认审计要求后执行）：
--    DELETE FROM design_fingerprint_vector WHERE is_active = 0;
