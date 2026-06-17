# 设计驱动生成流水线改造计划（三阶段演进）

> 版本：v2.4 | 日期：2026-06-17 | 项目：CreatAi / YuAiFather

---

## 总览：阶段 0 + 三阶段演进路线

整个改造围绕一个核心目标——**从源头消除生成页面的「AI 味道」**，并按「先确保链路生效，再逐层增强设计能力」的方式演进。

| 阶段 | 名称 | 数据来源 | 角色定位 | 解决的问题 |
|------|------|---------|---------|-----------|
| **0** | 前置校验（主链路切换） | 现有 `OrchestratorAgent` 与配置开关 | **生效前提** | 确保后续 Agent 改造真的进入用户生成链路 |
| **一** | 先验规则（冷启动基线） | 人工编写的 `skill/designs/*.md` | **先验规则** | 从 0 到 1，立刻让生成「有据可依」 |
| **二** | 后验证据（指纹积累） | 真实精选页面 → 六层指纹 | **后验证据** | 用真实数据持续校准、丰富规范 |
| **三** | 运行时召回（检索增强） | 先验规范 + 后验指纹 合并入库 | **检索与注入** | 按需召回 few-shot 范例，规模化提升专业度 |

```mermaid
flowchart LR
    subgraph P0[阶段0 前置校验]
        Z1[AppServiceImpl.chatToGenCode] --> Z2{agent.orchestrator.enabled?}
        Z2 -->|true + vue_project| Z3[OrchestratorAgent]
        Z2 -->|false/非Vue| Z4[AiCodeGeneratorFacade fallback]
    end
    subgraph P1[阶段一 先验规则]
        A1[skill/designs/*.md<br/>静态设计规范] --> A2[DesignTokenAgent<br/>零 LLM 加载]
        A2 --> A3[DesignSpec 写入 Blackboard]
    end
    subgraph P2[阶段二 后验证据]
        B1[真实精选页面] --> B2[DesignExtractorAgent<br/>提炼六层指纹]
        B2 --> B3[fingerprint JSON 入库]
    end
    subgraph P3[阶段三 运行时召回]
        C1[统一检索通道] --> C2[按 styleProfile + 结构化/语义召回 top-k]
        C2 --> C3[few-shot 注入 Coder/Style]
    end
    Z3 --> A2
    A3 -.先验.-> C1
    B3 -.后验.-> C1
    C3 -.高质量结果候选反哺.-> B1
    style P0 fill:#f8f9fa
    style P1 fill:#eef2ff
    style P2 fill:#fff4e6
    style P3 fill:#e6fcf5
```

## 总体工程原则

1. **先让链路生效，再谈效果优化**：任何 `Agent` 改造前，必须确认请求实际进入 `OrchestratorAgent`。
2. **职责边界清晰**：`StyleAgent` 独占 `src/styles/variables.css` 与 `src/styles/global.css`；`CoderAgent` 只生成组件、路由、页面和配置文件。
3. **结构化注入优先**：LLM prompt 只注入规整后的 token / patterns / evidence 摘要，不直接原样注入外部页面内容或完整 Markdown。
4. **层层降级**：阶段三检索失败 → 阶段一静态规范；阶段一规范加载失败 → 内置默认规范；主链路开关关闭 → 经典生成链路。
5. **安全默认关闭风险面**：外部页面抓取、向量检索、自动反哺入库均需有白名单、状态机、置信度和审核门槛。

---

# 阶段 0：前置校验——确保多 Agent 主链路真的生效

> 定位：**所有后续改造的生效前提**。如果真实用户请求仍走经典 `AiCodeGeneratorFacade`，则阶段一至阶段三改造不会影响实际生成结果。

## 0.1 当前风险

当前项目中已存在 `orchestratorEnabled` 配置和 `OrchestratorAgent` 注入，但 `AppServiceImpl.chatToGenCode` 仍需显式确认是否按开关切换到多 Agent 流水线。

> **M0 阻断项**：当前真实代码状态下，Vue 生成请求仍可能直接走 `AiCodeGeneratorFacade`。在完成本节分支前，阶段一/二/三即使实现也不会进入真实用户生成链路。
>
> 实现时还需同步补齐 `OrchestratorAgent / AgentContext / AgentEvent / JSONUtil / AiResponseMessage` 等 import，避免编译失败。

应补充/确认以下分支逻辑：

> 注意：真实 `AgentContext` 是 `@Data @Builder`，**没有 `of()` 静态工厂**，字段为 `appId / loginUser / userPrompt / codeGenType / blackboard`。示例必须用 builder 写法，并补 `loginUser`（多 Agent 链路多处依赖）。

