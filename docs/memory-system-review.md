# 记忆系统审查与优化建议

> 最后更新：2026-06-28  
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
| 是否被消费 | **部分** |

**消费现状**：
- 前端 `AppChatPage.vue` 进入时调用 `listAppChatHistory` 加载历史，渲染为聊天气泡
- 后端 `AppServiceImpl.chatToGenCode()` 将用户消息和 AI 回复分别入库
- **但历史对话并未作为上下文注入 LLM prompt**——每次生成只看当前消息，不看之前说了什么

**问题**：用户说"把上次的导航栏改成深色"，LLM 不知道"上次的导航栏"是什么，因为历史对话没有注入。

---

### ③ 生成运行记录（GenerationRun）

| 维度 | 结论 |
|------|------|
| 表结构 | `id, generationId, appId, userId, requestedMode, finalMode, pipelineType, status, fallbackFrom/To, workspacePath, startedAt, finishedAt, durationMs, errorMessage` |
| 实现完成度 | **完整**——start/success/fail/fallback 全流程 |
| 是否被消费 | **仅写入，未被复用** |

**问题**：数据只用于记录，没有被后续生成复用（如"上次用 V2 失败了，这次直接用 V1"的自适应路由）。

---

### ④ 用户画像（UserDesignProfile + UserPreferenceWeight + UserDesignEvent）

| 维度 | 结论 |
|------|------|
| 表结构 | 3 张表完整（画像/权重/事件） |
| 写入引擎 | `PreferenceUpdateWorker`——完整，8 类事件规则映射 |
| 加载服务 | `UserPreferenceService.loadUserDesignContext()`——完整 |
| 消费 | `UserPreferenceTool` 写入 Blackboard → `ContextCompressTool` 注入 prompt → `DesignRetrieveTool` 增强检索 |
| 实现完成度 | **引擎完整，但输入管道断裂** |

**关键断裂**：
- 没有对外 REST API → 前端无法上报行为事件 → 权重表永远是空的
- `profileSummary` 从未被自动更新 → prompt 中的用户偏好段落为空
- `PromptPreferenceExtractor` 是唯一写入权重的活跃路径，但它每次只写入即时偏好

---

### ⑤ 项目品牌记忆（ProjectBrandProfile）

| 维度 | 结论 |
|------|------|
| 表结构 | `id, userId, projectId, brandName, brandColorPrimary/Secondary/Accent, brandFont, brandTone, brandLogoUrl, brandGuidelines(JSON), isActive, createTime, updateTime` |
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
| 是否被消费 | **是**——`DesignRetrieveTool` → `DesignEvidence` → prompt 注入 |

---

### 额外组件

**GeneratedPage.java**：

| 维度 | 结论 |
|------|------|
| 表结构 | `id, userId, projectId, appId, generationRunId, pagePath, contentSnapshot, fingerprintId, qualityScore, userRating, createTime, updateTime` |
| 实现完成度 | **仅表结构，无 Service/Controller** |
| 是否被消费 | **否** |

**GenerationRagTrace.java**：

| 维度 | 结论 |
|------|------|
| 表结构 | `id, generationRunId, channel, queryProfile, queryTags, hitFingerprintIds, hitCount, searchLatencyMs, retrieveLatencyMs, evidenceUsed, createTime` |
| 实现完成度 | **仅表结构，无写入逻辑** |
| 是否被消费 | **否** |

---

## 3. 核心问题诊断

按严重程度排序：

### 问题 A：对话历史不注入 LLM（最严重）

**症状**：用户说"把导航栏改成深色"，LLM 不知道之前生成了什么  
**原因**：`chatToGenCode` 只传当前 `message`，不传历史对话  
**影响**：多轮迭代场景（CreatAi 的核心使用场景）完全依赖用户每次重复完整描述

### 问题 B：用户画像闭环断裂（引擎空转）

**症状**：`PreferenceUpdateWorker` 规则完备，但 `user_preference_weight` 表始终为空  
**原因**：没有 REST API 让前端上报行为事件；`profileSummary` 无自动刷新  
**影响**：个性化 RAG 检索实际无效——所有用户召回结果相同

### 问题 C：项目品牌无输入入口

**症状**：`ProjectBrandProfile` 实体和 Tool 完整，但用户无法通过前端设置品牌信息  
**原因**：无对外 API、无前端 UI  
**影响**：跨对话品牌一致性功能形同虚设

