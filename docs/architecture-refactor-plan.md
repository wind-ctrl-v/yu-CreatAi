# 架构改造计划：Agent / Tool / Service / Worker 分层重构

> 将当前扁平化的"一串 Agent"改造为清晰的 **LLM Agent + System Tool + Agent Tool + Service + Worker** 分层架构。

---

## 一、改造目标

### 1.1 核心原则

| 角色 | 判断标准 | 消耗 Token |
|------|----------|------------|
| **Agent** | 调用 Chat LLM，需要理解/规划/生成/评审/抽取，输出有不确定性 | Chat Token |
| **System Tool** | 确定性能力，由 Orchestrator / ContextAssembler 固定调用，不暴露给 LLM | 无 Chat Token |
| **Agent Tool** | 允许 LLM Agent 在白名单内按需调用 | 无 Chat Token（少数例外） |
| **Service** | Tool 背后的领域业务实现 | 无 |
| **Worker** | 异步后台任务，不阻塞主链路 | 无/可选 |

### 1.2 团队一眼能看懂

```text
Agent   → 通常会调用 LLM，消耗 Chat Token
Tool    → 工作流中的确定性能力，通常不消耗 Chat Token
Service → Tool 背后的领域业务实现
Worker  → 异步后台任务，不阻塞主链路
```

---

## 二、模块改名映射

### 2.1 保留为 Agent 的模块（真正需要 LLM）

| 当前名称 | 改为 | 说明 |
|----------|------|------|
| `ProductAgent` | 合并入 `PlanningAgent` | 需求解析 + 技术选型合并为一次 LLM 规划 |
| `ArchitectAgent` | 合并入 `PlanningAgent` | 同上 |
| `CoderAgent` | `CoderAgent`（保留） | LLM 生成 HTML/JS |
| `StyleAgent` | `StyleAgent`（保留） | LLM 生成 CSS |
| `CriticAgent` | `CriticAgent`（保留，可选） | LLM 评估生成质量 |
| — | `PageGenerateAgent`（新增） | 低成本模式下一次生成 HTML/CSS/JS |
| — | `DesignExtractorAgent`（保留） | LLM 提炼六层设计指纹 |

### 2.2 改为 System Tool 的模块（不调用 Chat LLM）

| 当前名称 | 改为 | 背后 Service |
|----------|------|-------------|
| `DesignTokenAgent` | `DesignTokenTool` | `DesignProfileResolver` |
| `UserPreferenceAgent` | `UserPreferenceTool` | `UserPreferenceService` |
| `ProjectPreferenceAgent` | `ProjectProfileTool` | `ProjectBrandProfileService` |
| `DesignRetrieveAgent` | `DesignRetrieveTool` | `VectorSearchService` |
| `DesignRerankAgent` | `DesignRerankTool` | `DesignRerankService` |
| `ImageAgent` | `ImageSearchTool` | `ImageAssetService` |
| `DeployAgent` | `DeployTool` | `DeploymentService` |
| — | `RuleCriticTool`（新增） | — |
| — | `ContextCompressTool`（新增） | — |
| — | `GenerationRecordTool`（新增） | `GenerationRecordService` |
| — | `RagTraceTool`（新增） | `RagTraceService` |

### 2.3 改为 Worker 的模块（异步后台）

| 当前名称 | 改为 |
|----------|------|
| `PreferenceUpdateAgent` | `PreferenceUpdateWorker` |
| `FingerprintIngestService` | `FingerprintIngestWorker` + `DesignFingerprintService` |
| `FingerprintCrawlScheduler` | `SelfFedFingerprintCrawlWorker` |
| —（新方案） | `CuratedSourceCrawlWorker` |
| `VectorSyncService` | `VectorSyncWorker` + `VectorSyncService` |

---

## 三、最终推荐架构

### 3.1 标准模式（默认）

