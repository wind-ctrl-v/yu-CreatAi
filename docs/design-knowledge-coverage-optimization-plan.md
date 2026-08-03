# 设计知识库覆盖度优化改造计划

> 生成日期：2026-08-03
> 基于代码库 commit `75f7688`
> 定位：解决"知识库模板种类少、无法覆盖所有需求"的问题。核心思路不是"多建模板"，而是把系统从**「查模板」演进为「查规律 + 现场造规律」**。与 `chat-context-token-optimization-plan.md`（对话上下文治理）独立，可并行实施。

---

## 目录

1. [问题诊断：三个根因](#1-问题诊断三个根因)
2. [现状关键机制（事实基线）](#2-现状关键机制事实基线)
3. [目标架构与分工格局](#3-目标架构与分工格局)
4. [阶段一：检索泛化 + Miss 度量（P0）](#4-阶段一检索泛化--miss-度量p0)
5. [阶段二：动态合成兜底（第四档降级）](#5-阶段二动态合成兜底第四档降级)
6. [阶段三：入库来源扩展](#6-阶段三入库来源扩展)
7. [阶段四：类目多维化 + 静态毕业通道](#7-阶段四类目多维化--静态毕业通道)
8. [数据模型与枚举变更](#8-数据模型与枚举变更)
9. [风险与决策点](#9-风险与决策点)
10. [验收与度量指标](#10-验收与度量指标)
11. [关键类索引](#11-关键类索引)

---

## 1. 问题诊断：三个根因

### 1.1 类目体系是封闭白名单

`DesignProfileResolver.PROFILE_WHITELIST` 仅 10 个合法值（startup / enterprise / ecommerce / portfolio / dashboard / landing / marketing / education / blog / default），`normalizeProfile` 对不在白名单的风格**一律归 `default`**。用户需求是连续语义空间（"赛博朋克游戏官网""新中式茶饮"），10 个离散桶永远装不下。

### 1.2 入库链路闭环自复制（最深根因）

`FingerprintCrawlScheduler` 只爬平台内部 `priority=99` 的已部署精选应用，而这些页面本身就是用 10 个模板生成的：

```
10 个静态模板 → 生成页面 → 精选页面提炼指纹 → 指纹反哺生成
     ↑                                              ↓
     └──────── 多样性永远不会增长，只会自我强化 ──────┘
```

### 1.3 粒度是"整页单标签"

一个页面只挂一个 `styleProfile`，真实页面却是 `行业 × 视觉风格 × 布局模式` 的组合，组合爆炸全被压扁进 10 个桶。

---

## 2. 现状关键机制（事实基线）

| 机制 | 位置 | 现状 | 本计划态度 |
|---|---|---|---|
| 风格归一化 | `DesignProfileResolver.normalizeProfile` | 白名单外 → `default` | 阶段四调整 |
| 静态规范加载 | `DesignProfileResolver.loadDesignSpec` | classpath `skill/designs/{profile}.md`，失败回退内置默认 | **保留，角色重定义**（§3） |
| 向量检索 | `DesignKnowledgeIndex.tryVectorRetrieve*` → `vectorRepo.search(embedding, styleProfile, ...)` | **styleProfile 硬过滤**，min-similarity 0.7 | 阶段一改软加权 |
| 结构化检索 | `fingerprintService.listApproved(styleProfile, ...)` | **styleProfile 硬过滤** | 阶段一改软加权 |
| 排序 | `priority → confidence → updatedAt → matchScore`；个性化 10 项重排 | `softMatchScore` 已是软匹配 | 复用 |
| 指纹入库 | `saveCandidate` | signature 去重（CANDIDATE/APPROVED 态）、schema 校验、confidence 客观计算 | **直接复用** |
| 审核门禁 | `VectorSyncWorker.syncOnApprove / deactivateOnRejectOrArchive` | 仅 APPROVED 同步向量 | **直接复用**（合成规范同受此门禁） |
| 链路追踪 | `GenerationRagTrace` + `RagTraceTool`，`DesignEvidence.queryText` 已回收查询文本 | 有 channel 记录 | 阶段一扩展 miss 埋点 |
| 负反馈 | `dislike` / `recordNegativeEvent` | 有 | 阶段三补正反馈通道 |
| 内部爬取 | `FingerprintCrawlScheduler` | 仅 priority=99 内部应用 | 阶段三扩展来源 |

---

## 3. 目标架构与分工格局

```
┌────────────────────────────────────────────────────────────┐
│ 静态模板（10~20 个，少而精）—— "宪法"                        │
│   人写，承载质量红线与高频主干；不为覆盖需求而新增             │
│   新增唯一来源：合成规范"毕业"（§7.3）                        │
├────────────────────────────────────────────────────────────┤
│ 动态指纹（持续生长）—— "判例"                                │
│   页面提炼（内部+外部）+ LLM 动态合成，人工审核后参与召回      │
│   覆盖长尾，需求驱动自我生长                                  │
├────────────────────────────────────────────────────────────┤
│ 检索层：语义软召回                                            │
│   profile 从硬过滤降级为排序加权项，跨类目召回                 │
├────────────────────────────────────────────────────────────┤
│ 降级链：VECTOR_3B → STRUCTURAL_3A → DYNAMIC_SYNTH → STATIC   │
└────────────────────────────────────────────────────────────┘
```

原则：**静态层保证质量下限，动态体系决定覆盖上限。**

---

## 4. 阶段一：检索泛化 + Miss 度量（P0）

> 改动最小、收益立竿见影，且为后续所有阶段提供数据支撑。

### 4.1 向量检索解除 profile 硬过滤

**改动点**：`DesignFingerprintVectorRepository.search` 新增不过滤 profile 的重载（或加 `boolean strictProfile` 参数）：

```java
// 现状：styleProfile 作为过滤条件参与 SQL WHERE
vectorRepo.search(queryEmbedding, styleProfile, k * 2, minSimilarity, queryUserId);
// 目标：profile 不参与过滤，命中后按 profile 相等与否折算 matchScore 加分
vectorRepo.searchWithoutProfile(queryEmbedding, k * 3, minSimilarity, queryUserId);
```

- `DesignKnowledgeIndex.tryVectorRetrieve*` 改用新方法；`fp.getStyleProfile().equals(styleProfile)` 时 matchScore +2，不等时 +0（仍靠相似度与既有排序竞争）
- `min-similarity=0.7` 保持不变，作为跨类目召回的相关性兜底

### 4.2 结构化检索同步放宽

`listApproved(styleProfile, limit, userId)` 目前按 profile 过滤。改造为：

- 主查询仍按 profile 取 `k*2` 条；
- **不足时**补充跨类目 APPROVED 指纹（按 confidence/updatedAt 排序取 `k` 条），交给 `softMatchScore` 与既有排序竞争；
- 保持 `currentUserId` 权限过滤不变（修复计划 02 的授权约束不动）。

### 4.3 Miss 判定与埋点

**Miss 定义**（满足其一）：

1. channel 为 `STATIC_FALLBACK` 且证据集中无指纹条目（只有静态规范）
2. 向量通道开启但最高相似度 < `min-similarity`

**落库**：新建 `design_miss_log` 表（见 §8），记录 queryText、styleProfile、需求关键词、channel、最高相似度、userId、appId。写入点：`DesignRetrieveTool.handle` 完成检索后异步落库（失败不阻塞，参照 VectorSyncWorker 原则）。

**聚合**：按"归一化需求关键词聚类 + 归 default 的 profile"统计 miss 频次，产出**定向补库清单**（配合负反馈按 profile 聚合，定位负反馈率高的未覆盖聚类）。

### 4.4 验收

- `STATIC_FALLBACK` 且无指纹证据的请求占比可查询、有周报口径
- 跨类目召回生效：白名单内 profile 请求可命中其他 profile 的指纹（日志可见 candidates 含异类 profile）

---

## 5. 阶段二：动态合成兜底（第四档降级）

### 5.1 降级链扩展

```
VECTOR_3B → STRUCTURAL_3A → DYNAMIC_SYNTH（新增）→ STATIC_FALLBACK
```

### 5.2 触发条件：同类 miss 第 2 次才合成（关键防浪费设计）

首次出现的长尾需求，动态合成与现场生成完全等价，入库无摊销收益。因此：

- 第 1 次 miss：归 `default` 兜底，仅记 `design_miss_log`
- 同一归一化需求聚类 miss 次数 ≥ 阈值（默认 2，配置项 `agent.retrieve.synth.miss-threshold`）：触发合成

### 5.3 合成器：新增 `DynamicSpecSynthesizer`

- 输入：`RequirementDoc` + `GenerationPlan` + queryTags（复用 `ContextCompressTool` 的压缩产物，控制 token）
- 模型：复用现有 `chatModel`（deepseek-chat），输出遵循六层契约的 `DesignFingerprintDTO` JSON（schema 与 `DesignExtractorAgent` 输出一致，复用 `FingerprintParser` 校验/规整）
- 产物第一去向：**Blackboard** 供本次生成消费（不落库也能工作）

### 5.4 入库规则

| 项 | 规则 |
|---|---|
| `sourceType` | 新增枚举 `DYNAMIC_SYNTHESIS`（区别于 `STATIC_MD` / `EXTRACTED_FINGERPRINT`） |
| `confidence` | **压低到 0.3~0.4**：合成无页面证据支撑，避免在 `confidence DESC` 排序中排到真实提炼指纹之前 |
| `status` | `CANDIDATE`，复用 `saveCandidate` 的 signature 去重与 schema 校验 |
| 向量同步 | **必须 APPROVED 后**由 `VectorSyncWorker.syncOnApprove` 执行，未审核不进召回池（防 LLM 幻觉污染） |
| 审核界面 | `DesignFingerprintController` 列表按 sourceType 可筛选，运营优先处理 DYNAMIC_SYNTHESIS 队列 |

### 5.5 收益逻辑

同类需求第二次起直接走 VECTOR_3B 命中审核过的规范：成本从一次 Chat 调用降为一次 Embedding 计算，质量从"未检验"升为"人工背书"，输出风格稳定可复现、可归因、可定向修复。

### 5.6 验收

- 构造重复 miss 需求：第 1 次走 STATIC，第 2 次触发合成并入 CANDIDATE
- 审核通过后再发同类需求：channel=VECTOR_3B 直接命中，无合成调用
- 合成产物 confidence ≤ 0.4，排序不超真实提炼指纹

---

## 6. 阶段三：入库来源扩展

打破闭环自复制，三条新来源**全部复用** `DesignExtractorAgent` 提炼与 `saveCandidate` 入库链路：

| 来源 | 实现 | 安全/合规要点 |
|---|---|---|
| **用户正反馈回流** | 新增调度（仿 `FingerprintCrawlScheduler`）：筛选"已部署 + 部署后 N 天无增量编辑 + 无 dislike"的应用 → 提炼 | 与 `recordNegativeEvent` 负反馈对称；自产来源 confidence 计算沿用 `selfGenerated` 分支 |
| **运营种子** | `DesignFingerprintController` 新增"提交参考 URL"入口 → 抓取 → 提炼 | **SSRF 防护**：URL 白名单域名校验、拒绝内网地址（9.\*/10.\*/11.\*/21.\*/30.\* 等）、仅管理员 |
| **外部优秀设计** | 定时爬 Awwwards 等获奖页 → 提炼 | 六层契约"不含业务文案"天然规避版权问题——提炼规律不复制内容；注意目标站点 robots 与频率控制 |

---

## 7. 阶段四：类目多维化 + 静态毕业通道

### 7.1 指纹多维标签

`design_fingerprint` 新增三列（DDL 见 §8）：`industry`（餐饮/医疗/金融…）、`visualStyle`（极简/赛博朋克/新中式…）、`layoutPattern`（Bento/分屏/时间线…）。存量指纹用 `DesignExtractorAgent` 批量重提炼回填。

> 必须与阶段一配套：多维标签只有配合语义软召回才有意义，否则只是更精细的硬过滤。

### 7.2 `normalizeProfile` 调整

白名单外风格不再静默归 `default`：保留原始识别值到 `visualStyle` 维度，profile 归 `default` 仅作为静态规范选择，检索以多维标签 + 语义为主。

### 7.3 静态模板"毕业通道"

**静态模板不再为覆盖需求而新增**，唯一新增来源：

```
DYNAMIC_SYNTHESIS 指纹
  + usageCount 高（如 ≥ 50）
  + positiveFeedbackCount / negativeFeedbackCount 比例健康
  → 人工精修（重点补 forbiddenPatterns 质量红线）
  → 固化为 skill/designs/{profile}.md + 白名单扩列
```

静态层的不可替代职能（确定性底座、先验始终注入、红线载体、LLM 锚定）保持不变。

---

## 8. 数据模型与枚举变更

### 8.1 新表 `design_miss_log`（阶段一）

```sql
CREATE TABLE design_miss_log (
  id            BIGINT PRIMARY KEY,
  styleProfile  VARCHAR(64),     -- 归一化后的 profile（含 default）
  rawProfile    VARCHAR(64),     -- ArchitectAgent 原始识别值
  queryText     TEXT,            -- 检索查询文本
  queryTags     VARCHAR(512),    -- 需求关键词，逗号分隔
  channel       VARCHAR(32),     -- STATIC_FALLBACK / VECTOR_3B / STRUCTURAL_3A
  maxSimilarity DOUBLE,          -- 向量通道最高相似度，无则 NULL
  userId        BIGINT,
  appId         BIGINT,
  createTime    DATETIME,
  KEY idx_profile_time (styleProfile, createTime)
);
```

### 8.2 枚举变更（阶段二）

- `design_fingerprint.sourceType` 新增 `DYNAMIC_SYNTHESIS`（字符串列，无 DDL；更新常量类与注释）

### 8.3 新增列（阶段四，DDL）

```sql
ALTER TABLE design_fingerprint
  ADD COLUMN industry     VARCHAR(64) NULL,
  ADD COLUMN visualStyle  VARCHAR(64) NULL,
  ADD COLUMN layoutPattern VARCHAR(64) NULL,
  ADD KEY idx_industry (industry), ADD KEY idx_visual_style (visualStyle);
```

---

## 9. 风险与决策点

| 风险 | 对策 |
|---|---|
| 跨类目召回引入噪音 | min-similarity 0.7 不动；profile 一致仅作加分不作准入；观察负反馈率变化 |
| 合成规范质量不稳 | confidence 压低 + 审核门禁 + miss 频次阈值三重防线 |
| 合成为一次性长尾浪费 | §5.2：同类 miss 第 2 次才触发 |
| 外部爬取版权 | 六层契约不含业务文案；只提炼规律 |
| SSRF（运营种子 URL） | 域名白名单 + 内网地址拒绝 + 仅管理员 |
| 多维标签回填成本 | 批量重提炼放离线低峰执行，失败可重跑（幂等） |

**待决策**：
1. miss 触发合成的频次阈值（默认 2，上线后按 miss 量调整）
2. 外部爬取的目标站点清单与法务确认
3. 组件级独立索引（跨风格复用 components 层）是否并入阶段四——建议先跑阶段一看混合证据效果再定

---

## 10. 验收与度量指标

| 指标 | 口径 | 目标 |
|---|---|---|
| Miss 率 | `STATIC_FALLBACK 且无指纹证据`请求占比 | 可观测，逐周下降 |
| 检索覆盖 | 向量/结构通道命中率 | 阶段一后提升（跨类目生效） |
| 合成效率 | DYNAMIC_SYNTHESIS 审核通过率、二次命中率（同类需求第 2 次直接命中） | 通过率 ≥ 60%，二次命中 100% |
| 自复制指标 | 新 signature 出现频率（长期为零 = 闭环自复制） | 阶段三后恢复增长 |
| 质量 | 归 default 需求的负反馈率 | 下降 |

---

## 11. 关键类索引

| 类 / 文件 | 涉及阶段 | 改动 |
|---|---|---|
| `service/design/DesignKnowledgeIndex` | 一 | 向量/结构检索解除 profile 硬过滤，改加权 |
| `repository/DesignFingerprintVectorRepository` | 一 | 新增不过滤 profile 的 search 重载 |
| `service/DesignFingerprintService#listApproved` | 一 | 跨类目补充召回 |
| `tool/system/DesignRetrieveTool` | 一 | miss 判定 + 异步落 `design_miss_log` |
| `service/design/DesignProfileResolver` | 四 | `normalizeProfile` 不再静默归 default |
| 新增 `service/design/DynamicSpecSynthesizer` | 二 | LLM 合成六层契约 DTO |
| `service/impl/DesignFingerprintServiceImpl#saveCandidate` | 二 | 复用；新增 `DYNAMIC_SYNTHESIS` 常量 |
| `worker/VectorSyncWorker` | 二 | 复用，无需改动（门禁天然生效） |
| `worker/FingerprintCrawlScheduler`（仿写） | 三 | 正反馈回流调度 |
| `controller/DesignFingerprintController` | 三 | 运营种子 URL 入口（SSRF 防护） |
| `service/design/DesignExtractorAgent` | 三/四 | 复用；存量多维标签批量回填 |
| `model/entity/GenerationRagTrace` / `RagTraceTool` | 一 | miss 埋点数据源参考 |
