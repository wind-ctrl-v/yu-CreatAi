# 生成体系分层与模式决策改造计划（Pipeline Router + Mode Resolver）

> 目标：把"用户身份判断"从各 Agent / Service 中收敛到统一的
> `GenerationModeResolver + GenerationPolicy`，让 V2 `GenerationOrchestrator`
> 只按 policy 编排执行；并用 `GenerationPipelineRouter` 统一管理
> **V2 → V1 → Classic** 三级降级。

---

## 0. 现状基线（改造前）

| 维度 | 现状 | 位置 |
|---|---|---|
| 入口 | `AppController.chatToGenCode` → `AppServiceImpl.chatToGenCode` | `controller/AppController.java`、`service/impl/AppServiceImpl.java:188` |
| 路由 | 内联 `if(orchestratorEnabled && VUE_PROJECT)`，V2→V1→Classic 用 try/catch | `AppServiceImpl.java:216-251` |
| V2 编排 | `GenerationOrchestrator.generate(AgentContext)` | `orchestrator/GenerationOrchestrator.java` |
| V2 模式 | 全局 `@Value("${generation.mode:standard}")` **写死**，非按请求 | `GenerationOrchestrator.java:40` |
| V1 | `OrchestratorAgent.handle(AgentContext)` | `agent/.../OrchestratorAgent` |
| Classic | `AiCodeGeneratorFacade.generateAndSaveCodeStream(message, type, appId)` | `core/AiCodeGeneratorFacade` |
| 用户模型 | `User` 仅 `userRole`(user/admin)，**无套餐/额度** | `model/entity/User.java` |
| 应用类型 | `CodeGenTypeEnum`：HTML / MULTI_FILE / VUE_PROJECT | `model/enums/CodeGenTypeEnum.java` |
| Tool 上下文 | `ToolContext` 已有 `generationMode` 字段 | `tool/core/ToolContext.java:38` |
| 生成记录 | 无 `generation_run` 表；`GenerationRecordTool.doRecord` 是占位 | `tool/system/GenerationRecordTool` |
| SSE | chunk 包成 `{"d": chunk}` + 末尾 `done` 事件，无结构化生成事件 | `AppController`、`AppServiceImpl.toStreamPayload` |
| 配置 | `application.yml` 仅 `agent.orchestrator.enabled`，无 modes/entitlements | `resources/application.yml:84` |

**核心结论**：V2/V1/Classic 三链路已存在，缺的是「按用户/额度/复杂度决策模式」与「统一降级 + 隔离 + 记录」的中间层。本计划以**新增中间层 + 最小侵入改造 `AppServiceImpl`** 为主，不重写既有 Agent。

---

## 1. 目标架构（落到本仓库真实类名）

```text
AppController.chatToGenCode
  ↓
AppServiceImpl.chatToGenCode      // 仅保留鉴权/写历史/收口，路由下沉
  ↓
GenerationPipelineRouter.route(request, loginUser, app)   // 新增
  ↓
GenerationModeResolver.resolve(...) → GenerationPolicy     // 新增
  ↓
GenerationOrchestrator.generate(AgentContext, GenerationPolicy)  // 改造：消费 policy
      ├── low_cost / standard / high_quality
  ↓ onErrorResume
OrchestratorAgent.handle(AgentContext)        // V1，已存在
  ↓ onErrorResume
AiCodeGeneratorFacade.generateAndSaveCodeStream(...)  // Classic，已存在
```

新增模块职责：

| 模块 | 类型 | 路径（建议） | 职责 |
|---|---|---|---|
| `GenerationMode` | enum | `model/enums/GenerationMode.java` | LOW_COST / STANDARD / HIGH_QUALITY |
| `PipelineType` | enum | `model/enums/PipelineType.java` | V2 / V1 / CLASSIC |
| `PlanType` | enum | `model/enums/PlanType.java` | FREE/PRO/PREMIUM/ENTERPRISE/ADMIN |
| `GenerationPolicy` | DTO | `model/dto/generation/GenerationPolicy.java` | 模式 + 各项开关/模型/超时/降级位 |
| `GenerationModeResolver` | service | `service/generation/GenerationModeResolver.java` | 综合身份+额度+复杂度→policy |
| `GenerationPipelineRouter` | service | `service/generation/GenerationPipelineRouter.java` | V2/V1/Classic 降级编排 |
| `UserEntitlementService` | service | `service/generation/UserEntitlementService.java` | 取套餐/额度（绿地，先配置驱动）|
| `QuotaService` | service | `service/generation/QuotaService.java` | 额度查询/扣减（绿地）|
| `RequirementComplexityService` | service | `service/generation/RequirementComplexityService.java` | 需求复杂度分析（启发式）|
| `GenerationProperties` | config | `config/GenerationProperties.java` | 绑定 yml modes/entitlements |