```text
GenerationOrchestrator
    ↓
PlanningAgent（LLM：需求解析 + 技术选型）
    ↓
DesignContextAssembler
    ├── DesignTokenTool         ← 加载静态设计规范
    ├── UserPreferenceTool      ← 读取用户偏好
    ├── ProjectProfileTool      ← 读取项目品牌
    ├── DesignRetrieveTool      ← 向量检索 + 权限过滤
    ├── DesignRerankTool        ← 综合评分 + 多样性过滤
    ├── ImageSearchTool         ← 检索配套图片
    └── ContextCompressTool     ← 压缩上下文生成 PromptContext
    ↓
PageGenerateAgent（LLM：一次生成 HTML/CSS/JS）
    ↓
RuleCriticTool（确定性规则检查）
    ↓
CriticAgent（LLM 评审，可选，按策略触发）
    ↓
DeployTool（构建 + 部署）
    ↓
GenerationRecordTool + RagTraceTool（记录追踪）
    ↓
AsyncFeedbackPipeline（异步）
    ├── PreferenceUpdateWorker
    ├── FingerprintIngestWorker
    └── VectorSyncWorker
```

### 3.2 高质量模式

```text
GenerationOrchestrator
    ↓
PlanningAgent（LLM）
    ↓
DesignContextAssembler（同上）
    ↓
CoderAgent（LLM）  ←  并发
StyleAgent（LLM）   ←  并发
    ↓
RuleCriticTool → CriticAgent（LLM）
    ↓
DeployTool → Trace → AsyncFeedbackPipeline
```

### 3.3 低成本模式

```text
GenerationOrchestrator
    ↓
PlanningAgent（LLM，可用小模型）
    ↓
DesignContextAssembler（精简版）
    ↓
PageGenerateAgent（LLM，一次生成）
    ↓
RuleCriticTool
    ↓
DeployTool
```

---

## 四、包结构规划

```text
com.yourapp.agent
    PlanningAgent              ← LLM：需求解析 + 技术选型
    PageGenerateAgent          ← LLM：低成本模式一次生成
    CoderAgent                 ← LLM：高质量模式生成 HTML/JS
    StyleAgent                 ← LLM：高质量模式生成 CSS
    CriticAgent                ← LLM：质量评审（可选）
    DesignExtractorAgent       ← LLM：提炼六层设计指纹

com.yourapp.tool.system
    DesignTokenTool            ← 加载静态设计规范
    UserPreferenceTool         ← 读取用户偏好画像
    ProjectProfileTool         ← 读取项目品牌画像
    DesignRetrieveTool         ← 向量检索 + 权限过滤
    DesignRerankTool           ← 综合评分 + 多样性过滤
    ImageSearchTool            ← 检索配套图片资源
    ContextCompressTool        ← 压缩上下文，生成 PromptContext
    RuleCriticTool             ← 确定性规则检查
    DeployTool                 ← 构建 + 部署（仅 Orchestrator 调用）
    GenerationRecordTool       ← 记录生成结果
    RagTraceTool               ← 记录 RAG 调用追踪

com.yourapp.tool.agent
    SearchUserHistoryTool      ← 搜索用户历史生成页面
    CompetitorReferenceTool    ← 查询竞品参考风格
    ReferenceUrlAnalyzeTool    ← 分析参考 URL 页面风格
    BrandExtractTool           ← 从描述提取品牌画像
    ClarifyRequirementTool     ← 澄清模糊需求

com.yourapp.service
    UserPreferenceService
    ProjectBrandProfileService
    DesignFingerprintService
    VectorSearchService
    DesignRerankService
    ImageAssetService
    DeploymentService
    GenerationRecordService
    RagTraceService

com.yourapp.worker
    PreferenceUpdateWorker
    FingerprintIngestWorker
    VectorSyncWorker
    CuratedSourceCrawlWorker
    SelfFedFingerprintCrawlWorker

com.yourapp.orchestrator
    GenerationOrchestrator
    DesignContextAssembler
    AsyncFeedbackPipeline

com.yourapp.tool.core
    ToolContext
    SystemTool<I, O>
    AgentTool<I, O>
    ToolRegistry
    ToolCallTraceService
```

