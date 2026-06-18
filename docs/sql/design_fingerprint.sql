-- =====================================================================
-- 阶段二：设计指纹（后验证据）建表脚本
-- 列名沿用本项目「驼峰命名」约定（与 app / chat_history 一致，非 snake_case）；
-- 主键为雪花 ID（应用层 KeyGenerators.snowFlakeId 生成，非自增）；
-- 逻辑删除字段 isDelete。
-- 该脚本仅作为参考 DDL，按项目惯例由 DBA / 初始化流程在 MySQL 中执行。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `design_fingerprint`
(
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `styleProfile`    VARCHAR(32)  NOT NULL COMMENT '风格画像：startup/enterprise/ecommerce/portfolio/dashboard/landing/default',
    `schemaVersion`   VARCHAR(16)  NOT NULL DEFAULT 'v1' COMMENT 'schema 版本',
    `sourceType`      VARCHAR(32)  NOT NULL COMMENT '来源类型：EXTRACTED_FINGERPRINT(真实精选) / SELF_FED_FINGERPRINT(自产反哺)',
    `sourceId`        VARCHAR(128) NULL COMMENT '来源标识：页面ID / 抓取批次号',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'CANDIDATE' COMMENT '治理状态：CANDIDATE/APPROVED/REJECTED/ARCHIVED',
    `signature`       VARCHAR(64)  NULL COMMENT '去重签名（styleProfile+layout.skeleton+token+components 的MD5）',
    `confidence`      DOUBLE       NULL COMMENT '置信度 0~1（客观计算）',
    `priority`        INT          NULL DEFAULT 30 COMMENT '优先级（默认低于人工静态规范基线50）',
    `tone`            VARCHAR(255) NULL COMMENT '调性关键词，逗号分隔（冗余列，便于软匹配）',
    `fingerprintJson` MEDIUMTEXT   NULL COMMENT '完整六层指纹 JSON（审计与投影用）',
    `reviewRemark`    VARCHAR(512) NULL COMMENT '审核备注（拒绝/归档原因）',
    `createTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`        TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除（逻辑删除）',
    PRIMARY KEY (`id`),
    KEY `idx_profile_status` (`styleProfile`, `status`),
    KEY `idx_signature` (`signature`),
    KEY `idx_status_priority` (`status`, `priority`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='设计指纹（阶段二后验证据）';
