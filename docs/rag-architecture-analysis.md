# RAG（检索增强生成）架构深度解析

> 最后更新：2026-06-28
> 基于源码 `docs/rag-design.md` 与全部实现代码分析，覆盖离线知识库建设、向量存储、运行时检索、提示词注入、降级保障、新特性全链路。

---

## 目录

1. [总体架构](#一总体架构)
2. [数据模型（指纹侧）](#二数据模型指纹侧)
3. [用户画像体系（个性化侧）](#三用户画像体系个性化侧)
4. [阶段二：离线知识库建设](#四阶段二离线知识库建设)
5. [阶段三：运行时检索](#五阶段三运行时检索)
6. [消费：注入 LLM Prompt](#六消费注入-llm-prompt)
7. [四重降级保障](#七四重降级保障)
8. [开关与条件装配](#八开关与条件装配)
9. [新增特性：PromptPreferenceExtractor](#九新增特性promptpreferenceextractor)
10. [前端消费](#十前端消费)
11. [关键文件索引](#十一关键文件索引)

---

## 一、总体架构

本项目实现了一套**三阶段渐进增强 RAG 体系**，核心思想：AI 生成代码前，先从知识库中召回真实应用的设计指纹（颜色、布局、组件、交互等六层结构），将这些「设计证据」注入到 CoderAgent 和 StyleAgent 的提示词上下文中，使生成结果有据可依。

```
┌──────────────────────────────────────────────────────────────────┐
│                      三阶段 RAG 架构                              │
├──────────┬──────────────────────┬───────────────────────────────┤
│ 阶段     │ 负责者               │ 产出                           │
├──────────┼──────────────────────┼───────────────────────────────┤
│ 一（先验）│ DesignTokenAgent     │ DesignSpec（CSS Token 契约）   │
│          │ 始终生效，零 LLM      │ 从 .md 文件加载，是硬兜底       │
├──────────┼──────────────────────┼───────────────────────────────┤
│ 二（离线）│ DesignExtractorAgent │ DesignFingerprint              │
│          │ LLM 提炼，异步构建    │ 入库 CANDIDATE → 审核 → APPROVED│
├──────────┼──────────────────────┼───────────────────────────────┤
│ 三（运行）│ DesignRetrieveTool   │ DesignEvidence                 │
│          │ 向量+结构化检索       │ 按开关启用，注入 Coder/Style    │
└──────────┴──────────────────────┴───────────────────────────────┘
```

### 在流水线中的位置

```
ProductAgent → ArchitectAgent → DesignTokenAgent(阶段一)
→ DesignRetrieveAgent(阶段三, 按开关) → ImageAgent
→ CoderAgent ∥ StyleAgent → CriticAgent → DeployAgent
```

---

## 二、数据模型（指纹侧）

### 2.1 核心表一览

| 表 | 存储引擎 | 用途 |
|----|---------|------|
| `design_fingerprint` | MySQL | 六层指纹主数据 + 状态/优先级 |
| `design_fingerprint_review_log` | MySQL | 管理员审核操作日志 |
| `design_fingerprint_vector` | PGvector | 向量 embedding（`styleProfile + tone` 拼接） |

### 2.2 DesignFingerprint（MySQL 主表）

文件：`model/entity/DesignFingerprint.java`  
DDL：`docs/sql/design_fingerprint.sql`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 雪花主键 |
| `styleProfile` | VARCHAR | 风格画像：startup/enterprise/ecommerce/portfolio/dashboard/landing/default |
| `sourceType` | VARCHAR | EXTRACTED_FINGERPRINT / SELF_FED_FINGERPRINT |
| `status` | VARCHAR | CANDIDATE → APPROVED → REJECTED → ARCHIVED |
| `signature` | VARCHAR | MD5 去重签名 |
| `confidence` | DOUBLE | 置信度 0~1 |
| `priority` | INT | 优先级（默认 30，低于静态规范 50） |
| `tone` | VARCHAR | 调性关键词（逗号分隔） |
| `fingerprintJson` | TEXT | 完整六层指纹 JSON |
| `ownerUserId` | BIGINT | 归属用户 ID |
| `sourceScope` | VARCHAR | GLOBAL / USER / PROJECT |
| `visibility` | VARCHAR | PUBLIC / PRIVATE / TEAM |

关键索引：`idx_profile_status`、`idx_signature`、`idx_status_priority`

### 2.3 DesignFingerprintDTO — 六层指纹结构

文件：`model/dto/agent/DesignFingerprintDTO.java`

```
① tokens        → colorRatio, fontScale, radii, shadow
② layout        → skeleton, whitespace, focus
③ components    → List<String>
④ interaction   → motion, transition
⑤ antiPatterns  → List<String>
⑥ styleProfile  + tone
```

### 2.4 DesignFingerprintVector（PGvector 向量表）

文件：`model/entity/DesignFingerprintVector.java`  
DDL：`docs/sql/design_fingerprint_vector.sql`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `fingerprintId` | BIGINT | 关联 MySQL 主表 ID |
| `content` | TEXT | `styleProfile + tone` 拼接文本 |
| `embedding` | vector(768) | 768 维向量（由 EmbeddingModel 生成） |

### 2.5 扩展表（未来启用）

根据 `docs/rag-design.md` 第二部分规划，还设计了以下扩展表：

| 表 | 用途 |
|----|------|
| `generated_page` | 每次生成的页面快照，供反馈回路追溯 |
| `generation_rag_trace` | RAG 检索链路追踪（检索词、命中 ID、耗时） |
| `project_brand_profile` | 项目级品牌记忆（跨对话持久化） |

---

## 三、用户画像体系（个性化侧）

> 用户画像是 RAG 体系的"个性化增强层"——让同一个知识库对不同的用户召回不同的结果，真正实现"越用越懂你"。

### 3.1 三张核心表

| 表 | 粒度 | 作用 |
|----|------|------|
| `user_design_profile` | 每用户一条 | 聚合画像 JSON：偏好的风格/颜色/布局/组件列表 + 自然语言摘要 |
| `user_preference_weight` | 每标签一条 | 精细化权重：`(userId, preferenceType, preferenceKey)` 唯一，weight 正=喜欢、负=反感 |
| `user_design_event` | 每行为一条 | 不可变行为日志：保存/导出/删除/改色/改布局/增减组件等 |

**DDL**：`docs/sql/001_user_preference.sql`

### 3.2 user_design_profile — 用户设计画像

文件：`model/entity/UserDesignProfile.java`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 雪花主键 |
| `userId` | BIGINT UNIQUE | 每用户仅一条 |
| `profileSummary` | TEXT | 自然语言偏好摘要（例："该用户偏爱极简深色风格，喜欢卡片布局"） |
| `preferredStyles` | JSON | 偏好的风格列表：`["minimal","tech","premium"]` |
| `dislikedStyles` | JSON | 不喜欢的风格 |
| `preferredColors` | JSON | 偏好颜色系列 |
| `dislikedColors` | JSON | 不喜欢的颜色 |
| `preferredLayouts` | JSON | 偏好布局类型 |
| `dislikedLayouts` | JSON | 不喜欢的布局 |
| `preferredComponents` | JSON | 偏好组件类型 |
| `dislikedComponents` | JSON | 不喜欢的组件 |
| `preferredInteractions` | JSON | 偏好交互模式 |
| `dislikedInteractions` | JSON | 不喜欢的交互模式 |

### 3.3 user_preference_weight — 精细化权重表

文件：`model/entity/UserPreferenceWeight.java`

联合唯一键 `(userId, preferenceType, preferenceKey)`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `userId` | BIGINT | 用户 ID |
| `preferenceType` | VARCHAR(64) | 类型：`style` / `color` / `layout` / `component` / `interaction` |
| `preferenceKey` | VARCHAR(128) | 具体标签，如 `"minimal"`, `"dark"`, `"grid"`, `"card"` |
| `weight` | DOUBLE | **正数=喜欢，负数=反感**（绝对值越大越强烈） |
| `evidenceCount` | INT | 证据累计次数 |
| `lastEvidenceAt` | DATETIME | 最后一次证据时间 |

关键设计：weight 的正负号直接区分喜欢/反感，绝对值代表偏好强度。查询时 `ORDER BY weight DESC` 即可获取 Top-K 偏好。

### 3.4 user_design_event — 不可变行为日志

文件：`model/entity/UserDesignEvent.java`

**只写不删**的事件日志，每次用户操作追加一条：

| 事件类型 | 触发场景 |
|---------|---------|
| `page_saved` | 用户保存了生成的页面 |
| `page_exported` | 用户导出了页面（强正反馈） |
| `page_deleted` | 用户删除了页面 |
| `page_regenerated` | 用户重新生成（轻微不满） |
| `color_changed` | 用户改变了颜色 |
| `layout_changed` | 用户改变了布局 |
| `component_added` | 用户添加了组件 |
| `component_removed` | 用户移除了组件 |
| `feedback_submitted` | 用户提交了反馈 |
| `user_rating_5` | 用户给了 5 星 |
| `user_rating_1_2` | 用户给了 1-2 星 |

### 3.5 权重更新引擎：PreferenceUpdateWorker

文件：`worker/PreferenceUpdateWorker.java`

**纯规则驱动，无 LLM 参与**。入口 `recordAndUpdate(event)`：

```
1. 事件 INSERT 到 user_design_event（不可变日志）
2. 根据 eventType 调用规则 → applyPreferenceUpdate()
3. 原子化 upsert user_preference_weight（先 UPDATE 后 INSERT，防并发冲突）
```

**规则映射表**：

| 事件 | weight delta | 说明 |
|------|:---:|------|
| `page_saved` | **+0.1** | 轻度正反馈 |
| `page_exported` | **+0.2** | 强正反馈 |
| `page_deleted` | **-0.1** | 轻度负反馈 |
| `page_regenerated` | **-0.05** | 轻微不满 |
| `color_changed` | 旧:-0.2 / 新:**+0.2** | 讨厌旧颜色，偏爱新颜色 |
| `component_added` | **+0.1** | 喜欢该组件 |
| `component_removed` | **-0.2** | 讨厌该组件 |
| `layout_changed` | 旧:-0.1 / 新:**+0.1** | 布局偏好切换 |

**并发安全的原子更新**（第 188-207 行）：

```sql
-- Step 1: 利用 MySQL 行锁原子加减
UPDATE user_preference_weight
SET weight = weight + ?, evidenceCount = evidenceCount + 1
WHERE userId = ? AND preferenceType = ? AND preferenceKey = ?

-- Step 2: 受影响行数为 0 → 首次 INSERT
INSERT INTO user_preference_weight (...) VALUES (...)

-- Step 3: UNIQUE 冲突（并发 INSERT）→ 重试 Step 1
```

### 3.6 还有一个离线入口：updateFromFingerprint

`PreferenceUpdateWorker.updateFromFingerprint(userId, fingerprint, delta)` 从 `DesignFingerprintDTO` 六层结构中批量提取标签（style/color/layout/component/interaction）并更新权重。例如管理员 APPROVE 一条"极简深色科技风"的指纹时，可以对该指纹涉及的用户批量 +0.05。

### 3.7 用户画像如何连接 RAG

```
UserPreferenceTool (注入在 V1/V2 流水线中)
    │
    ├─ 调用 UserPreferenceService.loadUserDesignContext(userId)
    │   ├─ 加载 user_design_profile
    │   └─ 加载 user_preference_weight
    │
    ├─ 写入 Blackboard: putUserDesignContext(context)
    │   （注意：UserPreferenceTool 只负责加载数据，不拼接 prompt；
    │    prompt 拼接由 ContextCompressTool 统一完成）
    │
    └─ DesignRetrieveTool 读取 UserDesignContext
         → 增强 DesignKnowledgeIndex.retrieve() 的检索精度
         → 同一个 styleProfile 下，不同用户的召回结果不同
```

**一个具体例子**：

```
用户 A：喜欢 minimal + dark → 检索偏好 = [minimal, dark, card, grid]
用户 B：喜欢 enterprise + light → 检索偏好 = [enterprise, light, table, form]

两个用户都生成"电商"应用（styleProfile=ecommerce）
→ 知识库中有 20 条 ecommerce 指纹
→ 用户 A 召回：偏极简深色的 5 条
→ 用户 B 召回：偏企业亮色的 5 条
```

### 3.8 profileSummary 自然语言摘要

`UserDesignProfile.profileSummary` 字段用于存储偏好权重的自然语言聚合句。该字段在 `loadUserDesignContext()` 时被读取并注入 `UserDesignContext`，最终由 `ContextCompressTool` 拼接到 LLM prompt 中。

> "该用户偏爱极简(minimal)和科技(tech)风格，偏好深色(dark)和蓝色(blue)色调，喜欢卡片(card)和网格(grid)布局，讨厌轮播(slider)组件"

**注意**：当前代码中 `profileSummary` 仅被读取，**没有自动定期刷新的实现**。该字段的更新需后续接入（如定时 Job 聚合 `user_preference_weight` 表中的 Top-K 标签拼接为摘要）。

### 3.9 对外接口现状

**当前没有独立的 `UserDesignProfileController`**，用户画像操作全部通过内部服务完成：

| 组件 | 调用方式 | 说明 |
|------|---------|------|
| `UserPreferenceService` | 流水线内部调用 | 加载画像 + 权重 |
| `PreferenceUpdateWorker` | 由生成流水线/事件触发 | 写入行为事件 + 更新权重 |
| `PromptPreferenceExtractor` | 由 `AppController.chatToGenCode` 调用 | 从 prompt 提取偏好并写入权重 |

> **待实现**：对外暴露 REST API（如 `POST /user_design_profile/event`、`POST /user_design_profile/feedback`），使前端能在用户交互（保存/删除/改色）时上报行为事件。

### 3.10 与 PromptPreferenceExtractor 的互补

| 维度 | PromptPreferenceExtractor | 用户画像体系（行为侧） |
|------|---------------------------|----------------------|
| 提取来源 | 当前对话 prompt（LLM 提取） | 历史操作行为（规则引擎） |
| 时效性 | 每次对话即时 | 长期累积 |
| 体现 | "这次想要什么" | "一直以来喜欢什么" |
| 写入方式 | LLM 提取 → 写入 `user_preference_weight` | 行为事件 → 写入 `user_preference_weight` |

两者写入**同一张权重表**，最终融合为统一的用户偏好向量，由 `UserPreferenceTool` 加载后注入 Blackboard。

---

### 4.1 全流程

```
FingerprintCrawlScheduler (定时任务)
    │  爬取精选页面的完整 HTML 源码
    ▼
DesignSourcePreprocessor (预处理)
    │  剥离 <script>、业务文案、个人信息
    │  → 防止 Prompt Injection，防止商业内容污染指纹
    ▼
DesignExtractorAgent (LLM 提炼)
    │  调用 LLM (绑定 agent-extractor-system-prompt.txt)
    │  入参：净化后的 HTML
    │  出参：六层 JSON (DesignFingerprintDTO)
    ▼
FingerprintParser (校验)
    │  JSON 结构校验 + 必填字段检查
    │  六层每层至少一个有效标签
    ▼
入库 MySQL (design_fingerprint)
    │  状态：CANDIDATE
    │  MD5 signature 去重（已存在则跳过）
    ▼
管理员审核 (AdminDashboard 指纹管理页)
    │  APPROVE → 可检索
    │  REJECT  → 记录原因，不入检索池
    ▼
VectorSyncWorker (PGvector 同步)
    │  拼接 content = styleProfile + " " + tone
    │  调用 EmbeddingModel.embed(content) → vector(768)
    │  UPSERT INTO design_fingerprint_vector
    │  （注：旧版 VectorSyncService 已 @Deprecated，功能迁移至 VectorSyncWorker）
    ▼
可检索 (DesignKnowledgeIndex.retrieve)
```

### 4.2 DesignSourcePreprocessor 处理逻辑

文件：`service/design/DesignSourcePreprocessor.java`

采用**选择性提取**策略（而非主动移除业务文案）：
- 移除所有 `<script>` 标签（防 XSS/Prompt Injection）
- 提取结构化标签计数（header/nav/main/section/article/aside/footer/h1-h3/form/button/table/ul/ol）
- 提取关键 CSS 声明（color/background/font-size/padding/margin/border-radius 等）
- 输出格式化的"结构骨架 + 关键样式"摘要（`summarizeHtml()`）

> 注意：业务文案（公司名、价格等）不被主动剥离，而是因为只提取结构信息和 CSS 属性而被自然过滤。

### 4.3 指纹提炼规则（LLM Prompt 约束）

- 必须使用固定 JSON Schema 输出
- `tokens` 中不能出现品牌色（如 #ff0000 要泛化为"强调红"）
- `antiPatterns` 优先级最高——检测到的反模式务必记录
- 每条 `preferredPattern` 携带代码示例

---

## 五、阶段三：运行时检索

### 5.1 DesignKnowledgeIndex 三层检索策略

文件：`service/rag/DesignKnowledgeIndex.java`

两个重载入口：
- 基础版：`retrieve(styleProfile, queryTags, staticSpec, topK)`
- 个性化增强版：`retrieve(styleProfile, queryTags, staticSpec, requirement, plan, userContext, topK)`

```
retrieve(...)
  │
  ├─ Layer 1: VECTOR_3B（PGvector 向量检索，受 agent.retrieve.vector.enabled 子开关控制）
  │     content = styleProfile + " " + queryTags.join(" ")
  │     embedding = EmbeddingModel.embed(content) → vector(768)
  │     vectorRepo.search(embedding, topK) → 余弦距离最近的 K 条
  │     失败则降级到 Layer 2
  │
  ├─ Layer 2: STRUCTURAL_3A（MySQL 结构化检索）
  │     fingerprintService.listApproved(styleProfile, limit)
  │     → 查 status=APPROVED 且 styleProfile 匹配的指纹
  │     → Java 侧 softMatchScore() 对 queryTags 做软匹配评分排序
  │     →（注意：关键词匹配在 Java 层完成，非 SQL REGEXP）
  │
  └─ Layer 3: STATIC_FALLBACK（始终加入）
         classpath:skill/designs/{profile}.md
         → 解析 preferredPatterns / forbiddenPatterns
         → 作为兜底证据条目加入结果
```

### 5.2 去重与排序

三层结果合并后：
1. 按 `sourceId` 去重（向量层和结构化层可能命中同一指纹）
2. 按 `priority DESC, confidence DESC` 统一排序
3. 截取 Top-K（由 `GenerationPolicy.maxEvidenceCount` 控制）

### 5.3 DesignEvidence 数据结构

检索结果包装为 `DesignEvidence` DTO（`model/dto/agent/DesignEvidence.java`）：

```
DesignEvidence {
    String styleProfile;           // 风格画像
    String channel;                // 命中的检索通道（STRUCTURAL_3A / VECTOR_3B / STATIC_FALLBACK）
    List<Item> items;              // 按优先级排序的证据条目列表
}

DesignEvidence.Item {
    String sourceType;             // APPROVED_FINGERPRINT / STATIC_SPEC
    String sourceId;               // 指纹 ID 或 .md 文件名
    int priority;                  // 优先级
    double confidence;             // 置信度
    List<String> tone;             // 调性标签
    List<String> layoutPatterns;   // 布局模式
    List<String> preferredPatterns;// 推荐写法
    List<String> interactionPatterns; // 交互规则
    List<String> forbiddenPatterns;// 反模式
}
```

写入 Blackboard：`putDesignEvidence(evidence)`

> 注意：证据按**条目（Item）维度**组织，不做预聚合；聚合逻辑由消费方（`ContextCompressTool`）在拼接 prompt 时完成。

---

## 六、消费：注入 LLM Prompt

### 6.1 CoderAgent prompt 注入

文件：`agent/codegen/CoderAgent.java`

设计证据由 `ContextCompressTool` 统一压缩拼接后注入 prompt。CoderAgent 的 `buildUserMessage()` 读取 Blackboard 的压缩上下文，最终注入内容形如：

```
=== 设计参考证据（来自精选应用库）===

【推荐写法】
- 使用 flexbox 布局，避免 float
- 卡片组件使用白色背景 + box-shadow
- ...

【禁用模式】
- 禁止使用 backdrop-filter 毛玻璃
- 禁止纯装饰性渐变背景
- ...

【CSS Token 清单】
--bg-page, --bg-card, --text-primary, --accent, ...

【调性参考】
克制、专业、中性色调
```

### 6.2 StyleAgent prompt 注入

文件：`agent/style/StyleAgent.java`

StyleAgent 同样读取 `designEvidence`，重点关注：

- `aggregatedTokens`：定义 `variables.css` 的 `:root {}` 变量
- `toneSummary`：影响 `global.css` 的整体风格基调
- `aggregatedForbiddenPatterns`：禁止使用的 CSS 模式

### 6.3 tokenGroups 强契约机制

**这是 RAG 最关键的质量保障机制**：

```
DesignSpec.tokenGroups = {"--bg-page", "--bg-card", "--text-primary", ...}

            │ 同时注入
    ┌───────┴───────┐
    ▼               ▼
StyleAgent       CoderAgent
"定义这些变量"    "只能引用这些变量"
```

双边强制约束：Style 负责创建、Coder 只引用，变量名严格一致。CriticAgent 的 `dangling-var` 检查会扫描所有文件，发现引用了未定义的变量立即标记 P0 错误。

---

## 七、四重降级保障

```
Level 1: 检索异常（网络/向量库不可用）
         → 跳过 DesignRetrieveAgent，不影响后续步骤

Level 2: DesignSpec 解析失败
         → 读取 classpath:skill/designs/default.md 内置规范

Level 3: default.md 也不可用
         → DesignProfileResolver 内置 Java 硬编码兜底规范
           (定义 12 个基础 CSS Token)

Level 4: 整个 retrieve 步骤抛异常
         → Orchestrator 的 onErrorResume 捕获，仅日志记录
         → 流水线继续执行（CoderAgent/StyleAgent 使用空 designEvidence）
```

---

## 八、开关与条件装配

### 配置层级

| 配置 | 位置 | 效果 |
|------|------|------|
| `agent.retrieve.enabled` | `application.yml` / `DesignRetrieveTool @Value` | 总开关，false 则整个检索步骤跳过 |
| `agent.retrieve.vector.enabled` | `application.yml` / `DesignKnowledgeIndex` | 向量检索子开关，false 则跳过 PGvector 层，仅走结构化+静态 |
| `@ConditionalOnBean(DesignFingerprintVectorRepository.class)` | `DesignKnowledgeIndex` 类注解 | 无 PGvector Repo → Index 不注入 |
| `@ConditionalOnBean(DesignKnowledgeIndex.class)` | `DesignRetrieveTool` 类注解 | 无 Index → Tool 不注入 → 流水线跳过 |
| `maxEvidenceCount` | `GenerationPolicy` | 控制注入证据条数（LOW=1, STD=3, HQ=5） |

### 证据条数控制

| 模式 | 证据条数 | 依据 |
|------|:---:|------|
| LOW_COST | 1 | 快速，不需要过多上下文 |
| STANDARD | 3 | 正常生成 |
| HIGH_QUALITY | 5 | 最多的设计参考 |

---

## 九、新增特性：PromptPreferenceExtractor

文件：`service/PromptPreferenceExtractor.java`（新增于 2026-06-27）

### 功能

从用户输入的自然语言 prompt 中**实时提取设计偏好关键词**，增强阶段三检索精度。

### 工作方式

**使用 LLM 调用**（非规则引擎），通过 LangChain4j `AiServices.create()` 构建 LLM 代理：

```java
interface PromptPreferenceAi {
    @SystemMessage("你是一个设计偏好提取器。从用户创建应用的提示词中提取设计偏好标签...")
    String extract(String prompt);
}
```

```
用户输入："帮我做一个科技感的数据大屏，要深色主题"
        ↓
PromptPreferenceExtractor.extractAndRecord(prompt, userId)
        ↓
1. LLM 调用 ai.extract(prompt)
   → 返回 JSON 偏好标签：{"style":["tech","dashboard"], "color":["dark"], ...}
        ↓
2. 解析 JSON，提取各类标签
        ↓
3. 调用 preferenceUpdateWorker.updateSingleWeight(userId, type, key, delta)
   → 写入 user_preference_weight 表
   → 与用户历史行为偏好融合（长期累积）
```

> 注意：提取的标签不是直接传递给检索 queryTags，而是通过写入 `user_preference_weight` 表，间接影响后续 `UserPreferenceTool` 加载的 `UserDesignContext`，最终由 `DesignRetrieveTool` 消费。

### 与用户行为偏好的区别

| 维度 | PromptPreferenceExtractor | UserPreferenceWeight（行为侧） |
|------|---------------------------|-------------------------------|
| 提取来源 | 当前对话 prompt | 历史操作行为（保存/删除/改色等） |
| 时效性 | 每次对话即时提取 | 长期累积 |
| LLM 参与 | **是**（LLM 提取标签） | 无（规则引擎） |
| 写入方式 | 提取后写入同一张权重表 | 行为事件触发写入同一张权重表 |

两者写入同一张 `user_preference_weight` 表，最终融合为统一的用户偏好向量。

---

## 十、前端消费

### 当前实现

前端没有直接的 RAG 状态 UI。检索结果通过以下路径影响用户可见产物：

```
DesignEvidence → Blackboard → CoderAgent/StyleAgent prompt 注入
→ 影响生成代码质量（颜色、布局、组件风格）
→ 用户看到更匹配预期的页面
```

### 可观测性

SSE 事件流中可能透传检索步骤的 `STATUS_CHANGE` 事件：

```
{ event: "STATUS_CHANGE", agentName: "DesignRetrieveAgent",
  payload: "正在检索设计参考…命中 3 条" }
```

前端 `sse.ts` 的 `onChunk` 会将此类文本输出到聊天气泡（以 `[阶段]` 前缀展示）。

### 未来增强（docs/rag-design.md 规划）

- 前端展示「设计灵感来源」（精选应用名称 + 预览缩略图）
- RAG 追踪面板（展示检索命中的 fingerprint 列表）
- 用户对此轮生成结果的反馈 → 写入 `user_design_event` → 更新权重

---

## 十一、关键文件索引

| 层级 | 文件 | 说明 |
|------|------|------|
| **设计文档** | `docs/rag-design.md` | 完整架构设计 + 三阶段升级路线 |
| **数据模型** | `model/entity/DesignFingerprint.java` | 指纹主表实体 |
| | `model/entity/DesignFingerprintVector.java` | PGvector 向量表 |
| | `model/dto/agent/DesignFingerprintDTO.java` | 六层指纹 DTO |
| **SQL** | `docs/sql/design_fingerprint.sql` | 指纹表 DDL |
| | `docs/sql/design_fingerprint_vector.sql` | 向量表 DDL |
| **离线提炼** | `agent/workers/DesignExtractorAgent.java` | LLM 提炼指纹 |
| | `service/design/DesignSourcePreprocessor.java` | HTML 预处理 |
| | `service/design/FingerprintParser.java` | 指纹 JSON 校验+置信度+MD5签名 |
| | `worker/FingerprintCrawlScheduler.java` | 定时爬取精选应用 |
| | `worker/VectorSyncWorker.java` | 审核后同步 PGvector |
| **运行时检索** | `service/rag/DesignKnowledgeIndex.java` | 三层检索入口 |
| | `tool/system/DesignRetrieveTool.java` | 检索工具（V1 Agent / V2 SystemTool） |
| **上下文装配** | `orchestrator/DesignContextAssembler.java` | V2 设计上下文统一装配（6 个 System Tool） |
| | `tool/system/ContextCompressTool.java` | 上下文压缩 + prompt 拼接 |
| **提示词注入** | `agent/codegen/CoderAgent.java` | CoderAgent prompt 装配 |
| | `agent/style/StyleAgent.java` | StyleAgent prompt 装配 |
| **审核管理** | `controller/DesignFingerprintController.java` | 审核 API |
| | `pages/admin/AdminFingerprintPage.vue` | 管理端审核页面 |
| **用户画像** | `model/entity/UserDesignProfile.java` | 用户画像实体 |
| | `model/entity/UserPreferenceWeight.java` | 偏好权重实体 |
| | `model/entity/UserDesignEvent.java` | 行为事件实体 |
| | `worker/PreferenceUpdateWorker.java` | 偏好更新引擎（规则驱动） |
| | `agent/support/UserPreferenceTool.java` | 偏好注入工具（流水线中消费） |
| **新增特性** | `service/PromptPreferenceExtractor.java` | Prompt 关键词实时提取 |
| **SQL** | `docs/sql/001_user_preference.sql` | 画像/权重/事件三表 DDL |
| **配置** | `application.yml` → `agent.retrieve.enabled` | RAG 总开关 |