---

## 五、接口设计

### 5.1 SystemTool 接口

System Tool 由系统固定调用，不暴露给 LLM。

```java
public interface SystemTool<I, O> {

    /** 工具名称 */
    String name();

    /** 执行确定性操作，context 由系统注入 */
    O execute(I input, ToolContext context);
}
```

示例实现：

```java
public class UserPreferenceTool implements SystemTool<UserPreferenceInput, UserDesignContext> {

    private final UserPreferenceService userPreferenceService;

    @Override
    public String name() {
        return "UserPreferenceTool";
    }

    @Override
    public UserDesignContext execute(UserPreferenceInput input, ToolContext context) {
        // userId 从 context 获取，不由外部传入
        return userPreferenceService.loadUserDesignContext(
            context.getCurrentUserId()
        );
    }
}
```

### 5.2 AgentTool 接口

Agent Tool 暴露给 LLM Agent 在白名单内按需调用。

```java
public interface AgentTool<I, O> {

    /** 工具名称 */
    String name();

    /** 返回 Tool Schema（名称、描述、参数定义），供 LLM Function Calling */
    ToolSchema schema();

    /** 执行操作，context 由系统注入 */
    O execute(I input, ToolContext context);
}
```

示例实现：

```java
public class SearchUserHistoryTool implements AgentTool<SearchHistoryInput, SearchHistoryResult> {

    private final UserHistoryService userHistoryService;

    @Override
    public String name() {
        return "SearchUserHistoryTool";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(
            "搜索用户历史生成的页面，支持自然语言查询。用户说"参考我上次那个页面"时使用。"
        );
    }

    @Override
    public SearchHistoryResult execute(SearchHistoryInput input, ToolContext context) {
        // userId 从 context 获取，LLM 不传
        return userHistoryService.search(
            context.getCurrentUserId(),
            input.getQuery(),
            Math.min(input.getLimit(), 5)
        );
    }
}
```

### 5.3 ToolContext

权限信息、用户信息由系统注入，不允许 LLM 自己传。

```java
public class ToolContext {

    /** 请求 ID */
    private String requestId;

    /** 当前用户 ID（系统注入，LLM 不可传） */
    private Long currentUserId;

    /** 当前项目 ID（系统注入） */
    private Long currentProjectId;

    /** 当前团队 ID（系统注入） */
    private Long currentTeamId;

    /** 权限集合（系统注入） */
    private Set<String> permissions;

    /** 生成模式：low_cost / standard / high_quality */
    private String generationMode;
}
```

**关键安全原则**：

> userId / projectId / teamId 不由 LLM 提供。LLM 调 Agent Tool 时只能传业务参数，身份信息由系统从 ToolContext 注入。

❌ 错误方式（LLM 传 userId）：

```json
{
  "userId": 1002,
  "query": "上次那个蓝色官网"
}
```

✅ 正确方式（userId 从 ToolContext 取）：

```json
{
  "query": "上次那个蓝色官网",
  "limit": 5
}
```

---

## 六、System Tool 详细设计

### 6.1 DesignTokenTool

| 项目 | 内容 |
|------|------|
| **原名** | `DesignTokenAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 根据 styleProfile 加载对应 DesignSpec，读取 `skill/designs/{profile}.md`，解析设计 token/布局约束/组件规范 |

### 6.2 UserPreferenceTool

| 项目 | 内容 |
|------|------|
| **原名** | `UserPreferenceAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 读取 `user_design_profile` + `user_preference_weight`，聚合用户偏好，返回 `UserDesignContext` |
| **背后 Service** | `UserPreferenceService` |

### 6.3 ProjectProfileTool

