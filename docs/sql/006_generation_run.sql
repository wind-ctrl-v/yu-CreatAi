-- ============================================================
-- 006_generation_run.sql
-- 生成运行记录表：沉淀每次生成的链路 / 模式 / 降级 / 耗时，便于统计与可观测
-- ============================================================

CREATE TABLE IF NOT EXISTS generation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    generation_id VARCHAR(128) NOT NULL COMMENT '生成全局追踪ID（贯穿各 attempt）',
    app_id BIGINT NOT NULL COMMENT '应用ID',
    user_id BIGINT NULL COMMENT '用户ID',

    requested_mode VARCHAR(32) NULL COMMENT '前端请求模式（不可信意图）',
    final_mode VARCHAR(32) NULL COMMENT '服务端最终模式：low_cost/standard/high_quality',
    pipeline_type VARCHAR(32) NULL COMMENT '实际成功链路：V2/V1/CLASSIC',

    status VARCHAR(32) NOT NULL COMMENT '状态：RUNNING/SUCCESS/FAILED/FALLBACK_SUCCESS',
    fallback_from VARCHAR(32) NULL COMMENT '降级来源链路',
    fallback_to VARCHAR(32) NULL COMMENT '降级目标链路',

    workspace_path VARCHAR(512) NULL COMMENT '生成产物工作目录',

    started_at TIMESTAMP NULL COMMENT '开始时间',
    finished_at TIMESTAMP NULL COMMENT '结束时间',
    duration_ms BIGINT NULL COMMENT '耗时（毫秒）',

    error_message TEXT NULL COMMENT '错误信息',

    INDEX idx_generation_id (generation_id),
    INDEX idx_user_started (user_id, started_at),
    INDEX idx_app_started (app_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成运行记录表';
