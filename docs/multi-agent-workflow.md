# 多 Agent 协作工作流程文档

> 最后更新：2026-06-28
> 本文档基于源码分析生成，覆盖 CreatAi 项目的完整多 Agent 流水线设计，包括路由决策、模式选择、套餐权益、降级链与 SSE 事件体系。

---

## 目录

1. [整体架构概述](#一整体架构概述)
2. [请求入口与路由决策](#二请求入口与路由决策)
3. [生成模式决策体系](#三生成模式决策体系)
4. [用户套餐与权益系统](#四用户套餐与权益系统)
5. [V2 编排器 — GenerationOrchestrator](#五v2-编排器--generationorchestrator)
6. [核心基础设施层](#六核心基础设施层)
7. [流水线完整流程图](#七流水线完整流程图)
8. [V1 编排器 — OrchestratorAgent](#八v1-编排器--orchestratoragent)
9. [ProductAgent — 需求解析器](#九productagent--需求解析器)
10. [ArchitectAgent — 架构规划师](#十architectagent--架构规划师)
11. [DesignTokenAgent — 设计规范加载器](#十一designtokenagent--设计规范加载器)
12. [DesignRetrieveAgent — 运行时证据召回器](#十二designretrieveagent--运行时证据召回器)
13. [ImageAgent — 图片资源获取器](#十三imageagent--图片资源获取器)
14. [CoderAgent — 代码生成器](#十四coderagent--代码生成器)
15. [StyleAgent — 全局样式生成器](#十五styleagent--全局样式生成器)
16. [CriticAgent — 质量检查器](#十六criticagent--质量检查器)
17. [DeployAgent — 构建部署器](#十七deployagent--构建部署器)
18. [DesignExtractorAgent — 离线指纹提炼器](#十八designextractoragent--离线指纹提炼器离线链路)
19. [Blackboard 共享数据契约](#十九blackboard-共享数据契约)
20. [关键支撑组件](#二十关键支撑组件)
21. [设计思想总结](#二十一设计思想总结)

---

## 一、整体架构概述

### 背景与动机

传统「单 LLM 一次性生成整个前端项目」存在：上下文污染、无法修复、资源浪费、扩展困难。

**多 Agent 流水线**将上述问题分解为多个专职 Agent，通过共享 Blackboard 传递中间产物，通过 Reactor `Flux` 事件流对外透传执行过程。

### 架构层次

```
                        Controller (HTTP SSE)
                              │
              ┌───────────────┴───────────────┐
              │   GenerationPipelineRouter     │   ← 路由决策层（新增）
              │   ┌───────────────────────┐    │
              │   │ GenerationModeResolver │    │   ← 模式决策层（新增）
              │   │ → GenerationPolicy     │    │
              │   └───────────────────────┘    │
              └───────────────┬───────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                     ▼
  GenerationOrchestrator  OrchestratorAgent   AiCodeGeneratorFacade
       (V2 主链路)          (V1 兼容链路)       (Classic 兜底)
```

- **V2**：三模式流水线（LOW_COST/STANDARD/HIGH_QUALITY），由 `GenerationPolicy` 驱动
- **V1**：传统串并联流水线，由 `@Value` 配置驱动
- **Classic**：单 LLM 全量生成，最终兜底

### 技术选型依据

| 技术 | 选择原因 |
|------|---------|
| Reactor `Flux` | 统一事件流抽象，支持 SSE 透传、串并联编排、背压控制 |
| `Blackboard` 模式 | 解耦 Agent 间依赖，避免强耦合的方法调用链 |
| LangChain4j `AiServices` | `@SystemMessage` 注解将 prompt 文件与方法绑定，职责清晰 |
| 构造函数注入 | 所有 Worker 依赖在编译期显式声明，便于单元测试 |

---

## 二、请求入口与路由决策

### 2.1 Controller 入口

`AppController.chatToGenCode()` 接收 HTTP SSE 请求：

```
GET /app/chat/gen/code?appId={id}&message={msg}&mode={low_cost|standard|high_quality}
```

`mode` 参数可选，为前端不可信意图，由服务端按套餐封顶。

Controller 将请求委托给 `AppServiceImpl`，后者调用 `GenerationPipelineRouter.route()`。

### 2.2 GenerationPipelineRouter — 三级路由

文件：`service/generation/GenerationPipelineRouter.java`

**职责**：统一管理 **V2 → V1 → Classic** 三级降级。自身不调用 LLM，只负责决策与编排。

**路由决策树**：

```
route(appId, message, requestedMode, loginUser, codeGenType)
  │
  ├─ ① modeResolver.resolve(...) → GenerationPolicy
  │
  ├─ ② 非 VUE_PROJECT 或 orchestratorEnabled=false
  │     → 直接走 CLASSIC 链路
  │
  └─ ③ VUE_PROJECT 且 orchestratorEnabled=true
        │
        ├─ policy.isV2Enabled()
        │     → 首选 V2: GenerationOrchestrator.generate(ctx, policy)
        │        ├─ 异常 → isAllowV1Fallback()? → 降级 V1
        │        └─ 异常 → isAllowClassicFallback()? → 降级 CLASSIC
        │
        └─ !policy.isV2Enabled()
              → V1: OrchestratorAgent.handle(ctx)
                 └─ 异常 → isAllowClassicFallback()? → 降级 CLASSIC
```

**关键条件**：

| 条件 | 配置项 | 说明 |
|------|--------|------|
| 多 Agent 总开关 | `agent.orchestrator.enabled` | 关闭则全走 Classic |
| V2 子开关 | `agent.orchestrator.v2.enabled` | 关闭则走 V1 |
| V1 降级 | `generation.pipeline.allow-v1-fallback` | V2 失败是否降级 V1 |
| Classic 降级 | `generation.pipeline.allow-classic-fallback` | 是否最终兜底 Classic |

### 2.3 结构化 SSE 事件

路由器在流中注入结构化事件（前端 `sse.ts` 解析分发）：

| 事件 | 触发时机 | 字段 |
|------|---------|------|
| `generation_started` | VUE 项目流首部 | `generationId`, `pipeline`, `mode`, `fallbackEnabled` |
| `pipeline_fallback` | 链路降级时 | `from`, `to`, `reason` |

事件通过 `AiResponseMessage` 包裹为文本下发（`\n[生成] {json}\n` / `\n[降级] {json}\n`），前端识别前缀提取 JSON 后结构化消费，不会泄漏到聊天气泡。

### 2.4 生成运行记录

`GenerationRunService` 记录每次生成的完整生命周期：

```
start() → SUCCESS / FALLBACK_SUCCESS / FAILED
            ├─ fallback()（记录每次降级）
            └─ 耗时统计（startedAt → finishedAt → durationMs）
```

记录落表 `generation_run`，所有写入操作容错不阻断主流程。

---

## 三、生成模式决策体系

文件：`service/generation/GenerationModeResolver.java`

### 决策顺序

```
resolve(requestedMode, loginUser, message, codeGenType)
  │
  ├─ 步骤0: 获取套餐与权益配置
  │    → UserEntitlementService.getPlan(loginUser) → PlanType
  │    → GenerationProperties.getEntitlementOrDefault(plan)
  │
  ├─ 步骤1: 解析前端意图（不可信）
  │    → low_cost / standard / high_quality，无效则用套餐默认模式
  │
  ├─ 步骤2: 复杂度动态调整
  │    → RequirementComplexityService.analyze(message, codeGenType)
  │    → SIMPLE → LOW_COST
  │    → COMPLEX → HIGH_QUALITY
  │    → NORMAL → 不变
  │
  ├─ 步骤3: 套餐封顶
  │    → GenerationMode.min(candidate, maxAllowedMode)
  │    → 模式序: LOW_COST(rank=1) < STANDARD(rank=2) < HIGH_QUALITY(rank=3)
  │
  ├─ 步骤4: 额度限制
  │    → QuotaService.applyQuotaLimit(loginUser, capped)
  │    → 额度不足时逐级降级
  │
  └─ 步骤5: 组装 GenerationPolicy（不可变对象）
       → 含 preferredPipeline / v2Enabled / fallback 开关
       → 含 enableLlmCritic / enableCoderStyleParallel
       → 含 maxCriticRounds / maxEvidenceCount
       → 含 planningModel / generateModel / criticModel / timeoutMs
```

### 三种生成模式

| 属性 | LOW_COST (快速) | STANDARD (标准) | HIGH_QUALITY (高质量) |
|------|:---:|:---:|:---:|
| Coder∥Style 并发 | ❌ | ✅ | ✅ |
| LLM Critic | ❌ | 规则失败时 1 轮 | 多轮 |
| Design Evidence 条数 | 1 | 3 | 5 |
| 超时 | 180s | 300s | 600s |
| 适用场景 | 简单页面、快速验证 | 常规项目 | 复杂项目、生产级 |

### GenerationPolicy 全部字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `preferredPipeline` | `PipelineType` | 首选链路（V2/V1） |
| `generationMode` | `GenerationMode` | 最终生成模式 |
| `v2Enabled` | `boolean` | 是否启用 V2 |
| `allowV1Fallback` | `boolean` | V2 失败是否降 V1 |
| `allowClassicFallback` | `boolean` | 是否兜底 Classic |
| `enableLlmCritic` | `boolean` | LLM Critic 开关 |
| `enableCoderStyleParallel` | `boolean` | Coder∥Style 并发 |
| `maxCriticRounds` | `int` | Critic 最大轮次 |
| `maxEvidenceCount` | `int` | Design evidence 条数 |
| `planningModel` | `String` | 规划模型 |
| `generateModel` | `String` | 生成模型 |
| `criticModel` | `String` | Critic 模型 |
| `timeoutMs` | `long` | 整体超时 |
| `requestedModeRaw` | `String` | 前端原始意图（仅可观测） |

---

## 四、用户套餐与权益系统

### 4.1 套餐等级

文件：`model/enums/PlanType.java`

| 套餐 | 中文名 | `defaultMode` | `maxMode`（封顶） | 每日配额 |
|------|--------|:---:|:---:|------|
| `FREE` | 免费用户 | `low_cost` | `low_cost` | low_cost × 10 |
| `PRO` | 普通会员 | `standard` | `standard` | standard × 50 |
| `PREMIUM` | 高级会员 | `standard` | `high_quality` | standard×100 + high_quality×10 |
| `ENTERPRISE` | 企业版 | `high_quality` | `high_quality` | high_quality × 100 |
| `ADMIN` | 管理员 | `high_quality` | `high_quality` | 无限制 |

### 4.2 权益服务

`UserEntitlementService.getPlan(loginUser)` 优先读 `User.planType` 字段，无值时回退角色映射兼容旧数据。

`GenerationProperties.EntitlementConfig` 配置项通过 `application.yml` 注入，支持运行时调整。

### 4.3 额度服务

`QuotaService` 基于 `user_quota` 表（`userId + quotaDate + generationMode` 唯一）实施每日额度限制：

- 每日额度不足时逐级降级：`high_quality → standard → low_cost`
- 管理员不受额度限制
- 每次生成完成时 `recordUsage()` 自动 upsert
- 前端通过 `/user/plan` 接口获取剩余额度

### 4.4 前端套餐感知

- 侧边栏显示彩色会员 Badge（灰色免费/蓝色PRO/紫色高级/橙色企业/红色管理员）
- 个人中心展示套餐名称 + 可用模式列表
- 对话页模式选择器按套餐禁用高级模式 + 标注「需升级套餐」
- 进入时自动降级超出套餐范围的模式

---

## 五、V2 编排器 — GenerationOrchestrator

文件：`orchestrator/GenerationOrchestrator.java`

### 与 V1 的核心区别

| 维度 | V1 OrchestratorAgent | V2 GenerationOrchestrator |
|------|----------------------|---------------------------|
| 接口 | 实现 `Agent` 接口 | 独立 `@Component` |
| 策略驱动 | `@Value` 配置 | 不可变 `GenerationPolicy` |
| 模式分化 | 单一流水线 | 三种模式独立实现 |
| 上下文装配 | 分散在流中逐工具调用 | `DesignContextAssembler` 统一装配 |
| 规则检查 | 无独立步骤 | `RuleCriticTool` 零 LLM 规则检查 |
| 并发控制 | Coder∥Style 始终并发 | 由 policy 决定 |

### V2 三模式工作流

| 步骤 | LOW_COST | STANDARD | HIGH_QUALITY |
|------|:---:|:---:|:---:|
| 1. PlanningAgent | ✅ | ✅ | ✅ |
| 2. DesignContextAssembler | 精简 evidence=1 | 正常 evidence=3 | 完整 evidence=5 |
| 3. 页面生成 | PageGenerateAgent | Coder∥Style 并发 | Coder∥Style 并发 |
| 4. RuleCriticTool | ✅ | ✅ | ✅ |
| 5. LLM Critic 循环 | ❌ | 规则失败时 1 轮 | 多轮 ≤ maxRounds |
| 6. Deploy | ✅ | ✅ | ✅ |

### DesignContextAssembler

将原本分散在各 Agent 中的设计知识加载收敛为统一装配器，串联 7 个 System Tool，产出 `DesignContext` DTO：

- DesignTokenTool：加载先验规则
- UserPreferenceTool：用户偏好
- ProjectBrandProfileTool：项目品牌
- DesignKnowledgeRetrieveTool：指纹召回
- ImageSearchTool：图片搜索
- GlobalStyleTool：全局样式
- PromptEnhanceTool：Prompt 增强

装配失败降级继续，不阻断主流程。

### Critic 循环

- `LOW_COST`：不做 LLM Critic
- `STANDARD`：`RuleCriticTool` 规则失败时才触发 1 轮 LLM Critic
- `HIGH_QUALITY`：`RuleCriticTool` 之后始终触发 LLM Critic，最多 `maxCriticRounds` 轮
- 乒乓抑制：Style↔Coder 来回 ≥ 2 次强制收敛

---

## 六、核心基础设施层

### 6.1 `Agent` 接口

```java
public interface Agent {
    Flux<AgentEvent> handle(AgentContext ctx);
    String name();
}
```

### 6.2 `AgentContext` — 请求上下文

| 字段 | 类型 | 说明 |
|------|------|------|
| `appId` | `Long` | 应用 ID |
| `loginUser` | `User` | 登录用户 |
| `userPrompt` | `String` | 用户原始需求 |
| `codeGenType` | `CodeGenTypeEnum` | 生成类型 |
| `blackboard` | `Blackboard` | 共享黑板 |

### 6.3 `AgentEvent` — 统一事件模型

| EventType | 用途 |
|-----------|------|
| `TEXT_CHUNK` | LLM 流式文本增量 |
| `TOOL_CALL` | 工具调用请求 |
| `TOOL_RESULT` | 工具执行回执 |
| `STATUS_CHANGE` | 阶段状态变更 |
| `ERROR` | 错误信息 |

### 6.4 `Blackboard` — 共享数据契约

底层 `ConcurrentHashMap`，保证多 Agent 并发安全：

```
ProductAgent         → putRequirementDoc(RequirementDoc)
ArchitectAgent       → putPlan(GenerationPlan)
DesignTokenAgent     → putDesignSpec(DesignSpec)
DesignRetrieveAgent  → putDesignEvidence(DesignEvidence)
ImageAgent           → putImageMap(Map<String,String>)
CoderAgent / Style   → addGeneratedFile(String)
CriticAgent          → putCriticReport(CriticReport)
DeployAgent          → putDeployUrl(String)
```

设计依据：各 Agent 只关心"我需要读什么、我需要写什么"，无需知道其他 Agent 的实现。

---

## 七、流水线完整流程图

```
用户请求 (HTTP SSE)
        │
        ▼
  AppController.chatToGenCode()
        │
        ▼
  ★ GenerationPipelineRouter.route()
        │
        ├─ ★ GenerationModeResolver.resolve() → GenerationPolicy
        │
        ├─ 非 VUE / 开关关闭 → Classic 兜底
        │
        └─ VUE 项目 + 开关开启
            │
            ├─ V2 开启 → ★ GenerationOrchestrator.generate(ctx, policy)
            │                │
            │                ├─ LOW_COST:  Planning→Assembler→PageGenerateAgent→RuleCritic→Deploy
            │                ├─ STANDARD:  Planning→Assembler→Coder∥Style→RuleCritic→(+LLM Critic)→Deploy
            │                └─ HIGH_QUALITY: 同 STANDARD + 多轮 Critic + 5 evidence
            │
            ├─ V2 未开启 → V1: OrchestratorAgent.handle(ctx)
            │                │
            │                ├─ PlanningAgent → DesignTokenTool → ImageSearchTool
            │                ├─ CoderAgent ∥ StyleAgent
            │                └─ runCriticLoop → (可选) DeployTool
            │
            └─ 失败降级: V2→V1→Classic (按分级 fallback 配置)
        │
        ▼
  SSE 流返回前端（含 generation_started / pipeline_fallback 结构化事件）
```

---

## 八、V1 编排器 — OrchestratorAgent

### 职责

V1 编排器，自身不调用 LLM，只负责串并联编排。

### 流水线

```
PlanningAgent → DesignTokenTool → UserPreferenceTool → ProjectProfileTool
→ DesignRetrieveTool → ImageSearchTool → CoderAgent∥StyleAgent
→ runCriticLoop → (可选) DeployTool → 完成
```

V1 将工具调用嵌入流中逐个执行，没有装配器统一封装。

### 乒乓球抑制

Style↔Coder 修复来回 ≥ 2 次 → 强制收敛降级。

---

## 九、ProductAgent — 需求解析器

### 职责

将用户中文自然语言转化为结构化 `RequirementDoc`，充当"翻译官"角色。

### 输出数据结构

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
    "primary": "#e74c3c", "secondary": "#2c3e50",
    "background": "#f5f5f5", "text": "#333333"
  },
  "imageNeeds": [
    { "slotId": "img_hero_1", "usage": "首页主图", "keyword": "modern shopping mall interior" }
  ]
}
```

### 设计依据

- **前置语义对齐**：模糊输入 → 精确结构
- **职责隔离**：不涉及技术选型
- **输出限制**：pages 默认 1 个，coreFeatures 3-6 项

---

## 十、ArchitectAgent — 架构规划师

### 职责

基于 `RequirementDoc` 输出 `GenerationPlan`——代码生成的"蓝图"。

### 输出数据结构

```json
{
  "techStack": "vue_project",
  "styleProfile": "ecommerce",
  "styleProfileConfidence": 0.88,
  "fileStructure": ["package.json", "vite.config.js", "src/App.vue", ...],
  "routerConfig": [...],
  "imageSlots": [...],
  "colorTheme": { ... }
}
```

### 设计风格枚举

`startup / enterprise / ecommerce / portfolio / dashboard / landing` + `default` 兜底。置信度阈值 0.6，低置信归 `default`。

### 技术栈约束

禁止 TypeScript、Pinia、Vuex、UI 组件库，固定 Vue3 + Vite + 原生 CSS 变量。

---

## 十一、DesignTokenAgent — 设计规范加载器

**阶段一「先验规则」**，不调用 LLM，纯 Java 解析 `classpath:skill/designs/{profile}.md`。

### 三阶段设计知识注入

- **阶段一（DesignTokenAgent）**：静态先验规则，零 LLM，每次都执行
- **阶段二（DesignExtractorAgent）**：离线提炼六层指纹，入库
- **阶段三（DesignRetrieveAgent）**：运行时召回证据，开关控制

### 降级策略

- 识别失败 → `default` 规范
- MD 解析失败 → 内置硬编码默认值
- 任意异常 → 仅推状态事件，不终止流

---

## 十二、DesignRetrieveAgent — 运行时证据召回器

**阶段三「后验证据」**，由 `agent.retrieve.enabled` 控制。通过 `DesignKnowledgeIndex` 检索已批准的设计指纹。

`@ConditionalOnBean(DesignKnowledgeIndex.class)`，未配置时不会注入容器。

---

## 十三、ImageAgent — 图片资源获取器

消费 `plan.imageSlots`，并发调用 Unsplash/Pexels API，输出 `keyword → url` 的 Map。

### 设计要点

- 零 LLM，确定性任务
- `Flux.flatMap` + `Mono.fromCallable` 非阻塞并发搜图
- 单个失败不阻断整体
- 必须在 Coder 之前完成（占位符替换需要 imageMap）

---

## 十四、CoderAgent — 代码生成器

按 `GenerationPlan.fileStructure` 逐文件落盘代码。

### Prompt 注入内容

- 原始需求 + `GenerationPlan`
- `tokenGroups` 精确变量名清单
- `forbiddenPatterns` / `preferredPatterns`
- `DesignEvidence`（阶段三召回）
- 上一轮 `CriticReport.issues`（修复建议）

### 文件职责划分

| 文件 | 归属 | 说明 |
|------|------|------|
| `src/styles/variables.css` | StyleAgent 独占 | 禁止创建 |
| `src/styles/global.css` | StyleAgent 独占 | 禁止创建 |
| `src/main.js` | CoderAgent | 必须 import 样式 |
| 其他所有文件 | CoderAgent | 按 plan 创建 |

---

## 十五、StyleAgent — 全局样式生成器

根据 `plan.colorTheme` + `DesignSpec.tokenGroups` 生成 `variables.css` + `global.css`。

### 设计要点

- 与 CoderAgent **并发执行**（互不依赖，都只读 Blackboard）
- `tokenGroups` 强契约：变量名由 Style 定义，Coder 引用，保证一致
- 禁止 `backdrop-filter`、装饰性渐变、硬编码颜色/px

---

## 十六、CriticAgent — 质量检查器

综合**确定性静态检查** + **LLM 综合评分**。

### 静态检查（零 LLM）

| 检测项 | 级别 | 说明 |
|--------|:---:|------|
| 核心文件缺失 | P0 | `package.json`、`index.html`、`main.js`、`App.vue` |
| 图片占位符残留 | P0 | `{{IMG:...}}` 未替换 |
| 缺少全局样式 import | P0 | `main.js` 缺少样式引入 |
| 悬空 CSS 变量 | P0 | 引用不在 tokenGroups 内的 `var(--xxx)` |
| 毛玻璃效果 | P1 | `backdrop-filter` |
| 装饰性渐变 | P1 | 纯装饰性渐变背景 |
| 硬编码 px | P1 | padding/margin/font-size |
| 硬编码颜色 | P1 | 十六进制颜色值 |
| 浮夸 hover | P1 | scale > 1.05 |

### 评分规则

| 级别 | 扣分 | 通过 |
|:---:|:---:|------|
| P0 | -30/项 | P0 命中 → 强制 `passed=false` |
| P1 | -15/项 | score ≥ 80 → passed=true |
| P2 | -5/项 | |

### 修复路由

- 仅样式问题 → StyleAgent
- 仅代码问题 → CoderAgent
- 两者都有 → StyleAgent → CoderAgent
- 乒乓 ≥ 2 次 → 强制收敛

---

## 十七、DeployAgent — 构建部署器

受 `agent.deploy.enabled` 控制（默认关闭）。执行 `npm install → npm run build → 上传 COS`。

### 安全措施

- 路径严格校验 `target.startsWith(projectRoot)`
- 相对路径 `..` 检查
- COS 凭证不暴露

---

## 十八、DesignExtractorAgent — 离线指纹提炼器（离线链路）

**不接入实时流水线**。将精选页面按六层契约提炼为 `DesignFingerprintDTO`，入库供 `DesignRetrieveAgent` 检索。

### 六层指纹

- 布局模式（layoutPatterns）
- 颜色 Token 模式（colorPatterns）
- 排版规则（typographyPatterns）
- 组件推荐写法（preferredPatterns）
- 交互规则（interactionPatterns）
- 动效规则（motionPatterns）

### 安全隔离

`DesignSourcePreprocessor` 预先剥离原始 HTML 的脚本与业务文案，防止 Prompt Injection。

---

## 十九、Blackboard 共享数据契约

```
┌─────────────────────────────────────────────────────────────┐
│                        Blackboard                           │
│                   (ConcurrentHashMap)                       │
├──────────────────┬──────────────────┬───────────────────────┤
│      Key         │      写入者       │        读取者          │
├──────────────────┼──────────────────┼───────────────────────┤
│ requirementDoc   │ ProductAgent     │ ArchitectAgent        │
├──────────────────┼──────────────────┼───────────────────────┤
│ plan             │ ArchitectAgent   │ DesignToken/Retrieve   │
│                  │                  │ Image/Coder/Style      │
│                  │                  │ CriticAgent           │
├──────────────────┼──────────────────┼───────────────────────┤
│ designSpec       │ DesignTokenAgent │ DesignRetrieve/Coder   │
│                  │                  │ Style/Critic(Lint)    │
├──────────────────┼──────────────────┼───────────────────────┤
│ designEvidence   │ DesignRetrieve   │ Coder/Style           │
├──────────────────┼──────────────────┼───────────────────────┤
│ imageMap         │ ImageAgent       │ Coder(Placeholder)    │
├──────────────────┼──────────────────┼───────────────────────┤
│ generatedFiles   │ Coder/Style      │ CriticAgent           │
├──────────────────┼──────────────────┼───────────────────────┤
│ criticReport     │ CriticAgent      │ Orchestrator / Coder  │
├──────────────────┼──────────────────┼───────────────────────┤
│ deployUrl        │ DeployAgent      │ 最终输出              │
└──────────────────┴──────────────────┴───────────────────────┘
```

---

## 二十、关键支撑组件

### DesignProfileResolver

白名单校验 + 置信度过滤，从 `classpath:skill/designs/{profile}.md` 解析 `DesignSpec`。

### DesignLintChecker

零 LLM 正则扫描，检测 7 类反模式。

### ImagePlaceholderReplacer

遍历项目文件，替换 `{{IMG:keyword}}` 为真实 URL。

### JsonExtractor

从 LLM 输出中容错提取 JSON（处理 markdown 围栏）。

### AgentAiService

LangChain4j `@SystemMessage` 绑定 prompt 文件：

| 方法 | Prompt | 调用 Agent |
|------|--------|-----------|
| `generateRequirementDoc` | `agent-product-system-prompt` | ProductAgent |
| `generatePlan` | `agent-architect-system-prompt` | ArchitectAgent |
| `generateStyleSheets` | `agent-style-system-prompt` | StyleAgent |
| `review` | `agent-critic-system-prompt` | CriticAgent |
| `generateFingerprint` | `agent-extractor-system-prompt` | DesignExtractorAgent |

---

## 二十一、设计思想总结

### 核心原则

| 原则 | 体现 |
|------|------|
| **单一职责** | 每个 Agent 只做一件事；Router 只做路由 |
| **策略外置** | 模式/套餐/额度/超时全部由 `GenerationPolicy` 承载，编排器零判断 |
| **确定性优先** | 能用 Java 的绝不用 LLM（规范解析、图片搜索、静态检查、规则检查） |
| **契约先于实现** | tokenGroups 是 Style/Coder 的共同契约，plan 是各 Agent 的共同蓝图 |
| **降级不中断** | 非核心 Agent 出错只降级不中断；链路失败按 V2→V1→Classic 降级 |
| **职责收敛** | 身份/套餐/复杂度/额度全部收敛于 `GenerationModeResolver`，编排器不再重复判断 |
| **静态优先于 LLM** | Critic 的 P0 静态结论高于 LLM 评分 |
| **精准修复** | Critic → Coder/Style 携带 issue 清单，避免盲目重写 |
| **安全设计** | 路径穿越防护、Prompt Injection 隔离、凭证不外泄 |

### 与传统单 LLM 方案的对比

| 维度 | 单 LLM | 多 Agent 流水线 |
|------|--------|----------------|
| **上下文质量** | 所有任务混合干扰 | 每个 LLM 聚焦单一任务 |
| **可修复性** | 生成后无法自动修复 | Critic 反馈循环自动修复 |
| **LLM 成本** | 全部消耗 LLM | 确定性任务零 LLM |
| **质量分级** | 不可控 | 三档模式按需选择 |
| **套餐适配** | 无 | 按套餐封顶模式 + 额度限制 |
| **生成质量** | 依赖单次输出 | 三阶段设计知识 + Critic 双重保障 |
| **可观测性** | 黑盒 | AgentEvent + SSE 事件实时进度 |
| **链路韧性** | 单 LLM 不可用=全局瘫痪 | V2→V1→Classic 三级降级 |
| **可扩展性** | 改动整体 prompt | 新增 Worker 实现 Agent 接口即可 |
