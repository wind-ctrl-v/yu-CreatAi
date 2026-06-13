# 多 Agent 协作架构改造计划

> 版本：v1.0 | 日期：2026-06-13 | 项目：CreatAi / YuAiFather

---

## 一、现状分析

### 当前架构

```
用户请求
  └─ AppServiceImpl.chatToGenCode
       └─ AiCodeGenTypeRoutingService（一次性路由，无反馈闭环）
            └─ AiCodeGeneratorFacade（单一大模型承担全部职责）
                 ├─ codegen-vue-project-system-prompt.txt（图片用 picsum 随机占位）
                 ├─ File 工具组（writeFile / read / modify / delete）
                 └─ CodeFileSaverExecutor
```

### 核心痛点

| # | 问题 | 影响 |
|---|------|------|
| 1 | `AiCodeGenTypeRoutingService` 只做一次类型路由，无质检闭环 | 生成失败无法自动修复 |
| 2 | `AiCodeGeneratorFacade` 一个 LLM 承担架构+编码+样式全部职责 | 代码质量不稳定 |
| 3 | `system-prompt.txt` 强制使用 `picsum.photos` 随机占位图 | 图片与内容语义完全不符 |

---

## 二、目标架构

```
用户请求
  └─ OrchestratorAgent（主编排器）
       ├─ 1. ProductAgent    → 需求澄清与分解
       ├─ 2. ArchitectAgent  → 技术选型 + 图片槽标注（imageSlots）
       ├─ 3. ImageAgent ✨   → 搜索/生成真实语义图片 → imageMap
       ├─ 4. CoderAgent      → 读 imageMap，写真实文件（并发）
       │    StyleAgent       → CSS / 主题色（并发）
       ├─ 5. CriticAgent     → 质检（最多3轮反馈循环）
       └─ 6. DeployAgent     → 构建 → COS → 返回 URL
```

### Blackboard 共享状态流

所有 Agent 通过线程安全的 `Blackboard` 共享状态：

| 写入方 | 写入内容 |
|--------|---------|
| ProductAgent | `requirementDoc`（需求文档） |
| ArchitectAgent | `plan.techStack` + `plan.imageSlots` |
| ImageAgent | `imageMap`（slotId → imageUrl） |
| CoderAgent | 生成的代码文件路径列表 |
| CriticAgent | 质检报告 |

---

## 三、包结构规划

```
src/main/java/com/yu/yuaifather/
├── agent/
│   ├── core/
│   │   ├── Agent.java              # 统一接口：Flux<AgentEvent> handle(AgentContext)
│   │   ├── AgentContext.java       # 请求上下文（appId/userId/prompt/codeGenType/Blackboard）
│   │   ├── AgentEvent.java         # 事件模型（TextChunk/ToolCall/ToolResult/StatusChange/Error）
│   │   └── Blackboard.java         # 线程安全黑板（ConcurrentHashMap + 语义方法）
│   ├── orchestrator/
│   │   └── OrchestratorAgent.java  # 主编排器
│   └── workers/
│       ├── ProductAgent.java
│       ├── ArchitectAgent.java
│       ├── ImageAgent.java         # ← 核心新增
│       ├── CoderAgent.java
│       ├── StyleAgent.java
│       ├── CriticAgent.java
│       └── DeployAgent.java
├── agent/tools/
│   ├── ImageSearchTool.java        # Unsplash / Pexels API 搜图
│   └── ImageGenerateTool.java      # 文生图兜底（可选）
└── config/
    └── ImageProperties.java        # @ConfigurationProperties("image")
```

---

## 四、分阶段执行计划

### Phase 1：核心抽象层（基础设施）

| # | 文件 | 说明 |
|---|------|------|
| 1.1 | `agent/core/Agent.java` | 统一接口 `Flux<AgentEvent> handle(AgentContext ctx)` |
| 1.2 | `agent/core/AgentContext.java` | 上下文：appId / userId / userPrompt / codeGenType / Blackboard |
| 1.3 | `agent/core/AgentEvent.java` | 事件 sealed class：TextChunk / ToolCall / ToolResult / StatusChange / Error |
| 1.4 | `agent/core/Blackboard.java` | 提供 `putPlan()` / `getImageMap()` / `putImageMap()` 等语义方法 |