---

## 2. 分阶段实施（每阶段可独立合入、可灰度）

### 阶段 1：抽出 PipelineRouter，承接现有三级降级（不改行为）

**目的**：把 `AppServiceImpl.chatToGenCode` 里内联的 V2→V1→Classic 逻辑迁移到 `GenerationPipelineRouter`，并补上**流中降级**（`onErrorResume`），先不引入模式决策。

1. 新增 `service/generation/GenerationPipelineRouter.java`，注入
   `GenerationOrchestrator`、`OrchestratorAgent`、`AiCodeGeneratorFacade`。
   - 方法：`Flux<String> route(Long appId, String message, User loginUser, App app, CodeGenTypeEnum type)`
   - 非 VUE_PROJECT 或开关关 → 直接 Classic。
   - VUE_PROJECT 且 v2 开 → `generationOrchestrator.generate(ctx).map(toStreamPayload)`
     `.onErrorResume(e -> v1...)`，V1 再 `.onErrorResume(e -> classic...)`，
     由 `allowV1Fallback / allowClassicFallback` 控制（阶段1先恒为 true）。
2. 改造 `AppServiceImpl.chatToGenCode`：保留 1~5 步（鉴权、写历史），
   第 6 步替换为 `pipelineRouter.route(...)`，再交 `doExecuteStream` 收口。
   把 `toStreamPayload` 移到 Router 或抽到公共组件复用。
3. **回归点**：开关默认值、SSE 输出格式、`StreamHandlerExecutor` 收口完全不变。

产出：路由集中化 + 流中降级补齐。**此阶段对外行为不变，纯重构。**

---

### 阶段 2：引入 GenerationMode / Policy / Resolver（按请求决策模式）

1. 新增枚举 `GenerationMode`、`PipelineType`、`PlanType`。
2. 新增 `GenerationPolicy`（字段对齐设计稿）：
   ```java
   PipelineType preferredPipeline;
   GenerationMode generationMode;
   boolean allowV1Fallback;
   boolean allowClassicFallback;
   int maxCriticRounds;
   boolean enableLlmCritic;
   boolean enableCoderStyleParallel;
   int maxEvidenceCount;
   String planningModel, generateModel, criticModel;
   long timeoutMs;
   boolean v2Enabled;
   ```
3. 新增 `GenerationProperties`（`@ConfigurationProperties(prefix="generation")`），
   绑定 `application.yml` 的 `modes.*` 与 `entitlements.*`（见 §4）。
4. 新增 `UserEntitlementService`（绿地）：
   - 阶段2先**仅按 `User.userRole` 映射**：admin→ADMIN(可 HIGH_QUALITY)，其余→默认 PRO/STANDARD（可配）。
   - 预留 `PlanType getPlan(Long userId)`，后续接真实套餐表。
5. 新增 `RequirementComplexityService`：基于 message 关键词 + `codeGenType` 的启发式
   （"改颜色/标题"→简单；"完整项目/多页面/响应式"→复杂），返回 `SIMPLE/NORMAL/COMPLEX`。
6. 新增 `GenerationModeResolver.resolve(request, loginUser, app) → GenerationPolicy`：
   - `requestedMode`（前端意图，**不可信**，仅作上限内意图）
   - `maxAllowedMode = byPlan(entitlement)`
   - `complexity` 动态升降级
   - `applyQuotaLimit`（阶段2先 no-op，阶段3接 QuotaService）
   - `buildPolicy(mode, ...)` 读 `GenerationProperties.modes[mode]` 组装。
7. `GenerationPipelineRouter.route` 改为先 `resolve` 得 policy，再据
   `policy.v2Enabled / allowXxxFallback` 路由。

> 安全要点：`requestedMode` 来自前端，服务端 `getMaxAllowedMode` 封顶，禁止 `if(user.isVip()) high_quality` 这类散落判断。

---

### 阶段 3：GenerationOrchestrator 消费 Policy（真正差异化三模式）

1. `GenerationOrchestrator.generate` 增加重载：
   `Flux<AgentEvent> generate(AgentContext ctx, GenerationPolicy policy)`，
   旧 `generate(ctx)` 保留为兼容（用默认 policy）。
2. 用 `policy` 替换内部 `@Value` 写死项：
   - `ToolContext.generationMode` ← `policy.getGenerationMode().value()`
   - critic 轮次 ← `policy.getMaxCriticRounds()`（替换 `criticMaxRounds`）
   - 是否 LLM Critic ← `policy.isEnableLlmCritic()`
   - evidence 数 ← 透传到 `DesignContextInput`（新增 `maxEvidenceCount`/`compactContext` 字段）
