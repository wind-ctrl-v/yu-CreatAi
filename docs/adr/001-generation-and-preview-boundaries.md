# ADR-001：生成路由与预览安全边界

- 状态：已接受
- 日期：2026-07-23

## 决策

1. `GenerationPipelineRouter` 是唯一生成链路选择入口。
2. V2 使用 `agent.orchestrator.v2.enabled` 单一开关，失败时按策略降级 Classic。
3. 生成文件以 `GeneratedArtifact` 表达，Agent 不以临时事件文本代替文件产物。
4. 旧生成 GET SSE 不自动重放；后续任务化 API 将执行与订阅完全分离。
5. 生成预览视为不可信内容，iframe 不授予 `allow-same-origin`；跨窗口通信必须校验 source、origin、类型和字段长度。

## 结果

- 避免多入口路由和双开关产生不可预测组合。
- 避免 SSE 重连重复生成、写历史和扣额度。
- 避免生成内容读取主站 Cookie、Storage 或 DOM。
- Classic 暂时增加维护成本，但提供稳定回滚路径。