---

### Phase 2：ImageAgent（P0 — 解决图片语义问题）

#### 2.1 `application.yml` 新增配置

```yaml
image:
  unsplash:
    access-key: ${UNSPLASH_ACCESS_KEY:}
    base-url: https://api.unsplash.com
  pexels:
    api-key: ${PEXELS_API_KEY:}
    base-url: https://api.pexels.com/v1
  fallback:
    enabled: true   # 搜图无结果时启用文生图兜底
```

> **安全要点**：所有 API Key 通过环境变量注入，禁止写入代码或 yml 明文。

#### 2.2 `agent/tools/ImageSearchTool.java` — 搜图逻辑

```
① 调用 Unsplash API（/search/photos?query={keyword}&per_page=1）
② 失败/无结果 → 调用 Pexels API
③ 仍无结果且 fallback.enabled=true → 文生图 API → 上传 COS → 返回 COS URL

安全校验：
  - 返回 URL 必须是 https://
  - 域名白名单：images.unsplash.com / images.pexels.com / {cos域名}
  - 防 SSRF：拒绝私有网段 IP（10.* / 192.168.* / 172.16-31.*）
```

#### 2.3 `agent/workers/ImageAgent.java`

```
输入：Blackboard 中的 plan.imageSlots（由 ArchitectAgent 写入）
输出：imageMap 写入 Blackboard

imageSlots 示例：
  [{ id: "hero-banner", keyword: "pet hospital modern clinic", style: "photo" }]

imageMap 示例：
  { "hero-banner": "https://images.unsplash.com/photo-xxx" }
```

---

### Phase 3：ArchitectAgent（生成含 imageSlots 的 Plan）

#### 3.1 `GenerationPlan` 数据结构

```java
class GenerationPlan {
    String techStack;            // "vue_project" / "html"
    List<ImageSlot> imageSlots;  // 图片槽列表
    String requirementSummary;   // 需求摘要
}

class ImageSlot {
    String id;       // "hero-banner"
    String keyword;  // 英文搜索关键词
    String style;    // "photo" / "illustration"
}
```

#### 3.2 ArchitectAgent system prompt 附加规则

```
分析用户需求，识别所有需要图片的位置，为每个位置生成语义化英文搜索关键词，
输出 JSON Plan，包含 imageSlots 数组。
```

---

### Phase 4：CoderAgent（消费 imageMap，替换占位符）

#### 4.1 修改 `codegen-vue-project-system-prompt.txt` 第 91 行

**删除**：
```
- 图片资源：使用 `https://picsum.photos` 服务或其他可靠的占位符
```

**替换为**：
```
- 图片资源：所有图片 src 必须使用语义化占位标记，格式为 {{IMG:英文关键词}}
  例如：<img src="{{IMG:coffee shop interior warm light}}" alt="咖啡厅内景">
  禁止使用 picsum / placeholder / unsplash 随机 URL
```

#### 4.2 运行时占位符替换

CoderAgent 生成代码后，读取 Blackboard 中的 `imageMap`，
用正则将 `{{IMG:xxx}}` 替换为真实 URL；
若 imageMap 中无对应 key，则触发 ImageAgent 补充搜图。

---

### Phase 5：CriticAgent（质检闭环）

#### 检查项

| # | 检查内容 | 通过条件 |
|---|---------|---------|
| 1 | 图片占位符检查 | 代码中不存在未替换的 `{{IMG:xxx}}` |
| 2 | 构建检查 | Vue 项目通过 `npm run build`（调用现有 `VueProjectBuilder`） |
| 3 | 语义检查（可选） | LLM 评分生成内容是否符合需求（≥ 8/10 通过） |

#### 循环策略

```
CriticAgent 不通过 → 回到 CoderAgent 修复（最多 3 轮）
超过 3 轮            → 降级返回当前最佳结果，不阻断主流程
```

---

### Phase 6：OrchestratorAgent（串联编排）

#### 主编排流程

```java
OrchestratorAgent.handle(ctx):
  1. ProductAgent.handle(ctx)          // 需求文档写黑板
  2. ArchitectAgent.handle(ctx)        // Plan + imageSlots 写黑板
  3. ImageAgent.handle(ctx)            // 并发搜图，imageMap 写黑板
  4. Flux.merge(                       // 并发生成代码和样式
       CoderAgent.handle(ctx),
       StyleAgent.handle(ctx)
     ).blockLast()
  5. for (int i = 0; i < 3; i++) {
       CriticAgent.handle(ctx)         // 质检
       if (passed) break
       CoderAgent.handle(ctx)          // 修复
     }
  6. DeployAgent.handle(ctx)           // 部署
