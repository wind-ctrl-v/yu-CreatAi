# 记忆系统审查与优化建议

> 最后更新：2026-06-29
> 本文档审查 CreatAi 项目中所有跨对话/跨生成持久化的知识与状态（统称"记忆系统"），评估每个组件的实现完成度，并给出优化建议。

---

## 目录

1. [记忆系统全景](#1-记忆系统全景)
2. [各层审查结论](#2-各层审查结论)
3. [核心问题诊断](#3-核心问题诊断)
4. [优化建议](#4-优化建议)

---

## 1. 记忆系统全景

项目的记忆系统分为 **6 层**，按持久化时间从短到长排列：

```
┌─────────────────────────────────────────────────────────────────┐
│                         记忆系统全景                              │
├──────────────┬──────────┬───────────────────────────────────────┤
│ 层级          │ 生命周期  │ 功能                                  │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ① Blackboard │ 单次生成  │ Agent 间共享中间产物（需求/计划/设计/代码）│
│              │          │ ConcurrentHashMap，生成完即丢弃         │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ② 对话历史    │ 单个应用  │ 用户与 AI 的对话记录                    │
│ ChatHistory  │          │ 用于多轮对话上下文                      │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ③ 生成运行记录 │ 永久     │ 每次生成的链路/模式/降级/耗时/状态        │
│ GenerationRun│          │ 可观测与统计                           │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ④ 用户画像    │ 永久     │ 用户设计偏好（风格/颜色/布局/组件权重）    │
│ Profile+Weight│         │ 个性化检索增强                         │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ⑤ 项目品牌    │ 永久     │ 项目级品牌约束（品牌色/字体/标志/调性）    │
│ BrandProfile │          │ 跨对话保持品牌一致性                    │
├──────────────┼──────────┼───────────────────────────────────────┤
│ ⑥ 设计知识库  │ 永久     │ 精选应用的六层设计指纹                   │
│ Fingerprint  │          │ RAG 检索的知识源                       │
└──────────────┴──────────┴───────────────────────────────────────┘
```

---

## 2. 各层审查结论

### ① Blackboard（单次生成的短期记忆）

| 维度 | 结论 |
|------|------|
| 实现完成度 | **完整** |
| 是否被消费 | 是——所有 Agent 通过它传递中间产物 |
| 生命周期 | 每次 `init()` 重建，生成结束即丢弃 |

全部方法：`putRequirementDoc` / `putPlan` / `putDesignSpec` / `putDesignEvidence` / `putUserDesignContext` / `putProjectBrandContext` / `putImageMap` / `putCriticReport` / `addGeneratedFile` / `putDeployUrl` + 对应 getter + `snapshot()`

**评估**：设计良好，语义化方法封装、线程安全。**无需优化**。

---

### ② 对话历史（ChatHistory）

| 维度 | 结论 |
|------|------|
| 表结构 | `id, message, messageType(user/ai), appId, userId, createTime, updateTime` |
| 实现完成度 | **完整**——增删改查 + 游标分页 |
| 数据量 | 118 条（2026-06-29 实测） |
| 是否被消费 | **部分** |

**消费现状**：
- ✅ 前端 `AppChatPage.vue` 进入时调用 `listAppChatHistory` 加载历史，渲染为聊天气泡
- ✅ 后端 `AppServiceImpl.chatToGenCode()` 将用户消息和 AI 回复分别入库
- ✅ 增量编辑 SSE 接口也持久化用户消息和 AI 回复到 chat_history
- ✅ `AiCodeGeneratorServiceFactory` 通过 `loadChatHistoryToMemory` 将历史加载到 LangChain4j ChatMemory
- ⚠️ **Vue 项目生成的 AiCodeGeneratorService 已声明 `@MemoryId`，框架会自动注入历史上下文；但 HTML/MULTI_FILE 模式的方法未声明 `@MemoryId`，不加载历史**

**问题**：HTML 和多文件模式每次只看当前消息，不支持多轮迭代。

---

### ③ 生成运行记录（GenerationRun）

| 维度 | 结论 |
|------|------|
| 表结构 | DDL 已设计（`docs/sql/006_generation_run.sql`） |
| 实现完成度 | ⚠️ **表未创建**——MySQL 中不存在 `generation_run` 表，无写入逻辑 |
| 是否被消费 | **否** |

**问题**：缺乏运行时状态追踪和"从错误中学习"的数据基础。

---

### ④ 用户画像（UserDesignProfile + UserPreferenceWeight + UserDesignEvent）

| 维度 | 结论 |
|------|------|
| 表结构 | 3 张表完整（画像/权重/事件） |
| 写入引擎 | `PreferenceUpdateWorker`——完整，8 类事件规则映射 |
| 上报 API | ✅ `POST /app/record-edit-event` 已实现 |
| 前端上报 | ✅ 前端 `recordEditEvent`/`recordTextEditEvent` 已在调用 |
| 当前数据 | 画像 1 条（fish）+ 权重 22 条（5类别×正负）+ 事件 12 条（2026-06-29 实测） |
| 加载服务 | `UserPreferenceService.loadUserDesignContext()`——完整 |
| 消费 | `UserPreferenceTool` 写入 Blackboard → `ContextCompressTool` 注入 prompt → `DesignRetrieveTool` 增强检索 |
| 实现完成度 | **基本完整** |

**剩余问题**：
- `profileSummary` 无自动刷新逻辑——当前是手动 SQL 写入的，后续新增事件不会自动更新摘要

---

### ⑤ 项目品牌记忆（ProjectBrandProfile）

| 维度 | 结论 |
|------|------|
| 表结构 | 完整 |
| 工具 | `ProjectBrandProfileTool`——从 Blackboard 读取 `ProjectBrandContext` |
| 服务 | `ProjectBrandProfileService`——`loadBrandContext(userId, projectId)` |
| 实现完成度 | **完整**——实体/Mapper/Service/Tool 全链路 |
| 是否被消费 | **是**——在 V1/V2 流水线中作为 System Tool 被调用 |

**问题**：
- 没有对外 API 供前端设置品牌信息（用户无法指定"我的品牌色是 #1677ff"）
- 品牌信息只能通过后台手动写入数据库

---

### ⑥ 设计知识库（DesignFingerprint）

已在 `docs/rag-architecture-analysis.md` 中详细分析。

| 维度 | 结论 |
|------|------|
| 实现完成度 | **完整**——入库/审核/向量同步/三层检索全链路 |
| 当前数据 | MySQL 139 APPROVED + 182 CANDIDATE；PGvector 131 条向量（2026-06-29 实测） |
| 是否被消费 | **是**——`DesignRetrieveTool` → `DesignEvidence` → prompt 注入 |

---

### 额外组件

**GeneratedPage + GenerationRecordTool**：

| 维度 | 结论 |
|------|------|
| 实体/Mapper | ✅ 完整 |
| 写入工具 | ✅ `GenerationRecordTool` 已实现，`GenerationOrchestrator.doRecord()` 中调用 |
| 写入时机 | V2 编排器三种模式（low_cost/standard/high_quality）末尾 |
| 当前数据 | 0 条（实现后尚无 V2 生成触发） |
| 实现完成度 | **完整** |

**GenerationRagTrace + RagTraceTool**：

| 维度 | 结论 |
|------|------|
| 实体/Mapper | ✅ 完整 |
| 写入工具 | ✅ `RagTraceTool` 已实现，与 `GenerationRecordTool` 同时调用 |
| 当前数据 | 0 条（同上） |
| 实现完成度 | **完整** |

---

## 3. 核心问题诊断

按严重程度排序：

### 问题 A：对话历史不注入 LLM——仅 HTML/MULTI_FILE 模式（已验证）

**症状**：HTML/多文件模式下用户说"把导航栏改成深色"，LLM 不知道之前生成了什么
**原因**：`AiCodeGeneratorService` 的 `generateHtmlCodeStream()` 和 `generateMultiFileCodeStream()` 未声明 `@MemoryId`
**影响**：HTML/多文件模式不支持多轮迭代
**现状**：Vue 项目模式已有 `@MemoryId` ✅；HTML/多文件缺失 ⚠️

### 问题 B：用户画像闭环——已部分修复

**症状（原）**：权重表为空 → **已修复**：22 条权重 + 12 条事件 + 1 条画像
**剩余**：`profileSummary` 无自动刷新逻辑

### 问题 C：项目品牌无输入入口

**症状**：`ProjectBrandProfile` 实体和 Tool 完整，但用户无法通过前端设置品牌信息
**原因**：无对外 API、无前端 UI
**影响**：跨对话品牌一致性功能形同虚设

### 问题 D：生成记录表未创建

**症状**：`generation_run` 表 DDL 存在但未执行
**影响**：无法实现自适应路由（如 V2 连续失败自动降级）

### 问题 E：GeneratedPage / GenerationRagTrace —— 已修复

**症状（原）**：表设计好了但没有写入逻辑 → **已修复**
**状态**：`GenerationRecordTool` + `RagTraceTool` 已实现，下次 V2 生成即写入

---

## 4. 优化建议

### 4.1 短期：接通断裂的管道（1-3 天）

#### A-1. HTML/MULTI_FILE 模式注入历史上下文（P0，预估 1h）

**现状**：Vue 项目已有 `@MemoryId` ✅；HTML 和 MULTI_FILE 缺失。

**方案**：直接在 `AiCodeGeneratorService` 对应方法签名上加 `@MemoryId`：

```java
// 修改前
Flux<String> generateHtmlCodeStream(String userMessage);
Flux<String> generateMultiFileCodeStream(String userMessage);

// 修改后
Flux<String> generateHtmlCodeStream(@MemoryId long appId, @UserMessage String userMessage);
Flux<String> generateMultiFileCodeStream(@MemoryId long appId, @UserMessage String userMessage);
```

> **注**：原文档建议手动拼接字符串，但项目已使用 LangChain4j 的 ChatMemory 体系。`AiCodeGeneratorServiceFactory` 已将 `chatHistory` 加载到 `chatMemory` 并通过 `chatMemoryProvider` 绑定。只需声明 `@MemoryId`，框架自动注入历史上下文，无需手动拼接。

#### A-2. profileSummary 自动刷新（P1，预估 1h）

**现状**：画像数据已存在（1条），权重 22 条，但 `profileSummary` 是手动 SQL 写入的，后续新增事件不会刷新。

**方案**：在 `PreferenceUpdateWorker.recordAndUpdate()` 中，每 10 次更新触发一次摘要重算。

**前提**：需要先在对应 Mapper 中新增以下方法（约 30 分钟）：
- `UserDesignEventMapper.countByUserId(userId)` — 统计用户事件总数
- `UserPreferenceWeightMapper.selectTopByType(userId, preferenceType, k, order)` — 按类型获取权重 top-k
- `UserDesignProfileMapper.updateSummaryField(userId, summary)` — 更新摘要字段

---

### 4.2 中期：完善记忆消费（3-7 天）

#### B-1. 项目品牌 API + 前端 UI（P1，预估 1d）

**后端**：`AppController` 新增：
- `POST /app/{appId}/brand` — 设置品牌信息（品牌色、字体、调性）
- `GET /app/{appId}/brand` — 获取品牌信息

**前端**：`AppChatPage.vue` 工具栏新增"品牌设置"按钮，弹出 Modal 让用户指定品牌色、字体、调性关键词。

#### B-2. 创建 generation_run 表 + 自适应路由（P2，预估 4h）

**前提**：先执行 `docs/sql/006_generation_run.sql` 创建表，再实现 `GenerationRunService`。

**方案**：在 `GenerationPipelineRouter.route()` 开头查询该 appId 最近 5 次生成，V2 连续失败 ≥ 3 次时自动降级 V1。

#### B-3. GeneratedPage 写入 —— ✅ 已完成

`GenerationRecordTool` 已实现，`GenerationOrchestrator.doRecord()` 中调用。下次 V2 生成即写入。

#### B-4. RAG Trace 写入 —— ✅ 已完成

`RagTraceTool` 已实现，与 B-3 同时调用。

---

### 4.3 长期：深度记忆能力（1-2 周）

#### C-1. 对话历史摘要压缩（P3，预估 1d）

当历史超过 5 轮时，用 LLM 对旧历史做一次摘要压缩：
```
最近 3 轮：原文保留
更早的历史：压缩为一段摘要
  "用户之前创建了一个电商网站，包含商品列表和购物车页面，
   使用了深色主题和卡片布局，后来把导航栏改成了固定顶部..."
```

#### C-2. 跨应用记忆迁移（P3，预估 1d）

新建应用时，查询该用户最近的 `GeneratedPage`，提取共性模式作为初始上下文。

#### C-3. 反馈驱动的知识库自进化（P3，预估 4h）

用户评分后反写指纹 confidence，低分指纹自动 ARCHIVE。

#### C-4. 结构化设计记忆（P4，预估 2d）

每次生成完成后，从代码中提取结构化设计快照（`DesignSnapshot`），存储关键决策。

---

## 总览

```
优先级 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

P0 ┃ A-1 HTML/MULTI_FILE 模式添加 @MemoryId     ← 1h，修复多轮迭代
   ┃
P1 ┃ A-2 profileSummary 自动刷新                ← 1h，需先补 Mapper 方法
   ┃ B-1 品牌 API + 前端 UI                     ← 1d，激活品牌记忆
   ┃
P2 ┃ B-2 创建 generation_run + 自适应路由       ← 4h，需先建表
   ┃
P3 ┃ C-1 对话历史摘要压缩                       ← 1d
   ┃ C-2 跨应用记忆迁移                         ← 1d
   ┃ C-3 反馈驱动知识库自进化                    ← 4h
   ┃
P4 ┃ C-4 结构化设计记忆                         ← 2d
   ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

已完成 ✅
   ✅ POST /app/record-edit-event 行为事件上报 API
   ✅ 前端 recordEditEvent/recordTextEditEvent 调用
   ✅ user_preference_weight 22条 + events 12条 + profile 1条
   ✅ GenerationRecordTool 写入 generated_page
   ✅ RagTraceTool 写入 generation_rag_trace
   ✅ Vue 项目 @MemoryId 历史注入
```

### 当前最优先

| # | 方案 | 解决什么 | 工作量 |
|---|------|---------|:---:|
| 1 | **HTML/MULTI_FILE 加 @MemoryId** | 所有模式支持多轮对话 | 1h |
| 2 | **补 Mapper 方法 + profileSummary 刷新** | 画像自动更新 | 1.5h |
| 3 | **品牌 API + 前端 UI** | 品牌记忆激活 | 1d |
