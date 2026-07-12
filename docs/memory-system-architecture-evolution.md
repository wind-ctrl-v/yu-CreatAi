# CreatAi 记忆系统架构演进设计

> 生成日期：2026-07-10
> 基于代码库 commit `9c553c7`
> 定位：与 `knowledge-base-design.md`（RAG/语义记忆详细设计）、`memory-system-review.md`（组件现状审查）互补，从**记忆分层架构**视角切入。

---

## 目录

1. [问题陈述：两层够不够](#1-问题陈述两层够不够)
2. [现状盘点：系统已自发长出 5 类记忆载体](#2-现状盘点系统已自发长出-5-类记忆载体)
3. [根因：分类维度错了](#3-根因分类维度错了)
4. [目标模型：按功能分的三层记忆](#4-目标模型按功能分的三层记忆)
   - [4.1 项目记忆的定位与启用前提](#41-项目记忆的定位与启用前提)
5. [缺口分析：一个伪缺口 + 一个真缺口](#5-缺口分析一个被证伪的伪缺口--一个真缺口)
   - [5.1 被证伪：不需要"负向记忆 / 程序记忆库"](#51-被证伪不需要负向记忆--程序记忆库)
   - [5.2 唯一的真缺口：记忆固化通道](#52-唯一的真缺口记忆固化通道consolidation)
6. [目标架构：统一编排与仲裁](#6-目标架构统一编排与仲裁)
7. [分阶段演进路径](#7-分阶段演进路径)
8. [关键类索引](#8-关键类索引)

---

## 1. 问题陈述：两层够不够

CreatAi 当前被描述为"二层记忆架构"：

- **短期记忆**：对话历史（`ChatHistory` 表 + Redis）
- **长期记忆**：知识库设计指纹 / 静态规范 + 用户偏好数据

结论先行：**两层不够用——但问题不在"层数少"，而在"分类维度错了"。**

"短期 / 长期"是按**时间 / 存储介质**划分的，它把性质完全不同的东西（全局共享知识、项目私有约束、用户个性化偏好）粗暴地塞进"长期"这一个筐里，同时**漏掉了已经客观存在的工作记忆**（`Blackboard`）。当不同性质的记忆需要以不同优先级注入 prompt 时，这种二分法无法表达它们之间的强弱与仲裁关系。

---

## 2. 现状盘点：系统已自发长出 5 类记忆载体

> 术语：本节数的是 **5 个载体**（物理存储/组件）；§4 把它们归并为 **3 个功能层**（映射见 §4 末）。"载体数 ≠ 层数"，不矛盾。

从代码看，以下"记忆载体"其实**已经存在**，只是没有被统一命名和编排：

| 实际载体 | 记忆性质 | 作用域 | 位置 | 当前归类 |
|---|---|---|---|---|
| `Blackboard` | **工作记忆**（单次生成的临时共享状态） | 单次生成 | `agent/core/Blackboard.java` | 未被当作"记忆" |
| `ChatHistory` + Redis | **情景记忆**（对话历史） | app 级 | `model/entity` + Redis | 归"短期" |
| `ProjectBrandProfile` | **项目 / 实体记忆**（项目品牌约束） | project 级 | `model/entity/ProjectBrandProfile.java` | 归"长期"（性质不同） |
| `user_preference_weight`（含时间衰减） | **用户语义记忆**（私有偏好） | user 级 | 见 `knowledge-base-design.md` §8 | 归"长期" |
| 设计指纹 / 静态规范 | **全局语义记忆**（共享知识） | 全局 | `design_fingerprint` + `skill/designs/*.md` | 归"长期" |

`Blackboard` 内部键（节选）已印证其"工作记忆"本质：`ragTraceId`、`designSpec`、`designEvidence`、`userDesignContext`、`projectBrandContext`、`requirementDoc`、`plan`、`criticReport` —— 这正是一次生成中多 Agent 协作的全部中间产物。

`ProjectBrandProfile` 的注释与逻辑标注了明确的优先级：**当前 prompt 需求 > 项目品牌 > 用户长期偏好**，说明**记忆冲突仲裁的需求已经出现**，只是散落在各处硬编码，而非统一机制。

> **项目记忆的适用边界（重要）**：`ProjectBrandProfile` 的检索是 `ProjectBrandService.loadProjectBrandContext(projectId)` 按 `projectId`（实际取自 `ctx.getAppId()`）的**主键式精确查询**，**每次生成都会查**、不挑轮次。但它**只在多轮迭代场景才有实际价值**——详见 [§4.1 项目记忆的定位与启用前提](#41-项目记忆的定位与启用前提)。

---

## 3. 根因：分类维度错了

二分法的两个具体病症：

1. **强约束与弱参考混为一谈**
   `ProjectBrandProfile`（该项目**必须遵守**的强约束）和知识库指纹（**可选参考**的弱证据）都被叫做"长期记忆"，注入 prompt 时优先级混乱。

2. **工作记忆被排除在体系之外**
   `Blackboard` 承载了单次生成的全部临时状态，本质是工作记忆，却从未被纳入"记忆系统"来治理（生命周期、可观测、清理策略都缺位）。

> 注：曾以为还有第三条"缺少负向经验的沉淀位置"，经 §5.1 逐层剥离后证明**不成立**——负反馈要么走自动修复，要么归入现有载体的负向条目。

---

## 4. 目标模型：按功能分的三层记忆

> 说明：认知科学里常见的"程序记忆（Procedural）"通常指**流程/工具调用套路**。但 CreatAi 是 **workflow 固定编排**，工具调用顺序已硬编码进代码，LLM 只在固定节点做基本增删改——**流程类程序记忆对本系统不适用**，故不单列该层。经过对"负向经验"的逐层剥离（见 §5.1），也**不需要独立的"负向记忆层"**：负反馈的正确归宿是已有语义记忆载体里的**负向条目**（负权重 / `forbiddenRules`）。

借鉴 LLM Agent 记忆分类，把现有载体**重新组织（正名 + 编排，非推倒重来）**：

```
┌─ 工作记忆 Working（易失，单次生成）
│    └─ Blackboard  ← 已有，纳入体系即可
│
├─ 情景记忆 Episodic（会话历史，可摘要沉淀）
│    └─ ChatHistory + Redis  ← 已有
│
└─ 语义记忆 Semantic（事实 / 知识 / 偏好，正负同表）
     ├─ 全局共享：设计指纹 / 静态规范      （正向弱参考，已有 RAG）
     ├─ 项目私有：ProjectBrandProfile      （强约束，含 forbiddenRules 负向）
     └─ 用户私有：偏好权重（含衰减）        （个性化，已支持负权重）
```

> **负反馈不是新的一层**：用户不喜欢的 → `user_preference_weight` 负权重（已实现）；项目禁止的 → `ProjectBrandProfile.forbiddenRules`（字段已存在）；确定性违规 → 不进记忆，走自动修复（见 §5.1）。

### 职责对照

| 层 | 存什么 | 生命周期 | 注入 prompt 时的角色 |
|---|---|---|---|
| 工作 Working | 本次生成的中间产物 | 单次生成，用完即弃 | 内部协作，不直接注入 |
| 情景 Episodic | 对话轮次 | 会话级，可摘要沉淀 | 上下文连续性 |
| 语义（全局） | 通用设计规律（正向） | 长期 | 弱参考证据 |
| 语义（项目） | 项目约定/品牌（含禁令） | 项目生命周期 | 强约束（正+负） |
| 语义（用户） | 个性化偏好（正+负权重） | 长期，带衰减 | 个性化调节 |

> **5 载体 → 3 层映射**：`Blackboard`→工作；`ChatHistory+Redis`→情景；`设计指纹`+`ProjectBrandProfile`+`user_preference_weight`→语义（三档强度）。即 §2 的 5 个载体在功能上归为 3 层。

### 4.1 项目记忆的定位与启用前提

项目记忆（`ProjectBrandProfile`）在三类语义记忆里最容易被质疑"是否多余"，这里明确它的边界。

**它填的是另外两者结构上够不着的洞：**

| | 作用域 | 能否表达"这个项目和我其他项目不一样" |
|---|---|---|
| 用户偏好 | **跨项目**（user 级） | ❌ 不能——同一用户的银行后台与儿童游戏站会互相污染 |
| 精选模板 | **起点**（一次性快照） | ❌ 不能——项目开工后确立的决策不回写模板 |
| 项目记忆 | **单项目**（project 级） | ✅ 能 |

**它主要服务于同一项目内的"多轮迭代 / 跨会话一致性"**，解决两个情景记忆的短板：

1. 多轮迭代下对话历史超窗口被截断，早期确立的决策（第 1 轮定的"暗色主题"）在第 N 轮时已被截掉；
2. 跨会话时对话历史归零，用户次日回来续做，之前的约定全丢。

项目记忆把"**在迭代中确立、但情景记忆留不住**"的决策沉淀到 project 级，让第 N 轮乃至新会话都自动延续同一套基调。

**"项目稳定约定"指什么**：不是某一次的临时选择，而是在项目中**反复复现或被显式声明**、应持续遵守的设计规则——对应 `ProjectBrandProfile` 的字段：`brandColors`（主色）、`brandTone`（基调）、`layoutPreference`（布局）、`typographyPreference`（字体）、`componentPreference`（组件套路）、`forbiddenRules`（禁令，如"禁用渐变"）。

**启用前提（避免过度设计）**：

- **单次生成为主** → 检索每次命中空、纯空转 → **暂不投入**；
- **多轮迭代 / 跨会话续做为高频** → 才值得做，此时它是项目内一致性的锚点。

> 冷启动说明：第 1 轮生成时该项目尚无画像，检索返回空上下文（非 bug），收益从第 2 轮起显现。

---

## 5. 缺口分析：一个被证伪的伪缺口 + 一个真缺口

### 5.1 被证伪：不需要"负向记忆 / 程序记忆库"

曾设想的"缺口"是——`CriticAgent` 反复挑出的"错误模式 → 修复手法"没有跨会话沉淀，应建负向证据库。经逐层剥离，**这个缺口不成立**：

**第一层剥离**——两类经验已被现有体系覆盖：

- **流程 / 工具调用套路**：workflow 已把调用顺序硬编码 → 不适用。
- **"需求 → 布局 / 组件套路"**：正是**设计指纹 RAG** 在做的事（从优秀页面提炼 → 语义召回 → 注入 Coder/Style）→ 已覆盖。

**第二层剥离**——剩下的 Critic "错误→修法"再拆两类，两头都不讨好：

| 错误来源 | 生成前是否已约束 | 能否结构化沉淀 | 该怎么处理 |
|---|---|---|---|
| **确定性反模式**（`DesignLintChecker` 7 种 + 文件检查） | ✅ **已硬约束**（见下） | ✅ 易 | **确定性自动修复**，不是记忆 |
| **LLM 主观 issue** | ❌ 未约束 | ❌ 发散难归一化 | 性价比低，暂不沉淀 |

确定性反模式在 `StyleAgent` prompt 里已是"违反视为严重错误"级硬约束：

```71:76:src/main/resources/prompt/agent-style-system-prompt.txt
## 设计禁用约束（违反视为严重错误）

- 禁止 `backdrop-filter` 毛玻璃效果。
- 禁止纯装饰性的 linear-gradient / radial-gradient 背景。
- 颜色 / 字号 / 间距 / 圆角一律用变量，禁止与 colorTheme 冲突的硬编码颜色...
- 若输入提供 forbiddenPatterns，需一并遵守。
```

**关键论证**：对确定性问题，"沉淀→检索→再注入生成"是**无意义闭环**。因为模型反复犯不是"**不知道**"（约束已在 prompt），而是"**没执行到位**"。检索注入解决的是知识缺失，解决不了执行合规——把已说过的禁令再说一遍，模型大概率还是不听。它们**能被规则精确检测**，正确解法是**检测到就直接改**（确定性 auto-fixer，零 LLM 成本），而非记下来下次提醒。

**第三层剥离**——盘点其余负反馈，全都无需独立负向库：

| 负反馈 | 归宿 | 状态 |
|---|---|---|
| 用户不喜欢的风格 | `user_preference_weight` 负权重 | ✅ 已实现（`PromptPreferenceExtractor`） |
| 用户删除 / 放弃应用 | 同上（负反馈异步提取） | ✅ 已实现 |
| 项目禁止的做法 | `ProjectBrandProfile.forbiddenRules` | 字段已存在，写入见 §5.2 |
| 确定性违规 | —— | 走自动修复，不进记忆 |
| 显式点踩 / 评分 | —— | 通道缺失，见 `rag-fix-plans/05-user-rating-feedback-not-implemented.md` |

**结论**：负反馈的正确归宿是**已有语义记忆载体里的负向条目**（负权重 / `forbiddenRules`），或**确定性自动修复**，**不需要、也不该单立"负向记忆 / 程序记忆库"**。真正该补的一个动作是**确定性 auto-fixer**——但它属于生成质量修复，不属于记忆系统。

### 5.2 唯一的真缺口：记忆固化通道（Consolidation）

人脑短期记忆会**固化**为长期记忆。当前系统里：

- 对话（情景）由 `RedisChatMemoryStore`（带 TTL）+ 消息窗口维护，**窗口外的旧消息直接丢弃**，且**当前无对话摘要沉淀**机制；
- 唯一的固化通道是 `PromptPreferenceExtractor`（对话 → 用户偏好表），**单向且狭窄**；
- 对话中确立的"项目技术决策 / 约定"（如"这个项目统一用暗色主题"）**不会回流**到 `ProjectBrandProfile`。

> ⚠️ 澄清：`ContextCompressTool` **不能**用于此处。它是**零 LLM 的上下文压缩器**（按字符数截断 `DesignSpec` / 证据等，压成注入用的 `PromptContext`），与"对话历史摘要"无关。固化项目约定需**新建一个 LLM 抽取器**（参照 `PromptPreferenceExtractor` 的 `AiServices` 模式）。

**固化管线建议**：会话结束 / 超窗口时，用**新增的 LLM 摘要抽取器**把情景记忆提炼，按内容路由到**已有载体的正/负向条目**：

```
情景记忆（对话摘要）
     │
     ├─ 偏好类（想要/不想要）  → user_preference_weight（正/负权重）  （已有通道）
     └─ 项目约定类（含禁令）    → ProjectBrandProfile（字段/forbiddenRules）（★ 待补写入口）
```

#### 收尾固化：Blackboard 作为固化取数点

固化**不保存 Blackboard 容器本身**（它是易失草稿纸，requirementDoc / plan / evidence 等中间产物用完即弃），而是在其销毁前**抽取少数有价值字段**路由到长期层。关键是——**固化钩子已经存在，无需新建骨架**：

- **落地点**：`GenerationOrchestrator.doRecord()`。三种模式末尾都已挂 `.concatWith(doRecord(ctx, tc))`，且它已在读 Blackboard（`getDesignSpec()` / `getDesignEvidence()`）并落库 `generated_page` + `generation_rag_trace`。只需旁挂一个 `doConsolidate` 步骤。
- **执行方式**：照抄 `PromptPreferenceExtractor` 已验证的模式——**虚拟线程 + try/catch 吞异常**，异步、不阻塞主链路、失败只记日志。

**固化的到底是什么**（可复用经验，不是原始状态）：

| 固化什么 | 从 Blackboard 哪来 | 存到哪 | 稳定性门槛（防噪声） |
|---|---|---|---|
| **项目稳定约定** | `designSpec` / `projectBrandContext` | `ProjectBrandProfile`（含 `forbiddenRules`） | 多轮复现或对话显式确立才 upsert，单次选择不写 |
| **用户偏好（正/负）** | 对话 / prompt | `user_preference_weight` | **已有**（`PromptPreferenceExtractor`），无需重做 |

**两处必须新增**（其余均可复用）：

- **`ProjectBrandService.upsert*` 写方法**：现状该 Service **只有读方法** `loadProjectBrandContext()`，需补写入口 + "跨轮次稳定性"判定。此项仅在**多轮迭代 / 跨会话为高频场景**时才值得投入（见 §4.1 启用前提）。
- **LLM 摘要抽取器**（新建）：从对话摘要中提炼"是否应固化为项目级约定 / 禁令"，参照 `PromptPreferenceExtractor` 的 `AiServices` + 虚拟线程模式。注意**不可复用** `ContextCompressTool`（那是零 LLM 的字符截断器，非摘要器）。

---

## 6. 目标架构：统一编排与仲裁

在现有载体之上叠一层**编排 + 仲裁**，不改动底层存储：

```
                    ┌─────────────────────────┐
                    │   MemoryOrchestrator     │  统一编排入口
                    │  （召回 / 注入 / 固化）    │
                    └───────────┬─────────────┘
                                │
         ┌──────────────┬───────┴───────────────────┐
         ▼              ▼                            ▼
   Working         Episodic                     Semantic
   Blackboard    ChatHistory        全局指纹 / 项目（含禁令） / 用户偏好（正+负）
         │              │                            │
         └──────────────┴──────────────┬─────────────┘
                                       ▼
                    ┌─────────────────────────┐
                    │  MemoryPriorityResolver  │  统一仲裁
                    │  prompt>项目>用户>全局    │
                    └─────────────────────────┘
```

- **`MemoryOrchestrator`**：统一的记忆召回 / 注入 / 固化入口，收敛现在散落在 Factory / ChatHistoryService / StreamHandler 的逻辑。
- **`MemoryPriorityResolver`**：把散落硬编码的优先级（`prompt > 项目 > 用户 > 全局`）收敛为一处权威实现，供注入时统一仲裁。

> 依据说明：`prompt > 项目 > 用户` 有代码依据（`ProjectBrandProfile` 注释）；"全局知识库最低"是基于"弱参考证据"性质的**合理推断**，落地前应确认。
- **负反馈**：作为语义记忆载体里的**负向条目**（用户负权重 / 项目 `forbiddenRules`）参与仲裁，不设独立记忆层。

---

## 7. 分阶段演进路径

| 阶段 | 动作 | 价值 | 成本 |
|---|---|---|---|
| **① 记忆正名** | 把 `Blackboard` / `ProjectBrandProfile` 纳入统一记忆分层，文档化三层模型 | 高（消除分类混乱） | 低 |
| **② 统一仲裁** | 把散落优先级收敛为 `MemoryPriorityResolver` | 高 | 中 |
| **③ 固化管线** | 收尾钩子 `doConsolidate`：从 Blackboard 抽取，路由到用户偏好 / 项目约定（含禁令） | 中高 | 中 |

**最小可行第一步（阶段①）**：先只做"正名 + 文档化"，零风险统一心智模型；随后引入 `MemoryPriorityResolver` 抽走硬编码优先级。

> 记忆系统之外的相关动作（不占本路径）：**确定性 auto-fixer**（针对 `DesignLintChecker` 7 种反模式的规则级自动修复）与**点踩评分通道**（`rag-fix-plans/05`）属于生成质量 / 反馈闭环，独立推进。

---

## 8. 关键类索引

### 现有载体

| 类 / 表 | 位置 | 记忆层 |
|---|---|---|
| `Blackboard` | `agent/core/Blackboard.java` | 工作 |
| `ChatHistory` + Redis | `model/entity` + Redis | 情景 |
| `ProjectBrandProfile` | `model/entity/ProjectBrandProfile.java` | 语义（项目） |
| `ProjectBrandService` | `service/ProjectBrandService.java` | 语义（项目） |
| `user_preference_weight` | 见 `knowledge-base-design.md` §8 | 语义（用户） |
| `design_fingerprint` / `skill/designs/*.md` | `knowledge-base-design.md` §4-5 | 语义（全局） |
| `PromptPreferenceExtractor` | `service` 层 | 固化通道（对话→偏好，正/负权重） |
| `ContextCompressTool` | 工具层 | **零 LLM 上下文压缩**（按字符截断→PromptContext，**非**对话摘要器） |
| `DesignLintChecker` + `CriticAgent` | `service.design` + agent | 确定性反模式检测（→ 自动修复，非记忆） |
| `RedisChatMemoryStore`（+ 消息窗口, TTL） | `config` | 情景记忆存储（窗口外丢弃，当前无摘要沉淀） |

### 待新增构件

| 构件 | 职责 | 对应阶段 |
|---|---|---|
| `MemoryOrchestrator` | 统一记忆召回 / 注入 / 固化编排 | ②③ |
| `MemoryPriorityResolver` | 统一优先级仲裁 | ② |
| `doConsolidate`（旁挂 `doRecord`） | 收尾从 Blackboard 抽取并路由固化 | ③ |
| `ProjectBrandService.upsert*` | 项目约定 / 禁令写入口 + 跨轮次稳定性判定（现状只读） | ③ |
| LLM 摘要抽取器 | 对话 → 项目约定 / 禁令的提炼（**不可**复用 `ContextCompressTool`） | ③ |

> 记忆系统之外：确定性 auto-fixer（复用 `DesignLintChecker` + `CriticAgent.suggestionOf`）独立推进，不列入本表。

---

**一句话结论**：不是层数少，而是分类维度错了。系统已自发长出多类记忆，当务之急是按"工作 / 情景 / 语义"的功能维度重组已有载体（`Blackboard` 正名、区分强约束与弱参考），并补上**记忆固化通道**（复用 `doRecord` 旁挂 `doConsolidate`，把对话中确立的偏好与项目约定回流到已有载体）。**无需为固定 workflow 单立"程序记忆"层，也无需"负向记忆库"**——负反馈的归宿是现有载体的负向条目（用户负权重 / 项目 `forbiddenRules`），确定性违规则走自动修复。其中**项目记忆结构上不冗余但工程上不紧急**，仅当"同一项目多轮迭代 / 跨会话"成为高频场景时才值得投入。
