# 当前架构

## 生成链路

`AppController → AppServiceImpl → GenerationPipelineRouter` 是唯一生成入口。

- `VUE_PROJECT` 且 `AGENT_ORCHESTRATOR_V2_ENABLED=true` 时可进入 V2。
- V2 按 `low_cost`、`standard`、`high_quality` 策略编排。
- `PageGenerateAgent` 使用专用页面生成契约，安全落盘后写入 `GeneratedArtifact`。
- V2 失败时按策略降级 Classic；Classic 在 V2 完整稳定性验证前保留。
- 旧副作用 GET SSE 断线后不自动重放，避免重复生成和扣额。

## 安全边界

- App 写操作通过 `AppAccessService` 校验 owner 或 admin。
- 外部网页抓取与截图通过 `SafeUrlValidator` 拒绝内网、环回、保留地址和不安全协议。
- 匿名分享使用 `PublicLayout`，不主动获取登录态。
- 预览 iframe 不授予 `allow-same-origin`，可视化编辑使用受校验的 `postMessage` 协议。
- 凭据只通过环境变量或本地忽略配置提供。

## 领域边界

- `app/application`：应用访问和应用级用例。
- `generation`、`agent`、`codegen`：V2 编排与 Classic 生成。
- `media/storage`、`media/screenshot`：对象存储、本地图片和截图。
- `service/design`：设计上下文、指纹和检索。
- 偏好权重由 `PreferenceUpdateWorker` 统一写入，异步任务使用有界 `preferenceExecutor`。

## 前端

- 受保护页面使用 `BasicLayout`，匿名分享使用 `PublicLayout`。
- 应用列表分页状态由 `useAppPage` 复用。
- `AppChatPage` 正逐步拆分独立交互组件。
