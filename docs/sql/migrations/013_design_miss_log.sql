-- ============================================================
-- 013_design_miss_log.sql
-- 设计检索 miss 日志表：记录三档降级（VECTOR_3B / STRUCTURAL_3A）均未召回
-- 任何指纹证据的检索现场。
-- 用途：
--   ① 定向补库分析：按 styleProfile / 需求关键词聚类统计 miss 频次
--   ② 动态合成触发：同一 styleProfile miss 次数达到阈值时触发 LLM 动态合成（阶段二）
-- 见 docs/plans/design-knowledge-coverage-optimization-plan.md
-- 列名沿用「驼峰」约定（与 design_fingerprint / generation_rag_trace 一致）
-- ============================================================

CREATE TABLE IF NOT EXISTS design_miss_log (
    id BIGINT PRIMARY KEY COMMENT '主键（雪花ID）',
    styleProfile VARCHAR(64) NOT NULL COMMENT '归一化后的风格画像（含 default）',
    rawProfile VARCHAR(64) NULL COMMENT '归一化前原始识别值（可空）',
    queryText TEXT NULL COMMENT '本次检索实际使用的查询文本',
    queryTags VARCHAR(512) NULL COMMENT '需求关键词，逗号分隔',
    channel VARCHAR(32) NOT NULL COMMENT '最终召回通道：STATIC_FALLBACK/VECTOR_3B/STRUCTURAL_3A',
    maxSimilarity DOUBLE NULL COMMENT '向量通道最高相似度（未走向量通道为 NULL）',
    userId BIGINT NULL COMMENT '用户ID（可空）',
    appId BIGINT NULL COMMENT '应用ID（可空）',
    createTime DATETIME NOT NULL COMMENT '创建时间',

    INDEX idx_profile_time (styleProfile, createTime),
    INDEX idx_create_time (createTime)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设计检索 miss 日志表';
