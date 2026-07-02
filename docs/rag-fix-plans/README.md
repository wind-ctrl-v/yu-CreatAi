# RAG 系统修改计划索引

> 生成日期：2026-07-02
> 说明：本文件夹专门存放 RAG（设计指纹）系统在评估/流程梳理过程中发现的问题的**修改计划**。
> 配套文档：`docs/rag-system-evaluation.md`（架构评估）、`docs/rag-pipeline-flow.md`（流程详解）、`docs/rag-comprehensive-analysis.md`（综合分析报告）
>
> **实施状态更新（2026-07-02）**：编号 01-05 的 5 项计划已全部完成评估与处理，其中 4 项完成代码改动，1 项经核实为原诊断有误（功能已存在），详见各文件末尾"实施记录"小节。
> **新发现（2026-07-03）**：复查发现计划 01 的补丁被打在了实际未被调用的类上，详见计划 06。

## 计划列表

| 编号 | 文件 | 问题概述 | 优先级 | 状态 |
|---|---|---|---|---|
| 01 | [01-private-fingerprint-vector-sync.md](./01-private-fingerprint-vector-sync.md) | 用户私有指纹只落 MySQL，未同步到 PGvector 向量库，导致向量召回通道（VECTOR_3B）永远命中不到私有指纹 | 高 | ⚠️ 已实施但落在错误的类上，实际未生效（详见计划 06） |
| 02 | [02-structural-retrieval-authz-gap.md](./02-structural-retrieval-authz-gap.md) | 结构化降级召回通道（STRUCTURAL_3A）`listApproved()` 未按 `ownerUserId`/`visibility` 过滤，存在跨用户私有指纹泄露风险 | 高（安全） | ✅ 已实施 |
| 03 | [03-rag-trace-query-text-bug.md](./03-rag-trace-query-text-bug.md) | `RagTraceTool` 调用参数传递错位，`queryText` 位置实际传入的是 `channel` 值，导致追踪表无法还原真实检索文本 | 中 | ✅ 已实施 |
| 04 | [04-fingerprint-feedback-loop-not-wired.md](./04-fingerprint-feedback-loop-not-wired.md) | `FingerprintFeedbackService.feedbackFingerprintQuality` 已实现但未被任何调用点接入，效果反哺闭环形同虚设 | 中 | ✅ 已核实（原诊断有误，功能已在 `CriticAgent` 中实现） |
| 05 | [05-user-rating-feedback-not-implemented.md](./05-user-rating-feedback-not-implemented.md) | `PreferenceUpdateWorker` 文档声称支持用户评分事件反馈，但代码与枚举均未实现，评分信号被静默丢弃 | 低-中 | ✅ 已实施（修正文档，未接入前端不存在的功能） |
| 06 | [06-duplicate-user-fingerprint-ingest-service.md](./06-duplicate-user-fingerprint-ingest-service.md) | 用户私有指纹入库存在 `FingerprintIngestWorker`/`UserFingerprintIngestService` 双实现，计划 01 的向量同步补丁打在了未被调用的类上，生产链路实际仍未修复 | 高 | 待评审（未实施） |

## 使用约定

- 每份计划文件需包含：**生成日期、问题描述、影响范围、涉及文件、修改方案、验证方式、风险评估**七个板块，实施后追加**实施记录**第八板块。
- 计划文件仅描述"打算怎么改"，实施完成后在对应文件末尾追加"实施记录"小节（状态/日期/提交记录），而不是删除计划本身，以保留变更历史。
- 后续如有新发现的问题，按 `0N-短横线命名.md` 格式追加，并同步更新本索引表。
