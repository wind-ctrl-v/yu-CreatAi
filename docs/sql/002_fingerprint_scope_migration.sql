-- =====================================================================
-- 阶段二：用户私有指纹池 - ALTER TABLE 脚本
-- 1. design_fingerprint（MySQL）：新增 source_scope / owner_user_id / visibility 等字段
-- 2. design_fingerprint_vector（PostgreSQL）：新增对应权限字段
-- =====================================================================

-- ===========================
-- 1. MySQL design_fingerprint 扩展
-- ===========================
ALTER TABLE `design_fingerprint`
    ADD COLUMN IF NOT EXISTS `ownerUserId`    BIGINT       NULL     COMMENT '归属用户ID（私有指纹用）',
    ADD COLUMN IF NOT EXISTS `projectId`      BIGINT       NULL     COMMENT '归属项目ID',
    ADD COLUMN IF NOT EXISTS `sourceScope`    VARCHAR(32)  NOT NULL DEFAULT 'GLOBAL' COMMENT '来源范围：GLOBAL/USER/PROJECT',
    ADD COLUMN IF NOT EXISTS `visibility`     VARCHAR(32)  NOT NULL DEFAULT 'PUBLIC' COMMENT '可见性：PUBLIC/PRIVATE/TEAM',
    ADD COLUMN IF NOT EXISTS `sourcePageId`   BIGINT       NULL     COMMENT '来源页面ID（generated_page.id）',
    ADD COLUMN IF NOT EXISTS `sourceGenerationId` BIGINT   NULL     COMMENT '来源生成记录ID（generated_page.id）';

-- 新增索引：按 source_scope + owner_user_id 加速权限过滤查询
CREATE INDEX IF NOT EXISTS `idx_scope_owner` ON `design_fingerprint` (`sourceScope`, `ownerUserId`);
-- 新增索引：按 project_id 查找项目级指纹
CREATE INDEX IF NOT EXISTS `idx_project_id` ON `design_fingerprint` (`projectId`);

-- ===========================
-- 2. PostgreSQL design_fingerprint_vector 扩展
-- ===========================
ALTER TABLE design_fingerprint_vector
    ADD COLUMN IF NOT EXISTS owner_user_id   BIGINT      NULL,
    ADD COLUMN IF NOT EXISTS project_id      BIGINT      NULL,
    ADD COLUMN IF NOT EXISTS source_scope    VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    ADD COLUMN IF NOT EXISTS visibility      VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN IF NOT EXISTS style_profile   VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS source_type     VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS embedding_model     VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS embedding_version   VARCHAR(64)  NULL,
    ADD COLUMN IF NOT EXISTS semantic_text_hash    VARCHAR(64)  NULL;

-- 更新现有数据：将已有全局指纹标记为 source_scope = 'GLOBAL'
UPDATE design_fingerprint_vector SET source_scope = 'GLOBAL' WHERE source_scope IS NULL;
UPDATE design_fingerprint_vector SET visibility = 'PUBLIC' WHERE visibility IS NULL;

-- 新增索引：权限过滤加速
CREATE INDEX IF NOT EXISTS idx_fv_scope_owner
    ON design_fingerprint_vector (source_scope, owner_user_id)
    WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_fv_project
    ON design_fingerprint_vector (project_id)
    WHERE is_active = true;