### 问题 D：生成记录和 RAG Trace 仅写不读

**症状**：`GenerationRun` 记录完整但从未被后续生成复用  
**原因**：缺少基于历史的自适应逻辑  
**影响**：错过了"从错误中学习"的机会（如 V2 连续失败 3 次应自动降级）

### 问题 E：GeneratedPage / GenerationRagTrace 仅有表结构

**症状**：表设计好了但没有写入逻辑  
**原因**：属于 `rag-design.md` 规划的未实现部分  
**影响**：无法追溯"哪条指纹参与了哪次生成""用户对生成结果的评分"

---

## 4. 优化建议

### 4.1 短期：接通断裂的管道（1-3 天）

#### A-1. 对话历史注入 LLM 上下文（P0，预估 4h）

**现状**：每次生成只看当前消息，不看历史对话。

**方案**：在 `AppServiceImpl.chatToGenCode()` 中，查询最近 N 条历史并拼接为上下文：

```java
List<ChatHistory> recent = chatHistoryService.listRecent(appId, 10);
String context = recent.stream()
    .map(h -> (h.getMessageType().equals("user") ? "用户: " : "AI: ") + h.getMessage())
    .collect(Collectors.joining("\n"));
String fullMessage = "=== 历史对话 ===\n" + context + "\n=== 当前需求 ===\n" + message;
```

**注意事项**：
- 历史过长时截断（如最近 5 轮 = 10 条），避免超出 token 限制
- 只取最近 N 条，按时间倒序
- AI 回复只保留前 200 字摘要（完整代码不注入）

#### A-2. 行为事件上报 API（P0，预估 2h）

**方案**：在 `AppController` 中新增：

```java
@PostMapping("/app/design/event")
public BaseResponse<Boolean> reportDesignEvent(
    @RequestBody UserDesignEvent event, HttpServletRequest request) {
    User user = userService.getLoginUser(request);
    event.setUserId(user.getId());
    preferenceUpdateWorker.recordAndUpdate(event);
    return ResultUtils.success(true);
}
```

前端在保存/删除/部署时调用：
```typescript
export function reportDesignEvent(event: { eventType: string; metadata?: any; appId?: string }) {
  return request.post('/app/design/event', event)
}
```

#### A-3. profileSummary 自动刷新（P0，预估 1h）

**方案**：在 `PreferenceUpdateWorker.recordAndUpdate()` 末尾：

```java
// 每 10 次更新触发一次摘要重算
long totalEvents = eventMapper.countByUserId(event.getUserId());
if (totalEvents % 10 == 0) {
    refreshProfileSummary(event.getUserId());
}

private void refreshProfileSummary(Long userId) {
    List<UserPreferenceWeight> top5 = weightMapper.selectTopK(userId, 5, "DESC");
    List<UserPreferenceWeight> bottom3 = weightMapper.selectTopK(userId, 3, "ASC");
    String summary = "偏好: " + top5.stream().map(w -> w.getPreferenceKey()).collect(joining(","))
                   + " | 反感: " + bottom3.stream().map(w -> w.getPreferenceKey()).collect(joining(","));
    profileMapper.updateSummary(userId, summary);
}
```

---

### 4.2 中期：完善记忆消费（3-7 天）

#### B-1. 项目品牌 API + 前端 UI（P1，预估 1d）

**后端**：`AppController` 新增：
- `POST /app/{appId}/brand` — 设置品牌信息（品牌色、字体、调性）
- `GET /app/{appId}/brand` — 获取品牌信息

**前端**：`AppChatPage.vue` 工具栏新增"品牌设置"按钮，弹出 Modal 让用户指定品牌色、字体、调性关键词。

#### B-2. 生成记录自适应路由（P2，预估 4h）

**方案**：在 `GenerationPipelineRouter.route()` 开头加一步——查询该 appId 最近 5 次生成记录，如果 V2 连续失败 ≥ 3 次，自动降级到 V1：

```java
long recentV2Failures = generationRunService.countRecentFailures(appId, "V2", 5);
if (recentV2Failures >= 3) {
    policy = policy.toBuilder().v2Enabled(false).build();
    log.info("appId={} V2 连续失败 {} 次，本次自动降级 V1", appId, recentV2Failures);
}
```

#### B-3. GeneratedPage 写入（P2，预估 3h）

每次生成完成后，将产物快照写入 `generated_page` 表：

