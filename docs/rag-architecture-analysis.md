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
    ├─ 读取 user_design_profile → 获取 preferredStyles / preferredColors / ...
    ├─ 读取 user_preference_weight → queryTags: 权重最高的 Top-N 标签
    │
    ├─ 注入 UserPreferenceContext → Blackboard
    │
    └─ DesignRetrieveTool 读取 queryTags
         → 增强 DesignKnowledgeIndex.retrieve(tags) 的检索精度
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

`UserDesignProfile.profileSummary` 字段将偏好权重聚合为一个自然语言句子（由 `UserDesignProfileService` 定期刷新），直接注入 CoderAgent/StyleAgent 的 prompt 中：

> "该用户偏爱极简(minimal)和科技(tech)风格，偏好深色(dark)和蓝色(blue)色调，喜欢卡片(card)和网格(grid)布局，讨厌轮播(slider)组件"

这使得即便 RAG 检索失败（阶段三关闭或空结果），用户偏好仍然通过这段文本注入到生成中。

### 3.9 对外接口

`UserDesignProfileController`：

| 接口 | 说明 |
|------|------|
| `GET /user_design_profile/{userId}` | 获取用户画像 |
| `POST /user_design_profile/event` | 上报行为事件（触发权重更新） |
| `POST /user_design_profile/feedback` | 用户主动反馈（rating + comment） |

前端在用户对生成结果进行操作时调用上报接口，形成闭环。

### 3.10 与 PromptPreferenceExtractor 的互补

| 维度 | PromptPreferenceExtractor | 用户画像体系 |
|------|---------------------------|-------------|
| 提取来源 | 当前对话 prompt | 历史行为日志 |
| 时效性 | 即时 | 长期累积 |
| 体现 | "这次想要什么" | "一直以来喜欢什么" |
| LLM 参与 | 无（规则引擎） | 无（规则引擎） |
| 权重 | 单次对话有效 | 永久存储，跨对话累积 |

两者在检索时**合并**为最终的 query tags：`extract(prompt) + getTopTags(userId, topK=10)`

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
VectorSyncService (PGvector 同步)
    │  拼接 content = styleProfile + " " + tone
    │  调用 EmbeddingModel.embed(content) → vector(768)
    │  INSERT INTO design_fingerprint_vector
    ▼
可检索 (DesignKnowledgeIndex.retrieve)
```

### 4.2 DesignSourcePreprocessor 安全措施

- 移除所有 `<script>` 标签（防 XSS/注入）
- 移除业务文案（公司名、价格、用户数据）
- 保留结构标签 + CSS 类名 + 布局信息
- 保留设计相关属性（颜色、字体、间距）

### 4.3 指纹提炼规则（LLM Prompt 约束）

- 必须使用固定 JSON Schema 输出
- `tokens` 中不能出现品牌色（如 #ff0000 要泛化为"强调红"）
- `antiPatterns` 优先级最高——检测到的反模式务必记录
- 每条 `preferredPattern` 携带代码示例

---

## 四、阶段二：离线知识库建设## 五、阶段三：运行时检索

### 4.1 DesignKnowledgeIndex 三层检索策略

文件：`service/rag/DesignKnowledgeIndex.java`

```
retrieve(styleProfile, queryTags)
  │
  ├─ Layer 1: VECTOR_3B（PGvector 向量检索）
  │     content = styleProfile + " " + tone.join(" ")
  │     embedding = EmbeddingModel.embed(content) → vector(768)
  │     PGvector: SELECT *, embedding <=> ? AS distance
  │            FROM design_fingerprint_vector
  │            ORDER BY distance ASC LIMIT topK
  │     → 余弦距离最近的前 K 个指纹
  │
  ├─ Layer 2: STRUCTURAL_3A（MySQL 结构化检索）
  │     SELECT * FROM design_fingerprint
  │     WHERE status = 'APPROVED'
  │       AND styleProfile = ?
  │       AND (tone REGEXP ? OR fingerprintJson REGEXP ?)
  │     ORDER BY priority DESC, confidence DESC
  │     → 精确匹配 styleProfile + 关键词匹配
  │
  └─ Layer 3: STATIC_FALLBACK（静态兜底）
         classpath:skill/designs/{profile}.md
         → 解析 preferredPatterns 节
         → 作为兜底证据返回
