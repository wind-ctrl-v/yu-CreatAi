# CreatAi RAG 系统综合分析报告

> 生成日期：2026-07-02
> 最近更新：2026-07-02（问题清单 5 项均已处理完毕，详见第三节"实施状态"列及 `docs/rag-fix-plans/` 各文件"实施记录"小节）
> 配套文档：
> - `docs/rag-pipeline-flow.md`（流程细节全量展开版，含代码位置引用）
> - `docs/rag-system-evaluation.md`（架构维度评分与设计合理性评估）
> - `docs/rag-fix-plans/`（本报告所列每个问题对应的详细修改计划文件夹）
>
> **说明**：本节以下"入库/检索流程链路总览"仍保留问题被发现时（修复前）的原始描述作为背景，
> 各流程图中标注的 ⚠️ 缺陷已在第三节"问题清单"中标记为已解决，具体改动见对应修改计划文件。

---

## 一、入库流程链路（Ingestion）总览

当前系统存在 **2 条独立的入库来源**，最终汇合到 MySQL `design_fingerprint` 表统一治理，再由 `APPROVED` 状态触发向量同步。

### 链路 A：精选页面爬取入库（全局知识，离线定时）

```
FingerprintCrawlScheduler（@Scheduled，默认30分钟/次，agent.fingerprint.crawl.enabled 默认 false）
  → 抓取已部署的精选应用 HTML
  → DesignSourcePreprocessor（SSRF 内网段黑名单过滤 + HTML 摘要/剥离 script）
  → DesignExtractorAgent（LLM 离线提炼六层指纹：tokens/layout/components/interaction/antiPatterns/tone）
  → FingerprintParser（schema 版本校验 + validate() + computeConfidence() 四维加权 + signature() MD5去重）
  → DesignFingerprintService.saveCandidate(dto, selfGenerated=false)
      sourceType = EXTRACTED_FINGERPRINT
      status = CANDIDATE
  → 人工 review：approve(id) / reject(id, remark)
  → approve 成功 → VectorSyncWorker.syncOnApprove()
      → EmbeddingService.buildSemanticText(dto) + embed()
      → DesignFingerprintVectorRepository.upsert() 写入 design_fingerprint_vector（PGvector, is_active=true）
```

### 链路 B：用户私有指纹反哺入库（个人知识，用户行为触发）

```
GenerationOrchestrator.doRecord()（V2 编排器生成完成后触发）
  → UserFingerprintIngestService.ingestUserFingerprint(pageId, userId, projectId)
      （agent.fingerprint.user-ingest.enabled 默认 false；★ 生产唯一入口，详见问题 #6）
  → buildSurfaceFingerprint()：纯正则规则提取（无 LLM），扫描 CSS 关键字判定配色/圆角/阴影，
     扫描 HTML 关键字判定布局骨架，confidence 基础分 0.6 + 加分
  → confidence < 0.65（USER_PRIVATE_CONFIDENCE_GATE）则丢弃
  → saveAsUserPrivate()：直接 designFingerprintService.save(entity)
      sourceType = USER_PRIVATE_FINGERPRINT
      status = APPROVED（★ 直接落 APPROVED，不经过 saveCandidate/approve 流程）
      sourceScope = USER, ownerUserId = userId, visibility = PRIVATE
  → ✅ [已修复于 2026-07-03] 补充调用 VectorSyncWorker.syncOnApprove(saved)（问题 #1 修正后真正生效，详见问题 #6）
```

> **补充说明**：2026-07-02 首次修复问题 #1 时，补丁被打在了 `worker.FingerprintIngestWorker`（一个未被任何调用点引用的孤儿类）上，`GenerationOrchestrator` 实际注入调用的是 `service.UserFingerprintIngestService`（另一个逻辑几乎完全相同的类）。2026-07-03 复查发现后，已将补丁迁移到 `UserFingerprintIngestService`，并删除了 `FingerprintIngestWorker` 及另一处同类型孤儿类 `service.VectorSyncService`（详见问题 #6）。

### 治理状态机（两条链路汇合后的统一治理）