| 项目 | 内容 |
|------|------|
| **原名** | `ProjectPreferenceAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 读取 `project_brand_profile`，获取项目品牌色/调性/禁用项，返回 `ProjectBrandContext` |
| **背后 Service** | `ProjectBrandProfileService` |

### 6.4 DesignRetrieveTool

| 项目 | 内容 |
|------|------|
| **原名** | `DesignRetrieveAgent` |
| **类型** | System Tool |
| **调用 Chat LLM** | 否 |
| **消耗 Embedding Token** | 是（构造 query embedding） |
| **职责** | 构造检索 query → 调用 embedding → 查询 PGvector → 权限过滤 → 返回候选 `DesignFingerprint` |
| **背后 Service** | `VectorSearchService`、`DesignFingerprintService` |

> **注意**：Embedding Token 与 Chat Token 是两种不同的计费模型，DesignRetrieveTool 不消耗 Chat Token。

### 6.5 DesignRerankTool

| 项目 | 内容 |
|------|------|
| **原名** | `DesignRerankAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 对召回候选做综合评分（语义+用户偏好+项目品牌+质量分），执行多样性过滤，输出最终注入的 evidence 列表 |

### 6.6 ImageSearchTool

| 项目 | 内容 |
|------|------|
| **原名** | `ImageAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 根据行业/页面类型/模块名称检索配套图片（hero、feature、testimonial 等），可接内部素材库/Unsplash/Pexels/COS |

### 6.7 ContextCompressTool（新增）

| 项目 | 内容 |
|------|------|
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 压缩 DesignSpec/用户偏好/项目品牌/DesignEvidence，为不同 Agent 生成定制化 PromptContext |

输出：

```text
PageGenerateAgentPromptContext   ← 单 Agent 模式使用
CoderAgentPromptContext          ← 并发模式 HTML 侧使用
StyleAgentPromptContext          ← 并发模式 CSS 侧使用
CriticAgentPromptContext         ← 评审时使用
```

这是控制 token 消耗的关键模块。

### 6.8 RuleCriticTool（新增）

| 项目 | 内容 |
|------|------|
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **职责** | 确定性规则检查：HTML/CSS/JS 是否为空、是否包含必要 section、CSS 变量是否使用、响应式断点、build 预检 |

原先可能由 CriticAgent 负责的规则检查迁移到此 Tool，只把真正需要"审美判断"的部分留给 LLM CriticAgent。

### 6.9 DeployTool

| 项目 | 内容 |
|------|------|
| **原名** | `DeployAgent` |
| **类型** | System Tool |
| **调用 LLM** | 否 |
| **安全约束** | **仅 Orchestrator 可调用，LLM Agent 无权调用** |
| **职责** | 构建项目 → 上传资源 → 返回访问 URL → 记录部署状态 |

---

## 七、Agent Tool 详细设计

Agent Tool 允许 LLM Agent 按需调用。第一阶段只开放给 `PlanningAgent`。

### 7.1 第一批 Agent Tool 清单

| Tool | 用途 | 典型触发场景 |
|------|------|-------------|
| `SearchUserHistoryTool` | 搜索用户历史生成页面 | "参考我上次那个页面" |
| `CompetitorReferenceTool` | 查询平台已有参考风格 | "类似 Linear / Stripe 风格" |
| `ReferenceUrlAnalyzeTool` | 分析参考 URL 页面风格 | 用户提供竞品链接 |
| `BrandExtractTool` | 从描述中提取品牌画像 | 项目首次创建时 |
| `ClarifyRequirementTool` | 生成需求澄清问题 | 需求过于模糊时 |

### 7.2 ReferenceUrlAnalyzeTool 安全约束

使用 `SafeUrlFetcher` 包装：

```text
禁止内网地址（10.*、9.*、11.*、21.*、30.*、localhost）
禁止 file:// 协议
限制超时和响应大小
只读，不写入
```

### 7.3 调用限制配置

```yaml
agent-tool-policy:
  max-calls-per-run: 3        # 单次 Agent 运行最多调用 3 次
  max-calls-per-tool: 1       # 同一 Tool 最多调用 1 次
  timeout-ms: 3000            # 单次调用超时 3 秒
  max-output-chars: 1500      # 单次输出最多 1500 字符
