-- =====================================================================
-- 阶段三：design_fingerprint 质量字段 ALTER
-- =====================================================================
ALTER TABLE `design_fingerprint`
    ADD COLUMN IF NOT EXISTS `extractConfidence`       DOUBLE       NULL     COMMENT '抽取置信度（LLM提取完整性，不表示设计好坏）',
    ADD COLUMN IF NOT EXISTS `designQualityScore`      DOUBLE       NULL     COMMENT '设计质量评分（页面设计本身的水平）',
    ADD COLUMN IF NOT EXISTS `retrievalSuccessScore`   DOUBLE       DEFAULT 0 COMMENT '检索成功评分（被召回后带来的生成效果好）',
    ADD COLUMN IF NOT EXISTS `usageCount`              INT          DEFAULT 0 COMMENT '被使用的次数',
    ADD COLUMN IF NOT EXISTS `positiveFeedbackCount`   INT          DEFAULT 0 COMMENT '正面反馈次数',
    ADD COLUMN IF NOT EXISTS `negativeFeedbackCount`   INT          DEFAULT 0 COMMENT '负面反馈次数',
    ADD COLUMN IF NOT EXISTS `extractorModel`          VARCHAR(128) NULL     COMMENT '提取器模型名称',
    ADD COLUMN IF NOT EXISTS `extractorPromptVersion`  VARCHAR(64)  NULL     COMMENT '提取器 prompt 版本',
    ADD COLUMN IF NOT EXISTS `fingerprintSchemaVersion` VARCHAR(64) NULL     COMMENT '指纹 schema 版本';