```
CANDIDATE ──approve()──▶ APPROVED ──archive()──▶ ARCHIVED
    │                        │
  reject()                reject()
    ▼                        ▼
REJECTED                 REJECTED
```
- `approve()` 前置校验：`confidence >= CONFIDENCE_GATE(0.7)`；自产反哺来源（`SELF_FED_FINGERPRINT`）额外校验多样性配额 `MAX_SELF_FED_RATIO=0.3`（每 profile 下自产占比不得超 30%）。
- `reject()`/`archive()` 会调用 `vectorSyncWorker.deactivateOnRejectOrArchive()` 软失效向量记录（`is_active=false`，不物理删除，保留审计）。
- 用户私有指纹（链路 B）**绕开了整套状态机**，直接以 `APPROVED` 落库，不受 `CONFIDENCE_GATE`/多样性配额约束。

### 入库链路小结表

| 维度 | 链路 A：精选爬取 | 链路 B：用户私有反哺 |
|---|---|---|
| 触发方式 | 定时调度 | 用户行为异步触发 |
| 提炼方式 | LLM 六层指纹提炼 | 正则规则表面提取 |
| 初始状态 | CANDIDATE（需人工审核） | 直接 APPROVED（无审核） |
| 置信度门槛 | 0.7（approve 时校验） | 0.65（入库前校验，审核环节被跳过） |
| 向量同步 | 有（approve 触发） | ✅ 有（2026-07-03 起真正生效，入库后立即触发 `syncOnApprove`，问题 #1/#6 已修复） |
| 权限范围 | GLOBAL / PUBLIC | USER / PRIVATE |
| 多样性配额 | 有（自产占比≤30%） | 无 |

---

## 二、检索流程链路（Retrieval）总览

当前系统保留 **V2 `GenerationOrchestrator`** 这一套实时编排器，默认关闭（`agent.*.enabled=false`）。V1 `OrchestratorAgent` 已移除；历史分析中的 V1 流程仅作为架构演进记录。

### 当前编排器：`GenerationOrchestrator` + `DesignContextAssembler`

```
GenerationOrchestrator（按 GenerationMode 分三档：low_cost / standard / high_quality）
  → DesignContextAssembler 统一装配 7 个 System Tool → DesignContext
      （三档模式下启用的 Tool 集合/配额不同，high_quality 档启用全部检索+重排+压缩能力）
  → ContextCompressTool 压缩为 PromptContext.fullPrompt（含字符截断配置）注入 CoderAgent/StyleAgent
  → GenerationRecordTool 记录 generated_page 快照
  → doRecord() 调用 RagTraceTool 落追踪（✅ [已修复于 2026-07-02] queryText 参数错位问题，详见问题 #3）
```

### 核心召回：`DesignRetrieveTool` → `DesignKnowledgeIndex.retrieve()` 三档降级

```
VECTOR_3B（向量语义召回，agent.retrieve.vector.enabled）
  ├─ 有个性化重载：tryVectorRetrieveWithPersonalization()
  │    PersonalizedQueryBuilder.build() 拼装含用户偏好的查询文本
  │    → EmbeddingService.embed() → vectorRepo.search(embedding, styleProfile, topK, minSimilarity, userId)
  │    → SQL 权限过滤：(source_scope='GLOBAL' OR owner_user_id=?)   ✅ 已正确实现
  └─ 命中则 channel=VECTOR_3B，跳过降级
        │
        ▼ 未命中
STRUCTURAL_3A（结构化降级召回）
  fingerprintService.listApproved(styleProfile, limit, currentUserId)
  → ✅ [已修复于 2026-07-02] 按 status + styleProfile 过滤，且已补充 ownerUserId/visibility 权限过滤（问题 #2）
  → softMatchScore() 关键词软匹配打分
        │
        ▼ 仍无结果
STATIC_FALLBACK（内置默认规范）
  DesignProfileResolver.buildBuiltinDefaultSpec()
```

召回结果经 `applyDiversityFilter()`（私池指纹≤40%，精选指纹≥30%）→ `DesignRerankService.rerank()` 9 维加权精排：

```
final_score = 0.25·semantic + 0.15·style + 0.15·component + 0.10·layout + 0.10·quality
            + 0.15·userPref + 0.15·projectBrand
            - 0.20·dislikePenalty - 0.20·forbiddenPenalty - 0.10·duplicatePenalty
```