```

---

## 八、ToolRegistry 设计

### 8.1 管理范围

```text
System Tool  → 由 Spring DI 注入给 Orchestrator / Assembler，不注册到 ToolRegistry
Agent Tool   → 注册到 ToolRegistry，提供给 LLM Agent 白名单调用
```

### 8.2 Agent Tool 白名单配置

```yaml
agent-tools:
  PlanningAgent:
    - SearchUserHistoryTool
    - CompetitorReferenceTool
    - ReferenceUrlAnalyzeTool
    - BrandExtractTool
    - ClarifyRequirementTool

  PageGenerateAgent: []      # 默认无工具

  CoderAgent: []             # 默认无工具

  StyleAgent: []             # 默认无工具

  CriticAgent: []            # 默认无工具
```

### 8.3 安全原则

- `CoderAgent` / `StyleAgent` 默认无工具权限，只能基于 PromptContext 生成代码
- `DeployTool` / 数据库写入类 / 删除类工具绝不能被 Agent 调用
- 只有 `PlanningAgent`（安全沙箱内）可以调用有限的只读查询工具

---

## 九、DesignContextAssembler 设计

System Tool 的统一编排器，负责将所有上下文装配为一个 `DesignContext`。

### 9.1 输入

```java
public class DesignContextInput {
    private Long userId;
    private Long projectId;
    private RequirementDoc requirementDoc;
    private GenerationPlan generationPlan;
    private String generationMode;
}
```

### 9.2 输出

```java
public class DesignContext {
    private DesignSpec designSpec;                  // 静态设计规范
    private UserDesignContext userContext;           // 用户偏好
    private ProjectBrandContext projectContext;      // 项目品牌
    private List<DesignEvidence> designEvidence;     // RAG 证据（已 rerank）
    private ImageMap imageMap;                       // 配套图片
    private PromptContext promptContext;              // 压缩后的 Prompt 上下文
}
```

### 9.3 编排逻辑

```java
public class DesignContextAssembler {

    private final DesignTokenTool      designTokenTool;
    private final UserPreferenceTool   userPreferenceTool;
    private final ProjectProfileTool   projectProfileTool;
    private final DesignRetrieveTool   designRetrieveTool;
    private final DesignRerankTool     designRerankTool;
    private final ImageSearchTool      imageSearchTool;
    private final ContextCompressTool  contextCompressTool;

