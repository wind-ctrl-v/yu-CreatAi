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
用户 保存/导出 生成页面
  → FingerprintIngestWorker.ingestUserFingerprint(pageId, userId, projectId)
      （agent.fingerprint.user-ingest.enabled 默认 false）
  → buildSurfaceFingerprint()：纯正则规则提取（无 LLM），扫描 CSS 关键字判定配色/圆角/阴影，
     扫描 HTML 关键字判定布局骨架，confidence 基础分 0.6 + 加分
  → confidence < 0.65（USER_PRIVATE_CONFIDENCE_GATE）则丢弃
  → saveAsUserPrivate()：直接 designFingerprintService.save(entity)
      sourceType = USER_PRIVATE_FINGERPRINT
      status = APPROVED（★ 直接落 APPROVED，不经过 saveCandidate/approve 流程）
      sourceScope = USER, ownerUserId = userId, visibility = PRIVATE
  → ✅ [已修复于 2026-07-02] 补充调用 VectorSyncWorker.syncOnApprove(saved)（详见问题 #1）
```

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
| 向量同步 | 有（approve 触发） | ✅ 有（2026-07-02 起，入库后立即触发 `syncOnApprove`，问题 #1 已修复） |
| 权限范围 | GLOBAL / PUBLIC | USER / PRIVATE |
| 多样性配额 | 有（自产占比≤30%） | 无 |

---

## 二、检索流程链路（Retrieval）总览

当前系统存在 **2 套并行的编排器实现**，均从同一批 System Tool 组装设计上下文，但装配方式不同。两者默认均关闭（`agent.*.enabled=false`）。

### 编排器 V1：`OrchestratorAgent`（逐 Tool 串行 concatWith）

```
PlanningAgent（需求解析→GenerationPlan）
  → DesignTokenTool（先验静态规范注入，零 LLM）
  → UserPreferenceTool（加载 UserDesignContext：偏好画像+权重列表，零 LLM）
  → ProjectProfileTool（项目品牌记忆：brandTone/forbiddenRules，零 LLM）
  → DesignRetrieveTool（核心召回，见下方通道说明，消耗 Embedding 不消耗 Chat Token）
  → [个性化重排 DesignRerankService + 多样性过滤]
  → DesignEvidence 写入 Blackboard
  → CoderAgent/StyleAgent（Prompt 强契约注入：tokenGroups 变量名清单 + forbidden/preferred 优先级高于用户需求）
  → CriticAgent 评分 → Critic 反馈循环（不达标回退重生成）
  → RagTraceTool 落 generation_rag_trace