### 用户偏好闭环（贯穿检索链路的输入源）

```
用户行为事件（保存/导出/删除/改色/加删组件） → AppController
  → PreferenceUpdateWorker.recordAndUpdate()
      规则表：page_saved(+0.1) / page_exported(+0.2) / page_deleted(-0.1) / page_regenerated(-0.05)
             / color_changed(±0.2) / component_added(+0.1) / component_removed(-0.2) / layout_changed(±0.1)
      ✅ [核实于 2026-07-02] user_rating_5/user_rating_1_2 前端无对应埋点入口，已修正类 Javadoc 移除失实描述（问题 #5）
  → upsertWeight()：行锁 UPDATE + 0 行回退 INSERT + 唯一约束冲突重试（原子 upsert）
  → 每 10 次事件触发 maybeRefreshProfileSummary()：取 Top5 喜欢 + Top3 反感重算画像摘要
  → 回灌：UserPreferenceService.loadUserDesignContext() 供下一次检索的 PersonalizedQueryBuilder / DesignRerankService 使用
```

另有 `PromptPreferenceExtractor`（LLM 从用户 Prompt 中提取偏好标签，仅正向 +0.15，不做负向提取）作为补充信号源。

### 效果反哺（设计闭环）

```
CriticAgent 评分 → FingerprintFeedbackService.feedbackFingerprintQuality()
                    回写指纹 usage_count / retrieval_success_score
                → ✅ [核实于 2026-07-02] 复查发现该调用点实际已存在于 CriticAgent.handle() 内部
                    （V1/V2 均复用 CriticAgent，故两条链路均已生效），原问题 #4 诊断有误
```

---

## 三、当前 RAG 系统问题清单

| # | 问题 | 所在链路 | 严重程度 | 实施状态 | 详细计划 |
|---|---|---|---|---|---|
| 1 | 用户私有指纹只落 MySQL，未触发 `VectorSyncWorker.syncOnApprove()`，导致 VECTOR_3B 通道永远召回不到私有指纹 | 入库 B / 检索核心召回 | 高 | ✅ 已修复（2026-07-02 首次实施，2026-07-03 修正生效，详见问题 #6） | [rag-fix-plans/01](./rag-fix-plans/01-private-fingerprint-vector-sync.md) |
| 2 | `listApproved()`（STRUCTURAL_3A 降级通道）未按 `ownerUserId`/`visibility` 过滤，存在跨用户私有指纹越权泄露风险 | 检索核心召回 | 高（安全） | ✅ 已修复（2026-07-02） | [rag-fix-plans/02](./rag-fix-plans/02-structural-retrieval-authz-gap.md) |
| 3 | `GenerationOrchestrator.doRecord()` 调用 `RagTraceTool` 时 `queryText` 参数位误传 `channel` 值，追踪表无法还原真实查询文本 | 检索 V2 追踪落库 | 中 | ✅ 已修复（2026-07-02） | [rag-fix-plans/03](./rag-fix-plans/03-rag-trace-query-text-bug.md) |
| 4 | `FingerprintFeedbackService.feedbackFingerprintQuality` 已实现但无任何调用点，效果反哺闭环未接入主链路 | 效果反哺 | 中 | ✅ 已核实（2026-07-02）：复查发现原诊断有误，`CriticAgent` 内部已实际接入该调用，V2 生效，未做代码改动。2026-07-03 另有独立提交打通了 `ragTraceId` 回填链路（详见补充说明） | [rag-fix-plans/04](./rag-fix-plans/04-fingerprint-feedback-loop-not-wired.md) |
| 5 | `PreferenceUpdateWorker` 文档声称支持 `user_rating_5`/`user_rating_1_2` 评分反馈，但 `switch` 分支与 `EventTypeEnum` 均未实现，评分信号被静默丢弃 | 用户偏好闭环 | 低-中 | ✅ 已处理（2026-07-02）：前端确认无评分入口，已修正 Javadoc 移除失实描述，未新增死代码 | [rag-fix-plans/05](./rag-fix-plans/05-user-rating-feedback-not-implemented.md) |
| 6 | 用户私有指纹入库存在 `FingerprintIngestWorker`/`UserFingerprintIngestService` 双实现，问题 #1 的补丁误打在未被调用的类上，生产链路实际未修复；同一模式的孤儿类 `VectorSyncService` 亦被发现 | 入库 B | 高 | ✅ 已修复（2026-07-03）：补丁迁移至生产实际调用的 `UserFingerprintIngestService`，删除 `FingerprintIngestWorker`/`VectorSyncService` 两处死代码 | [rag-fix-plans/06](./rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md) |