3. 按 `policy.getGenerationMode()` 分派（对齐设计稿 §7）：
   - `generateLowCost`：PlanningAgent(compact) → Assembler(evidence=1,compact) → PageGenerateAgent(单遍) → RuleCriticTool。
   - `generateStandard`：Planning → Assembler → (并发开关) Coder∥Style 或 PageGenerate → RuleCritic →（规则失败且 enableLlmCritic 才）Critic 1 轮。
   - `generateHighQuality`：Planning(detailed) → Assembler(full,evidence=N) → Coder∥Style → RuleCritic → Critic 多轮（≤ maxCriticRounds）。
4. `planningModel/generateModel/criticModel` 透传给各 Agent（若 Agent 当前用固定模型，
   需为其 handle 增加 model 覆盖入参；如改动大可阶段3只接 critic 轮次/并发，模型选择留阶段5）。

> 注意：当前 `low_cost` 与 `standard` 实现相同、`runRuleCritic` 是占位。阶段3要让三模式**真正有差异**，并把 `RuleCriticTool` 接成真实规则检查。

---

### 阶段 4：generationId + attempt 级 workspace 隔离 + generation_run 记录

1. 在 `chatToGenCode`/Router 入口生成 `generationId`（如 `gen_` + 雪花/UUID），
   写入 `AgentContext`（新增字段 `generationId`、`pipelineAttemptId`）。
2. workspace 目录改为按设计稿隔离：
   ```text
   workspace/app_{appId}/generation_{generationId}/attempt_{v2|v1|classic}/
   ```
   - 定位现有写目录逻辑（VueProjectBuilder / Facade 的 appId 目录），新增 attempt 维度。
   - 成功 attempt 标记 `current_success_attempt`（meta json）。
3. 新增表 `generation_run`（见 §5 SQL），新增实体/Mapper/Service。
   `GenerationPipelineRouter` 在 start/success/fallback/fail 各节点写记录，
   替换 `GenerationRecordTool.doRecord` 占位。
4. 字段：requested_mode / final_mode / pipeline_type / status(RUNNING/SUCCESS/FAILED/FALLBACK_SUCCESS) / fallback_from / fallback_to / workspace_path / 时间 / error。

---

### 阶段 5：结构化 SSE 事件 + 额度真实扣减 + 配置完善

1. SSE：在 Router 流首部 emit `generation_started`，降级时 emit `pipeline_fallback`，
   末尾 `completed`：
   ```json
   {"event":"generation_started","generationId":"gen_x","pipeline":"V2","mode":"standard","fallbackEnabled":true}
   {"event":"pipeline_fallback","from":"V2","to":"V1","reason":"..."}
   ```
   - 需扩展 `AgentEvent` 类型或在 `AppController` SSE 包装层增加 event name 透传。
2. `QuotaService` 接真实额度（新增 `user_quota` 表或复用计费模块）；
   `GenerationModeResolver.applyQuotaLimit` 真正生效：额度不足 high_quality→standard→low_cost，
   并通过事件提示前端"已切换为标准模式"。
3. `application.yml` 补全 modes / entitlements 配置（见 §4），
   `agent.orchestrator.v2.enabled`、`generation.mode` 默认值显式化。
4. 前端：生成入口增加「快速/标准/高质量」选项 → `requestedMode`；消费上述 SSE 事件展示最终模式与降级提示。

---

## 3. 改动文件清单（汇总）

**新增**
- `model/enums/GenerationMode.java`、`PipelineType.java`、`PlanType.java`
- `model/dto/generation/GenerationPolicy.java`、`RequirementComplexity.java`
- `config/GenerationProperties.java`
- `service/generation/GenerationModeResolver.java`
- `service/generation/GenerationPipelineRouter.java`
- `service/generation/UserEntitlementService.java`
- `service/generation/QuotaService.java`
- `service/generation/RequirementComplexityService.java`
- `model/entity/GenerationRun.java` + Mapper + `service/GenerationRunService.java`
- `docs/sql/006_generation_run.sql`

