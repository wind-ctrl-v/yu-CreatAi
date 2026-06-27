# RAG（检索增强生成）架构深度解析

> 最后更新：2026-06-28  
> 本文档沿着 **知识从哪来 → 怎么存 → 怎么检索 → 怎么注入 → 效果怎么保障** 的数据流顺序，完整讲清 CreatAi 的 RAG 体系。

---

## 目录

1. [一句话理解](#1-一句话理解)
2. [全景数据流](#2-全景数据流)
3. [知识从哪来——三阶段渐进增强](#3-知识从哪来三阶段渐进增强)
4. [知识怎么存——数据模型](#4-知识怎么存数据模型)
5. [怎么检索——运行时三层召回](#5-怎么检索运行时三层召回)
6. [个性化——用户画像体系](#6-个性化用户画像体系)
7. [怎么注入——Prompt 消费](#7-怎么注入prompt-消费)
8. [怎么保障——降级与开关](#8-怎么保障降级与开关)
9. [优化路线图](#9-优化路线图)
10. [关键文件索引](#10-关键文件索引)

---

## 1. 一句话理解

AI 生成代码前，先从知识库召回真实应用的**设计指纹**（颜色、布局、组件、交互等），作为"设计证据"注入 CoderAgent / StyleAgent 的 prompt 中，让生成结果**有据可依、风格可控**。

---

## 2. 全景数据流

下图展示知识从"入库"到"消费"的完整链路，一次看懂全局：

```
                        ┌─────────────────────────────────────┐
                        │          知识库建设（离线）            │
                        │                                     │
  精选页面 HTML ──→ DesignSourcePreprocessor（去脚本、提结构） │
                        │                                     │
                        ├──→ DesignExtractorAgent（LLM 提炼）  │
                        │       ↓ 六层指纹 JSON                │
                        ├──→ FingerprintParser（校验 + MD5 去重）│
                        │       ↓                              │
                        ├──→ MySQL: design_fingerprint          │
                        │       状态: CANDIDATE                 │
                        ├──→ 管理员审核 → APPROVED               │
                        │       ↓                              │
                        └──→ VectorSyncWorker                   │
                                ↓ embedding                    │
                            PGvector: design_fingerprint_vector│
                        └─────────────────────────────────────┘
                                        │
          ┌─────────────────────────────┼──────────────────────────┐
          │              运行时检索 + 个性化增强                      │
          │                                                        │
          │  用户 prompt ──→ PromptPreferenceExtractor（LLM 提取）   │
          │       ↓ 偏好标签写入 user_preference_weight              │
          │                                                        │
          │  用户行为（保存/删除/改色） ──→ PreferenceUpdateWorker     │
          │       ↓ 规则映射，写入 user_preference_weight             │
          │                                                        │
          │  DesignRetrieveTool ←── UserPreferenceTool（加载画像）   │
          │       │                                                │
          │       ├── Layer 1: PGvector 向量检索                    │
          │       ├── Layer 2: MySQL 结构化检索                     │
          │       └── Layer 3: 静态 .md 兜底                       │
          │       ↓                                                │
          │  DesignEvidence（按条目组织的证据列表）                   │
          │       ↓ 写入 Blackboard                                │
          └───────┼────────────────────────────────────────────────┘
                  │
          ┌───────┴────────────────────────────┐
          │          Prompt 注入 + 质量闭环       │
          │                                     │
          │  ContextCompressTool（压缩拼接）     │
          │       ↓                              │
          │  CoderAgent ← 推荐写法 + 反模式 + Token 清单 │
          │  StyleAgent ← 调性 + 变量定义 + 反模式       │
          │       ↓                              │
          │  CriticAgent → dangling-var 检查      │
          │  （引用了未定义的变量 = P0 错误）       │
          └────────────────────────────────────┘
```

---

## 3. 知识从哪来——三阶段渐进增强

系统按"从简单到复杂"分三个阶段引入设计知识，**每个阶段独立可用**，后一个阶段是对前一个的增强而非替代：

### 阶段一：静态先验规则（始终生效，零 LLM）

**谁干的**：`DesignTokenAgent`  
**做什么**：从 `classpath:skill/designs/{profile}.md` 加载行业设计规范（7 种风格），解析出 CSS Token 清单、布局规则、推荐/禁止写法  
**产出**：`DesignSpec` → 写入 Blackboard  
**特点**：纯 Java 正则解析，无幻觉风险，是最后的硬兜底

### 阶段二：离线指纹提炼（异步构建知识库）

**谁干的**：`FingerprintCrawlScheduler` → `DesignSourcePreprocessor` → `DesignExtractorAgent` → `FingerprintParser`  
**做什么**：

```
1. 定时爬取精选应用 HTML（30 分钟一次，开关控制）
2. 预处理：去 <script>，选择性提取结构标签和 CSS 属性
   （业务文案被自然过滤，不是主动剥离）
3. LLM 提炼六层指纹：tokens / layout / components / interaction / antiPatterns / styleProfile+tone
4. 校验 + MD5 去重 → 入库 CANDIDATE
5. 管理员在后台审核：APPROVE → 可被检索 / REJECT → 打入冷宫
6. VectorSyncWorker：拼接 styleProfile + tone → 生成 embedding → 写入 PGvector
```

**产出**：`DesignFingerprint`（MySQL） + `DesignFingerprintVector`（PGvector）

### 阶段三：运行时证据召回（开关控制）

**谁干的**：`DesignRetrieveTool`  
**做什么**：从知识库中检索与当前需求最相关的设计指纹，结合用户个性化偏好，输出证据列表  
**产出**：`DesignEvidence` → 写入 Blackboard  
**特点**：受 `agent.retrieve.enabled` 总开关控制，关闭时流水线正常运行（仅使用阶段一）

---

## 4. 知识怎么存——数据模型

### 4.1 指纹侧（三张表）

| 表 | 存储 | 用途 |
|----|------|------|
| `design_fingerprint` | MySQL | 指纹主数据：六层 JSON + 状态 + 优先级 + 置信度 |
| `design_fingerprint_review_log` | MySQL | 审核操作日志 |
| `design_fingerprint_vector` | PGvector | 向量 embedding，用于语义检索 |

**指纹状态流转**：`CANDIDATE → APPROVED → ARCHIVED`（或 `REJECTED`）

**六层指纹结构**（`DesignFingerprintDTO`）：

| 层 | 内容 | 示例 |
|----|------|------|
| ① tokens | 颜色比例、字号、圆角、阴影 | `fontScale: "1.2/1.414"` |
| ② layout | 骨架、留白、视觉焦点 | `skeleton: "sidebar-main"` |
| ③ components | 使用的组件列表 | `["card", "tabs", "breadcrumb"]` |
| ④ interaction | 动效、过渡 | `motion: "fade-in 0.3s"` |
| ⑤ antiPatterns | 检测到的反模式 | `["backdrop-filter", "decorative-gradient"]` |
| ⑥ meta | 风格画像 + 调性 | `styleProfile: "ecommerce", tone: "modern,warm"` |

### 4.2 用户画像侧（三张表）

| 表 | 粒度 | 作用 |
|----|------|------|
| `user_design_profile` | 每用户一条 | 聚合画像：偏好的风格/颜色/布局/组件列表 + 自然语言摘要 |
| `user_preference_weight` | 每标签一条 | 精细化权重：`(userId, type, key)` 唯一，正=喜欢、负=反感 |
| `user_design_event` | 每行为一条 | 不可变行为日志（只追加不修改） |

### 4.3 检索结果（内存 DTO）

`DesignEvidence` 不入库，仅在一次生成过程中存在于 Blackboard：

```
DesignEvidence {
    styleProfile: "ecommerce"
    channel: "VECTOR_3B"           // 最终命中的检索通道
    items: [                       // 按优先级排序的证据条目
        Item { sourceType, sourceId, priority, confidence,
               tone, layoutPatterns, preferredPatterns,
               interactionPatterns, forbiddenPatterns }
    ]
}
```

> 注意：证据按**条目**组织，**不做预聚合**。聚合（合并去重推荐写法等）由 `ContextCompressTool` 在拼接 prompt 时完成。

---

## 5. 怎么检索——运行时三层召回

文件：`service/rag/DesignKnowledgeIndex.java`

三层**级联降级**（先尝试高精度，失败则降级）：

```
Layer 1: 向量检索（VECTOR_3B）
    条件：agent.retrieve.vector.enabled = true 且 PGvector 可用
    方式：拼接查询文本 → EmbeddingModel 生成 embedding → 余弦距离排序
    ↓ 失败则降级

Layer 2: 结构化检索（STRUCTURAL_3A）
    方式：MySQL 查 status=APPROVED 且 styleProfile 匹配
    排序：Java 侧 softMatchScore() 对 queryTags 做软匹配评分
    ↓ 始终追加

Layer 3: 静态兜底（STATIC_FALLBACK）
    方式：从 classpath .md 文件解析 preferredPatterns / forbiddenPatterns
    特点：始终加入结果，保证不会返回空
```

**去重与排序**：三层结果按 `sourceId` 去重 → 按 `priority DESC, confidence DESC` 排序 → 截取 Top-K

**Top-K 由生成模式决定**：

| 模式 | 证据条数 | 原因 |
|------|:---:|------|
| LOW_COST | 1 | 快速生成，上下文短 |
| STANDARD | 3 | 常规平衡 |
| HIGH_QUALITY | 5 | 最丰富的参考 |

---

## 6. 个性化——用户画像体系

> 核心目标：**同一个知识库，不同用户的检索结果不同**——越用越懂你。

### 6.1 偏好从哪来（两条写入路径）

**路径一：用户行为驱动（规则引擎，无 LLM）**

由 `PreferenceUpdateWorker` 处理。每次用户操作触发规则：

| 用户行为 | 权重变化 | 解读 |
|---------|:---:|------|
| 保存页面 | +0.1 | 认可 |
| 导出页面 | +0.2 | 强认可 |
| 删除页面 | -0.1 | 不满 |
| 重新生成 | -0.05 | 轻微不满 |
| 改颜色 | 旧-0.2 / 新+0.2 | 讨厌旧的，喜欢新的 |
| 加组件 | +0.1 | 喜欢 |
| 删组件 | -0.2 | 讨厌 |
| 改布局 | 旧-0.1 / 新+0.1 | 偏好切换 |

**路径二：Prompt 语义提取（LLM 驱动）**

由 `PromptPreferenceExtractor` 处理。每次对话时 LLM 从用户 prompt 中提取偏好标签：

```
"帮我做一个科技感的数据大屏，要深色主题"
    ↓ LLM 提取
{"style":["tech","dashboard"], "color":["dark"]}
    ↓ 写入 user_preference_weight
```

**两条路径写入同一张权重表**，最终融合为统一的用户偏好向量。

### 6.2 偏好怎么消费

```
UserPreferenceTool（流水线中的 System Tool）
    ↓ 调用 UserPreferenceService.loadUserDesignContext(userId)
    ↓ 加载画像 + 权重 Top-K 标签
    ↓ 写入 Blackboard: putUserDesignContext(context)

DesignRetrieveTool 读取 UserDesignContext
    → 偏好标签作为 queryTags 增强检索
    → 同一 styleProfile 下，不同用户召回不同结果
```

**举个例子**：

| 用户 | 偏好 | 生成"电商"应用时召回 |
|------|------|---------------------|
| 用户 A | minimal + dark | 极简深色风格的 5 条指纹 |
| 用户 B | enterprise + light | 企业亮色风格的 5 条指纹 |

### 6.3 当前限制（待实现）

- `profileSummary` 字段仅被读取，**没有自动刷新实现**
- **没有对外 REST API**，前端无法上报行为事件（保存/删除/改色）
- 即时偏好提取（`PromptPreferenceExtractor`）与长期偏好（行为权重）的融合权重尚未精细调优

---

## 7. 怎么注入——Prompt 消费

检索结果到达 Blackboard 后，由 `ContextCompressTool` 统一压缩拼接，注入 CoderAgent / StyleAgent 的 prompt：

```
=== 设计参考证据 ===

【推荐写法】
- 使用 flexbox 布局，避免 float
- 卡片组件使用白色背景 + box-shadow

【禁用模式】
- 禁止使用 backdrop-filter 毛玻璃
- 禁止纯装饰性渐变背景

【CSS Token 清单】
--bg-page, --bg-card, --text-primary, --accent, ...

【调性参考】
克制、专业、中性色调

【用户偏好】
该用户偏爱极简深色风格，喜欢卡片布局
```

### 核心质量保障：tokenGroups 强契约

```
DesignSpec.tokenGroups = {"--bg-page", "--bg-card", "--text-primary", ...}

            │ 同一份清单同时注入两个 Agent
    ┌───────┴───────┐
    ▼               ▼
StyleAgent       CoderAgent
"定义这些变量"    "只能引用这些变量"
```

CriticAgent 的 `dangling-var` 检查扫描所有文件，引用了未定义的变量 → **P0 强制不通过**，形成闭环。

---

## 8. 怎么保障——降级与开关

### 8.1 四重降级（确保永远不中断流水线）

```
1. 向量检索失败 → 降级为结构化检索
2. 结构化检索也失败 → 使用静态 .md 兜底证据
3. .md 解析失败 → DesignProfileResolver 内置 Java 硬编码默认值（12 个基础 Token）
4. 整个检索步骤抛异常 → Orchestrator 捕获，仅日志，流水线继续
```

### 8.2 开关层级

| 开关 | 位置 | 控制范围 |
|------|------|---------|
| `agent.retrieve.enabled` | `application.yml` | **总开关**，false 则跳过整个检索 |
| `agent.retrieve.vector.enabled` | `application.yml` | **向量子开关**，false 则跳过 PGvector，仅走结构化+静态 |
| `@ConditionalOnBean(VectorRepo)` | `DesignKnowledgeIndex` 类 | 无 PGvector Repo → Index 不注入 |
| `@ConditionalOnBean(Index)` | `DesignRetrieveTool` 类 | 无 Index → Tool 不注入 → 流水线自动跳过 |
| `maxEvidenceCount` | `GenerationPolicy` | 注入条数（LOW=1, STD=3, HQ=5） |

**条件装配链**：`PGvector Repo → DesignKnowledgeIndex → DesignRetrieveTool`，任何一环缺失则下游自动跳过。

---

## 9. 优化路线图

> 以下按投入产出比排序，分三个阶段。

### 9.1 短期：接通最后一公里（1-2 天）

#### ① 补齐行为事件上报 API（P0，预估 2h）

**问题**：`PreferenceUpdateWorker` 引擎已完整实现（规则映射、权重 upsert、并发安全），但没有对外 REST API，前端无法上报用户行为。整个"越用越懂你"闭环是**断裂的**——引擎空转，没有燃料。

**方案**：新增 `POST /user/design/event` 接口，前端在 `AppChatPage` 的保存/删除/部署等操作后调用上报。

```
前端操作 → POST /user/design/event {eventType, metadata}
         → PreferenceUpdateWorker.recordAndUpdate()
         → user_preference_weight 更新
         → 下次生成时 UserPreferenceTool 加载到更精准的偏好
```

**这是激活整个用户画像体系的最关键一步。**

#### ② profileSummary 自动刷新（P0，预估 1h）

**问题**：`profileSummary` 字段只被读取，从未更新。即使权重表有数据，摘要也永远是空的，导致 prompt 中的"用户偏好"段落是空白。

**方案**：在 `PreferenceUpdateWorker.recordAndUpdate()` 末尾加一步——每 N 次更新（如每 10 次）触发摘要重算：

```java
if (totalUpdates % 10 == 0) {
    List<Weight> topPreferred = getTopK(userId, 5, "DESC");  // 最喜欢
    List<Weight> topDisliked  = getTopK(userId, 3, "ASC");   // 最讨厌
    String summary = buildSummary(topPreferred, topDisliked); // 纯字符串拼接
    profileMapper.updateSummary(userId, summary);
}
```

无需 LLM，纯 Java 拼接。

#### ③ PromptPreferenceExtractor 异步化（P1，预估 30min）

**问题**：当前在 `chatToGenCode` 中同步调用 LLM 提取偏好标签，增加了生成首字延迟（约 1-3 秒）。

**方案**：

```java
// 改前：同步阻塞
promptPreferenceExtractor.extractAndRecord(message, userId);

// 改后：异步不阻塞当前请求
CompletableFuture.runAsync(() ->
    promptPreferenceExtractor.extractAndRecord(message, userId)
);
```

偏好写入是**下次生成**才生效的，不需要阻塞当前请求。

---

### 9.2 中期：检索质量与可观测性（3-5 天）

#### ④ RAG Trace 落表（P1，预估 4h）

**问题**：检索是黑盒，无法知道"命中了什么、对生成质量有没有帮助"。`generation_rag_trace` 表已在 `rag-design.md` 中设计但未实现。

**方案**：在 `DesignKnowledgeIndex.retrieve()` 返回时记录：

```sql
INSERT INTO generation_rag_trace
  (generation_id, query_tags, channel, hit_fingerprint_ids, latency_ms, top_k_used)
VALUES (?, ?, ?, ?, ?, ?)
```

**价值**：
- 哪些指纹被高频命中 → 重点维护
- 哪些从未命中 → 考虑 ARCHIVE
- 向量 vs 结构化命中率对比 → 决定是否值得维护 PGvector

#### ⑤ 向量与结构化并行检索（P2，预估 2h）

**现状**：三层是级联降级——向量失败才走结构化。

**问题**：
- 向量擅长**语义模糊匹配**（"科技感" ≈ "futuristic, tech"）
- 结构化擅长**精确字段匹配**（styleProfile = "ecommerce"）
- 级联降级意味着向量成功时完全不走结构化，**丢失了精确匹配的补充**

**方案**：

```java
CompletableFuture<List<Item>> vectorFuture = supplyAsync(() -> vectorSearch(...));
CompletableFuture<List<Item>> structFuture = supplyAsync(() -> structSearch(...));

List<Item> merged = mergeAndDedup(
    vectorFuture.get(3, SECONDS),  // 向量超时 3 秒
    structFuture.get(1, SECONDS)   // 结构化超时 1 秒
);
```

合并后按 `(0.6 × vectorScore + 0.4 × structScore)` 混合排序。

#### ⑥ 偏好权重时间衰减（P2，预估 1h）

**问题**：权重只增不减（除非负向行为），3 个月前的偏好和今天的同等重要。

**方案**：查询时引入衰减因子（不改表结构）：

```java
// UserPreferenceService.loadUserDesignContext()
double daysSince = ChronoUnit.DAYS.between(weight.getLastEvidenceAt(), LocalDate.now());
double effectiveWeight = weight.getWeight() * Math.pow(0.995, daysSince);
// decay=0.995 → 半衰期 ≈ 138 天
```

效果：最近频繁保存的"深色项目"权重远高于半年前保存的一个。

---

### 9.3 长期：架构升级（1-2 周）

#### ⑦ 指纹粒度：从"整站"到"组件"（P3，预估 1-2d）

**问题**：一个指纹 = 一整个页面的六层摘要。粒度太粗——"电商首页"的指纹同时包含导航栏、商品卡片、页脚的设计模式，无法精准匹配"我只需要一个好看的导航栏"。

**方案**：

```
DesignExtractorAgent 提炼时拆分：
  一个页面 → N 条组件级指纹（sourceScope=COMPONENT）
  
  NavBar 指纹 { layout: "sticky-top", preferredPatterns: ["flex-between", "logo-left"] }
  ProductCard 指纹 { layout: "card-grid", preferredPatterns: ["shadow-hover", "aspect-ratio-1:1"] }
  Footer 指纹 { layout: "multi-column", preferredPatterns: ["grid-4-cols"] }
```

**价值**：当需求是"加一个商品卡片"时，精确召回卡片组件的设计参考，而非整站泛泛而谈。

#### ⑧ 反馈闭环：生成后评分反写指纹置信度（P3，预估 4h）

**问题**：指纹 `confidence` 是 LLM 提炼时给出的初始值，入库后就不再变化。好的指纹和差的指纹长期共存。

**方案**：

```
用户打 5 星 → 本次命中的指纹 confidence += 0.02
用户打 1-2 星 → 本次命中的指纹 confidence -= 0.05
confidence < 0.3 → 自动 ARCHIVE（退出检索池）
```

**价值**：知识库形成**正反馈循环**——好指纹越用越强，差的自然淘汰，无需人工维护。

#### ⑨ 多模态证据：截图 embedding（P4，预估 2-3d）

**问题**：向量检索只用 `styleProfile + tone` 的文本 embedding，语义信息有限（"科技感"的文本表示不如一张科技风截图直观）。

**方案**：

```
指纹入库时：
  精选页面截图 → CLIP 图像编码器 → image_embedding(512d)
  写入新列：design_fingerprint_vector.image_embedding

检索时：
  用户上传参考截图 → CLIP 编码 → 与 image_embedding 余弦距离
  文本+图像混合排序：0.5 × textScore + 0.5 × imageScore
```

**价值**：支持"以图搜指纹"，视觉匹配比纯文本语义精准得多。

---

### 总览

| 优先级 | 方案 | 工作量 | 价值 | 依赖 |
|:---:|------|:---:|------|------|
| **P0** | ① 行为事件上报 API | 2h | 激活闭环 | 无 |
| **P0** | ② profileSummary 自动刷新 | 1h | 修复空数据 | 无 |
| **P1** | ③ Extractor 异步化 | 30min | 降低延迟 | 无 |
| **P1** | ④ RAG Trace 落表 | 4h | 可观测性 | `generation_rag_trace` DDL |
| **P2** | ⑤ 并行检索 | 2h | 检索质量 | 无 |
| **P2** | ⑥ 权重时间衰减 | 1h | 偏好时效性 | 无 |
| **P3** | ⑦ 组件级指纹 | 1-2d | 检索精度 | ExtractorAgent 改造 |
| **P3** | ⑧ 反馈反写置信度 | 4h | 知识库自进化 | ① 行为上报 |
| **P4** | ⑨ 多模态截图 embedding | 2-3d | 视觉匹配 | CLIP 模型接入 |

---

## 10. 关键文件索引

### 离线知识库建设

| 文件 | 说明 |
|------|------|
| `worker/FingerprintCrawlScheduler.java` | 定时爬取精选应用 |
| `service/design/DesignSourcePreprocessor.java` | HTML 预处理（去脚本 + 提取结构） |
| `agent/workers/DesignExtractorAgent.java` | LLM 提炼六层指纹 |
| `service/design/FingerprintParser.java` | 指纹校验 + 置信度 + MD5 签名 |
| `worker/VectorSyncWorker.java` | 审核后同步 PGvector |

### 数据模型

| 文件 | 说明 |
|------|------|
| `model/entity/DesignFingerprint.java` | 指纹主表实体 |
| `model/entity/DesignFingerprintVector.java` | PGvector 向量表 |
| `model/dto/agent/DesignFingerprintDTO.java` | 六层指纹 DTO |
| `model/dto/agent/DesignEvidence.java` | 检索结果 DTO（Item 列表） |
| `docs/sql/design_fingerprint.sql` | 指纹表 DDL |
| `docs/sql/design_fingerprint_vector.sql` | 向量表 DDL |

### 运行时检索

| 文件 | 说明 |
|------|------|
| `service/rag/DesignKnowledgeIndex.java` | 三层检索入口 |
| `tool/system/DesignRetrieveTool.java` | 检索工具（V1 Agent / V2 SystemTool） |

### 用户画像

| 文件 | 说明 |
|------|------|
| `model/entity/UserDesignProfile.java` | 画像实体 |
| `model/entity/UserPreferenceWeight.java` | 权重实体 |
| `model/entity/UserDesignEvent.java` | 行为事件实体 |
| `worker/PreferenceUpdateWorker.java` | 偏好更新引擎（规则驱动） |
| `tool/system/UserPreferenceTool.java` | 偏好加载工具 |
| `service/PromptPreferenceExtractor.java` | Prompt 偏好提取（LLM） |
| `docs/sql/001_user_preference.sql` | 画像/权重/事件三表 DDL |

### 上下文装配与消费

| 文件 | 说明 |
|------|------|
| `orchestrator/DesignContextAssembler.java` | V2 统一装配（6 个 System Tool） |
| `tool/system/ContextCompressTool.java` | 上下文压缩 + prompt 拼接 |
| `agent/codegen/CoderAgent.java` | 消费压缩后的上下文 |
| `agent/style/StyleAgent.java` | 消费压缩后的上下文 |

### 配置与文档

| 文件 | 说明 |
|------|------|
| `application.yml` → `agent.retrieve.*` | RAG 开关 |
| `docs/rag-design.md` | 完整架构设计 + 升级路线 |