```java
if (orchestratorEnabled && codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
    AgentContext ctx = AgentContext.builder()
            .appId(appId)
            .loginUser(loginUser)
            .userPrompt(message)
            .codeGenType(codeGenTypeEnum)
            .build();
    Flux<String> codeStream = orchestratorAgent.handle(ctx).map(this::toStreamPayload);
    return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
}

Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
```

## 0.2 验收标准

- `agent.orchestrator.enabled=true` 且应用类型为 `vue_project` 时，SSE 能看到 `ProductAgent / ArchitectAgent / ImageAgent / CoderAgent / StyleAgent / CriticAgent` 阶段事件。
- `agent.orchestrator.enabled=false` 时，仍走 `AiCodeGeneratorFacade`，存量行为不变。
- 非 `vue_project` 类型不强行进入多 Agent 链路，避免破坏 HTML 生成链路。

---

# 阶段一：先验规则——静态设计规范知识库 + DesignTokenAgent

> 定位：**冷启动基线**。无需任何真实数据积累，靠人工编写的行业规范让生成立刻有据可依。

## 1.1 产品概述

对当前多 Agent Vue 项目生成流水线进行升级，引入「品牌级设计规范知识库 + DesignTokenAgent」，从源头消除生成页面的「AI 味道」（千篇一律的毛玻璃、装饰渐变、硬编码字号 / 间距 / 圆角等）。同时修复 CriticLoop 修复轮次中 CoderAgent 拿不到问题清单导致盲目重试的隐患。

## 1.2 核心功能

- **行业风格识别**：ArchitectAgent 在生成 `GenerationPlan` 时新增 `styleProfile` 字段（startup / enterprise / ecommerce / portfolio / dashboard / landing），从需求中识别行业标签。
- **设计规范知识库**：在 `resources/skill/designs/` 下提供 6 份行业 DESIGN.md，包含字号 / 间距 / 圆角 / 阴影 Token 体系和「禁用模式 / 推荐写法」清单。
- **DesignTokenAgent（新 Worker）**：不调用 LLM，纯 Java 读取 Markdown + 解析，根据 `styleProfile` 加载对应规范写入 Blackboard，失败时降级使用默认规范、不中断流水线。
- **StyleAgent 升级**：消费结构化 `DesignSpec`，把 `variables.css` 从 ~15 个变量扩展到 ~35 个完整 Design Token（7 级字号 + 间距刻度 + 行高 + 字间距 + 圆角 + 阴影）。
- **CoderAgent 升级**：`buildUserMessage` 注入两段强约束——设计禁用 / 推荐清单 + 上一轮 Critic 问题清单，使生成与修复都有明确依据。
- **流水线插入新节点**：Product → Architect → DesignToken → Image → [Coder ‖ Style] → Critic。

## 1.3 技术栈

沿用项目当前栈：Java 21 + Spring Boot + LangChain4j + Reactor（Flux）+ Hutool + Jackson。阶段一新增内容全部为纯 Java 类与 Markdown 资源，不引入新依赖。

## 1.4 实现策略

1. **零 LLM 设计规范加载**：`DesignTokenAgent` 通过 classpath 读取 `skill/designs/{profile}.md`，使用固定白名单 profile 映射和简单解析器解析 token / forbidden / preferred，延迟 < 10ms。
2. **强约束注入而非建议**：`CoderAgent` 在 userMessage 末尾以「## 强制设计规范（违反视为严重错误）」标题硬注入禁用清单，相比仅写在 system prompt 更不易被 LLM 忽略。
3. **修复轮次有据可依**：复用已有 `CriticReport` 结构，`CoderAgent` 在 `buildUserMessage` 时若检测到 `criticReport != null && !passed`，则把 issues 注入 prompt，根除「盲目重试」。
4. **降级策略**：`DesignTokenAgent` 失败仅 `sink.complete()` + 推 status 事件，绝不 `sink.error()`，保证后续 Image / Coder / Style 仍能运行。
5. **复用现有模式**：新 Worker 继承现有 `Agent` 接口、使用 `subscribeOn(Schedulers.boundedElastic())`、通过 `AgentEvent.status()` 推事件，与 ArchitectAgent / StyleAgent 保持一致。

## 1.5 关键工程边界

### 1.5.1 CoderAgent 与 StyleAgent 的文件所有权

当前 `CoderAgent` 会按 `GenerationPlan.fileStructure` 逐文件落盘，`StyleAgent` 也会写入 `src/styles/variables.css` 与 `src/styles/global.css`。为避免并发覆盖，明确以下边界：

| 文件类型 | 负责 Agent | 规则 |
|---------|------------|------|
| `src/styles/variables.css` | `StyleAgent` | 独占写入，定义全局 token |
| `src/styles/global.css` | `StyleAgent` | 独占写入，定义全局基础样式 |
| Vue 页面 / 组件 / 路由 / 配置 | `CoderAgent` | 可引用 CSS 变量，但不得写全局样式文件 |