```

#### 与现有代码的集成方式（最小侵入）

`AppServiceImpl.chatToGenCode` 内部新增判断：

```java
if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
    // 走新的 OrchestratorAgent 流程
    return orchestratorAgent.handle(ctx);
} else {
    // 保持原有 Facade 流程不变（HTML / MULTI_FILE）
    return aiCodeGeneratorFacade.generate(...);
}
```

---

### Phase 7：前端事件适配（可选 / 后期）

前端目前通过 SSE 接收 `AiResponseMessage` / `ToolRequestMessage` / `ToolExecutedMessage`。

新增 Agent 状态事件类型，前端可展示各 Agent 进度：

```
ProductAgent ✅ → ArchitectAgent ✅ → ImageAgent ✅ → CoderAgent ⏳ → ...
```

---

## 五、文件变更清单

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `agent/core/Agent.java` | 统一接口 |
| `agent/core/AgentContext.java` | 请求上下文 |
| `agent/core/AgentEvent.java` | 事件模型 |
| `agent/core/Blackboard.java` | 共享黑板 |
| `agent/orchestrator/OrchestratorAgent.java` | 主编排器 |
| `agent/workers/ProductAgent.java` | 需求澄清 Agent |
| `agent/workers/ArchitectAgent.java` | 架构设计 Agent |
| `agent/workers/ImageAgent.java` | 图片搜索 Agent（核心新增）|
| `agent/workers/CoderAgent.java` | 代码生成 Agent |
| `agent/workers/StyleAgent.java` | 样式生成 Agent |
| `agent/workers/CriticAgent.java` | 质检 Agent |
| `agent/workers/DeployAgent.java` | 部署 Agent |
| `agent/tools/ImageSearchTool.java` | 搜图工具 |
| `agent/tools/ImageGenerateTool.java` | 文生图兜底工具（可选）|
| `config/ImageProperties.java` | 图片配置绑定类 |

### 修改文件

| 文件路径 | 修改内容 |
|---------|---------|
| `resources/application.yml` | 新增 `image.*` 配置块 |
| `resources/prompt/codegen-vue-project-system-prompt.txt` | 第91行图片规则：picsum → `{{IMG:xxx}}` 语义占位符 |
| `service/impl/AppServiceImpl.java` | `chatToGenCode` 接入 OrchestratorAgent |

### 保持不变（零侵入）

- HTML / MULTI_FILE 生成全链路
- 所有 File 工具类（`FileWriteTool` 等）
- `CosManager`
- 数据库结构
- 前端 SSE 协议（基础字段不变）

---

## 六、实施优先级

| 优先级 | 阶段 | 内容 |
|-------|------|------|
| P0（立即）| Phase 2 + Phase 4 | ImageAgent + prompt 修改，解决图片语义问题 |
| P1 | Phase 1 + Phase 3 | 抽象层 + ArchitectAgent |
| P2 | Phase 5 + Phase 6 | CriticAgent 质检 + OrchestratorAgent 编排串联 |
| P3（后期）| Phase 7 | 前端 Agent 进度展示 |

---

## 七、RAG 知识库评估结论

**本期改造不引入 RAG**，原因：

1. **无私有知识**：全部使用公开生态（Vue3 / Vite / Vue Router），LLM 训练数据已包含
2. **无向量数据库**：pom.xml 中无 `langchain4j-embeddings` 依赖，引入成本较高
3. **Redis 已有对话记忆**：`RedisChatMemoryStore` 已解决上下文记忆问题
4. **复杂度控制**：8 个 Agent 编排本身复杂度已较高，RAG 会增加调试难度

**未来可考虑**：当需要支持用户上传私有组件库文档 / PRD 来精确指导代码生成时，在 `CoderAgent` 前增加 `RAGRetrievalAgent` 是自然的扩展点。
