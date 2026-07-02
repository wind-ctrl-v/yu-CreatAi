# 修改计划 03：RagTraceTool 参数传递错位（queryText 误传 channel）

> 生成日期：2026-07-02
> 状态：✅ 已实施（2026-07-02）
> 优先级：中

## 1. 问题描述

`GenerationOrchestrator.doRecord()` 中调用 `RagTraceTool.execute()` 记录检索追踪信息时，`TraceInput` 的 `queryText` 参数位实际传入的是 `evidence.getChannel()`（召回通道标识，如 `VECTOR_3B`/`STRUCTURAL_3A`），而不是真正的检索查询文本。

导致 `generation_rag_trace` 表落库后，`query_text` 字段里存的是通道名而非真实查询内容，**无法追溯"这次检索到底查了什么"**，严重削弱了 RAG 可观测性/问题排查能力。

## 2. 影响范围

- `generation_rag_trace` 表历史及新增数据中 `query_text` 字段语义错误。
- 依赖该字段做效果分析、A/B 对比、召回质量排查的场景（若存在）会得出错误结论。
- 不影响实际检索/生成结果，纯属**可观测性数据质量问题**，不影响线上功能正确性。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/orchestrator/GenerationOrchestrator.java`（`doRecord()` 方法，核心改动点）
- `src/main/java/com/yu/yuaifather/tool/system/RagTraceTool.java`（`TraceInput` record 定义，供核对字段顺序/命名）

## 4. 修改方案

### 4.1 核对 `TraceInput` 字段定义

先确认 `RagTraceTool.TraceInput` record 的字段顺序与命名（`queryText`、`channel` 各自的位置），避免修复时再次张冠李戴。

### 4.2 修正调用参数

在 `doRecord()` 中，将真正的查询文本（例如个性化查询构建结果 `PersonalizedQueryBuilder.build(...)` 的输出，或 `DesignEvidence` 中记录的原始 query 字段）传入 `queryText` 位，`channel` 位传入 `evidence.getChannel()`：

```java
// 修改前（错误）：
new RagTraceTool.TraceInput(..., evidence.getChannel() /* 误传给 queryText */, ...);

// 修改后：
new RagTraceTool.TraceInput(..., realQueryText, evidence.getChannel(), ...);
```

具体 `realQueryText` 的来源需要在实施时确认——若 `DesignEvidence` 当前未保留原始查询文本，需要先在 `DesignKnowledgeIndex.retrieve()` 内部把查询文本一并写入 `DesignEvidence`（新增一个字段，如 `queryText`），再由 `GenerationOrchestrator` 读取转发，而不是凭空重新拼装（保证记录与实际检索请求完全一致）。

### 4.3（可选）同时排查 V1 编排器

`OrchestratorAgent`（V1）中如有类似的 `RagTraceTool` 调用，需要同步检查是否存在同样的参数错位问题。

## 5. 验证方式

1. 单元测试：构造一次带明确 query 文本的检索请求，断言落库的 `generation_rag_trace.query_text` 等于实际查询文本，`channel` 字段等于实际召回通道（如 `VECTOR_3B`），两者不再混淆。
2. 手工验证：触发一次真实生成请求，查询 `generation_rag_trace` 表最新记录，人工核对 `query_text` 语义是否合理（应为可读的查询描述，而非 `VECTOR_3B` 这种枚举值）。

## 6. 风险评估

- **风险低**：为参数传递修正，不涉及表结构变更，不影响主流程功能，仅修正落库数据字段语义。
- 需注意历史脏数据：修复后新写入的数据是正确的，但历史 `query_text` 字段仍是错误值，如需要做历史数据分析，应在分析文档中注明"某时间点之前的 query_text 字段不可信"，不建议做历史数据回填（无法逆向还原原始查询文本）。

## 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-02
- **改动内容**：
  1. `DesignEvidence` 新增 `queryText` 字段，用于携带"本次检索实际使用的查询文本"，与 `channel`（召回通道标识）语义解耦。
  2. `DesignKnowledgeIndex.tryVectorRetrieveWithPersonalization()` 新增 `String[] queryTextOut` 出参，将内部构建的查询文本（`PersonalizedQueryBuilder.build(...)` 或 `EmbeddingService.buildQueryText(...)`）回传给调用方。
  3. `DesignKnowledgeIndex` 两个 `retrieve(...)` 重载在构建返回的 `DesignEvidence` 时填充 `queryText`：向量通道命中时使用回传值；未命中/未开启向量检索（STRUCTURAL_3A / STATIC_FALLBACK）时兜底为 `EmbeddingService.buildQueryText(styleProfile, queryTags)`，保证任何通道下 `queryText` 都是"本次检索意图"的可读文本，而非通道枚举值。
  4. `GenerationOrchestrator.doRecord()` 中 `RagTraceTool.TraceInput` 的 `queryText` 位改为传入 `evidence.getQueryText()`，`channel` 位保持传入 `evidence.getChannel()`，两者不再混淆。
- **涉及文件**：`DesignEvidence.java`、`DesignKnowledgeIndex.java`、`GenerationOrchestrator.java`。
- **排查结论（4.3）**：V1 `OrchestratorAgent` 全流程中未发现任何 `RagTraceTool` 调用（V1 未接入 RAG 追踪落库能力），不存在同样的参数错位问题，无需同步修改。
- **遗留事项**：历史错误数据不做回填，与方案第 6 节风险评估结论一致。
