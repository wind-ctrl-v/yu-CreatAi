# 可观测性提升改造计划

> 生成日期：2026-08-02
> 基于代码库 commit `ee95051`（对话上下文 token 治理已合入）
> 定位：日志 / 指标 / 追踪三层可观测性建设。优先解决一个具体工程问题：**LLM Token 成本完全黑盒**。不做大而全的 APM 引入，每一步都复用现有收口点。

---

## 目录

1. [问题陈述](#1-问题陈述)
2. [现状盘点](#2-现状盘点)
3. [方案总览](#3-方案总览)
4. [阶段一：Token 用量观测（P0）](#4-阶段一token-用量观测p0)
5. [阶段二：应用指标暴露（P1）](#5-阶段二应用指标暴露p1)
6. [阶段三：日志串联 MDC（P2）](#6-阶段三日志串联-mdcp2)
7. [阶段四（可选）：分布式链路追踪](#7-阶段四可选分布式链路追踪)
8. [落地顺序与验收](#8-落地顺序与验收)
9. [关键类索引](#9-关键类索引)

---

## 1. 问题陈述

LLM 调用是本项目**主要变动成本**（DeepSeek 按 token 计费），且生成链路长（单轮 VUE 生成含多次工具往返、V2 流水线含多个 Agent 各自调模型）。但当前：

- **Token 用量零记录**：每次模型调用的 input/output token、模型耗时无任何落点，日报/周报成本全靠云控制台估算，无法按应用 / 用户 / 链路归因
- **无应用指标**：JVM、HTTP QPS、连接池、生成成功率/耗时分位数全黑盒，撞容量前无预警
- **日志无法串联**：纯文本日志无 requestId / generationId / appId 占位，一次生成故障要在数万行日志里肉眼捞针
- **无告警**：生成成功率跌零、token 消耗异常飙升，只能等用户反馈

上一轮的"记忆压缩 token 治理"解决了**输入侧膨胀**问题，本轮解决**用量看不见**问题，两者闭环：压缩效果（`记忆压缩 memoryId={} {}→{} 字符`）需要指标化才能验证收益。

---

## 2. 现状盘点

| 维度 | 现状 | 缺口 |
|---|---|---|
| 业务追踪 | ✅ `generation_run` 表 + `GenerationRunService`：generationId、appId、userId、模式、链路、状态（RUNNING/SUCCESS/FALLBACK_SUCCESS/FAILED）、耗时、降级原因 | 无 token 字段；无指标导出 |
| 追踪 ID | ✅ `generationId = gen_UUID` 贯穿 V2/Classic，SSE 首帧下发 | 未进日志 MDC |
| 日志 | ⚠️ SLF4J 纯文本，关键路径有 log（压缩打点、生成失败等） | 无 requestId 串联、无结构化 |
| 应用指标 | ❌ pom 无 actuator / micrometer | 全缺 |
| Token 观测 | ❌ 无 | **本次核心** |
| 链路追踪 | ❌ 无 | 可选，优先级低 |
| 告警 | ❌ 无 | 依赖指标先行 |

**可复用的技术资产：**

- 项目 vendored 的 `StreamingChatModel` 已支持 `ChatModelListener`（onRequest / onResponse / onError）
- 三个模型实例：`chatModel`、`openAiStreamingChatModel`（langchain4j starter 自动配置，**自动收集所有 `ChatModelListener` Bean**）；`reasoningStreamingChatModel`（`ReasoningStreamingChatModelConfig` 手工 build，需显式 `.listeners(...)`）
- `ChatResponse.tokenUsage()` 提供 `inputTokenCount / outputTokenCount / totalTokenCount`
- 收口点集中：`GenerationRunService`（生成全生命周期）、`CompressingChatMemoryStore`（压缩）、限流 AOP（拒绝）

---

## 3. 方案总览

```
阶段一  Token 用量观测    P0   ← 成本黑盒，收益最直接
阶段二  应用指标暴露      P1   ← Actuator + Prometheus，指标化阶段一成果
阶段三  日志串联 MDC      P2   ← 排查效率，成本极低
阶段四  分布式链路追踪    可选  ← 单体应用，观察后再定
```

设计约束（全阶段遵守）：

- **观测代码零业务侵入**：只做收口点埋点，不改业务方法签名
- **容错原则**：打点失败仅 log.warn，绝不阻断生成（对齐 GenerationRunService 既有约定）
- **线程安全**：流式 LLM 回调在 HTTP client 线程，MDC 不跨线程传播，**listener 内禁止依赖 MDC**

---

## 4. 阶段一：Token 用量观测（P0）

### 4.1 新增组件

| 类 | 职责 |
|---|---|
| `TokenUsageChatModelListener`（`@Component`，`implements ChatModelListener`） | 统一监听全部模型调用，记录 token 用量 / 耗时 / 模型名 |
| `LlmMetrics`（`@Component`） | Micrometer 指标封装（阶段二启用，阶段一可只打日志，二选一不重复建设） |

### 4.2 listener 逻辑

```java
public void onResponse(ChatModelResponse response) {
    TokenUsage usage = response.chatResponse().tokenUsage();
    log.info("LLM 调用 model={} input={} output={} total={} 耗时={}ms",
            model, usage.inputTokenCount(), usage.outputTokenCount(),
            usage.totalTokenCount(), elapsed);
}
```

- 耗时：onRequest 时按请求对象 identity 存 `ConcurrentHashMap` 起始时间，onResponse/onError 取出后移除（防止泄漏）
- onError：记录模型与错误类型，超时 / 限流 / context_length_exceeded 分类计数

### 4.3 注册方式（两条路径，一次验证）

| 模型实例 | 注册方式 |
|---|---|
| `chatModel` / `openAiStreamingChatModel` | starter 自动收集 `ChatModelListener` Bean，声明即生效 |
| `reasoningStreamingChatModel` | `ReasoningStreamingChatModelConfig:30` 手工 `.listeners(tokenUsageListener)` |

### 4.4 取舍说明

- `generation_run` 表**暂不扩 token 字段**：流式工具调用单轮含多次模型调用，按 generationId 聚合需要额外归因链路，阶段一先日志 + 指标看总量趋势；确有按应用归因需求再开独立迭代
- 不改 `AiCodeGeneratorService` 任何接口签名

### 4.5 改动清单

| 文件 | 改动 |
|---|---|
| 新增 `observability/TokenUsageChatModelListener` | onRequest/onResponse/onError 打点 |
| `ReasoningStreamingChatModelConfig` | `.listeners(...)` 注入 |
| 其余 | **零改动** |

---

## 5. 阶段二：应用指标暴露（P1）

### 5.1 依赖与端点

- pom 新增：`spring-boot-starter-actuator`、`micrometer-registry-prometheus`
- yml 新增 `management.*`：暴露 `health,info,prometheus`（随 `context-path` 挂在 `/api/actuator/*`）
- **鉴权**：prometheus 端点加管理员校验或仅内网可达，禁止公网裸露

### 5.2 自定义业务指标（全部复用既有收口点）

| 指标 | 类型 | 埋点位置 | 标签 |
|---|---|---|---|
| `generation.duration` | Timer | `GenerationRunService.finish()` | codeGenType / pipeline / mode / status |
| `generation.total` | Counter | 同上 | status（SUCCESS / FALLBACK_SUCCESS / FAILED） |
| `generation.fallback` | Counter | `GenerationPipelineRouter` 降级分支 | from / to |
| `memory.compress.chars` | DistributionSummary | `CompressingChatMemoryStore.updateMessages` | before / after 两个系列 |
| `llm.tokens` | Counter | 阶段一 listener | model / direction（input/output） |
| `ratelimit.rejected` | Counter | 限流 AOP 拒绝分支 | interface |

### 5.3 看板与告警（Grafana 配置，非代码）

- 看板：生成成功率、P50/P95 生成耗时（分类型）、日 token 消耗曲线、记忆压缩率、限流触发
- 告警规则建议：5 分钟生成成功率 < 90%；日 token 消耗环比 > 3 倍；actuator health down

---

## 6. 阶段三：日志串联 MDC（P2）

1. **新增 `MdcFilter`**（`OncePerRequestFilter`，最高优先级）：生成 `requestId`（UUID 前 8 位）入 MDC，写响应头 `X-Request-Id`，finally 中 `MDC.clear()`
2. **生成链路埋点**：`GenerationPipelineRouter.route()` 开头 `MDC.put("generationId", ...)`、`MDC.put("appId", ...)`，流终止（doFinally）清除
3. **日志格式**：application.yml 加 `logging.pattern.level: "%5p [requestId=%X{requestId}] [generationId=%X{generationId}] [appId=%X{appId}]"`，无 MDC 时占位为空不影响阅读
4. **可选**：`logstash-logback-encoder` 输出 JSON 日志，为 Loki / ELK 采集铺路（确认有采集端再上）

---

## 7. 阶段四（可选）：分布式链路追踪

- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` → Tempo / Jaeger
- 单体应用 + 已有 generationId 业务追踪，边际收益低；**建议阶段一~三跑稳后按需评估**，不一次上齐

---

## 8. 落地顺序与验收

```
阶段一（P0，成本可见）→ 阶段二（P1，指标化）→ 阶段三（P2，排查提效）→ 阶段四（按需）
```

### 验收标准

| 项 | 标准 |
|---|---|
| Token 可见 | 任意一次生成（HTML / MULTI_FILE / VUE / 增量编辑）均产出 LLM 调用日志，含 input/output/total token 与耗时 |
| 全模型覆盖 | 三个模型实例调用均被 listener 捕获（含 reasoningStreamingChatModel） |
| 指标可拉取 | `/api/actuator/prometheus` 返回 generation/memory/llm 系列指标；未登录/非管理员访问被拒 |
| 业务零回归 | 生成、增量编辑、V2 流水线行为与改造前一致；打点代码不抛异常入主流程 |
| 日志可串联 | 同一请求的日志含相同 requestId；生成链路日志含 generationId |

---

## 9. 关键类索引

| 类 / 文件 | 作用 | 本计划涉及 |
|---|---|---|
| `config/ReasoningStreamingChatModelConfig` | 手工构建推理流式模型 | 阶段一（注册 listener） |
| `codegen/memory/CompressingChatMemoryStore` | 记忆压缩打点 | 阶段二（压缩率指标） |
| `service/generation/GenerationRunService` | 生成全生命周期收口 | 阶段二（耗时/成功率指标） |
| `service/generation/GenerationPipelineRouter` | 链路与降级收口、generationId 源头 | 阶段二（降级指标）、阶段三（MDC 埋点） |
| `ratelimiter/`（限流 AOP） | 接口限流拒绝分支 | 阶段二（限流指标） |
| `model/entity/GenerationRun` + `generation_run` 表 | 生成运行记录 | 本计划不改表结构（见 §4.4 取舍） |
| pom.xml / application.yml | 依赖与配置 | 阶段二 / 阶段三 |
