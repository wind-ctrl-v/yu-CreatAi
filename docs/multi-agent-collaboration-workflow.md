# 多 Agent 协作工作流程文档

> 本文档基于当前源码（`com.yu.yuaifather.agent` 包）实测梳理，描述 CreatAi 在「生成 Vue 工程」场景下的多 Agent 协作流水线：触发条件、编排机制、各 Agent/Tool 职责、共享数据契约、质检反馈循环与事件回传。
>
> 适用范围：`codeGenType == VUE_PROJECT` 且 `agent.orchestrator.enabled=true`。其余类型（HTML / MULTI_FILE）与开关关闭时走经典单 LLM 链路，不在本文范围。

---

## 目录

1. [整体定位](#一整体定位)
2. [触发条件与降级策略](#二触发条件与降级策略)
3. [核心基础设施](#三核心基础设施)
4. [完整流水线总览](#四完整流水线总览)
5. [阶段详解](#五阶段详解)
6. [Critic 反馈循环](#六critic-反馈循环)
7. [Blackboard 共享数据契约](#七blackboard-共享数据契约)
8. [事件流与 SSE 回传](#八事件流与-sse-回传)
9. [模型与成本分层](#九模型与成本分层)
10. [配置开关清单](#十配置开关清单)
11. [设计要点与命名说明](#十一设计要点与命名说明)

---

## 一、整体定位

多 Agent 流水线把「单 LLM 一次性生成整个前端工程」拆解为多个**单一职责**的执行单元，通过线程安全的 `Blackboard` 传递中间产物，通过 Reactor `Flux<AgentEvent>` 事件流对外实时透传执行过程。

它在编排范式上属于 **Workflow（预定义代码路径）**，组合了四种经典模式：

| 模式 | 体现 |
|------|------|
| Prompt Chaining（提示链） | Planning → 设计规范 → 搜图 → 编码 串行 |
| Parallelization（并行） | `Flux.merge(coderAgent, styleAgent)` |
| Orchestrator-Workers（编排者-工人） | `OrchestratorAgent` 调度各 Worker |
| Evaluator-Optimizer（评估-优化） | `runCriticLoop`：Critic 评分 → 回退修复 → 再评 |

`OrchestratorAgent` 自身**零 LLM**，只负责编排；真正"agentic"（LLM 自主决定工具调用）的只有 `CoderAgent` 内部。

---

## 二、触发条件与降级策略

### 入口调用链

```
前端 EventSource
  └─ GET /api/app/chat/gen/code?appId=&message=  (SSE)
       AppController.chatToGenCode            // @RateLimit USER 5次/60s
         └─ AppServiceImpl.chatToGenCode(appId, message, loginUser)
              ① 查 App + 权限校验（仅本人可生成）
              ② 写【用户消息】入 chat_history
              ③ 读取 app.codeGenType
              ④ 分流判断
```

### 分流判断（`AppServiceImpl.chatToGenCode`）

```
if (orchestratorEnabled && codeGenType == VUE_PROJECT) {
    if (orchestratorV2Enabled) {
        try  -> generationOrchestrator.generate(ctx)   // V2 编排器优先
        catch-> 降级经典链路
    }
    try  -> orchestratorAgent.handle(ctx)               // V1 多 Agent 流水线
    catch-> 订阅前异常，回退经典链路
}
else -> aiCodeGeneratorFacade.generateAndSaveCodeStream(...)  // 经典单 LLM 链路
```

### 降级策略

| 场景 | 处理 |
|------|------|
| 开关关闭 / 非 Vue 类型 | 直接走经典链路 |
| V2 编排器抛异常 | 降级到 V1 |
| V1 **订阅前**构建异常 | 回退经典链路（尚未向 SSE emit 任何内容，安全） |
| V1 **流执行中** Worker 异常 | `onErrorResume` 转 ERROR 事件，正常关闭 SSE（不回退） |

> 本文以默认的 **V1 `OrchestratorAgent`** 为主线。

---

## 三、核心基础设施

### 3.1 `Agent` 接口

```java
public interface Agent {
    Flux<AgentEvent> handle(AgentContext ctx);
    String name();
}
```

统一接口使 Orchestrator 能以相同方式驱动任意 Worker，新增 Agent 只需实现接口（开闭原则）。

### 3.2 `AgentContext` — 请求上下文

| 字段 | 类型 | 说明 |
|------|------|------|
| `appId` | `Long` | 应用 ID，兼作 LangChain4j MemoryId 与项目工作目录标识 |
| `loginUser` | `User` | 发起请求的登录用户 |
| `userPrompt` | `String` | 用户原始需求 |
| `codeGenType` | `CodeGenTypeEnum` | 生成类型（vue_project） |
| `blackboard` | `Blackboard` | 线程安全共享黑板（默认新建空实例） |

将「不可变输入」与「可变共享状态」分离：不可变字段构造时设定，共享状态统一收归 Blackboard。

### 3.3 `AgentEvent` — 统一事件模型

| 字段 | 说明 |
|------|------|
| `type` | `EventType`：`TEXT_CHUNK` / `TOOL_CALL` / `TOOL_RESULT` / `STATUS_CHANGE` / `ERROR` |
| `agentName` | 产生事件的 Agent 名 |
| `content` | 文本内容 |
| `data` | 附加结构化数据（产物摘要、工具参数等） |

提供静态工厂：`AgentEvent.text(...)` / `status(...)` / `error(...)`。

### 3.4 `Blackboard` — 共享黑板

基于 `ConcurrentHashMap` 的线程安全键值存储，各 Agent 通过语义化方法读写产物（详见[第七节](#七blackboard-共享数据契约)）。

---

## 四、完整流水线总览

```
OrchestratorAgent.handle(ctx)   ← 惰性串联 Flux，subscribe 时才执行；自身零 LLM
│
│  Flux.just("流水线启动")
├─① .concatWith(planningAgent.handle)        ┐
├─② .concatWith(designTokenTool.handle)      │
├─③ .concatWith(userPreferenceTool.handle)   │ 串行：concatWith
├─④ .concatWith(projectProfileTool.handle)   │ 前一步完成才启动下一步
├─⑤ .concatWith(designRetrieveTool.handle)   │
├─⑥ .concatWith(imageSearchTool.handle)      ┘
├─⑦ .concatWith(Flux.merge(coderAgent, styleAgent))   ← 并发
├─⑧ .concatWith(runCriticLoop(ctx))          ← 质检 + 反馈循环（最多 3 轮）
├─⑨ .concatWith(deployTool.handle)           ← 仅 agent.deploy.enabled
│   .concatWith("流水线完成")
└─ .onErrorResume → 转 ERROR 事件
```

### 阶段一览表

| # | 执行单元 | 类型 | LLM | 必选 | 读黑板 | 写黑板 |
|---|---------|------|:---:|:---:|--------|--------|
| ① | PlanningAgent | Worker | ✅ ×2 | ✅ | userPrompt | RequirementDoc, GenerationPlan |
| ② | DesignTokenTool | System Tool | ❌ | ✅ | plan.styleProfile | DesignSpec |
| ③ | UserPreferenceTool | System Tool | ❌ | 可选 | loginUser | UserDesignContext |
| ④ | ProjectProfileTool | System Tool | ❌ | 可选 | appId | ProjectBrandContext |
| ⑤ | DesignRetrieveTool | System Tool | Embedding | 可选 | plan/偏好 | DesignEvidence |
| ⑥ | ImageSearchTool | System Tool | ❌ | ✅ | plan.imageSlots | imageMap |
| ⑦a | CoderAgent | Worker（agentic） | ✅ | ✅ | plan/spec/evidence/criticReport | generatedFiles |
| ⑦b | StyleAgent | Worker | ✅ | ✅ | plan.colorTheme/spec | generatedFiles |
| ⑧ | CriticAgent | Worker | ✅ | ✅ | spec/plan/generatedFiles | CriticReport |
| ⑨ | DeployTool | System Tool | ❌ | 可选 | appId | deployUrl |

> 可选 System Tool 通过 `@Autowired(required = false)` 注入，未装配时 Orchestrator emit 一条"跳过"状态事件后继续。

---

## 五、阶段详解

### ① PlanningAgent — 需求解析 + 架构规划

- **职责**：合并旧版 ProductAgent + ArchitectAgent 为一次 Agent，内部两次 LLM 调用：
  1. `agentAiService.generateRequirementDoc(userPrompt)` → `RequirementDoc`（projectName、功能点等）
  2. `agentAiService.generatePlan(input)` → `GenerationPlan`（fileStructure、routerConfig、imageSlots、colorTheme、styleProfile）
- **产物**：`Blackboard.putRequirementDoc()`、`putPlan()`
- **prompt**：`agent-product-system-prompt.txt`、`agent-architect-system-prompt.txt`
- **失败**：emit ERROR 并终止流（后续步骤依赖 plan，无法继续）

### ② DesignTokenTool — 设计规范加载（零 LLM）

- **职责**：根据 `plan.styleProfile` + confidence，经 `DesignProfileResolver.normalizeProfile()` 归一化后，从 `skill/designs/*.md` 解析出结构化 `DesignSpec`（tokenGroups、forbiddenPatterns、preferredPatterns）。
- **兜底**：异常时 `buildBuiltinDefaultSpec()` 返回内置默认规范。
- **产物**：`Blackboard.putDesignSpec()`，供并发分支的 StyleAgent / CoderAgent 消费。

### ③ UserPreferenceTool — 用户偏好加载（零 LLM，可选）

- 加载用户历史设计偏好画像与权重 → `UserDesignContext`。
- 开关 `agent.personalization.enabled`，缺省跳过。

### ④ ProjectProfileTool — 项目品牌加载（零 LLM，可选）

- 加载项目品牌画像 → `ProjectBrandContext`。

### ⑤ DesignRetrieveTool — 运行时证据召回（可选）

- 基于向量检索（PGvector）+ 权限过滤召回精选设计指纹证据 → `DesignEvidence`。
- 开关 `agent.retrieve.enabled`（默认关），失败自动回退结构检索 / 跳过。

### ⑥ ImageSearchTool — 图片资源获取（零 LLM）

- 消费 `plan.imageSlots`，经 `ImageSearchEngine` 并发搜图（Unsplash / Pexels / picsum 兜底），输出 `keyword → URL` 的 `imageMap`。
- 并发度 `image.replace.concurrency`（默认 4），SSRF 防护（HTTPS + 域名白名单 + 私网拒绝）。
- **产物**：`Blackboard.putImageMap()`

### ⑦a CoderAgent — 代码生成（agentic 核心）

- **职责**：复用 Vue 工程流式生成能力（带文件工具），按 plan 逐文件落盘到 `vue_project_{appId}/`。
- **模型**：`reasoningStreamingChatModel`（推理模型）。
- **机制**：`service.generateVueProjectCodeStream()` 返回 `TokenStream`，LLM **自主决定**调用 `FileWriteTool` 等工具逐文件写盘。
- **prompt 拼装**（`buildUserMessage`）关键约束：
  - 用 `filterStyleFiles()` 剔除全局样式文件（由 StyleAgent 独占），并强制在 `main.js` 中 import；
  - 注入 `DesignSpec.tokenGroups` 确切变量名清单，禁止自创变量；
  - 注入 forbidden / preferred 强约束；
  - 注入 `DesignEvidence`（仅供参考，不得覆盖强约束）；
  - 若上一轮质检未通过，注入 `CriticReport.issues` 逐条修复。
- **收尾**：`onCompleteResponse` 触发 `ImagePlaceholderReplacer.replaceAll(appId)` 替换 `{{IMG:keyword}}` 为真实图片。
- **产物**：每次 `onToolExecuted` 解析 `relativeFilePath` 写入 `Blackboard.addGeneratedFile()`。

### ⑦b StyleAgent — 全局样式生成

- **职责**：根据 `plan.colorTheme` + `DesignSpec.tokenGroups` 生成 `src/styles/variables.css` 与 `global.css`。
- **强契约**：要求 LLM 按 tokenGroups **确切变量名**在 `:root` 中逐一定义，global.css 只能引用这些变量。
- **路径安全**：`writeFile` 校验 `target.startsWith(projectRoot)` 防穿越。
- **产物**：写盘 + `Blackboard.addGeneratedFile()`。
- **prompt**：`agent-style-system-prompt.txt`，期望输出 `{ "variables_css": "...", "global_css": "..." }`。

> ⑦a 与 ⑦b 通过 `Flux.merge` **并发执行**；分工契约（Style 独占样式文件、Coder 禁写但必须 import）保证两者并发不冲突。

### ⑧ CriticAgent — 质量检查

- **静态检查**（零 LLM）：
  - P0 核心文件（`package.json`/`index.html`/`src/main.js`/`src/App.vue`）缺失 → 强制 `passed=false`；
  - `{{IMG:}}` 占位符残留 → 强制失败；
  - P1 推荐文件缺失 → 仅记录，不阻塞；
  - `DesignLintChecker` 确定性反模式扫描（缺 import / 悬空变量 / 毛玻璃 / 装饰渐变 / 硬编码 px / 硬编码颜色 / 浮夸 scale）。
- **LLM 评分**：`agentAiService.review()` 综合评分（score ≥ 80 通过）；LLM 失败时退化为纯静态兜底报告。
- **产物**：`Blackboard.putCriticReport()`。
- **反哺**（阶段三）：若有召回证据，按 score 调用 `FingerprintFeedbackService` 反哺指纹质量。

### ⑨ DeployTool — 构建部署（零 LLM，可选）

- 开关 `agent.deploy.enabled`（默认关）。
- `VueProjectBuilder.buildProject()` 执行 `npm install` + `npm run build` → 校验 `dist/` → 递归上传 COS（路径校验防穿越）→ `Blackboard.putDeployUrl()`。

---

## 六、Critic 反馈循环

`runCriticLoop` 用 `Flux.defer` 惰性启动，递归调用 `doCriticRound(round, lastBranch, pingpong)`：

```
doCriticRound(round, lastBranch, pingpong):
  criticAgent.handle → 读取 Blackboard.CriticReport
    ├─ report == null 或 passed == true        → Flux.empty()  结束 ✓
    ├─ round >= criticMaxRounds(默认 3)         → 降级返回当前结果
    ├─ pingpong >= 2 (Style↔Coder 反复)         → 强制收敛降级
    └─ 未通过且未超限 → 按 issue 分类选修复分支：
          仅样式类(category=style / src/styles/) → 重跑 StyleAgent      [BRANCH_STYLE]
          仅代码类                               → 重跑 CoderAgent      [BRANCH_CODER]
          两类皆有                               → 先 Style 再 Coder    [BRANCH_BOTH]
        → 递归 doCriticRound(round+1, thisBranch, newPingpong)
```

### 关键规则

| 规则 | 说明 |
|------|------|
| 轮次记账 | 按「质检次数」统一计，无论重跑 Coder / Style / 两者，都只占 1 轮预算 |
| 乒乓抑制 | 记录上一轮分支，若本轮与上一轮在 Style↔Coder 间来回，pingpong+1；连续 2 次（≥2）强制收敛 |
| 静态优先 | P0 缺失 / 占位符残留 / Lint P0 命中，强制 `passed=false`，覆盖 LLM 结论 |
| issue 分类 | category=="style" 或 file 以 `src/styles/` 开头判为样式类，否则代码类 |

---

## 七、Blackboard 共享数据契约

| Key | 写入方 | 读取方 | 类型 |
|-----|--------|--------|------|
| `requirementDoc` | PlanningAgent | PlanningAgent | RequirementDoc |
| `plan` | PlanningAgent | DesignTokenTool / Image / Coder / Style / Critic | GenerationPlan |
| `designSpec` | DesignTokenTool | Coder / Style / Critic | DesignSpec |
| `userDesignContext` | UserPreferenceTool | DesignRetrieve / Coder / Style | UserDesignContext |
| `projectBrandContext` | ProjectProfileTool | 下游消费 | ProjectBrandContext |
| `designEvidence` | DesignRetrieveTool | Coder / Style / Critic | DesignEvidence |
| `imageMap` | ImageSearchTool | ImagePlaceholderReplacer | Map<String,String> |
| `generatedFiles` | Coder / Style | Critic | Map<String,Boolean> |
| `criticReport` | CriticAgent | Orchestrator / Coder | CriticReport |
| `deployUrl` | DeployTool | 下游 | String |

> 内部均为 `ConcurrentHashMap`，`imageMap` / `generatedFiles` 用 `computeIfAbsent` 保证并发写安全（Coder∥Style 并发期间均会写 `generatedFiles`）。

---

## 八、事件流与 SSE 回传

```
各 Agent emit Flux<AgentEvent>
   │  (TEXT_CHUNK / TOOL_CALL / TOOL_RESULT / STATUS_CHANGE / ERROR)
   ↓
OrchestratorAgent 串/并联 → Flux<AgentEvent>
   ↓
AppServiceImpl.toStreamPayload(event)   序列化为前端文本
   · TEXT_CHUNK / TOOL_CALL / TOOL_RESULT → 原样透传（已是 JSON 协议串）
   · STATUS_CHANGE / ERROR → 包装为 "[阶段]/[错误] AgentName：content"
   ↓
doExecuteStream → StreamHandlerExecutor.doExecute (VUE_PROJECT)
   → JsonMessageStreamHandler.handle
       逐块解析 AI_RESPONSE / TOOL_REQUEST / TOOL_EXECUTED
       → toolManager.getTool(name) 生成前端可读展示
       → doOnComplete 写【AI 消息】入 chat_history
   ↓
AppController 包装 ServerSentEvent → 末尾追加 event:"done"
   ↓
前端 EventSource 实时渲染：阶段进度 + 工具调用 + 代码增量
```

---

## 九、模型与成本分层

| 执行单元 | 模型 | 成本 |
|---------|------|------|
| PlanningAgent / StyleAgent / CriticAgent | `openAiStreamingChatModel`（普通） | 中 |
| CoderAgent | `reasoningStreamingChatModel`（推理） | 高 |
| DesignTokenTool / UserPreferenceTool / ProjectProfileTool / ImageSearchTool / DeployTool | 无 | 零 token |
| DesignRetrieveTool | Embedding | 低 |

「按需选模型」是多 Agent 拆分带来的直接收益——确定性任务零 LLM，强推理任务才用贵模型。

---

## 十、配置开关清单

| 配置项 | 默认 | 作用 |
|--------|------|------|
| `agent.orchestrator.enabled` | false | 多 Agent 流水线总开关（仅 Vue 生效） |
| `agent.orchestrator.v2.enabled` | false | 启用 V2 编排器（GenerationOrchestrator）优先 |
| `agent.critic.max-rounds` | 3 | Critic 反馈最大轮次 |
| `agent.deploy.enabled` | false | 末尾是否自动构建上传 COS |
| `agent.retrieve.enabled` | false | 运行时设计证据召回 |
| `agent.retrieve.vector.enabled` | false | 向量检索（基于 PGvector） |
| `agent.personalization.enabled` | false | 用户偏好加载 |
| `image.replace.concurrency` | 4 | 搜图并发度 |

---

## 十一、设计要点与命名说明

### 设计要点

1. **关注点分离**：每个 Agent 单一职责 + 专属 system prompt，避免巨型 prompt 指令打架。
2. **质量闭环**：独立 CriticAgent + 反馈循环（Evaluator-Optimizer），静态检查优先于 LLM 主观评分。
3. **并发提速**：Coder∥Style 无依赖部分并发；分工契约保证不冲突。
4. **可插拔**：可选 Tool 用 `@Autowired(required=false)` + 开关，按需挂载不影响主链路。
5. **可观测**：每个阶段 emit STATUS_CHANGE，前端进度可视。
6. **安全**：文件写入全程路径穿越校验；搜图 SSRF 防护；COS 仅上传 dist。
7. **容错**：订阅前异常回退经典链路；流执行中异常 `onErrorResume` 转事件不崩断 SSE。

### 命名说明（重要）

严格按 Workflow / Agent 定义，本流水线中**大多数"Agent"实为 Workflow 节点（固定 LLM 调用步骤）**：

- `PlanningAgent` / `StyleAgent` / `CriticAgent`：带专属 prompt 的固定步骤，无运行时自主决策；
- `*Tool`（DesignToken / Image / Deploy 等）：零 LLM 的确定性工具；
- **真正 agentic 的仅 `CoderAgent`**——其内部 LLM 自主决定调用哪些文件工具、写哪些文件。

因此本系统准确描述为：**「多节点 Workflow + 单节点 Agent」的混合编排**。命名沿用 "Agent" 表达「职责独立的执行单元」，不代表它们都具备自主性。

---

> 文档版本：基于当前 `agent` 包源码梳理。若后续 Agent 命名 / 阶段顺序 / 开关变更，请同步更新本文。
