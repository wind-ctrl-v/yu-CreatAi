-- =====================================================================
-- 阶段三：项目品牌记忆 + 评估治理 - 建表脚本
-- =====================================================================

-- 1. 项目品牌画像表
CREATE TABLE IF NOT EXISTS `project_brand_profile`
(
    `id`                  BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `userId`              BIGINT       NOT NULL COMMENT '用户ID',
    `projectId`           BIGINT       NOT NULL COMMENT '项目ID',
    `brandName`           VARCHAR(255) NULL COMMENT '品牌名称',
    `industry`            VARCHAR(128) NULL COMMENT '行业',
    `targetAudience`      TEXT         NULL COMMENT '目标用户群体',
    `brandTone`           JSON         NULL COMMENT '品牌调性 ["专业","前沿","可信赖"]',
    `brandColors`         JSON         NULL COMMENT '品牌颜色 {"primary":"#2563EB","secondary":"#7C3AED","mode":"dark"}',
    `typographyPreference` JSON        NULL COMMENT '排版偏好',
    `layoutPreference`    JSON         NULL COMMENT '布局偏好',
    `componentPreference` JSON         NULL COMMENT '组件偏好',
    `designGuidelines`    JSON         NULL COMMENT '设计指南（结构化规则）',
    `forbiddenRules`      JSON         NULL COMMENT '禁用规则 ["卡通风","低龄化","过度装饰"]',
    `profileSummary`      TEXT         NULL COMMENT '品牌画像摘要（自然语言）',
    `createTime`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_id` (`projectId`),
    KEY `idx_user_id` (`userId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='项目品牌画像';

-- 2. 设计指纹审核日志表
CREATE TABLE IF NOT EXISTS `design_fingerprint_review_log`
(
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `fingerprintId`   BIGINT       NOT NULL COMMENT '指纹ID',
    `reviewerId`      BIGINT       NULL COMMENT '审核人ID',
    `action`          VARCHAR(32)  NOT NULL COMMENT '审核动作：APPROVE/REJECT/ARCHIVE',
    `reason`          TEXT         NULL COMMENT '审核原因',
    `qualityScore`    DOUBLE       NULL COMMENT '人工质量评分',
    `createTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_fingerprint_id` (`fingerprintId`),
    KEY `idx_reviewer_id` (`reviewerId`),
    KEY `idx_creation_time` (`createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='设计指纹审核日志';