**改造**
- `service/impl/AppServiceImpl.java`：`chatToGenCode` 第6步下沉到 Router；保留鉴权/写历史/收口。
- `orchestrator/GenerationOrchestrator.java`：新增 `generate(ctx, policy)`，三模式差异化，去掉写死 `@Value` 模式。
- `agent/core/AgentContext.java`：新增 `generationId`、`pipelineAttemptId`（阶段4）。
- `orchestrator/DesignContextInput`：新增 `maxEvidenceCount`、`compactContext`、`fullProjectProfile`。
- `tool/system/GenerationRecordTool.java`：接真实 `generation_run` 写入。
- `controller/AppController.java`：SSE 支持结构化 event（阶段5）。
- `resources/application.yml`：补 modes / entitlements / v2 开关。
- 前端生成入口与 SSE 消费（阶段5）。

---

## 4. 配置草案（application.yml 追加）

```yaml
generation:
  pipeline:
    preferred: V2
    allow-v1-fallback: true
    allow-classic-fallback: true
  modes:
    low_cost:    { planning-model: cheap-model,   generate-model: cheap-model,   critic-model: none,           enable-coder-style-parallel: false, enable-llm-critic: false, max-critic-rounds: 0, max-evidence-count: 1, timeout-ms: 180000 }
    standard:    { planning-model: standard-model, generate-model: standard-model, critic-model: standard-model, enable-coder-style-parallel: true,  enable-llm-critic: true,  critic-trigger: on_rule_failure, max-critic-rounds: 1, max-evidence-count: 3, timeout-ms: 300000 }
    high_quality:{ planning-model: strong-model,   generate-model: strong-model,   critic-model: strong-model,   enable-coder-style-parallel: true,  enable-llm-critic: true,  critic-trigger: always,          max-critic-rounds: 3, max-evidence-count: 5, timeout-ms: 600000 }
entitlements:
  FREE:       { default-mode: low_cost,  max-mode: low_cost,     daily-low-cost-limit: 10 }
  PRO:        { default-mode: standard,  max-mode: standard,     daily-standard-limit: 50 }
  PREMIUM:    { default-mode: standard,  max-mode: high_quality, daily-high-quality-limit: 10,  daily-standard-limit: 100 }
  ENTERPRISE: { default-mode: high_quality, max-mode: high_quality, daily-high-quality-limit: 100 }
  ADMIN:      { default-mode: high_quality, max-mode: high_quality }
```

> 模型名 `cheap/standard/strong-model` 需映射到现有 AI 配置中的真实模型 key。

---

## 5. 数据库（docs/sql/006_generation_run.sql）

```sql
CREATE TABLE generation_run (
    id BIGINT PRIMARY KEY,
    generation_id VARCHAR(128) NOT NULL,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    requested_mode VARCHAR(32),
    final_mode VARCHAR(32),
    pipeline_type VARCHAR(32),
    status VARCHAR(32),            -- RUNNING / SUCCESS / FAILED / FALLBACK_SUCCESS
    fallback_from VARCHAR(32),
    fallback_to VARCHAR(32),
    workspace_path VARCHAR(512),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    duration_ms BIGINT,
    error_message TEXT
);
-- 索引：generation_id、(user_id, started_at)、(app_id, started_at)
```

用户套餐/额度若需持久化，另建 `user_plan` / `user_quota`（阶段5评估，先配置驱动）。

---

## 6. 风险与回退

1. **绿地范围大**：套餐/额度在 `User` 中完全缺失。阶段2先用 `userRole` 兜底 + 配置驱动，**不阻塞主链路**；真实额度留阶段5。
2. **模型选择改动面**：若 Agent 当前硬编码模型，阶段3可先只接「critic 轮次/并发/evidence」，模型 key 透传留阶段5，降低风险。
3. **降级一致性**：阶段4前不同 attempt 共用 appId 目录可能污染；务必在引入流中降级（阶段1）后尽快补 generationId 隔离（阶段4），或阶段1先约束「成功才落库/截图」。
4. **每阶段独立可灰度**：所有新链路受 `agent.orchestrator.v2.enabled` 与 `generation.pipeline.*` 开关控制，默认保持现状，问题可一键回退到经典链路。

---

## 7. 落地顺序（一句话）

> 先抽 Router 收敛降级（阶段1，纯重构）→ 引入 Resolver/Policy 按请求决策（阶段2）→
> Orchestrator 真正差异化三模式（阶段3）→ generationId 隔离 + 记录表（阶段4）→
> 结构化 SSE + 真实额度 + 配置/前端（阶段5）。
> 核心原则：**身份/额度判断只在 `GenerationModeResolver` 收敛，`GenerationOrchestrator` 只消费 `GenerationPolicy`。**

---

## 8. 落地状态（实施记录）

### 已完成

