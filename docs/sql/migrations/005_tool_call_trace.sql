-- ============================================================
-- 005_tool_call_trace.sql
-- 阶段六：Tool 调用追踪表
-- ============================================================

CREATE TABLE IF NOT EXISTS tool_call_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(128) NOT NULL COMMENT '请求ID',
    generation_id BIGINT NULL COMMENT '生成记录ID',

    caller_type VARCHAR(32) NOT NULL COMMENT '调用者类型：ORCHESTRATOR/CONTEXT_ASSEMBLER/AGENT/WORKER',
    caller_name VARCHAR(128) NOT NULL COMMENT '调用者名称',
    tool_type VARCHAR(32) NOT NULL COMMENT '工具类型：SYSTEM_TOOL/AGENT_TOOL',
    tool_name VARCHAR(128) NOT NULL COMMENT '工具名称',

    input_summary JSON NULL COMMENT '输入摘要（JSON）',
    output_summary JSON NULL COMMENT '输出摘要（JSON）',

    success BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否成功',
    error_message TEXT NULL COMMENT '错误信息',
    latency_ms BIGINT NULL COMMENT '执行耗时（毫秒）',

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_request_id (request_id),
    INDEX idx_generation_id (generation_id),
    INDEX idx_caller_type (caller_type),
    INDEX idx_tool_name (tool_name),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool 调用追踪表';