`CoderAgent.buildUserMessage` 必须额外注入：

```text
禁止创建或覆盖 src/styles/variables.css 与 src/styles/global.css；这两个文件由 StyleAgent 独占维护。
组件内样式必须优先使用 var(--xxx) 引用全局 token。
```

#### 解决「plan 列出样式文件」与「禁止写样式文件」的指令冲突

`ArchitectAgent` 的 prompt 强制 `fileStructure` 必须包含 `src/styles/variables.css` 与 `src/styles/global.css`，而 `CoderAgent` 又被要求「严格按 plan 逐文件落盘」。若仅在 userMessage 追加「禁止写」，等于给 LLM 同时下达「写」与「不写」两条矛盾指令。

优先采用低侵入方案：传入 `CoderAgent` 前对 `GenerationPlan` 做副本，**过滤掉** `fileStructure` 中的 `src/styles/variables.css` / `src/styles/global.css`，让 Coder 看到的 plan 本身不再列出这两个文件。该方案不改变 `GenerationPlan.fileStructure = List<String>` 的现有 JSON 契约，对 `ArchitectAgent`、DTO 反序列化和旧 prompt 影响最小。

备选方案：在 `GenerationPlan.fileStructure` 元素上增加 `owner` 标记（`CODER` / `STYLE`），`CoderAgent` 落盘时跳过 `owner=STYLE` 的文件。但该方案会把 `fileStructure` 从 `List<String>` 改为结构化列表，影响 `GenerationPlan`、`ArchitectAgent` prompt、`CoderAgent` prompt 与解析逻辑，建议仅在后续需要更精细文件归属时采用。

只有上述任一处理落地后，1.7「无并发写入冲突」的结论才成立。

#### 硬约束的注入落点

`CoderAgent` 实际加载的 system prompt 是 `codegen-vue-project-system-prompt.txt`（非 `agent-coder-system-prompt.txt`）。「禁止写全局样式文件」属于硬约束，仅放 userMessage 容易被忽略，建议**同时**在该 system prompt 内固化此约束，userMessage 仅做强化。

### 1.5.2 StyleAgent 输出契约修正

当前 `StyleAgent` 的 Java 实现是「LLM 返回 JSON，Java 写文件」，不是「LLM 调工具写文件」。这是 **M0/M1 阻断项**：如果 prompt 仍要求工具写文件或只回复自然语言，`StyleAgent` 很可能解析不到 `variables_css / global_css`，导致并发流水线失败。因此阶段一需同步修正 `agent-style-system-prompt.txt`：

- 输出严格 JSON：`{"variables_css":"...", "global_css":"..."}`
- 禁止 markdown 围栏、解释文本、工具调用描述
- 由 Java 负责写入文件
- 所有 token 来自 `DesignSpec` 或 `plan.colorTheme`

### 1.5.3 CoderAgent 方法签名调整

当前 `buildUserMessage(String userPrompt, GenerationPlan plan)` 无法读取 `DesignSpec` 和 `CriticReport`。建议改为：

```java
private String buildUserMessage(AgentContext ctx, GenerationPlan plan)
```

内部读取：

- `ctx.getUserPrompt()`
- `ctx.getBlackboard().getDesignSpec()`
- `ctx.getBlackboard().getCriticReport()`
- 后续阶段三的 `ctx.getBlackboard().getDesignEvidence()`

该签名调整也是阶段一验收阻断项：如果 `CoderAgent` 仍只接收 `String userPrompt, GenerationPlan plan`，则 `CriticReport.issues`、`DesignSpec` 与 `DesignEvidence` 都无法进入下一轮生成。

> 注入 `issues` 时按对象字段拼装，建议取 `severity + category + file + description + suggestion`，尤其不要漏掉 `suggestion`（其携带修复方向，对下一轮生成最有价值）。

### 1.5.4 Critic issue 分类与修复分支

当前 `OrchestratorAgent` 的 Critic 重试链路只重跑 `CoderAgent`。阶段一需明确：不是所有设计问题都应由 Coder 修复。

| issue 类型 | 建议修复分支 |
|-----------|--------------|
| 页面结构、组件代码、路由、图片占位符 | 重跑 `CoderAgent` |
| `variables.css` token 不足、变量命名错误 | 重跑 `StyleAgent` |
| `global.css` reset / typography / 基础布局错误 | 重跑 `StyleAgent` |
| Coder 与 Style 同时相关的问题 | 先重跑 `StyleAgent`，再重跑 `CoderAgent` 或串行修复 |

