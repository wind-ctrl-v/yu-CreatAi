-- 008_user_quota.sql
-- 用户每日生成额度表，按 (user_id, quota_date, generation_mode) 唯一
CREATE TABLE IF NOT EXISTS `user_quota` (
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `user_id`         BIGINT       NOT NULL COMMENT '用户ID',
    `quota_date`      DATE         NOT NULL COMMENT '额度日期',
    `generation_mode` VARCHAR(32)  NOT NULL COMMENT '生成模式：low_cost/standard/high_quality',
    `used_count`      INT          NOT NULL DEFAULT 0 COMMENT '当日已使用次数',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date_mode` (`user_id`, `quota_date`, `generation_mode`),
    KEY `idx_user_date` (`user_id`, `quota_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日生成额度记录';
