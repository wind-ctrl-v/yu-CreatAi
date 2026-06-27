-- 007_user_plan_type.sql
-- 为 user 表新增 planType 列，已有行默认 FREE（后续管理后台/充值流程更新）
ALTER TABLE `user`
    ADD COLUMN `planType` VARCHAR(32) DEFAULT 'FREE' COMMENT '套餐类型：FREE/PRO/PREMIUM/ENTERPRISE/ADMIN' AFTER `userRole`;