```

### 4.2 去重与排序

三层结果：
1. 按 `fingerprintId` 去重（向量层和结构化层可能命中同一指纹）
2. 按 `priority DESC, confidence DESC` 统一排序
3. 截取 Top-K（由 `maxEvidenceCount` 控制）

### 4.3 DesignEvidence 包装

检索结果包装为 `DesignEvidence` DTO：

```
DesignEvidence {
    List<DesignFingerprintDTO> fingerprints;
    Map<String, List<String>> aggregatedTokens;     // 合并去重 CSS Token
    List<String> aggregatedPreferredPatterns;        // 推荐写法
    List<String> aggregatedForbiddenPatterns;         // 反模式
    String toneSummary;                               // 调性摘要文本
}
```

写入 Blackboard：`putDesignEvidence(evidence)`

---

## 六、消费：注入 LLM Prompt

### 5.1 CoderAgent prompt 注入

文件：`agent/codegen/CoderAgent.java`

CoderAgent 从 Blackboard 读取 `designEvidence`，`buildUserMessage()` 方法注入：

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

### 5.2 StyleAgent prompt 注入

文件：`agent/style/StyleAgent.java`

StyleAgent 同样读取 `designEvidence`，重点关注：

- `aggregatedTokens`：定义 `variables.css` 的 `:root {}` 变量
- `toneSummary`：影响 `global.css` 的整体风格基调
- `aggregatedForbiddenPatterns`：禁止使用的 CSS 模式

### 5.3 tokenGroups 强契约机制

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
| `agent.retrieve.enabled=true` | `application.yml` | 总开关 |
| PGvector EmbeddingModel Bean 存在 | Spring 自动检测 | 无则向量检索降级为纯结构化 |
| `@ConditionalOnBean(DesignKnowledgeIndex.class)` | DesignRetrieveTool 类注解 | 无则 Tool 不注入 → 流水线跳过 |
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

```
用户输入："帮我做一个科技感的数据大屏，要深色主题"
        ↓
PromptPreferenceExtractor.extract(prompt)
        ↓
关键词映射（规则引擎，无 LLM 调用）：
  "科技感"   → style=tech, color=dark, component=chart
  "数据大屏" → style=dashboard, layout=grid
  "深色主题" → color=dark, color=blue
        ↓
关键词列表：["tech", "dark", "dashboard", "grid", "chart", "blue"]
        ↓
追加到 DesignRetrieveTool 的 queryTags 参数
        ↓
结构化和向量检索同时匹配这些关键词 → 更精准的 Top-K
```

### 与用户偏好记忆的区别

| 维度 | PromptPreferenceExtractor | UserPreferenceWeight |
|------|---------------------------|----------------------|
| 提取来源 | 当前对话 prompt | 历史行为日志 |
| 时效性 | 即时 | 长期累积 |
| 更新方式 | 每次对话提取一次 | 每次行为事件触发 |
| LLM 参与 | 无（规则引擎） | 无（规则引擎） |

两者互补：即时意图 + 长期偏好 → 更精准的检索。

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
| **离线提炼** | `agent/design/extractor/DesignExtractorAgent.java` | LLM 提炼指纹 |
| | `agent/support/DesignSourcePreprocessor.java` | HTML 预处理 |
| **运行时检索** | `service/rag/DesignKnowledgeIndex.java` | 三层检索入口 |
| | `agent/support/DesignRetrieveTool.java` | 检索工具（注入流水线） |
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
