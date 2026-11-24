-- ============================================================
-- 024_generation_run_token_usage.sql
-- 为 generation_run 增加按步骤汇总的 LLM token 用量。
-- 计数在生成结束（或客户端取消）时写入；记账失败不阻断生成。
-- ============================================================

ALTER TABLE generation_run
    ADD COLUMN input_tokens BIGINT NULL COMMENT '已知输入 token 合计',
    ADD COLUMN output_tokens BIGINT NULL COMMENT '已知输出 token 合计',
    ADD COLUMN total_tokens BIGINT NULL COMMENT '已知总 token 合计',
    ADD COLUMN token_call_count INT NULL COMMENT '模型调用次数（含上游未返回 usage 的调用）',
    ADD COLUMN unknown_usage_calls INT NULL COMMENT '上游未返回 token usage 的调用次数',
    ADD COLUMN token_usage_json TEXT NULL COMMENT '按步骤汇总的 token JSON';