实现上**直接复用 `CriticReport.Issue` 已有的结构化字段**，无需关键词 hack：`Issue` 现已包含 `severity`（P0/P1/P2）、`category`（`build` / `placeholder` / `style` / `a11y` / `content` 等）、`file`（相对路径，可空）、`description`、`suggestion`。路由规则：

- `category == "style"`，或 `file` 以 `src/styles/` 开头 → 进入 `StyleAgent` 修复分支；
- 其余 → 维持 `CoderAgent` 修复；
- 仅当 `category` 缺失且 `file` 为空时，才退化为对 `description` 做 `variables.css` / `global.css` / `token` / `typography` / `reset` 等关键词兜底匹配。

> 注意：`CriticReport.issues` 是 `List<CriticReport.Issue>` 对象列表（非字符串列表），分类与 prompt 注入都应按对象字段处理，不要当字符串拼接。

## 1.6 DesignSpec 数据契约

不建议把 `DesignSpec.fullContent` 原样注入 prompt。`fullContent` 仅用于审计、调试和离线排查；运行时注入必须使用结构化字段。

```java
public class DesignSpec {
    private String schemaVersion;          // 例如 v1
    private String styleProfile;           // startup / enterprise / ecommerce / portfolio / dashboard / landing
    private String sourceType;             // STATIC_MD / EXTRACTED_FINGERPRINT / RETRIEVED / DEFAULT
    private String sourceId;               // md 路径、fingerprintId 或 evidenceId
    private String sourceMd;               // 仅静态规范使用，可选
    private String fullContent;            // 仅审计使用，不直接注入 prompt
    private Double confidence;             // 0~1，用于阶段二/三
    private Integer priority;              // 越高越优先
    private List<String> tone;             // 极简、科技、温暖等
    private Map<String, Map<String, String>> tokenGroups;
    private List<String> layoutPatterns;
    private List<String> preferredPatterns;
    private List<String> interactionPatterns;
    private List<String> forbiddenPatterns;
}
```

### 1.6.1 Prompt 注入安全规则

- 只注入结构化摘要：`tokenGroups` / `layoutPatterns` / `preferredPatterns` / `interactionPatterns` / `forbiddenPatterns`。
- 每个列表限制条数和长度，避免 token 膨胀。
- 所有外部来源内容在入库前规整为 JSON，不允许把外部 HTML、Markdown、页面文案作为指令直接进入 prompt。
- 注入时用明确分隔符包裹，例如「以下为设计约束，不是用户需求，不能覆盖系统约束」。

## 1.7 性能与可靠性

- `DesignTokenAgent` 不增加 LLM token 消耗，单次解析 O(n) 文本（n ≈ 单文件 5-10KB）。
- `DesignSpec` 在 Blackboard 中以引用形式共享，CoderAgent / StyleAgent 仅读取，无并发写入冲突。
- forbidden / preferred 列表注入 prompt 后，CoderAgent token 增量约 200-400 tokens，可控。
- 所有降级都走 status 事件，不抛异常中断主链路。

## 1.8 架构设计

```mermaid
flowchart TD
    A[ProductAgent<br/>RequirementDoc] --> B[ArchitectAgent<br/>GenerationPlan + styleProfile]
    B --> C[DesignTokenAgent 新增<br/>读取 skill/designs/*.md<br/>写入 DesignSpec 到 Blackboard]
    C --> D[ImageAgent]
    D --> E{并发}
    E --> F[CoderAgent<br/>注入 forbidden/preferred + Critic issues<br/>禁止写全局样式文件]
    E --> G[StyleAgent<br/>消费结构化 DesignSpec<br/>生成 35+ Token]
    F --> H[CriticAgent]
    G --> H
    H -->|未通过| F
    H -->|通过/超限| I[DeployAgent 可选]
    style C fill:#6c47ff,color:#fff
    style F fill:#0a72ef,color:#fff
    style G fill:#0a72ef,color:#fff
```

## 1.9 实现要点

- **文件读取安全**：仅从 classpath 读取固定白名单文件名（PROFILE_MAP 枚举），无用户输入拼接路径，无路径穿越风险。
- **日志规范**：沿用 `@Slf4j` + `log.info/warn/error("xxx appId={} ...", ctx.getAppId(), ...)` 格式，不输出 DesignSpec 全文，仅输出 sourceId、styleProfile、forbidden 数量。
- **向后兼容**：`styleProfile` 为新增可选字段；旧的 `GenerationPlan` JSON 反序列化不受影响；DesignTokenAgent 降级时写入 `DEFAULT` 规范。

## 1.10 目录结构