```java
// GenerationPipelineRouter.doOnComplete
GeneratedPage page = GeneratedPage.builder()
    .userId(userId).appId(appId)
    .generationRunId(run.getId())
    .contentSnapshot(snapshotFiles(workspacePath)) // 文件列表 JSON
    .build();
generatedPageMapper.insert(page);
```

**价值**：为后续"版本回退""生成对比""反馈闭环"提供数据基础。

#### B-4. RAG Trace 写入（P2，预估 2h）

在 `DesignKnowledgeIndex.retrieve()` 返回时写入追踪记录：

```java
GenerationRagTrace trace = GenerationRagTrace.builder()
    .channel(evidence.getChannel())
    .queryProfile(styleProfile)
    .queryTags(String.join(",", queryTags))
    .hitFingerprintIds(evidence.getItems().stream().map(i -> i.getSourceId()).collect(joining(",")))
    .hitCount(evidence.getItems().size())
    .searchLatencyMs(elapsed)
    .build();
ragTraceMapper.insert(trace);
```

---

### 4.3 长期：深度记忆能力（1-2 周）

#### C-1. 对话历史摘要压缩（P3，预估 1d）

**问题**：原始对话历史注入会迅速吃满 token 窗口。

**方案**：当历史超过 5 轮时，用 LLM 对旧历史做一次摘要压缩：

```
最近 3 轮：原文保留
更早的历史：压缩为一段摘要
  "用户之前创建了一个电商网站，包含商品列表和购物车页面，
   使用了深色主题和卡片布局，后来把导航栏改成了固定顶部..."
```

类似 ChatGPT 的"记忆"功能，用少量 token 保留关键历史信息。

#### C-2. 跨应用记忆迁移（P3，预估 1d）

**问题**：用户创建新应用时，之前应用的设计偏好没有带过来。

**方案**：新建应用时，查询该用户最近的 `GenerationRun` + `GeneratedPage`，提取共性模式作为初始上下文。

#### C-3. 反馈驱动的知识库自进化（P3，预估 4h）

用户对生成结果评分后，反写本次命中指纹的 `confidence`：
- 5 星 → `confidence += 0.02`
- 1-2 星 → `confidence -= 0.05`
- `confidence < 0.3` → 自动 ARCHIVE

#### C-4. 结构化设计记忆（P4，预估 2d）

**问题**：当前对话历史是纯文本，无法精确提取"用户的导航栏是什么样的"。

**方案**：每次生成完成后，从代码中提取结构化设计快照（`DesignSnapshot`），存储关键决策：

```json
{
  "navbar": { "position": "fixed", "background": "dark", "height": "64px" },
  "colorScheme": "dark",
  "layout": "sidebar-main",
  "components": ["ProductCard", "CartDrawer", "NavBar"]
}
```

下次用户说"把导航栏改成浅色"，可以精确定位到 `navbar.background = "dark" → "light"`。

---

## 总览

```
优先级 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   
P0 ┃ A-1 对话历史注入 LLM          ← 最影响多轮体验
   ┃ A-2 行为事件上报 API           ← 激活画像闭环
   ┃ A-3 profileSummary 刷新       ← 修复空数据
   ┃
P1 ┃ B-1 品牌 API + 前端 UI        ← 激活品牌记忆
   ┃
P2 ┃ B-2 生成记录自适应路由         ← 从错误中学习
   ┃ B-3 GeneratedPage 写入        ← 版本追溯基础
   ┃ B-4 RAG Trace 写入            ← 检索可观测
   ┃
P3 ┃ C-1 对话历史摘要压缩           ← 长对话支持
   ┃ C-2 跨应用记忆迁移             ← 用户连贯体验
   ┃ C-3 反馈驱动知识库自进化        ← 自动淘汰差指纹
   ┃
P4 ┃ C-4 结构化设计记忆             ← 精确多轮修改
   ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### 最紧急的三件事

| # | 方案 | 解决什么 | 工作量 |
|---|------|---------|:---:|
| 1 | **对话历史注入 LLM** | 多轮对话无记忆 | 4h |
| 2 | **行为事件上报 API** | 画像引擎空转 | 2h |
| 3 | **profileSummary 刷新** | prompt 中偏好为空 | 1h |

这三项总共约 **7 小时**，完成后记忆系统从"6 层架构但 3 层断裂"变为"6 层全部贯通"。
