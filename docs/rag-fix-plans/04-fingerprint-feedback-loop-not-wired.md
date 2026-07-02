# 修改计划 04：指纹效果反哺闭环未接入主链路

> 生成日期：2026-07-02
> 状态：✅ 已核实（2026-07-02）—— 复查发现该功能实际已实现，本计划的原始诊断有误，未做代码改动
> 优先级：中

## 1. 问题描述

`FingerprintFeedbackService.feedbackFingerprintQuality` 方法已经实现（用于根据生成结果质量回写指纹的 `usage_count`/`retrieval_success_score` 等统计字段，支撑"越用越准"的正反馈闭环），但在当前代码库中**未找到任何实际调用点**。也就是说，无论生成结果好坏、`CriticAgent` 评分高低，被召回引用过的指纹的效果统计字段永远不会被更新。

这使得"根据历史效果动态调整指纹优先级/置信度"这一设计意图完全没有落地，`DesignFingerprintServiceImpl`/`DesignKnowledgeIndex` 中所有排序逻辑只能依赖静态的 `confidence`/`priority`，无法感知真实使用效果。

## 2. 影响范围

- 指纹治理/排序完全依赖入库时的静态置信度，长期无法通过实际使用效果做动态调优。
- 低质量指纹即使被反复召回后生成效果不佳，也不会被降权或淘汰。
- 高质量指纹即使多次带来好评，也不会被提升优先级。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/service/FingerprintFeedbackService.java`（现有实现，待复用）
- `src/main/java/com/yu/yuaifather/orchestrator/GenerationOrchestrator.java`（V2 主链路，接入点候选）
- `src/main/java/com/yu/yuaifather/agent/orchestrator/OrchestratorAgent.java`（V1 主链路，接入点候选）
- `CriticAgent` 相关代码（评分来源，需先定位具体类路径）

## 4. 修改方案

### 4.1 确定接入时机

在生成流程的评分环节之后（`CriticAgent` 给出评分/通过判定之后），且能拿到本次生成实际引用的指纹 ID 列表（`DesignEvidence` 中应已包含召回的指纹 ID）时，调用反馈接口。

建议接入点：
- V2：`GenerationOrchestrator` 在 `CriticAgent` 评分完成后、`doRecord()` 落库追踪信息的同一阶段，追加调用 `fingerprintFeedbackService.feedbackFingerprintQuality(...)`。
- V1：`OrchestratorAgent` 的 Critic 反馈循环节点之后，同样位置追加调用。

### 4.2 接入示例（伪代码，具体参数以实际方法签名为准）

```java
List<Long> usedFingerprintIds = evidence.getItems().stream()
        .map(DesignEvidence.Item::getFingerprintId)
        .filter(Objects::nonNull)
        .toList();
if (!usedFingerprintIds.isEmpty()) {
    fingerprintFeedbackService.feedbackFingerprintQuality(usedFingerprintIds, criticScore);
}
```

需要先阅读 `FingerprintFeedbackService.feedbackFingerprintQuality` 的真实方法签名，确认其期望的入参（单条 vs 批量、评分范围、是否需要区分好评/差评阈值等），本计划暂不假设具体签名，实施时以现有实现为准，避免臆造接口。

### 4.3 失败降级

反馈调用应视为"副作用增强"，失败不应影响主生成流程返回结果，建议包裹 try-catch 或异步化处理（可参考 `PreferenceUpdateWorker.recordAndUpdate` 异步落库的写法）。

## 5. 验证方式

1. 触发一次生成请求，确认引用的指纹在 `design_fingerprint` 表中的 `usage_count`/`retrieval_success_score`（或对应字段，以实际表结构为准）在生成完成后发生了预期变化。
2. 构造一次低分 Critic 评价，验证对应指纹的效果分是否按预期下降（或被标记待复核）。
3. 验证反馈失败（如手动抛异常）不会导致整个生成请求失败或超时。

## 6. 风险评估

- **中风险**：需要先核实 `FingerprintFeedbackService` 现有实现的具体字段/表结构是否与当前 `design_fingerprint` 表结构一致（服务可能是早期设计遗留，字段命名/表结构可能已经漂移，需要实施前重新核对，不能假设可以直接调用）。
- 涉及主链路（生成主流程）新增副作用调用，需要严格保证异步/异常隔离，避免拖慢或阻断生成请求主路径。
- 建议先小流量验证反馈写入的准确性，再逐步依赖其结果调整召回排序权重（本计划仅覆盖"接入调用"，不包含"排序逻辑消费反馈结果"的后续工作，后者应作为独立计划）。

## 7. 实施记录

- **状态**：已核实——**原诊断不准确，功能实际已实现，无需新增代码**
- **核实时间**：2026-07-02
- **核实结论**：复查 `src/main/java/com/yu/yuaifather/agent/workers/CriticAgent.java` 发现，`handle()` 方法在 `ctx.getBlackboard().putCriticReport(report)` 之后，已存在如下调用点（约第 155-179 行）：
  ```java
  if (feedbackService != null) {
      DesignEvidence evidence = ctx.getBlackboard().getDesignEvidence();
      List<Long> fpIds = evidence.getItems().stream()
              .map(DesignEvidence.Item::getSourceId)
              .filter(id -> id != null && id.matches("\\d+"))
              .map(Long::parseLong).collect(Collectors.toList());
      double qualityScore = report.getScore() / 100.0;
      feedbackService.feedbackFingerprintQuality(fpIds, qualityScore, null, JsonExtractor.mapper().writeValueAsString(report));
  }
  ```
  由于 `CriticAgent` 同时被 V1（`OrchestratorAgent`）与 V2（`GenerationOrchestrator`）两条主链路复用（均调用 `criticAgent.handle(ctx)`），效果反哺闭环**在两条链路下均已生效**，并非"无任何调用点"。原计划的诊断结论有误（推测是分析阶段仅检索了两个 Orchestrator 类而未检索 Worker Agent 内部实现所致）。
- **本次未做的改动**：未新增/修改任何代码，避免重复接入或引入冗余调用。
- **已知遗留 TODO**（非本计划范围，供后续参考）：`CriticAgent` 调用 `feedbackFingerprintQuality` 时 `ragTraceId` 固定传 `null`（代码注释：`// TODO: 阶段三补填 — ragTraceId 当前无法获取，需在 DesignRetrieveAgent 中将 traceId 写入 Blackboard`），导致 `generation_rag_trace.critic_result` 字段始终无法回填。这是一个独立的小缺口，建议后续作为单独的修改计划处理，不纳入本次范围。