```
src/main/java/com/yu/yuaifather/
├── agent/
│   ├── core/
│   │   └── Blackboard.java                # [MODIFY] 新增 putDesignSpec/getDesignSpec；预留 putDesignEvidence/getDesignEvidence
│   ├── orchestrator/
│   │   └── OrchestratorAgent.java         # [MODIFY] 构造函数注入 DesignTokenAgent；插入 Architect 之后、Image 之前
│   ├── support/
│   │   └── DesignProfileResolver.java     # [NEW] profile 白名单映射 + Markdown 解析 + DesignSpec 构建
│   └── workers/
│       ├── DesignTokenAgent.java          # [NEW] 加载静态规范，失败降级默认规范，不调用 LLM
│       ├── StyleAgent.java                # [MODIFY] 从 Blackboard 读取 DesignSpec，输出严格 JSON，由 Java 写 CSS
│       └── CoderAgent.java                # [MODIFY] 注入设计约束、Critic issues、全局样式文件禁写规则
├── ai/model/dto/
│   ├── DesignSpec.java                    # [NEW] 扩展版设计规范 DTO
│   └── GenerationPlan.java                # [MODIFY] 新增 String styleProfile 字段及 javadoc

src/main/resources/
├── prompt/
│   ├── agent-architect-system-prompt.txt  # [MODIFY] 输出契约 JSON 加入 styleProfile 字段
│   └── agent-style-system-prompt.txt      # [MODIFY] 输出严格 JSON + 35+ Token 模板 + 禁止 markdown
└── skill/designs/                         # [NEW] 设计规范知识库目录
    ├── startup.md
    ├── enterprise.md
    ├── ecommerce.md
    ├── portfolio.md
    ├── dashboard.md
    └── landing.md
```

## 1.11 阶段一验收标准

- 多 Agent 链路开启时，SSE 阶段事件中能看到 `DesignTokenAgent`。
- `variables.css` 至少包含 35 个 token：颜色、字号、行高、字距、间距、圆角、阴影。
- 生成页面中禁用模式命中数为 0：毛玻璃滥用、装饰渐变滥用、硬编码 px 过多、浮夸 scale hover。
- `CoderAgent` 不再创建或覆盖 `src/styles/variables.css` / `src/styles/global.css`。
- Critic 未通过后，下一轮 `CoderAgent` prompt 中包含 issues 清单。

---

# 阶段二：后验证据——六层指纹提炼、治理与入库

> 定位：**用真实精选页面产出的「后验证据」持续校准、丰富阶段一的人工先验规范**。新增 `DesignExtractorAgent`，把优秀页面按六个可迁移维度结构化提炼为「设计决策清单」。

## 2.1 核心理念：六层指纹结构

把一张精选页面拆成六个抽象层，从原子到整体地提炼**可迁移的设计决策**。每一层 = 入库 `fingerprint` JSON 的一个顶层字段。

```mermaid
flowchart TD
    A[一张精选页面] --> L1[① 设计 Token 层<br/>配色/字号/间距/圆角/阴影]
    A --> L2[② 布局骨架层<br/>区块顺序/栅格/留白/视觉重心]
    A --> L3[③ 组件模式层<br/>导航/卡片/CTA/表单结构]
    A --> L4[④ 交互动效层<br/>hover/过渡/滚动节奏]
    A --> L5[⑤ 反模式层 负样本<br/>它刻意没做什么]
    A --> L6[⑥ 风格归类层<br/>profile + 调性关键词]
    style L5 fill:#e8590c,color:#fff
```

| 层 | 名称 | 装什么（具体字段） | 为什么需要这一层 |
|----|------|------------------|----------------|
| ① | **设计 Token** | 主/辅色及比例关系、字号阶梯、间距刻度、圆角强度、阴影层级、行高字距 | 专业感的「原子」，最可迁移 |
| ② | **布局骨架** | 区块顺序、栅格列数、留白节奏、视觉重心落点、信息层级 | 决定「高级感」的往往是留白和层级 |
| ③ | **组件模式** | 导航/卡片/CTA/表单的惯用结构（不含文案） | 可作为 few-shot 结构范例 |
| ④ | **交互动效** | hover 行为、过渡时长/曲线、滚动动效——克制 or 浮夸 | 克制动效是消除 AI 味的重要信号 |
| ⑤ | **反模式（负样本）** | 它刻意避开了什么：无毛玻璃、无装饰渐变、无硬编码 px | 与「消除 AI 味道」直接对应 |
| ⑥ | **风格归类** | `styleProfile` 标签 + 调性词（极简/科技/温暖） | 检索时的过滤维度 |

## 2.2 指纹 JSON 输出契约

