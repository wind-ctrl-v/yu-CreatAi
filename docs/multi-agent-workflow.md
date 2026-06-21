# 多 Agent 协作工作流程文档

> 本文档基于源码分析生成，覆盖 CreatAi 项目的完整多 Agent 流水线设计，包括每个 Agent 的职责、设计依据与协作机制。

---

## 目录

1. [整体架构概述](#一整体架构概述)
2. [核心基础设施层](#二核心基础设施层)
3. [流水线完整流程图](#三流水线完整流程图)
4. [OrchestratorAgent — 总编排者](#四orchestratoragent--总编排者)
5. [ProductAgent — 需求解析器](#五productagent--需求解析器)
6. [ArchitectAgent — 架构规划师](#六architectagent--架构规划师)
7. [DesignTokenAgent — 设计规范加载器](#七designtokenagent--设计规范加载器)
8. [DesignRetrieveAgent — 运行时证据召回器](#八designretrieveagent--运行时证据召回器)
9. [ImageAgent — 图片资源获取器](#九imageagent--图片资源获取器)
10. [CoderAgent — 代码生成器](#十coderagent--代码生成器)
11. [StyleAgent — 全局样式生成器](#十一styleagent--全局样式生成器)
12. [CriticAgent — 质量检查器](#十二criticagent--质量检查器)
13. [DeployAgent — 构建部署器](#十三deployagent--构建部署器)
14. [DesignExtractorAgent — 离线指纹提炼器](#十四designextractoragent--离线指纹提炼器离线链路)
15. [Blackboard 共享数据契约](#十五blackboard-共享数据契约)
16. [关键支撑组件](#十六关键支撑组件)
17. [设计思想总结](#十七设计思想总结)

---

## 一、整体架构概述

### 背景与动机

传统的「单 LLM 一次性生成整个前端项目」存在以下问题：

1. **上下文污染**：需求理解、技术选型、样式生成、代码生成、质量检查等任务混在同一个 prompt 中，LLM 容易顾此失彼
2. **无法修复**：代码一次生成，没有反馈回路，质量无法保证
3. **资源浪费**：图片搜索、设计规范加载等确定性任务也要消耗 LLM token
4. **扩展困难**：新增能力（如设计知识库检索）需要改动整个生成逻辑

**多 Agent 流水线**将上述问题分解为多个专职 Agent，每个 Agent 聚焦单一职责，通过共享 Blackboard 传递中间产物，通过 Reactor `Flux` 事件流对外透传执行过程。

### 技术选型依据

| 技术 | 选择原因 |
|------|---------|
| Reactor `Flux` | 统一事件流抽象，支持 SSE 透传、串并联编排、背压控制 |
| `Blackboard` 模式 | 解耦 Agent 间依赖，避免强耦合的方法调用链 |
| LangChain4j `AiServices` | `@SystemMessage` 注解将 prompt 文件与方法绑定，职责清晰 |
| 构造函数注入 | 所有 Worker 依赖在编译期显式声明，便于单元测试 |

---

## 二、核心基础设施层

### 2.1 `Agent` 接口

```java
public interface Agent {
    Flux<AgentEvent> handle(AgentContext ctx);
    String name();
}
```

**设计依据**：统一接口使 Orchestrator 可以用相同方式驱动任意 Worker，新增 Agent 只需实现接口，无需修改编排逻辑（开闭原则）。

---

### 2.2 `AgentContext` — 请求上下文

贯穿整条流水线的不可变输入载体，包含：

| 字段 | 类型 | 说明 |
|------|------|------|
| `appId` | `Long` | 应用 ID，同时作为内存标识与项目工作目录 |
| `loginUser` | `User` | 发起请求的登录用户（权限鉴别） |
| `userPrompt` | `String` | 用户原始需求（产品经理视角的中文描述） |
| `codeGenType` | `CodeGenTypeEnum` | 路由器决定的生成类型（vue_project / html 等） |
| `blackboard` | `Blackboard` | 线程安全的共享黑板，各 Agent 读写中间产物 |

**设计依据**：将不可变输入与可变共享状态分离。不可变字段在构造时设定，共享状态统一收归 Blackboard，避免 Agent 间直接传参导致的隐式耦合。

---

### 2.3 `AgentEvent` — 统一事件模型

| EventType | 用途 |
|-----------|------|
| `TEXT_CHUNK` | LLM 流式文本增量，前端打字机效果 |
| `TOOL_CALL` | 工具调用请求（文件写入等） |
| `TOOL_RESULT` | 工具执行完成回执 |
| `STATUS_CHANGE` | Agent 阶段状态变更（开始/完成/阶段产物摘要） |
| `ERROR` | 错误信息，流被安全终止 |

**设计依据**：统一事件模型使前端 SSE 只需一套解析逻辑，即可展示所有 Agent 的实时进度。`agentName` 字段区分事件来源，前端可据此渲染各 Agent 的进度状态。

---

### 2.4 `Blackboard` — 共享数据结构

底层使用 `ConcurrentHashMap`，保证多 Agent 并发读写的线程安全。通过语义化方法封装具体 key，禁止外部直接操作原始 Map：

```
ProductAgent        → putRequirementDoc(RequirementDoc)
ArchitectAgent      → putPlan(GenerationPlan)
DesignTokenAgent    → putDesignSpec(DesignSpec)
DesignRetrieveAgent → putDesignEvidence(DesignEvidence)
ImageAgent          → putImageMap(Map<String,String>)
CoderAgent/Style    → addGeneratedFile(String)
CriticAgent         → putCriticReport(CriticReport)
DeployAgent         → putDeployUrl(String)
```

**设计依据**：Blackboard 模式是多 Agent 协作的经典解耦方案。各 Agent 只知道"我需要读什么、我需要写什么"，无需关心其他 Agent 的实现细节，实现松耦合的协作。

---

## 三、流水线完整流程图

```
用户请求 (HTTP SSE)
        │
        ▼
 OrchestratorAgent（不调 LLM，纯调度）
        │
        │ 串行 concatWith
        ▼
 ① ProductAgent       [LLM]
    用户 prompt → RequirementDoc
    写入黑板：requirementDoc
        │ 串行
        ▼
 ② ArchitectAgent      [LLM]
    requirementDoc → GenerationPlan
    写入黑板：plan
        │ 串行
        ▼
 ③ DesignTokenAgent    [零 LLM]
    plan.styleProfile → DesignSpec（读 classpath MD 文件）
    写入黑板：designSpec
        │ 串行
        ▼
 ④ DesignRetrieveAgent [零 LLM，可选]
    styleProfile + queryTags → DesignEvidence（向量/结构化检索）
    写入黑板：designEvidence
    （未配置 EmbeddingModel 时跳过）
        │ 串行
        ▼
 ⑤ ImageAgent          [零 LLM]
    plan.imageSlots → imageMap（并发调 Unsplash/Pexels API）
    写入黑板：imageMap
        │ 串行（等 ImageAgent 完成）
        ▼
 ⑥ Flux.merge(并发)
    ┌─────────────────────────────┐
    │ CoderAgent  [LLM + 文件工具]│
    │ 按 plan 流式落盘所有代码文件   │
    │ 完成后替换图片占位符           │
    └─────────────────────────────┘
    ┌─────────────────────────────┐
    │ StyleAgent   [LLM]          │
    │ 生成 variables.css/global.css│
    └─────────────────────────────┘
        │ 串行（等 Coder + Style 均完成）
        ▼
 ⑦ CriticAgent（质检反馈循环，最多 criticMaxRounds 轮）
    静态检查（零 LLM）+ LLM 综合评分 → CriticReport
    ┌─ passed=true 或 score≥80 ──→ 通过，结束循环
    │
    ├─ 仅样式问题 ──────────────→ 重跑 StyleAgent
    │
    ├─ 仅代码问题 ──────────────→ 重跑 CoderAgent
    │
    ├─ 样式+代码问题 ───────────→ 先 StyleAgent 再 CoderAgent
    │
    └─ 超出最大轮次 ────────────→ 降级返回当前结果
        │
        ▼ （可选，agent.deploy.enabled=true）
 ⑧ DeployAgent         [零 LLM]
    npm install → npm run build → 上传 COS
    写入黑板：deployUrl
        │
        ▼
 流水线完成，推送 "流水线完成" 状态事件，SSE 关闭
```

---

## 四、OrchestratorAgent — 总编排者

### 职责

整条流水线的"指挥官"，**自身不调用任何 LLM**，只负责：
- 按固定顺序串联/并联各 Worker Agent
- 管理 Critic 反馈循环（最多 `criticMaxRounds` 轮）
- 按问题分类（样式/代码/两者）精准路由修复分支
- 全局异常兜底，保证 SSE 连接不因 Worker 异常断开

### 设计依据

**关注点分离原则**：将"什么顺序执行"（编排逻辑）与"具体执行什么"（业务逻辑）分离。Orchestrator 只持有编排图，Worker 只关注自身任务。

**惰性构建（Lazy Evaluation）**：`handle()` 调用时仅构建 Flux 链，不立即执行。真正执行发生在 Spring WebFlux 订阅此 Flux 时，保证在 Coder 完成之前 Critic 不会提前触发。

**乒乓抑制（Ping-Pong Guard）**：若 Style↔Coder 修复在两种分支间来回超过 2 次，强制收敛降级，避免无意义的重试循环消耗 LLM 调用次数。

### 关键配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.critic.max-rounds` | 3 | Critic 反馈循环最大轮次 |
| `agent.deploy.enabled` | false | 是否在末尾自动构建并部署 |
| `agent.retrieve.enabled` | false | 是否启用运行时设计证据召回 |

### 串并联编排实现

```
Flux.just(status)
  .concatWith(productAgent)       // 串行：必须在 architect 之前
  .concatWith(architectAgent)     // 串行
  .concatWith(designTokenAgent)   // 串行：必须在并发分支之前
  .concatWith(designRetrieveAgent)// 串行
  .concatWith(imageAgent)         // 串行：必须在 Coder 之前
  .concatWith(Flux.merge(         // 并发：Coder 与 Style 互不依赖
      coderAgent, styleAgent))
  .concatWith(runCriticLoop())    // 串行：必须在生成完成后
  .concatWith(deployAgent)        // 串行（可选）
```

---

## 五、ProductAgent — 需求解析器

### 职责

将用户的**一段中文自然语言**转化为下游 Agent 可直接消费的**结构化需求文档**（`RequirementDoc`）。

### 主要工作

1. 接收用户原始 prompt（如"帮我做一个电商网站"）
2. 调用 LLM（绑定 `agent-product-system-prompt.txt`）
3. LLM 扮演"产品经理"角色，进行合理的产品补全与规划
4. 输出严格 JSON，解析为 `RequirementDoc`，写入 Blackboard

### 输出数据结构（`RequirementDoc`）

```json
{
  "projectName": "精选好物商城",
  "summary": "用户可以浏览、筛选、购买商品的在线购物平台",
  "targetUser": "25-40岁喜欢网购的消费者",
  "coreFeatures": ["商品浏览", "商品详情", "加入购物车", "下单结算"],
  "pages": [
    { "path": "/", "name": "首页", "purpose": "商品展示与分类导航" },
    { "path": "/product/:id", "name": "商品详情", "purpose": "商品详情与购买" }
  ],
  "colorTheme": {
    "primary": "#e74c3c",
    "secondary": "#2c3e50",
    "background": "#f5f5f5",
    "text": "#333333"
  },
  "imageNeeds": [
    { "slotId": "img_hero_1", "usage": "首页主图", "keyword": "modern shopping mall interior" }
  ]
}
```

### 设计依据

**前置语义对齐**：用户输入往往是模糊的自然语言，直接交给 ArchitectAgent 做技术规划，会因理解偏差导致生成的代码与用户需求不符。ProductAgent 充当"翻译官"，把模糊输入转化为精确的结构化契约。

**职责隔离**：ProductAgent 的 system prompt 明确禁止涉及技术选型与代码实现，防止"越权"干扰 ArchitectAgent 的决策。

**限制输出字段**：`pages` 默认 1 个（防止 LLM 过度脑补多页面），`coreFeatures` 限 3-6 项（避免需求膨胀），`imageNeeds.keyword` 强制英文（保证图片搜索质量）。

---

## 六、ArchitectAgent — 架构规划师

### 职责

基于 `RequirementDoc` 进行**技术选型与文件结构规划**，输出 `GenerationPlan`，是代码生成的"蓝图"。

### 主要工作

1. 从 Blackboard 读取 `RequirementDoc`
2. 结合 `ctx.getCodeGenType()`（路由器决策的技术栈）
3. 调用 LLM（绑定 `agent-architect-system-prompt.txt`）
4. LLM 输出：行业风格识别（`styleProfile`）、完整文件列表、路由配置、图片槽
5. 解析为 `GenerationPlan`，写入 Blackboard

### 输出数据结构（`GenerationPlan`）

```json
{
  "techStack": "vue_project",
  "requirementSummary": "电商商城，含商品列表、详情、购物车三页",
  "styleProfile": "ecommerce",
  "styleProfileConfidence": 0.88,
  "fileStructure": [
    "package.json", "vite.config.js", "index.html",
    "src/main.js", "src/App.vue", "src/router/index.js",
    "src/pages/Home.vue", "src/pages/ProductDetail.vue", "src/pages/Cart.vue",
    "src/components/ProductCard.vue", "src/components/NavBar.vue",
    "src/stores/cart.js", "src/data/products.js",
    "src/styles/variables.css", "src/styles/global.css"
  ],
  "routerConfig": [
    { "path": "/", "name": "home", "component": "src/pages/Home.vue" },
    { "path": "/product/:id", "name": "product", "component": "src/pages/ProductDetail.vue" },
    { "path": "/cart", "name": "cart", "component": "src/pages/Cart.vue" }
  ],
  "imageSlots": [
    { "slotId": "img_hero_1", "keyword": "modern shopping bags products", "style": "photo", "usedIn": "首页 hero" }
  ],
  "colorTheme": { "primary": "#e74c3c", ... }
}
```

### 设计依据

**行业风格枚举（`styleProfile`）**：设计了 6 种行业风格（`startup/enterprise/ecommerce/portfolio/dashboard/landing`）+ `default` 兜底。风格识别由 LLM 完成，但加置信度阈值（0.6），低置信自动归 `default`，防止误分类导致不协调的设计规范。

**技术栈强约束**：system prompt 明确禁止 TypeScript、Pinia、Vuex、UI 组件库，固定 Vue3 + Vite + 原生 CSS 变量，保证下游 CoderAgent 的代码风格一致。

**文件结构蓝图**：`fileStructure` 给出精确的文件路径列表，使 CoderAgent 知道"应该创建哪些文件"，避免随意创建无关文件。

**多页面强约束**：电商类必须包含 Home + ProductDetail + Cart 三页，防止 LLM 为了省事只生成单页应用。

---

## 七、DesignTokenAgent — 设计规范加载器

### 职责

**阶段一「先验规则」**加载器，**不调用任何 LLM**，纯 Java 根据 `GenerationPlan.styleProfile` 从 `classpath:skill/designs/{profile}.md` 加载并解析行业设计规范，输出 `DesignSpec`。

### 主要工作

1. 从 Blackboard 读取 `GenerationPlan` 的 `styleProfile` 与 `styleProfileConfidence`
2. 调用 `DesignProfileResolver.normalizeProfile()` 进行白名单校验与置信度过滤
3. 读取对应的 `.md` 规范文件，解析出：
   - `tokenGroups`：CSS 变量名与默认值的权威清单（分组）
   - `tone`：调性描述（如"克制、中性、通用"）
   - `layoutPatterns`：布局规则
   - `preferredPatterns`：推荐写法
   - `forbiddenPatterns`：禁用模式
   - `interactionPatterns`：交互规则
4. 写入 Blackboard，供并发分支的 StyleAgent/CoderAgent 消费

### 设计依据

**三阶段设计知识注入体系**：
- **阶段一（DesignTokenAgent）**：静态先验规则，每次都执行，零 LLM，成本最低
- **阶段二（离线 DesignExtractorAgent）**：从精选页面提炼六层指纹，入库为设计知识库
- **阶段三（DesignRetrieveAgent）**：运行时从知识库召回受控证据，开关控制，需要向量搜索基础设施

**零 LLM 设计**：设计规范是结构化知识，用 LLM 解析 MD 文件既浪费又不稳定。用 Java 正则解析既快又准，且无幻觉风险。

**降级策略（绝不中断流水线）**：
- 识别失败 / 不在白名单 → 归 `default` 规范
- MD 读取或解析失败 → 回退内置硬编码默认规范
- 任意异常 → 仅推 status 事件，绝不 `sink.error()`

**插入时机**：必须在 ArchitectAgent 之后、并发分支（Coder/Style）之前，确保并发执行时 `DesignSpec` 已就绪。

---

## 八、DesignRetrieveAgent — 运行时证据召回器

### 职责

**阶段三「后验证据」**召回器（开关默认关闭），通过 `DesignKnowledgeIndex` 检索已批准的设计指纹，召回 top-k 受控证据（`DesignEvidence`）写入 Blackboard。

### 主要工作

1. 检查 `agent.retrieve.enabled` 开关，关闭则直接跳过
2. 从 Blackboard 读取 `DesignSpec`（先验）和 `GenerationPlan`
3. 构造查询标签：静态规范调性词 + 需求摘要关键词（去重）
4. 调用 `DesignKnowledgeIndex.retrieve()` 执行检索
5. 召回结果非空则写入 Blackboard `designEvidence`，供 Coder/Style 消费
6. 检索失败一律降级（不 `sink.error()`）

### 设计依据

**渐进式增强**：系统在没有设计知识库时（阶段一）已能正常运行；有了精选指纹库后，通过开关启用阶段三，进一步提升生成质量，不破坏已有流程。

**`@ConditionalOnBean(DesignKnowledgeIndex.class)`**：若未配置 EmbeddingModel，此 Agent 不会注入 Spring 容器，Orchestrator 通过 `designRetrieveAgent != null` 判断是否跳过。

---

## 九、ImageAgent — 图片资源获取器

### 职责

消费 `GenerationPlan.imageSlots`，并发调用图片搜索 API（Unsplash/Pexels），输出 `imageMap`（关键词 → 真实图片 URL），**不调用 LLM**。

### 主要工作

1. 从 Blackboard 读取 `plan.imageSlots`
2. 若 slots 为空，直接跳过（减少不必要的网络请求）
3. 使用 `Flux.flatMap` + `Mono.fromCallable` 实现**全程非阻塞并发搜图**
4. 并发度由 `image.replace.concurrency` 配置控制
5. 单个搜图失败只记录警告，不影响其他 slot
6. 全部失败时写入空 map + 推完成事件（不终止流水线）
7. 将 `keyword → url` 的 Map 写入 Blackboard，供 CoderAgent 的 `ImagePlaceholderReplacer` 替换占位符

### 设计依据

**零 LLM**：图片搜索是确定性任务（关键词 → API 调用），不需要语义推理，直接调用 API 即可，省去 LLM 调用成本。

**必须在 Coder 之前**：CoderAgent 生成的代码中使用 `{{IMG:keyword}}` 占位符，CoderAgent 完成后会立即触发替换。若 ImageAgent 未完成，imageMap 为空，替换无法进行，导致 Critic 发现占位符残留失败。因此串行插入 Coder 之前。

**非阻塞响应式实现**：`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 将阻塞 HTTP 调用切换到弹性线程池，不占用 Reactor 调度线程，保证系统吞吐量。

---

## 十、CoderAgent — 代码生成器

### 职责

复用现有的 Vue 项目流式代码生成能力（携带文件写入工具），按 `GenerationPlan.fileStructure` 逐文件落盘代码。是整条流水线中最重量级的 Agent。

### 主要工作

1. 从 Blackboard 读取 `plan`、`designSpec`、`criticReport`（上一轮质检问题）
2. 调用 `buildUserMessage()` 拼装完整 prompt，注入：
   - 原始需求与过滤后的生成计划（已移除 StyleAgent 独占的样式文件）
   - `tokenGroups` 确切变量名清单（CSS 组件只能引用，不得自创）
   - `forbiddenPatterns`/`preferredPatterns` 强制设计约束
   - `DesignEvidence`（阶段三召回的参考证据，来自指纹库）
   - 上一轮 `CriticReport.issues`（带修复建议，避免盲目重写）
3. 调用 `AiCodeGeneratorService` 启动流式生成（LLM + 文件写入工具）
4. 流式事件透传：`TEXT_CHUNK`、`TOOL_CALL`、`TOOL_RESULT`
5. 每次工具调用成功后向 Blackboard `addGeneratedFile()`
6. 生成完成后触发 `ImagePlaceholderReplacer.replaceAll()`，替换代码中的 `{{IMG:keyword}}` 为真实 URL

### 文件职责划分

| 文件范围 | 归属 | 说明 |
|---------|------|------|
| `src/styles/variables.css` | StyleAgent 独占 | CoderAgent 禁止创建或覆盖 |
| `src/styles/global.css` | StyleAgent 独占 | CoderAgent 禁止创建或覆盖 |
| `src/main.js` | CoderAgent 负责 | **必须** import 两个样式文件 |
| 其他所有文件 | CoderAgent 负责 | 按 plan.fileStructure 逐文件创建 |

### 设计依据

**Token 强契约**：将 `tokenGroups` 注入 prompt，告知 LLM"只能引用这些 CSS 变量名，不得自创"，与 StyleAgent 共享同一份变量名清单，消除两者之间的 CSS 变量不匹配问题。

**Critic 驱动修复**：第二轮及以后，将上一轮的 `CriticReport.issues`（含 severity、file、description、suggestion）注入 prompt，使 LLM 知道"哪个文件的哪里有问题、应该怎么修"，而非盲目重写全部代码。

**样式文件隔离**：通过 `filterStyleFiles()` 过滤掉 plan 中的样式文件，配合 system prompt 中"禁止创建 variables.css/global.css"的指令，双重保证 StyleAgent 对样式文件的独占写入权，避免两者并发时产生竞态写入。

---

## 十一、StyleAgent — 全局样式生成器

### 职责

根据 `plan.colorTheme` + `DesignSpec.tokenGroups` 生成 `variables.css`（CSS 变量定义）与 `global.css`（全局基础样式），并直接写入项目文件系统。

### 主要工作

1. 从 Blackboard 读取 `plan` 和 `designSpec`（含 tokenGroups）
2. 构建 LLM 输入：colorTheme + tokenGroups（权威变量名清单）+ forbiddenPatterns + preferredPatterns
3. 若有 `DesignEvidence`，追加其中的 `preferredPatterns` 作为参考调性
4. 调用 LLM（绑定 `agent-style-system-prompt.txt`）
5. 解析 LLM 输出的 JSON：`{"variables_css": "...", "global_css": "..."}`
6. 路径安全校验（防止路径穿越）后写入 `src/styles/variables.css` 和 `src/styles/global.css`
7. 向 Blackboard `addGeneratedFile()` 记录

### variables.css 生成规则

- `:root {}` 中定义所有 CSS 变量
- **必须按 tokenGroups 给出的确切变量名逐一定义**（变量名是契约，不得改名增删，值可结合 colorTheme 微调）
- 覆盖：颜色、字号、行高、字距、间距、圆角、阴影、字体

### global.css 生成规则

- 全局重置（box-sizing、margin、height）
- body 默认字体/背景/颜色/行高全部引用变量
- 响应式断点：768px / 480px
- 工具类 `.container`（最大宽 1200px）
- 链接、按钮、表单元素基础样式

### 设计依据

**与 CoderAgent 并发**：样式生成与代码生成互不依赖（StyleAgent 不读 Coder 输出，Coder 不读 Style 输出，两者都只读 Blackboard 中的 plan/designSpec），因此可以并发执行，缩短总耗时。

**tokenGroups 强契约**：StyleAgent 负责"定义"CSS 变量，CoderAgent 负责"引用"这些变量。通过注入相同的 tokenGroups 清单，保证两者使用的变量名完全一致，彻底消除"CoderAgent 引用了 StyleAgent 未定义的变量"的问题。

**设计禁用约束**：system prompt 明确禁止 `backdrop-filter` 毛玻璃、纯装饰性渐变背景、硬编码颜色/px 值，这些规则与 DesignLintChecker 的检测项一一对应，形成"生成约束 ↔ 静态检查"的闭环。

---

## 十二、CriticAgent — 质量检查器

### 职责

综合**确定性静态检查**（零 LLM）与 **LLM 综合评分**，输出结构化 `CriticReport`，供 Orchestrator 决策是否进入修复回路。

### 主要工作

#### 静态检查（零 LLM，确定性）

1. **P0 核心文件检查**：`package.json`、`index.html`、`src/main.js`、`src/App.vue` 缺失则强制 `passed=false`
2. **P1 推荐文件检查**：`vite.config.js`、`src/styles/variables.css`、`src/styles/global.css` 缺失只记录日志，不阻塞
3. **图片占位符残留检查**：扫描所有 `.vue/.html/.js/.css` 文件中是否还有 `{{IMG:...}}` 未替换，有则强制 `passed=false`
4. **DesignLintChecker 扫描**：检测 7 类反模式（详见下表）

#### DesignLintChecker 检测项

| 检测项 | 级别 | 说明 |
|--------|------|------|
| `missing-global-style-import` | P0 | `src/main.js` 缺少全局样式 import |
| `dangling-var` | P0 | 引用了不在 tokenGroups 清单内的 `var(--xxx)` |
| `backdrop-filter` | P1 | 使用了毛玻璃效果 |
| `decorative-gradient` | P1 | 使用了纯装饰性渐变背景 |
| `hardcoded-px` | P1 | 硬编码 px 值（padding/margin/font-size 等属性） |
| `hardcoded-color` | P1 | 硬编码十六进制颜色 |
| `overscale-hover` | P1 | hover 时 scale > 1.05 的浮夸放大 |

#### LLM 综合评分

- 将静态检查结果（P0/P1 缺失、占位符残留、反模式计数）一并传给 LLM 参考
- LLM 输出 `passed`、`score`（0-100）、`summary`、`issues`（含 severity/category/file/description/suggestion）
- LLM 评分失败时退化为纯静态兜底报告（P0 问题每个扣 30 分）

#### 强制规则（静态优先于 LLM）

- P0 文件缺失 或 占位符残留 → 强制 `passed=false`（即使 LLM 打了高分）
- 设计 P0 命中（缺少 import、悬空变量）→ 强制 `passed=false`

#### 评分规则

| 问题级别 | 扣分 | 通过阈值 |
|---------|------|---------|
| P0 | -30 / 项 | score ≥ 80 则 passed=true |
| P1 | -15 / 项 | |
| P2 | -5 / 项 | |

### 设计依据

**静态检查先行**：确定性问题（文件是否存在、占位符是否残留）不需要 LLM 判断，先用 Java 做静态扫描，既快又准，结果还作为 LLM 的参考输入，引导 LLM 做更精准的评估。

**客观优先于主观**：静态检查结论（P0 命中 → 强制失败）的优先级高于 LLM 的主观评分，防止 LLM 对有明显错误的代码给出虚高分数。

**结构化 issues + suggestion**：每个 issue 携带文件路径、问题描述、修复建议，CoderAgent 在下一轮修复时能精准定位并修复，而非盲目重写整个项目。

**P0/P1 分级**：将文件缺失分为 P0（必须修复）和 P1（可选），避免因缺少可选文件导致 Critic 无限重试循环。

---

## 十三、DeployAgent — 构建部署器

### 职责

在流水线末尾（受 `agent.deploy.enabled` 开关控制）执行 `npm install → npm run build → 上传 COS`，输出可访问的部署 URL。

### 主要工作

1. 检查项目目录是否存在
2. 调用 `VueProjectBuilder.buildProject()` 执行 npm 构建
3. 校验 `dist` 目录路径（防止路径穿越攻击）
4. 递归上传 `dist` 内所有文件到腾讯云 COS
5. 构建访问 URL，写入 Blackboard `deployUrl`，向前端推送部署完成事件

### 安全措施

- 路径严格校验：`target.startsWith(projectRoot)` 防穿越
- 相对路径检查：`rel.contains("..")` 则跳过该文件
- COS 凭证通过 Spring Bean 注入，不暴露在代码/日志中

### 设计依据

**可选开关**：默认关闭，避免每次代码生成都消耗构建和存储资源。生产环境按需开启。

---

## 十四、DesignExtractorAgent — 离线指纹提炼器（离线链路）

### 职责

**阶段二「后验证据」**的离线预热工具，**不接入实时生成流水线**。调用 LLM 将精选页面摘要按六层契约（布局/颜色/排版/组件/交互/动效）提炼为 `DesignFingerprintDTO`，入库供 DesignRetrieveAgent 检索。

### 六层指纹提炼

输入经 `DesignSourcePreprocessor` 归一化（剥离脚本、业务文案），防止 Prompt Injection 攻击，再交给 LLM 提炼：
- **布局模式**（layoutPatterns）
- **颜色/Token 模式**（colorPatterns）
- **排版规则**（typographyPatterns）
- **组件推荐写法**（preferredPatterns）
- **交互规则**（interactionPatterns）
- **动效规则**（motionPatterns）

### 设计依据

**离线预热**：指纹提炼是低频、耗时的操作，适合离线批量执行（定时任务或手动触发），不适合实时流水线。Orchestrator 不包含此 Agent，失败不影响实时生成。

**输入安全隔离**：原始 HTML 不直接喂给 LLM，先经 `DesignSourcePreprocessor` 归一化，移除可能包含的注入指令，防止页面内容劫持 LLM 行为。

---

## 十五、Blackboard 共享数据契约

```
┌─────────────────────────────────────────────────────────────┐
│                        Blackboard                           │
│                   (ConcurrentHashMap)                       │
├──────────────────┬──────────────────┬───────────────────────┤
│      Key         │      写入者       │        读取者          │
├──────────────────┼──────────────────┼───────────────────────┤
│ requirementDoc   │ ProductAgent     │ ArchitectAgent        │
├──────────────────┼──────────────────┼───────────────────────┤
│ plan             │ ArchitectAgent   │ DesignTokenAgent      │
│                  │                  │ DesignRetrieveAgent   │
│                  │                  │ ImageAgent            │
│                  │                  │ CoderAgent            │
│                  │                  │ StyleAgent            │
│                  │                  │ CriticAgent           │
├──────────────────┼──────────────────┼───────────────────────┤
│ designSpec       │ DesignTokenAgent │ DesignRetrieveAgent   │
│                  │                  │ CoderAgent            │
│                  │                  │ StyleAgent            │
│                  │                  │ CriticAgent(Lint)     │
├──────────────────┼──────────────────┼───────────────────────┤
│ designEvidence   │DesignRetrieve    │ CoderAgent            │
│                  │ Agent            │ StyleAgent            │
├──────────────────┼──────────────────┼───────────────────────┤
│ imageMap         │ ImageAgent       │ CoderAgent            │
│                  │                  │ (ImagePlaceholder     │
│                  │                  │  Replacer)            │
├──────────────────┼──────────────────┼───────────────────────┤
│ generatedFiles   │ CoderAgent       │ CriticAgent           │
│                  │ StyleAgent       │                       │
├──────────────────┼──────────────────┼───────────────────────┤
│ criticReport     │ CriticAgent      │ OrchestratorAgent     │
│                  │                  │ CoderAgent（下一轮）   │
├──────────────────┼──────────────────┼───────────────────────┤
│ deployUrl        │ DeployAgent      │ 最终输出              │
└──────────────────┴──────────────────┴───────────────────────┘
```

---

## 十六、关键支撑组件

### 16.1 `DesignProfileResolver` — 规范解析器

- **功能**：归一化 `styleProfile`（白名单校验 + 置信度过滤），从 `classpath:skill/designs/{profile}.md` 解析 `DesignSpec`
- **安全**：仅从 classpath 读取白名单内的固定文件名，profile 经枚举校验，无路径穿越风险
- **降级**：MD 解析失败 → 回退内置硬编码默认规范，保证 `tokenGroups` 强契约始终存在

### 16.2 `DesignLintChecker` — 反模式扫描器

- **功能**：纯 Java 正则扫描生成产物，检测 7 类反模式，输出结构化命中清单
- **特点**：零 LLM、确定性、可复现，命中数为 0 即客观达标
- **用途**：为 CriticAgent 提供客观的静态检查依据，不依赖 LLM 主观判断

### 16.3 `ImagePlaceholderReplacer` — 占位符替换器

- **功能**：遍历项目目录下的代码文件，将 `{{IMG:keyword}}` 替换为 imageMap 中对应的真实 URL
- **触发时机**：CoderAgent 流式生成完成后立即触发

### 16.4 `JsonExtractor` — JSON 提取工具

- **功能**：从 LLM 输出中提取 JSON（处理 LLM 偶尔输出的 markdown 围栏），并反序列化为目标 DTO
- **重要性**：LLM 输出不总是严格 JSON，此工具负责容错解析

### 16.5 `AgentAiService` — LLM 调用接口

通过 LangChain4j `@SystemMessage` 注解将 prompt 文件与方法绑定：

| 方法 | 绑定 Prompt | 调用 Agent |
|------|------------|-----------|
| `generateRequirementDoc` | `agent-product-system-prompt.txt` | ProductAgent |
| `generatePlan` | `agent-architect-system-prompt.txt` | ArchitectAgent |
| `generateStyleSheets` | `agent-style-system-prompt.txt` | StyleAgent |
| `review` | `agent-critic-system-prompt.txt` | CriticAgent |
| `generateFingerprint` | `agent-extractor-system-prompt.txt` | DesignExtractorAgent（离线） |

---

## 十七、设计思想总结

### 核心原则

| 原则 | 体现 |
|------|------|
| **单一职责** | 每个 Agent 只做一件事，ProductAgent 不做技术选型，StyleAgent 不写业务代码 |
| **确定性优先** | 能用 Java 做的绝不用 LLM（设计规范解析、图片搜索、静态代码检查） |
| **契约先于实现** | tokenGroups 是 Style/Coder 的共同契约，plan 是 Image/Coder 的共同契约，先定义契约再并发执行 |
| **降级不中断** | 每个非核心 Agent（DesignToken/DesignRetrieve/Image）出错只降级，绝不 `sink.error()` 中断流水线 |
| **静态检查优先于 LLM** | CriticAgent 的 P0 静态结论高于 LLM 评分，防止 LLM 对有明显错误的代码打高分 |
| **精准修复** | Critic → Coder/Style 的修复循环携带 issue 清单，避免 LLM 盲目重写 |
| **安全设计** | 路径穿越防护、Prompt Injection 隔离、凭证不外泄、SSRF 防护 |

### 流水线质量保障机制

```
静态检查（确定性）
    ├─ 必备文件存在性检查（P0/P1 分级）
    ├─ 图片占位符残留检查（P0）
    └─ DesignLintChecker 反模式扫描（P0/P1）

LLM 综合评分（主观评估）
    ├─ 引用静态检查结果作为参考
    └─ 输出结构化 issues（携带修复建议）

强制规则（静态优先）
    └─ P0 命中 → 强制 passed=false（覆盖 LLM 结论）

修复路由（精准分流）
    ├─ 仅样式问题 → StyleAgent
    ├─ 仅代码问题 → CoderAgent
    └─ 两类问题 → StyleAgent 串行 CoderAgent

乒乓抑制
    └─ Style↔Coder 来回 ≥ 2 次 → 强制收敛降级
```

### 与传统单 LLM 方案的对比

| 维度 | 单 LLM | 多 Agent 流水线 |
|------|--------|----------------|
| **上下文质量** | 所有任务混合，相互干扰 | 每个 LLM 调用聚焦单一任务 |
| **可修复性** | 生成后无法自动修复 | Critic 反馈循环，最多 3 轮自动修复 |
| **LLM 成本** | 所有任务都消耗 LLM | 确定性任务零 LLM（搜图/规范加载/静态检查） |
| **生成质量** | 依赖单次 LLM 输出质量 | 三阶段设计知识注入 + Critic 质检双重保障 |
| **可观测性** | 黑盒，前端无法展示进度 | 每个 Agent 推送 AgentEvent，前端可实时展示各阶段进度 |
| **可扩展性** | 新增能力需改动整体 prompt | 新增 Worker 只需实现 `Agent` 接口 + Orchestrator 插入步骤 |