    public DesignContext assemble(DesignContextInput input, ToolContext toolContext) {

        // 1. 加载静态设计规范
        DesignSpec designSpec = designTokenTool.execute(
            new DesignTokenInput(input.getGenerationPlan().getStyleProfile()), toolContext);

        // 2. 读取用户偏好
        UserDesignContext userContext = userPreferenceTool.execute(
            new UserPreferenceInput(), toolContext);

        // 3. 读取项目品牌
        ProjectBrandContext projectContext = projectProfileTool.execute(
            new ProjectProfileInput(), toolContext);

        // 4. 向量检索 + 权限过滤
        RetrieveResult retrieveResult = designRetrieveTool.execute(
            new RetrieveInput(input.getRequirementDoc(), input.getGenerationPlan(),
                userContext, projectContext), toolContext);

        // 5. 综合 rerank + 多样性过滤
        RerankResult rerankResult = designRerankTool.execute(
            new RerankInput(retrieveResult, userContext, projectContext), toolContext);

        // 6. 检索图片
        ImageMap imageMap = imageSearchTool.execute(
            new ImageSearchInput(input.getRequirementDoc(), input.getGenerationPlan()),
            toolContext);

        // 7. 压缩上下文生成 PromptContext
        PromptContext promptContext = contextCompressTool.execute(
            new ContextCompressInput(designSpec, userContext, projectContext,
                rerankResult.getInjectedEvidence(), imageMap), toolContext);

        return DesignContext.builder()
            .designSpec(designSpec)
            .userContext(userContext)
            .projectContext(projectContext)
            .designEvidence(rerankResult.getInjectedEvidence())
            .imageMap(imageMap)
            .promptContext(promptContext)
            .build();
    }
}
```

---

## 十、GenerationOrchestrator 主流程

```java
public GenerationResult generate(GenerateRequest request) {

    // 构建系统上下文（userId/projectId 由系统注入）
    ToolContext toolContext = ToolContext.builder()
        .requestId(request.getRequestId())
        .currentUserId(request.getUserId())
        .currentProjectId(request.getProjectId())
        .currentTeamId(request.getTeamId())
        .generationMode(request.getGenerationMode())
        .build();

    // ===== 1. LLM 规划阶段 =====
    PlanningOutput planning = planningAgent.run(
        PlanningInput.from(request, toolContext));

    // ===== 2. System Tool 上下文装配 =====
    DesignContext designContext = designContextAssembler.assemble(
        DesignContextInput.builder()
            .userId(request.getUserId())
            .projectId(request.getProjectId())
            .requirementDoc(planning.getRequirementDoc())
            .generationPlan(planning.getGenerationPlan())
            .generationMode(request.getGenerationMode())
            .build(),
        toolContext);

    // ===== 3. LLM 页面生成 =====
    PageGenerateOutput page = pageGenerateAgent.run(
        PageGenerateInput.builder()
            .requirementDoc(planning.getRequirementDoc())
            .generationPlan(planning.getGenerationPlan())
            .promptContext(designContext.getPromptContext())
            .build());

    // ===== 4. System Tool 规则检查 =====
    RuleCriticResult ruleResult = ruleCriticTool.execute(
        new RuleCriticInput(page), toolContext);

    // ===== 5. 可选 LLM Critic =====
    if (criticPolicy.needLlmCritic(ruleResult, request)) {
        CriticResult criticResult = criticAgent.run(
            CriticInput.from(page, ruleResult, designContext));
        page = repairIfNeeded(page, criticResult);
    }

    // ===== 6. System Tool 部署 =====
    DeployResult deployResult = deployTool.execute(
        new DeployInput(page), toolContext);

    // ===== 7. 记录生成结果 =====
    generationRecordTool.execute(
        new GenerationRecordInput(request, planning, designContext, page, deployResult),
        toolContext);

    // ===== 8. 记录 RAG Trace =====
    ragTraceTool.execute(
        new RagTraceInput(request, designContext), toolContext);

    // ===== 9. 异步反哺 =====
    asyncFeedbackPipeline.publishGenerated(
        request, planning, designContext, page, deployResult);

    return GenerationResult.from(page, deployResult);
}
```

---

## 十一、异步 Worker 设计

### 11.1 PreferenceUpdateWorker

| 项目 | 内容 |
|------|------|
| **原名** | `PreferenceUpdateAgent` |
| **触发事件** | `page_saved`、`page_exported`、`page_deleted`、`page_regenerated`、`feedback_submitted`、`color_changed`、`layout_changed`、`component_added`、`component_removed` |
| **职责** | 消费 `user_design_event` → 更新 `user_preference_weight` → 必要时刻更新 `user_design_profile` → 可选定期调 LLM 总结 `profile_summary` |

### 11.2 FingerprintIngestWorker

| 项目 | 内容 |
|------|------|
| **原名** | `FingerprintIngestService` |
| **拆分** | `FingerprintIngestWorker` + `DesignFingerprintService` |
| **触发事件** | 用户保存/导出页面 |
| **职责** | 读取 `generated_page` → 调 `DesignExtractorAgent` 抽取指纹 → 调 `DesignFingerprintService` 入库 → 触发 `VectorSyncWorker` |

### 11.3 VectorSyncWorker

| 项目 | 内容 |
|------|------|
| **触发事件** | `fingerprint_approved` 事件 |
| **职责** | 生成 embedding → 写入 PGvector → 更新同步状态 |

---

## 十二、Trace 和审计

### 12.1 tool_call_trace 表

```sql
CREATE TABLE tool_call_trace (
    id BIGINT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    generation_id BIGINT NULL,

    caller_type VARCHAR(32) NOT NULL,    -- ORCHESTRATOR / CONTEXT_ASSEMBLER / AGENT / WORKER
    caller_name VARCHAR(128) NOT NULL,
    tool_type VARCHAR(32) NOT NULL,      -- SYSTEM_TOOL / AGENT_TOOL
    tool_name VARCHAR(128) NOT NULL,

    input_summary JSON NULL,
    output_summary JSON NULL,

    success BOOLEAN NOT NULL,
    error_message TEXT NULL,
    latency_ms BIGINT NULL,

    created_at TIMESTAMP NOT NULL
);
```

### 12.2 必须记录的 Tool 调用

```text
DesignTokenTool         UserPreferenceTool      ProjectProfileTool
DesignRetrieveTool      DesignRerankTool        ImageSearchTool
ContextCompressTool     RuleCriticTool          DeployTool
GenerationRecordTool    RagTraceTool
```

### 12.3 可观测能力

```text
每次生成 → 完整 Tool 调用链路可见
可定位：RAG 未召回、偏好未命中、部署失败等问题
可统计：每个 Tool 的耗时和失败率
与 generation_rag_trace 关联，形成完整追踪
```

---

## 十三、配置总览

```yaml
# ===== 生成模式 =====
generation:
  mode: standard                # low_cost | standard | high_quality