```json
{
  "schemaVersion": "v1",
  "styleProfile": "startup",
  "sourceType": "EXTRACTED_FINGERPRINT",
  "sourceId": "page_20260617_001",
  "confidence": 0.86,
  "tokens": {
    "colorRatio": "中性为主, 主色<10%",
    "fontScale": ["32/24/16"],
    "radii": "8px 统一圆角",
    "shadow": "单层轻阴影"
  },
  "layout": {
    "skeleton": "Hero + 3栏特性 + 定价",
    "whitespace": "宽松",
    "focus": "中间定价卡"
  },
  "components": ["sticky-nav", "bordered-card", "restrained-cta"],
  "interaction": { "motion": "克制", "transition": "200ms ease" },
  "antiPatterns": ["no-glassmorphism", "no-decorative-gradient", "no-hardcoded-px"],
  "tone": ["极简", "科技感"]
}
```

## 2.3 提炼三原则（入层准则）

任何细节要进某一层，必须三问全为「是」，否则排除（一次性业务内容如具体价格、文案、logo、业务逻辑一律不入层）：

1. **可迁移吗？** 换个行业还成立吗？
2. **和专业度相关吗？** 体现设计功底，还是只是业务内容填充？
3. **能变成对 LLM 的约束/范例吗？** 能写成 token、模式或 few-shot 才有用。

## 2.4 与阶段一 DesignSpec 的关系：部分同构 + 可投影转换

六层指纹与 `DesignSpec` 不是字段级完全同构，而是**语义同构，可投影转换**。映射如下：

| 指纹层 | 投影到 DesignSpec 的字段 |
|--------|--------------------------|
| `tokens` | `tokenGroups` |
| `layout` | `layoutPatterns` |
| `components` | `preferredPatterns` |
| `interaction` | `interactionPatterns` |
| `antiPatterns` | `forbiddenPatterns` |
| `styleProfile + tone` | `styleProfile` / `tone` |

## 2.5 数据治理规则

阶段二不应把所有提炼结果直接投入召回池，需要引入治理状态机：

| 状态 | 含义 | 是否参与召回 |
|------|------|--------------|
| `CANDIDATE` | 新提炼结果，schema 已通过但未审核 | 否 |
| `APPROVED` | 人工或自动规则审核通过 | 是 |
| `REJECTED` | 低质量、重复、含风险内容 | 否 |
| `ARCHIVED` | 过期或被更优样本替代 | 否 |

治理规则：

- **Schema 校验**：必须包含六层核心字段，字段类型合法。
- **置信度门槛**：`confidence < 0.7` 默认进入 `CANDIDATE`，不得直接召回。
- **去重检测**：按 `styleProfile + layout.skeleton + token signature + components` 做重复检测。
- **质量防漂移**：由系统自身生成的页面不能仅凭 Critic 通过就入库；必须达到高评分、无禁用模式命中、可选人工确认。
- **来源可追溯**：每条指纹必须保存 `sourceType` / `sourceId` / `createdAt` / `reviewStatus`。

## 2.6 外部页面输入安全

如果阶段二支持 URL / HTML / 截图输入，必须遵守：

- 默认禁止请求内网、保留地址、特殊网段与未确认的内部域名。
- 按项目安全规则，**确需访问任何内部域名前，必须先询问用户确认**；用户未确认则一律拒绝。
- 禁止访问常见内网段，并额外拦截 `9.*`、`10.*`、`11.*`、`21.*`、`30.*` 等风险地址段。
- 只抓取 HTML/CSS/截图所需内容，不执行页面脚本。
- 外部 HTML / 文案只作为数据，不作为指令；进入 LLM 前要加隔离说明。
- 不持久化敏感信息、cookie、token、用户私有内容。

## 2.7 新增组件清单（阶段二）

```
src/main/java/com/yu/yuaifather/
├── agent/workers/
│   └── DesignExtractorAgent.java          # [NEW] 调用 LLM，输入精选页面摘要/截图描述，按六层契约产出 fingerprint JSON
├── agent/support/
│   ├── FingerprintParser.java             # [NEW] 校验/规整 LLM 输出，保证入库结构合法
│   └── FingerprintProjector.java          # [NEW] DesignFingerprint -> DesignSpec / DesignEvidence 投影转换
├── ai/model/dto/
│   └── DesignFingerprint.java             # [NEW] 六层指纹 DTO（运行时/投影用）
├── model/entity/
│   └── DesignFingerprint.java             # [NEW] 持久化实体，含 styleProfile / status / signature 字段
├── mapper/
│   └── DesignFingerprintMapper.java       # [NEW] MyBatis-Flex Mapper
└── service/
    ├── DesignFingerprintService.java      # [NEW] 指纹入库/查询/状态流转接口
    └── impl/DesignFingerprintServiceImpl.java # [NEW] 封装去重与索引维护

src/main/resources/prompt/
└── agent-extractor-system-prompt.txt      # [NEW] 六层提炼指令 + 三原则约束 + JSON 输出契约
```