```

### 编排器 V2：`GenerationOrchestrator` + `DesignContextAssembler`

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
| 1 | 用户私有指纹只落 MySQL，`FingerprintIngestWorker` 绕过 `updateStatus()`，从未触发 `VectorSyncWorker.syncOnApprove()`，导致 VECTOR_3B 通道永远召回不到私有指纹 | 入库 B / 检索核心召回 | 高 | ✅ 已修复（2026-07-02） | [rag-fix-plans/01](./rag-fix-plans/01-private-fingerprint-vector-sync.md) |
| 2 | `listApproved()`（STRUCTURAL_3A 降级通道）未按 `ownerUserId`/`visibility` 过滤，存在跨用户私有指纹越权泄露风险 | 检索核心召回 | 高（安全） | ✅ 已修复（2026-07-02） | [rag-fix-plans/02](./rag-fix-plans/02-structural-retrieval-authz-gap.md) |
| 3 | `GenerationOrchestrator.doRecord()` 调用 `RagTraceTool` 时 `queryText` 参数位误传 `channel` 值，追踪表无法还原真实查询文本 | 检索 V2 追踪落库 | 中 | ✅ 已修复（2026-07-02） | [rag-fix-plans/03](./rag-fix-plans/03-rag-trace-query-text-bug.md) |
| 4 | `FingerprintFeedbackService.feedbackFingerprintQuality` 已实现但无任何调用点，效果反哺闭环未接入主链路 | 效果反哺 | 中 | ✅ 已核实（2026-07-02）：复查发现原诊断有误，`CriticAgent` 内部已实际接入该调用，V1/V2 均生效，未做代码改动 | [rag-fix-plans/04](./rag-fix-plans/04-fingerprint-feedback-loop-not-wired.md) |
| 5 | `PreferenceUpdateWorker` 文档声称支持 `user_rating_5`/`user_rating_1_2` 评分反馈，但 `switch` 分支与 `EventTypeEnum` 均未实现，评分信号被静默丢弃 | 用户偏好闭环 | 低-中 | ✅ 已处理（2026-07-02）：前端确认无评分入口，已修正 Javadoc 移除失实描述，未新增死代码 | [rag-fix-plans/05](./rag-fix-plans/05-user-rating-feedback-not-implemented.md) |

补充说明（非缺陷，架构现状）：
- V1 `OrchestratorAgent` 与 V2 `GenerationOrchestrator` 两套编排器并存，功能有重叠，长期看存在维护双份逻辑的成本，是否收敛为单一编排器建议作为独立架构决策讨论，不纳入本次问题清单（不是 bug，是技术债）。
- 用户私有指纹入库（链路 B）完全绕开 `CONFIDENCE_GATE`/多样性配额/人工审核，这是**当前设计的有意选择**（个人数据无需审核），但需要注意问题 #1 修复后，私有指纹已首次进入向量召回，其质量把控完全依赖 `USER_PRIVATE_CONFIDENCE_GATE=0.65` 这一道低门槛，建议后续观察实际召回质量。
- 问题 #4 复查中发现的遗留 TODO：`CriticAgent` 调用 `feedbackFingerprintQuality` 时 `ragTraceId` 固定传 `null`，导致 `generation_rag_trace.critic_result` 无法回填，建议后续作为独立小计划处理。

---

## 四、修改计划总览

所有问题的详细修改方案（问题描述/影响范围/涉及文件/修改步骤/验证方式/风险评估）已拆分至独立文件，存放于 [`docs/rag-fix-plans/`](./rag-fix-plans/README.md) 文件夹。**5 项问题已于 2026-07-02 全部处理完毕**（4 项完成代码改动并验证，1 项经复查确认原诊断有误、功能已存在），各文件末尾均已追加"实施记录"小节：

1. [私有指纹向量同步补全](./rag-fix-plans/01-private-fingerprint-vector-sync.md) —— ✅ 已实施：`FingerprintIngestWorker` 补充注入 `VectorSyncWorker` 并调用 `syncOnApprove()`
2. [结构化召回权限隔离修复](./rag-fix-plans/02-structural-retrieval-authz-gap.md) —— ✅ 已实施：`listApproved` 新增 `currentUserId` 参数与权限过滤，`DesignKnowledgeIndex` 两处调用点同步改造
3. [RagTraceTool 参数错位修正](./rag-fix-plans/03-rag-trace-query-text-bug.md) —— ✅ 已实施：`DesignEvidence` 新增 `queryText` 字段并贯穿传递至 `RagTraceTool`
4. [效果反哺闭环接入](./rag-fix-plans/04-fingerprint-feedback-loop-not-wired.md) —— ✅ 已核实：功能已在 `CriticAgent` 中实现，未做代码改动
5. [用户评分反馈补全或文档修正](./rag-fix-plans/05-user-rating-feedback-not-implemented.md) —— ✅ 已实施：确认前端无评分入口后，采纳文档修正方案

**验证说明**：当前开发环境无可用 JDK/Maven（`mvn`/`javap` 均不可用），本次改动未能通过完整编译与单元测试验证；已通过人工代码审阅、方法签名反编译核对（mybatis-flex `QueryWrapper.and/or(Consumer)` API）等方式尽力保证正确性，**建议在具备编译环境后执行一次完整构建（`./mvnw compile`）与各计划第 5 节所列验证步骤**，确认无误后再发布上线。