# ===== Critic 策略 =====
critic:
  mode: llm_on_failure          # rule_only | llm_on_failure | always

# ===== 上下文压缩 =====
context:
  max-evidence-count: 3
  max-evidence-chars: 180
  max-user-preference-chars: 300
  max-project-profile-chars: 300
  max-design-spec-chars: 1500

# ===== Agent Tool 配置 =====
agent-tools:
  enabled: true
  max-calls-per-run: 3
  max-calls-per-tool: 1
  timeout-ms: 3000
  max-output-chars: 1500

# ===== Tool Trace =====
tool-trace:
  enabled: true
```

---

## 十四、分阶段实施计划

### 阶段一：命名和包结构调整

**目标**：先让团队和代码结构清楚地区分 Agent / Tool / Service / Worker。

**任务**：

1. `ProductAgent` + `ArchitectAgent` 合并为 `PlanningAgent`
2. `DesignTokenAgent` → `DesignTokenTool`
3. `UserPreferenceAgent` → `UserPreferenceTool`
4. `ProjectPreferenceAgent` → `ProjectProfileTool`
5. `DesignRetrieveAgent` → `DesignRetrieveTool`
6. `DesignRerankAgent` → `DesignRerankTool`
7. `ImageAgent` → `ImageSearchTool`
8. `DeployAgent` → `DeployTool`
9. `PreferenceUpdateAgent` → `PreferenceUpdateWorker`
10. 建立新的 package 结构

**验收标准**：

- 代码中只有真正调用 LLM 的模块叫 Agent
- 不调用 LLM 的主链路模块都改为 Tool
- 异步任务统一叫 Worker

---

### 阶段二：引入 Tool 接口和 ToolContext

**目标**：统一 Tool 调用方式，补齐权限上下文。

**任务**：

1. 新增 `SystemTool<I, O>` 接口
2. 新增 `AgentTool<I, O>` 接口
3. 新增 `ToolContext`
4. 改造所有 System Tool 的 `execute` 方法
5. 确保 userId / projectId / teamId 从 ToolContext 获取，不由 LLM 传入

**验收标准**：

- 所有 Tool 调用都携带 ToolContext
- LLM 无法自己传入 userId / projectId 越权查询
- System Tool 和 Agent Tool 类型清晰

---

### 阶段三：引入 DesignContextAssembler

**目标**：把固定 Tool 调用收敛到一个上下文装配器里。

**任务**：

1. 新建 `DesignContextAssembler`
2. 串联 `DesignTokenTool` → `UserPreferenceTool` → `ProjectProfileTool` → `DesignRetrieveTool` → `DesignRerankTool` → `ImageSearchTool`
3. 新增 `ContextCompressTool`
4. 输出统一 `DesignContext`

**验收标准**：

- `PageGenerateAgent` / `CoderAgent` / `StyleAgent` 只消费 `PromptContext`
- 不再由生成 Agent 自己查用户偏好或 RAG
- 每次生成都有完整 `DesignContext` 可追踪

---

### 阶段四：改造 Orchestrator 主流程

**目标**：由 Orchestrator 控制主链路，System Tool 固定调用，Agent 专注 LLM 任务。

**任务**：

1. 新建或改造 `GenerationOrchestrator`
2. 接入 `PlanningAgent` → `DesignContextAssembler` → `PageGenerateAgent`（或 `CoderAgent` + `StyleAgent`）
3. 接入 `RuleCriticTool` → 可选 `CriticAgent` → `DeployTool`
4. 接入 `GenerationRecordTool` + `RagTraceTool`
5. 接入 `AsyncFeedbackPipeline`

**验收标准**：

- 主流程 LLM 调用次数可控
- `DeployTool` 只能由 Orchestrator 调用
- Worker 不阻塞主请求

---

### 阶段五：引入 Agent ToolRegistry

**目标**：允许 `PlanningAgent` 在安全范围内按需调用少量 Agent Tool。

**任务**：

1. 新建 `ToolRegistry`
2. 注册 `SearchUserHistoryTool`、`CompetitorReferenceTool`、`ReferenceUrlAnalyzeTool`、`BrandExtractTool`、`ClarifyRequirementTool`
3. 为 `PlanningAgent` 配置白名单
4. 加入最大调用次数、超时、输出裁剪限制

**验收标准**：

- `PlanningAgent` 只能调用白名单工具
- `CoderAgent` / `StyleAgent` 默认无工具权限
- 部署、写入、删除类工具不能被任何 Agent 调用

---

### 阶段六：Trace 和监控

**目标**：让每次 Tool 调用可观测、可审计、可定位问题。

**任务**：

1. 新增 `tool_call_trace` 表
2. 为 System Tool 和 Agent Tool 调用增加 trace
3. 记录 `input_summary` / `output_summary` / `latency` / `success`
4. 与 `generation_rag_trace` 关联

**验收标准**：

- 每次生成能看到完整 Tool 调用链路
- 能定位 RAG 没召回、偏好没命中、部署失败等问题
- 能统计每个 Tool 的耗时和失败率

---

## 十五、最终命名结果一览

```text
GenerationOrchestrator
    ↓