| 阶段 | 产出 | 关键文件 |
|---|---|---|
| 1 | 抽出 `GenerationPipelineRouter`，迁移 V2→V1→Classic 降级并补**流中 `onErrorResume`**；`AppServiceImpl.chatToGenCode` 仅留校验/鉴权/写历史/收口 | `service/generation/GenerationPipelineRouter.java`、`service/impl/AppServiceImpl.java` |
| 2 | 新增 `GenerationMode/PipelineType/PlanType`、`GenerationPolicy`(不可变)、`RequirementComplexity`、`GenerationProperties`(内置默认)、`UserEntitlementService`(userRole 兜底)、`QuotaService`(no-op)、`RequirementComplexityService`、`GenerationModeResolver`；`requestedMode` 经 Controller→Service→Router 透传并由套餐封顶 | `model/enums/*`、`model/dto/generation/*`、`config/GenerationProperties.java`、`service/generation/*`、`controller/AppController.java` |
| 3 | `GenerationOrchestrator` 新增 `generate(ctx, policy)`，按 policy 差异化 low_cost(单遍)/standard(并发开关+规则失败才 LLM Critic)/high_quality(并发+多轮 Critic)；`runRuleCritic` 接真实 `RuleCriticTool`；扩展 `DesignContextInput`(maxEvidenceCount/compactContext/fullProjectProfile)；旧 `generate(ctx)` 保留兼容 | `orchestrator/GenerationOrchestrator.java`、`orchestrator/DesignContextInput.java` |
| 4 | `AgentContext` 增 `generationId/pipelineAttemptId`；新增 `generation_run` 表/实体/Mapper/`GenerationRunService`；Router 生成 `generationId` 并落库 start/fallback/success(含 FALLBACK_SUCCESS)/fail，全程容错不阻断 | `agent/core/AgentContext.java`、`model/entity/GenerationRun.java`、`mapper/GenerationRunMapper.java`、`service/generation/GenerationRunService.java`、`docs/sql/006_generation_run.sql` |
| 5 | Router 在 VUE 路径推送 `generation_started`/`pipeline_fallback`（AiResponseMessage 包装，兼容现有解析；`completed` 由 Controller `done` 承载）；`application.yml` 补全 `generation.*`(pipeline/modes/entitlements)与显式 `agent.orchestrator.v2.enabled`；前端 `connectChatSSE` 支持 `mode`，`AppChatPage` 增「快速/标准/高质量」选择器 | `service/generation/GenerationPipelineRouter.java`、`resources/application.yml`、`frontend/src/utils/sse.ts`、`frontend/src/pages/AppChatPage.vue` |

### 有意延后（需独立推进，附原因）

1. **attempt 级物理 workspace 隔离**：写目录 `{codeGenType}_{appId}`（如 `vue_project_{appId}`）在 `BaseTool`、`CodeFileSaverTemplate`、`StyleAgent`、`CriticAgent`、`DeployTool`、`RuleCriticTool`、`DesignLintChecker`、`ImagePlaceholderReplacer`、`StaticResourceController`(预览)、`AppController`(下载)、`AppServiceImpl`(部署) 等 **10+ 处硬编码**。物理迁移到 `app_{appId}/generation_{id}/attempt_{x}/` 需统一的"写入路径解析/拦截层"，否则会破坏预览/部署/下载。当前已通过 `generationId/pipelineAttemptId` 贯通追踪，`generation_run.workspace_path` 记录规范目录；物理隔离作为独立重构。
2. **真实额度扣减**：`QuotaService.applyQuotaLimit` 仍为 no-op。真实额度需 `user_quota` 表 + 每日计数 + 重置策略（时区/持久化）等基建与产品决策，单列推进；接入后额度不足按 high_quality→standard→low_cost 降级并推事件提示。
3. **模型 key 透传**：`GenerationPolicy.planningModel/generateModel/criticModel` 已建模、`application.yml` 预留，但各 Agent 当前模型装配未改造消费它。需在 Agent `handle` 增加 model 覆盖入参后接通，避免一次性大改动。
4. **`GenerationRecordTool`**：仍为 `generated_page` 内容记录占位（与生成链路生命周期是不同关注点）；链路级记录已由 `GenerationRunService` 落 `generation_run` 承担。
5. **前端事件结构化消费**：`generation_started/pipeline_fallback` 当前以可读文本随流下发，前端尚未单独解析为结构化 UI（如降级 Toast），可后续增强。

### 上线注意

- 执行 `docs/sql/006_generation_run.sql` 建表。
- 默认开关保持现状（`agent.orchestrator.enabled`、`agent.orchestrator.v2.enabled` 默认 `false`），不影响存量；灰度时分别打开。
- 本机无 JDK，未跑 `mvn compile`；已通过 IDE 语言服务诊断全量校验改动文件无错误。