补充说明（非缺陷，架构现状）：
- V1 `OrchestratorAgent` 已删除，实时生成链路已收敛为 V2 `GenerationOrchestrator`；历史 V1 设计说明仅保留在架构演进文档中。
- 用户私有指纹入库（链路 B）完全绕开 `CONFIDENCE_GATE`/多样性配额/人工审核，这是**当前设计的有意选择**（个人数据无需审核），但需要注意问题 #1 修复后，私有指纹已首次进入向量召回，其质量把控完全依赖 `USER_PRIVATE_CONFIDENCE_GATE=0.65` 这一道低门槛，建议后续观察实际召回质量。
- 问题 #4 复查中发现的遗留 TODO（`ragTraceId` 固定传 `null`）已由另一次独立提交（`80e9446`）打通：`Blackboard` 新增 `ragTraceId` 读写，`GenerationOrchestrator` 捕获 `RagTraceTool.execute()` 返回值写入 Blackboard，`CriticAgent` 读取并回填 `critic_result`。同一提交还将 Embedding 模型从本地哈希兜底切换为智谱 AI `embedding-2`（1024维语义模型），解决了此前评估中标注的"默认 embedding 不具备语义能力"这一 P0 风险点。
- 问题 #6 的根因（同一功能在 `worker`/`service` 两个包下各有一份实现，且历史上出现过两次）提示项目中可能还有第三例类似情况，建议后续做一次全局排查（搜索所有 Javadoc 中含"已重命名为"/"改名重构而来"关键字的类）。

---

## 四、修改计划总览

所有问题的详细修改方案（问题描述/影响范围/涉及文件/修改步骤/验证方式/风险评估）已拆分至独立文件，存放于 [`docs/rag-fix-plans/`](./rag-fix-plans/README.md) 文件夹。**6 项问题已全部处理完毕**（5 项完成代码改动并验证，1 项经复查确认原诊断有误、功能已存在），各文件末尾均已追加"实施记录"小节：

1. [私有指纹向量同步补全](./rag-fix-plans/01-private-fingerprint-vector-sync.md) —— ✅ 已实施并于 2026-07-03 修正：补丁最终落在 `UserFingerprintIngestService`（生产实际调用）
2. [结构化召回权限隔离修复](./rag-fix-plans/02-structural-retrieval-authz-gap.md) —— ✅ 已实施：`listApproved` 新增 `currentUserId` 参数与权限过滤，`DesignKnowledgeIndex` 两处调用点同步改造
3. [RagTraceTool 参数错位修正](./rag-fix-plans/03-rag-trace-query-text-bug.md) —— ✅ 已实施：`DesignEvidence` 新增 `queryText` 字段并贯穿传递至 `RagTraceTool`
4. [效果反哺闭环接入](./rag-fix-plans/04-fingerprint-feedback-loop-not-wired.md) —— ✅ 已核实：功能已在 `CriticAgent` 中实现，未做代码改动
5. [用户评分反馈补全或文档修正](./rag-fix-plans/05-user-rating-feedback-not-implemented.md) —— ✅ 已实施：确认前端无评分入口后，采纳文档修正方案
6. [用户私有指纹入库双实现清理](./rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md) —— ✅ 已实施：向量同步补丁迁移至生产实际调用类，清理两处孤儿死代码

**验证说明**：当前开发环境无可用 JDK/Maven（`mvn`/`javap` 均不可用），本次改动未能通过完整编译与单元测试验证；已通过人工代码审阅、全局文本搜索确认无残留引用等方式尽力保证正确性，**建议在具备编译环境后执行一次完整构建（`./mvnw compile`）与各计划第 5 节所列验证步骤**，确认无误后再发布上线。