PlanningAgent                          ← LLM Agent
    ↓
DesignContextAssembler
    ├── DesignTokenTool                ← System Tool
    ├── UserPreferenceTool             ← System Tool
    ├── ProjectProfileTool             ← System Tool
    ├── DesignRetrieveTool             ← System Tool（消耗 Embedding Token）
    ├── DesignRerankTool               ← System Tool
    ├── ImageSearchTool                ← System Tool
    └── ContextCompressTool            ← System Tool
    ↓
PageGenerateAgent / CoderAgent + StyleAgent  ← LLM Agent
    ↓
RuleCriticTool                         ← System Tool
    ↓
CriticAgent（可选）                     ← LLM Agent
    ↓
DeployTool                             ← System Tool
    ↓
GenerationRecordTool + RagTraceTool    ← System Tool
    ↓
AsyncFeedbackPipeline
    ├── PreferenceUpdateWorker         ← Worker
    ├── FingerprintIngestWorker        ← Worker
    └── VectorSyncWorker               ← Worker
```

### 一句话总结

```text
Agent    → 负责智能生成（消耗 Chat Token）
Tool     → 负责确定性执行（不消耗 Chat Token）
Service  → 负责领域业务实现
Worker   → 负责异步反哺（不阻塞主链路）
Orchestrator → 负责主流程编排
```

这样改完之后，系统命名更清晰，token 成本边界更明确，安全边界也更可控。
