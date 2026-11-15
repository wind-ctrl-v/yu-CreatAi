-- =====================================================================
-- 022：design_fingerprint.usageCount 列名兼容
--
-- 应用实体映射的是驼峰列 `usageCount`（与 004 一致）。
-- 已有库可能只有 `usageCount`、只有 `usage_count`，或两列都在。
-- 本脚本可重复执行，不删除任何列，也不覆盖已有的更大计数值：
--   1. 两列都在：把较大的计数值写入 `usageCount`，保留 `usage_count` 原值
--   2. 只有 `usage_count`：重命名为 `usageCount`（数据随列移动）
--   3. 两列都没有：补上 `usageCount`
--   4. 只有 `usageCount`：不改动
--
-- 用 mysql 客户端执行。条件不匹配时动态语句是 SELECT 1。
-- =====================================================================

-- 1. 两列并存：合并到驼峰列，不丢较大的计数，也不删除 snake_case 列
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usageCount') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usage_count') = 1,
        'UPDATE `design_fingerprint` SET `usageCount` = GREATEST(IFNULL(`usageCount`, 0), IFNULL(`usage_count`, 0))',
        'SELECT 1'
    )
);
PREPARE stmt_fp_usage_merge FROM @sql;
EXECUTE stmt_fp_usage_merge;
DEALLOCATE PREPARE stmt_fp_usage_merge;

-- 2. 只有 snake_case：重命名，保留原数据
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usageCount') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usage_count') = 1,
        'ALTER TABLE `design_fingerprint` CHANGE COLUMN `usage_count` `usageCount` INT DEFAULT 0 COMMENT ''被使用的次数''',
        'SELECT 1'
    )
);
PREPARE stmt_fp_usage_rename FROM @sql;
EXECUTE stmt_fp_usage_rename;
DEALLOCATE PREPARE stmt_fp_usage_rename;

-- 3. 两列都没有：补驼峰列（004 未执行或被跳过时）
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usageCount') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint'
            AND COLUMN_NAME = 'usage_count') = 0
        AND (SELECT COUNT(*) FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'design_fingerprint') = 1,
        'ALTER TABLE `design_fingerprint` ADD COLUMN `usageCount` INT DEFAULT 0 COMMENT ''被使用的次数''',
        'SELECT 1'
    )
);
PREPARE stmt_fp_usage_add FROM @sql;
EXECUTE stmt_fp_usage_add;
DEALLOCATE PREPARE stmt_fp_usage_add;