> 持久化分层与现状对齐：项目使用 **MyBatis-Flex**（`model/entity` + `mapper` + `service`），不采用独立 `repository/` 包。实体命名沿用现有 `App / User / ChatHistory` 风格，不额外加 `Entity` 后缀；指纹的入库、状态流转与去重统一封装在 `DesignFingerprintService` 中。

## 2.8 阶段二验收标准

- 指纹 JSON schema 校验通过率 ≥ 95%。
- 每个 `styleProfile` 至少沉淀 N 条 `APPROVED` 指纹（N 可先设为 5）。
- 重复指纹能被识别并阻止重复入库。
- 外部 URL 输入不允许访问内网与风险网段。
- CANDIDATE / APPROVED / REJECTED 状态流转可追溯。

---

# 阶段三：运行时召回——轻量检索到向量 RAG 的渐进增强

> 定位：把阶段一的**先验规范**与阶段二的**后验指纹**合并进统一知识层，运行时按 `styleProfile` + 需求特征召回 top-k 范例，作为 few-shot 注入生成。

## 3.1 为什么拆成 3A / 3B

当前项目已具备 MySQL / Redis / LangChain4j，但尚未明确 embedding 模型、向量索引与向量库依赖。为降低复杂度，阶段三拆成两步：

- **3A：轻量结构化检索版**：先用 MySQL 结构化字段过滤与排序，验证召回注入收益。
- **3B：向量 RAG 完整版**：在 3A 有收益后再引入 embedding 与向量索引。

## 3.2 3A：轻量结构化检索版

### 检索依据

- 硬过滤：`status = APPROVED`、`styleProfile = plan.styleProfile`
- 软匹配：`tone`、`components`、`layoutPatterns`、`antiPatterns`
- 排序：`priority DESC`、`confidence DESC`、`updatedAt DESC`
- 默认 `topK = 2`，最大不超过 3

> 注意 List 字段的「软匹配」实现：`tone / components / layoutPatterns / antiPatterns` 均为列表，MySQL 直接软匹配较重。3A 落地时需明确实现方式，二选一：
> - **JSON 函数**：字段以 JSON 数组列存储，用 `JSON_CONTAINS` / `JSON_OVERLAPS` 做包含匹配（实现简单，索引能力弱）；
> - **冗余标签表**：拆出 `fingerprint_tag(fingerprint_id, tag_type, tag_value)` 倒排表，靠 `tag_value` 索引做命中计数排序（写入成本高，检索更快更可控）。
> 建议 3A 先用 JSON 函数验证收益，但这只适合小规模样本池。超过约数百条指纹或出现慢查询后，必须迁移到冗余标签倒排表，并为 `tag_type + tag_value`、`fingerprint_id` 建索引，避免结构化召回退化为全表扫描。

### 优点

- 不引入新依赖。
- 不需要向量数据库。
- 可以快速验证「真实设计证据注入」是否提高生成质量。

## 3.3 3B：向量 RAG 完整版

在 3A 验证有效后，再引入：

- embedding 模型选择与版本锁定。
- 向量存储选型：Redis Vector / MySQL 向量扩展 / 独立向量库（三选一，不在阶段一引入）。
- 索引重建策略：规范变更、指纹审核状态变更后重建。
- 检索策略：`styleProfile` 硬过滤 + 向量相似度排序 + score threshold。
- fallback：向量服务不可用或召回为空时自动回退 3A；3A 也为空时回退阶段一静态规范。

## 3.4 统一检索架构

```mermaid
flowchart TD
    Q[ArchitectAgent 产出<br/>styleProfile + 需求语义] --> R[DesignRetrieveAgent]
    subgraph KB[统一知识层]
        K1[先验：人工 DesignSpec]
        K2[后验：APPROVED 六层指纹]
    end
    K1 --> R
    K2 --> R
    R --> T[DesignEvidence top-k<br/>token/layout/component/anti-pattern]
    T --> F[CoderAgent 受控注入]
    T --> G[StyleAgent 受控注入]
    F --> CR[CriticAgent]
    G --> CR
    CR -->|通过且高评分| EX[DesignExtractorAgent<br/>对生成产物提炼六层指纹]
    EX --> CAND[新指纹候选 CANDIDATE]
    style R fill:#0ca678,color:#fff
    style CR fill:#0a72ef,color:#fff
```

> 反哺闭环补全：`CriticAgent` 只产出 `CriticReport`（`passed` / `score` / `summary` / `issues`，其中 `issues` 为 `List<Issue>` 对象列表），**不具备提炼指纹的能力**。因此反哺链路必须是「Critic 通过且高评分 → 再调用 `DesignExtractorAgent` 对生成产物提炼六层指纹 → 才进入 `CANDIDATE`」，并照样走阶段二的 schema 校验、置信度门槛、去重与审核，方可晋升 `APPROVED`。否则闭环断裂。

