-- =====================================================================
-- 023：user_preference_weight 证据列名兼容
--
-- 建表脚本 001 与实体映射使用驼峰列：
--   userId / preferenceType / preferenceKey / evidenceCount / lastEvidenceAt
-- 偏好提取的原子更新曾经写成 snake_case（evidence_count 等），
-- 部分环境为此补过兼容列。本脚本把数据对齐到驼峰列，可重复执行，
-- 不删除 snake_case 列，也不用空值覆盖已有驼峰数据。
--
-- 每个列对的处理顺序：
--   1. 两列都在：合并（计数取较大值，时间取较晚值，其余仅在驼峰列为 NULL 时回填）
--   2. 只有 snake_case：重命名为驼峰列
--   3. 两列都没有且表存在：补驼峰列
--   4. 只有驼峰列：不改动
--
-- 用 mysql 客户端执行。条件不匹配时动态语句是 SELECT 1。
-- =====================================================================

-- evidence_count -> evidenceCount
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidenceCount') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidence_count') = 1,
        'UPDATE `user_preference_weight` SET `evidenceCount` = GREATEST(IFNULL(`evidenceCount`, 0), IFNULL(`evidence_count`, 0))',
        'SELECT 1'
    )
);
PREPARE stmt_pref_evidence_merge FROM @sql;
EXECUTE stmt_pref_evidence_merge;
DEALLOCATE PREPARE stmt_pref_evidence_merge;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidenceCount') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidence_count') = 1,
        'ALTER TABLE `user_preference_weight` CHANGE COLUMN `evidence_count` `evidenceCount` INT DEFAULT 0 COMMENT ''证据计数''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_evidence_rename FROM @sql;
EXECUTE stmt_pref_evidence_rename;
DEALLOCATE PREPARE stmt_pref_evidence_rename;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidenceCount') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'evidence_count') = 0
        AND (SELECT COUNT(*) FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight') = 1,
        'ALTER TABLE `user_preference_weight` ADD COLUMN `evidenceCount` INT NOT NULL DEFAULT 0 COMMENT ''证据计数''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_evidence_add FROM @sql;
EXECUTE stmt_pref_evidence_add;
DEALLOCATE PREPARE stmt_pref_evidence_add;

-- last_evidence_at -> lastEvidenceAt（取较晚的非空时间）
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'lastEvidenceAt') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'last_evidence_at') = 1,
        'UPDATE `user_preference_weight` SET `lastEvidenceAt` = CASE WHEN `lastEvidenceAt` IS NULL THEN `last_evidence_at` WHEN `last_evidence_at` IS NULL THEN `lastEvidenceAt` WHEN `last_evidence_at` > `lastEvidenceAt` THEN `last_evidence_at` ELSE `lastEvidenceAt` END',
        'SELECT 1'
    )
);
PREPARE stmt_pref_last_merge FROM @sql;
EXECUTE stmt_pref_last_merge;
DEALLOCATE PREPARE stmt_pref_last_merge;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'lastEvidenceAt') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'last_evidence_at') = 1,
        'ALTER TABLE `user_preference_weight` CHANGE COLUMN `last_evidence_at` `lastEvidenceAt` DATETIME NULL COMMENT ''最后一次证据时间''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_last_rename FROM @sql;
EXECUTE stmt_pref_last_rename;
DEALLOCATE PREPARE stmt_pref_last_rename;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'lastEvidenceAt') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'last_evidence_at') = 0
        AND (SELECT COUNT(*) FROM information_schema.TABLES
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight') = 1,
        'ALTER TABLE `user_preference_weight` ADD COLUMN `lastEvidenceAt` DATETIME NULL COMMENT ''最后一次证据时间''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_last_add FROM @sql;
EXECUTE stmt_pref_last_add;
DEALLOCATE PREPARE stmt_pref_last_add;

-- user_id -> userId（只在驼峰列为空时回填，避免覆盖已有归属）
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'userId') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'user_id') = 1,
        'UPDATE `user_preference_weight` SET `userId` = `user_id` WHERE `userId` IS NULL AND `user_id` IS NOT NULL',
        'SELECT 1'
    )
);
PREPARE stmt_pref_user_merge FROM @sql;
EXECUTE stmt_pref_user_merge;
DEALLOCATE PREPARE stmt_pref_user_merge;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'userId') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'user_id') = 1,
        'ALTER TABLE `user_preference_weight` CHANGE COLUMN `user_id` `userId` BIGINT NULL COMMENT ''用户ID''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_user_rename FROM @sql;
EXECUTE stmt_pref_user_rename;
DEALLOCATE PREPARE stmt_pref_user_rename;

-- preference_type -> preferenceType
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preferenceType') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preference_type') = 1,
        'UPDATE `user_preference_weight` SET `preferenceType` = `preference_type` WHERE `preferenceType` IS NULL AND `preference_type` IS NOT NULL',
        'SELECT 1'
    )
);
PREPARE stmt_pref_type_merge FROM @sql;
EXECUTE stmt_pref_type_merge;
DEALLOCATE PREPARE stmt_pref_type_merge;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preferenceType') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preference_type') = 1,
        'ALTER TABLE `user_preference_weight` CHANGE COLUMN `preference_type` `preferenceType` VARCHAR(64) NULL COMMENT ''偏好类型''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_type_rename FROM @sql;
EXECUTE stmt_pref_type_rename;
DEALLOCATE PREPARE stmt_pref_type_rename;

-- preference_key -> preferenceKey
SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preferenceKey') = 1
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preference_key') = 1,
        'UPDATE `user_preference_weight` SET `preferenceKey` = `preference_key` WHERE `preferenceKey` IS NULL AND `preference_key` IS NOT NULL',
        'SELECT 1'
    )
);
PREPARE stmt_pref_key_merge FROM @sql;
EXECUTE stmt_pref_key_merge;
DEALLOCATE PREPARE stmt_pref_key_merge;

SET @sql := (
    SELECT IF(
        (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preferenceKey') = 0
        AND (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_preference_weight' AND COLUMN_NAME = 'preference_key') = 1,
        'ALTER TABLE `user_preference_weight` CHANGE COLUMN `preference_key` `preferenceKey` VARCHAR(128) NULL COMMENT ''偏好键值''',
        'SELECT 1'
    )
);
PREPARE stmt_pref_key_rename FROM @sql;
EXECUTE stmt_pref_key_rename;
DEALLOCATE PREPARE stmt_pref_key_rename;
