-- =====================================================================
-- 阶段一：用户偏好闭环 - 建表脚本
-- 列名沿用本项目「驼峰命名」约定；
-- 主键为雪花 ID（应用层 KeyGenerators.snowFlakeId 生成）；
-- 逻辑删除字段 isDelete。
-- =====================================================================

-- 1. 用户设计画像表
CREATE TABLE IF NOT EXISTS `user_design_profile`
(
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `userId`           BIGINT       NOT NULL COMMENT '用户ID',
    `profileSummary`   TEXT         NULL COMMENT '用户偏好摘要（自然语言描述）',
    `preferredStyles`  JSON         NULL COMMENT '偏好的设计风格列表 ["minimal","tech","premium"]',
    `dislikedStyles`   JSON         NULL COMMENT '不喜欢的风格列表',
    `preferredColors`  JSON         NULL COMMENT '偏好的颜色系列 ["dark","blue","purple"]',
    `dislikedColors`   JSON         NULL COMMENT '不喜欢的颜色系列',
    `preferredLayouts` JSON         NULL COMMENT '偏好的布局类型 ["centered-hero","card-grid"]',
    `dislikedLayouts`  JSON         NULL COMMENT '不喜欢的布局类型',
    `preferredComponents`  JSON     NULL COMMENT '偏好的组件类型 ["pricing","testimonials","faq"]',
    `dislikedComponents`   JSON     NULL COMMENT '不喜欢的组件类型',
    `preferredInteractions` JSON    NULL COMMENT '偏好的交互模式',
    `dislikedInteractions`  JSON    NULL COMMENT '不喜欢的交互模式',
    `createTime`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`userId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户设计画像';

-- 2. 用户偏好权重表（精细化排序用）
CREATE TABLE IF NOT EXISTS `user_preference_weight`
(
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `userId`          BIGINT       NOT NULL COMMENT '用户ID',
    `preferenceType`  VARCHAR(64)  NOT NULL COMMENT '偏好类型：style/color/layout/component/interaction',
    `preferenceKey`   VARCHAR(128) NOT NULL COMMENT '偏好键值',
    `weight`          DOUBLE       NOT NULL DEFAULT 0 COMMENT '权重（正=喜欢，负=反感）',
    `evidenceCount`   INT          NOT NULL DEFAULT 0 COMMENT '证据计数',
    `lastEvidenceAt`  DATETIME     NULL COMMENT '最后一次证据时间',
    `createTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_type_key` (`userId`, `preferenceType`, `preferenceKey`),
    KEY `idx_user_id` (`userId`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户偏好权重';

-- 3. 用户设计行为事件表
CREATE TABLE IF NOT EXISTS `user_design_event`
(
    `id`           BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `userId`       BIGINT       NOT NULL COMMENT '用户ID',
    `projectId`    BIGINT       NULL COMMENT '项目ID',
    `generationId` BIGINT       NULL COMMENT '关联的生成记录ID',
    `pageId`       BIGINT       NULL COMMENT '关联的页面ID',
    `eventType`    VARCHAR(64)  NOT NULL COMMENT '事件类型：page_saved/page_exported/page_deleted/color_changed/layout_changed/component_added/component_removed/feedback_submitted',
    `targetType`   VARCHAR(64)  NULL COMMENT '目标类型（用于扩展）',
    `targetId`     BIGINT       NULL COMMENT '目标ID',
    `metadata`     JSON         NULL COMMENT '事件元数据（JSON）',
    `createTime`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`userId`),
    KEY `idx_user_event` (`userId`, `eventType`),
    KEY `idx_creation_time` (`createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户设计行为事件';

-- 4. 用户生成页面快照表
CREATE TABLE IF NOT EXISTS `generated_page`
(
    `id`                     BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `userId`                 BIGINT       NOT NULL COMMENT '用户ID',
    `projectId`              BIGINT       NULL COMMENT '项目ID',
    `requirementText`        TEXT         NULL COMMENT '原始需求文本',
    `normalizedRequirement`  JSON         NULL COMMENT '标准化需求（JSON）',
    `styleProfile`           VARCHAR(64)  NULL COMMENT '风格画像',
    `html`                   LONGTEXT     NULL COMMENT '生成的HTML',
    `css`                    LONGTEXT     NULL COMMENT '生成的CSS',
    `js`                     LONGTEXT     NULL COMMENT '生成的JS',
    `screenshotUrl`          VARCHAR(1024) NULL COMMENT '页面截图URL',
    `designSpecSnapshot`     JSON         NULL COMMENT '生成时使用的设计规范快照',
    `designEvidenceSnapshot` JSON         NULL COMMENT '生成时注入的RAG证据快照',
    `isSaved`                TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已保存',
    `isExported`             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已导出',
    `isDeleted`              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已删除（用户操作）',
    `userRating`             INT          NULL COMMENT '用户评分（1-5）',
    `qualityScore`           DOUBLE       NULL COMMENT '自动质量评分',
    `createTime`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`userId`),
    KEY `idx_user_project` (`userId`, `projectId`),
    KEY `idx_creation_time` (`createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户生成页面快照';

-- 5. RAG 调用追踪表
CREATE TABLE IF NOT EXISTS `generation_rag_trace`
(
    `id`                    BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `generationId`          BIGINT       NOT NULL COMMENT '关联的 generated_page.id',
    `userId`                BIGINT       NOT NULL COMMENT '用户ID',
    `projectId`             BIGINT       NULL COMMENT '项目ID',
    `queryText`             TEXT         NULL COMMENT '检索查询文本',
    `queryTags`             JSON         NULL COMMENT '检索标签（JSON）',
    `styleProfile`          VARCHAR(64)  NULL COMMENT '风格画像',
    `retrievedItems`        JSON         NULL COMMENT '原始检索结果（JSON）',
    `rerankedItems`         JSON         NULL COMMENT '重排后结果（JSON）',
    `injectedItems`         JSON         NULL COMMENT '最终注入evidence的条目ID列表',
    `retrieveChannelStats`  JSON         NULL COMMENT '各检索通道统计',
    `minSimilarity`         DOUBLE       NULL COMMENT '最小相似度',
    `topSimilarity`         DOUBLE       NULL COMMENT '最高相似度',
    `usedUserProfile`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否使用了用户偏好画像',
    `criticResult`          JSON         NULL COMMENT 'CriticAgent评估结果（阶段三补填）',
    `createTime`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_generation_id` (`generationId`),
    KEY `idx_user_id` (`userId`),
    KEY `idx_creation_time` (`createTime`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG调用追踪';