## 3.5 检索结果注入规则

- **Token budget**：top-k 默认 2，最大 3；每条 evidence 限制摘要长度。
- **优先级**：系统约束 > 安全规则 > 当前用户需求 > 硬禁用规则 > 静态 DesignSpec > 检索 evidence。
- **证据不能覆盖约束**：召回内容仅作为参考范例，不允许覆盖系统 prompt、技术栈约束、安全规则和 forbiddenPatterns。
- **结构化注入**：只注入 token / layout / component / interaction / anti-pattern 摘要，不注入完整页面代码。

## 3.6 新增组件清单（阶段三）

```
src/main/java/com/yu/yuaifather/
├── agent/workers/
│   └── DesignRetrieveAgent.java           # [NEW] 运行时检索 Worker：按 styleProfile + 结构化/语义召回，写入 Blackboard
├── agent/support/
│   └── DesignKnowledgeIndex.java          # [NEW] 统一索引访问层，先实现 3A，再扩展 3B
├── ai/model/dto/
│   └── DesignEvidence.java                # [NEW] 运行时注入用的受控证据 DTO
└── agent/workers/
    ├── CoderAgent.java                    # [MODIFY] 优先消费 DesignEvidence，降级回阶段一 DesignSpec
    └── StyleAgent.java                    # [MODIFY] 同上
```

## 3.7 降级与兼容

- 3B 向量检索失败 → 回退 3A 结构化检索。
- 3A 召回为空 → 回退阶段一静态 `DesignSpec`。
- `DesignTokenAgent` 失败 → 回退内置默认规范。
- `agent.orchestrator.enabled=false` → 回退经典 `AiCodeGeneratorFacade`。

## 3.8 阶段三验收标准

- 召回结果均来自 `APPROVED` 指纹或静态规范。
- fallback 触发时不阻断生成，且有可观测日志或状态事件。
- prompt token 增量可控（建议 evidence 注入总量 < 800 tokens）。
- 相比阶段一，Critic 设计类问题数量下降，人工抽检页面专业度提升。

---

## 三阶段数据契约对照

| 维度 | 阶段一 DesignSpec | 阶段二 六层指纹 | 阶段三 DesignEvidence |
|------|------------------|----------------|-----------------------|
| 来源 | 人工 Markdown | LLM 提炼真实页面 | 静态规范 + APPROVED 指纹召回 |
| 角色 | 先验规则 | 后验证据 | 运行时证据注入 |
| 是否调用 LLM | 否 | 是（离线） | 检索否 / 生成是 |
| 是否在实时链路 | 是 | 否 | 是 |
| 失败影响 | 降级默认规范 | 不影响实时链路 | 降级回阶段一 |
| 安全重点 | classpath 白名单 | SSRF / prompt injection / 数据治理 | token budget / evidence 不覆盖系统约束 |

## 演进里程碑建议

1. **M0（阶段 0）**：确认 `orchestratorEnabled` 生效，Vue 生成请求真正进入 `OrchestratorAgent`，并修正 `StyleAgent` prompt 与 Java JSON 解析契约不一致的阻断问题。
2. **M1（阶段一）**：落地静态规范库 + DesignTokenAgent，解决 Coder/Style 文件职责冲突，补齐 Critic issue 分类修复分支，先把「有据可依」跑通。
3. **M2（阶段二）**：上线 DesignExtractorAgent，离线预热一批精选页面指纹，建立 schema 校验、状态机、去重和审核机制。
4. **M3A（阶段三轻量版）**：接入 MySQL 结构化检索，灰度替换静态注入为 evidence 注入。
5. **M3B（阶段三完整版）**：在轻量检索验证有效后，引入 embedding 与向量索引，打通更精确的语义召回。

## 最小落地顺序

为了降低风险，建议严格按以下顺序推进：

1. 修复/确认 `AppServiceImpl` 的 Orchestrator 开关分支（M0 阻断项）。
2. 修正 `StyleAgent` prompt 输出契约与实际 Java JSON 解析一致（M0/M1 阻断项）。
3. 明确 `CoderAgent` 不写全局样式文件，并优先采用「传给 Coder 前过滤 plan 副本」方案。
4. 添加 `styleProfile`、`DesignSpec`、`DesignTokenAgent`、`Blackboard` 读写方法。
5. 扩展 `CoderAgent` / `StyleAgent` 结构化设计约束注入，并将 `buildUserMessage` 改为接收 `AgentContext`。
6. 为 Critic 修复轮次增加 issue 分类：样式类问题重跑 `StyleAgent`，代码类问题重跑 `CoderAgent`。
7. 再进入阶段二、阶段三。
