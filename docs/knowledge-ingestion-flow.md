# 知识入库流程全景梳理

> 最后更新：2026-06-28  
> 本文档梳理 CreatAi 中所有数据（指纹/用户偏好/项目品牌/生成记录/对话历史）如何进入系统——覆盖每一条写入路径、触发条件、调用链。

---

## 目录

1. [入库路径总览](#1-入库路径总览)
2. [指纹入库（三条路径）](#2-指纹入库三条路径)
3. [用户偏好入库（四条路径）](#3-用户偏好入库四条路径)
4. [项目品牌入库](#4-项目品牌入库)
5. [生成运行记录入库](#5-生成运行记录入库)
6. [对话历史入库](#6-对话历史入库)
7. [活跃度总结](#7-活跃度总结)

---

## 1. 入库路径总览

```
┌──────────────────────────────────────────────────────────────────┐
│                      知识入库全景                                  │
├──────────────────┬──────────────────┬────────────────────────────┤
│ 入库目标          │ 写入路径          │ 触发方式                    │
├──────────────────┼──────────────────┼────────────────────────────┤
│ design_fingerprint│ 定时爬取          │ 每 30 分钟，爬取精选应用      │
│                  │ 用户私有指纹       │ 码已写好但无调用点（未激活）    │
│                  │ 管理员审核 APPROVE │ 手动操作                    │
├──────────────────┼──────────────────┼────────────────────────────┤
│ PGvector 向量表   │ 审核通过后自动同步  │ APPROVE 触发                 │
├──────────────────┼──────────────────┼────────────────────────────┤
│ user_design_event│ 行为事件上报        │ 前端改色/改布局/增删组件时    │
├──────────────────┼──────────────────┼────────────────────────────┤
│ user_preference_  │ 行为事件驱动        │ 由 recordEditEvent API 进入  │
│ weight           │ Prompt 提取        │ 创建应用 + 增量编辑时         │
│                  │ 指纹反哺           │ 码已写好但无调用点（未激活）    │
├──────────────────┼──────────────────┼────────────────────────────┤
│ user_design_      │ 权重更新满10次      │ PreferenceUpdateWorker 内部  │
│ profile.summary   │ 自动拼接摘要写回    │ 无需外部触发                 │
├──────────────────┼──────────────────┼────────────────────────────┤
│ project_brand_    │ 无写入入口         │ ❌ 需手动写数据库              │
│ profile           │                   │                             │
├──────────────────┼──────────────────┼────────────────────────────┤
│ generation_run    │ 每次生成完成/失败   │ GenerationPipelineRouter     │
│                  │                   │ doOnComplete / doOnError     │
├──────────────────┼──────────────────┼────────────────────────────┤
│ chat_history      │ 每次对话           │ 用户发消息 + AI 回复完成时     │
└──────────────────┴──────────────────┴────────────────────────────┘
```

---

## 2. 指纹入库（三条路径）

### 2.1 路径一：定时爬取精华应用 ✅ 活跃

**文件**：`worker/FingerprintCrawlScheduler.java`

```
每 30 分钟（可配置 agent.fingerprint.crawl.interval-ms）
  │  开关: agent.fingerprint.crawl.enabled = true
  ▼
crawlFeaturedApps()
  │
  ├─ ① 查询目标应用（第 132-137 行）
  │     WHERE priority = 99 AND deploy_key IS NOT NULL
  │     → 已部署的精选应用列表
  │
  ├─ ② 对每个应用构建 URL，抓取完整 HTML
  │     fetchHtml(buildAppUrl(app)) → 原始 HTML 文本
  │
  ├─ ③ DesignExtractorAgent.extractFromHtml(rawHtml)
  │     → DesignSourcePreprocessor.summarizeHtml(rawHtml) 预处理
  │     → LLM 调用 agent-extractor-system-prompt，返回六层 JSON
  │     → FingerprintParser 校验 + 置信度计算 + MD5 签名
  │
  └─ ④ fingerprintService.saveCandidate(dto, true)
         → sourceType = "SELF_FED_FINGERPRINT"
         → status = "CANDIDATE"
         → MD5 去重（已存在则跳过）
         → 等待管理员审核
```

**关键配置**：
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `agent.fingerprint.crawl.enabled` | `false` | 必须手动开启 |
| `agent.fingerprint.crawl.interval-ms` | `1800000`（30分钟） | 爬取间隔 |

### 2.2 路径二：用户私有指纹 ⚠️ 码已写但未激活

**文件**：`worker/FingerprintIngestWorker.java`

```
设计意图（第 47 行 ingestUserFingerprint）：
  用户保存/导出页面
    → 异步触发
    → 读取 generated_page 表的 html/css
    → 基于表层特征（非 LLM）构造 DesignFingerprintDTO
    → 直接以 APPROVED + scope=USER 入库
    → 不经过审核

当前状态：开关 agent.fingerprint.user-ingest.enabled = false (默认)
         且无任何代码调用 ingestUserFingerprint 方法
```

激活条件：设置 `agent.fingerprint.user-ingest.enabled=true` 并在用户保存/导出时调用。

### 2.3 路径三：管理员审核 APPROVE ✅ 活跃

**文件**：`controller/DesignFingerprintController.java`（第 80 行）

```
管理员在后台点击"通过"
    ↓
POST /fingerprint/admin/approve { id }
    ↓
DesignFingerprintServiceImpl.approve(id)
    ├─ 校验：confidence >= 0.7
    ├─ 校验：自产反哺配额不超过 30%
    ├─ MySQL: UPDATE status = 'APPROVED'
    └─ VectorSyncWorker.syncOnApprove(fp)  ← 自动触发
          ├─ 解析 fingerprintJson
          ├─ 拼接语义文本: styleProfile + tone
          ├─ EmbeddingModel.embed(text) → vector(768)
          └─ PGvector: UPSERT INTO design_fingerprint_vector

拒绝："REJECTED"，调用 deactivateOnRejectOrArchive(id) 标记向量失效
```

**关键校验**（`DesignFingerprintServiceImpl.java` 第 105-119 行）：
- 置信度 ≥ 0.7（低于此值的指纹不准通过）
- 自产反哺（从本平台爬取的）指纹不超过总量的 30%，避免"回声室效应"

---

## 3. 用户偏好入库（四条路径）

### 3.1 路径一：行为事件驱动 ✅ 活跃

**文件**：`controller/AppController.java`（第 529-551 行）、`worker/PreferenceUpdateWorker.java`

```
用户在 AppChatPage 的修改操作
    ↓
前端调用 POST /app/record-edit-event {appId, eventType, metadata}
    ↓
AppController.recordEditEvent()
    ├─ 构造 UserDesignEvent {userId, eventType, metadata, createTime}
    └─ preferenceUpdateWorker.recordAndUpdate(event)
          ↓
    ┌──────────────────────────────────────────┐
    │ ① eventMapper.insert(event)               │  ← 写入 user_design_event（不可变日志）
    │                                           │
    │ ② applyPreferenceUpdate(event)            │
    │    根据 eventType 路由：                   │
    │    page_saved        → tags +0.1          │
    │    page_exported     → tags +0.2          │
    │    page_deleted      → tags -0.1          │
    │    page_regenerated  → tags -0.05         │
    │    color_changed     → fromTag -0.2, toTag +0.2 │
    │    component_added   → component +0.1     │
    │    component_removed → component -0.2     │
    │    layout_changed    → from -0.1, to +0.1 │
    │         ↓                                 │
    │    调用 upsertWeight(userId, type, key, delta) │
    │    → MySQL atomic UPDATE ... WHERE 行锁   │
    │    → 受影响行=0 → INSERT 新行             │
    │    → UNIQUE冲突 → 重试UPDATE             │
    │                                           │
    │ ③ maybeRefreshProfileSummary(userId)      │  ← 新增：每次检测是否触发摘要刷新
    │    每 10 次事件 → 重算 profileSummary     │
    └──────────────────────────────────────────┘
```

**前端触发时机**（`frontend/src/pages/AppChatPage.vue`）：

| 用户操作 | eventType | 触发位置 |
|---------|-----------|---------|
| 改变元素颜色 | `color_changed` | 可视化编辑器中改色后 |
| 改变元素布局 | `layout_changed` | 可视化编辑器中改布局后 |
| 添加组件 | `component_added` | 可视化编辑器中增组件后 |
| 修改文本样式 | `style_changed` | 文本编辑操作后 |
| 修改交互 | `interaction_changed` | 交互编辑操作后 |

### 3.2 路径二：Prompt 语义提取 ✅ 活跃

**文件**：`service/PromptPreferenceExtractor.java`

```
用户创建应用或增量编辑
    ↓
AppController:
  - addApp 时: new Thread(() → extractAndRecord(userId, prompt)).start()
  - incrementalEdit 时: Thread.ofVirtual().start(() → extractAndRecord(userId, instruction))

PromptPreferenceExtractor.extractAndRecord(userId, prompt)
    ├─ ① LLM 调用: ai.extract("用户提示词：" + prompt)
    │     → system prompt 指定 JSON Schema
    │     → 返回 {"colors":["浅绿"], "styles":["科技感"], "layouts":["居中"], "components":["轮播"], ...}
    │
    ├─ ② 解析 JSON，提取五类标签
    │     colors → recordGroup("color", ...)
    │     styles → recordGroup("style", ...)
    │     layouts → recordGroup("layout", ...)
    │     components → recordGroup("component", ...)
    │     interactions → recordGroup("interaction", ...)
    │
    └─ ③ recordGroup 对每个标签调用
          preferenceUpdateWorker.updateSingleWeight(userId, type, tag, +0.15)
          → upsertWeight() → user_preference_weight 表
```

**特点**：
- 异步执行，不阻塞创建/编辑主流程
- 每次只写入 +0.15（比行为事件的 +0.1/+0.2 略低，因为这是 LLM 推断，非用户明确操作）
- 与行为事件写入同一张权重表，自然融合

### 3.3 路径三：指纹反哺 ⚠️ 码已写但无调用点

**文件**：`worker/PreferenceUpdateWorker.updateFromFingerprint()`（第 70-106 行）

```
设计意图：
  管理员审批通过一条指纹
    → 从 DesignFingerprintDTO 六层结构中提取标签
    → 对该指纹的创建用户更新偏好权重
    → 让优质指纹反哺用户画像

当前状态：方法已实现，但在全项目中无任何调用点
           搜索 "updateFromFingerprint" 的调用方 = 仅找到定义本身
```

激活方式：在 `DesignFingerprintServiceImpl.approve()` 末尾加入调用链：
```java
// 审核通过后反哺用户偏好
if (fp.getOwnerUserId() != null) {
    DesignFingerprintDTO dto = parseFingerprintJson(fp.getFingerprintJson());
    preferenceUpdateWorker.updateFromFingerprint(fp.getOwnerUserId(), dto, 0.05);
}
```

### 3.4 路径四：直接 API ⚠️ 不存在

搜索 `PUT.*preference_weight`、`POST.*preference_weight` → **无任何直接操作权重表的 REST 接口**。

所有权重写入均通过内部 Service/Worker 完成，不直接暴露给外部。

---

## 4. 项目品牌入库

**状态：❌ 无写入入口**

`ProjectBrandProfile` 表结构完整（brandName, brandColorPrimary/Secondary/Accent, brandFont, brandTone, brandLogoUrl, brandGuidelines JSON），但：

- 搜索 `POST.*brand`、`PUT.*brand` → 无 REST 接口
- `ProjectBrandProfileService` 只有读取方法 `loadBrandContext(userId, projectId)`
- 前端无品牌配置 UI

**当前只能通过数据库手动 INSERT**。要在流水线中生效，需补齐：
1. `POST /app/{appId}/brand` API
2. 前端 AppChatPage 工具栏"品牌设置"按钮 + 弹窗表单

---

## 5. 生成运行记录入库

**文件**：`service/generation/GenerationPipelineRouter.java`（第 163-168 行）
**文件**：`service/generation/GenerationRunService.java`

**状态：✅ 完整且活跃**

```
每次生成请求 → GenerationPipelineRouter.route()
    │
    ├─ 开始时: generationRunService.start()
    │     → INSERT generation_run (status=RUNNING, startedAt=now)
    │
    ├─ 降级时: generationRunService.fallback(from, to, reason)
    │     → UPDATE fallbackFrom / fallbackTo / errorMessage
    │
    ├─ 成功时: doOnComplete → generationRunService.success()
    │     → UPDATE status=FALLBACK_SUCCESS (如有降级) 或 SUCCESS
    │     → 计算 durationMs = finishedAt - startedAt
    │
    └─ 失败时: doOnError → generationRunService.fail(errorMessage)
          → UPDATE status=FAILED, 错误消息截断 4000 字
```

记录内容：generationId / appId / userId / requestedMode / finalMode / pipelineType / status / fallbackFrom→To / workspacePath / startedAt→finishedAt / durationMs / errorMessage

**注意**：写入是容错的——失败只记录日志，不阻断生成主流程。

---

## 6. 对话历史入库

**文件**：`controller/AppController.java` 多处

**状态：✅ 完整且活跃**

```
用户发消息: 调用 chatHistoryService.addChatMessage(appId, message, "user", userId)
    ↓ INSERT INTO chat_history (message, messageType='user', appId, userId)

流结束后(doOnTerminate): AI 回复文本写入
    ↓ INSERT INTO chat_history (message=aiFullText, messageType='ai', appId, userId)
```

两个调用点：
- `chatToGenCode`（正常生成）
- `incrementalEditStream`（增量编辑）
- 写入失败不阻断主流程（catch 只记日志）

---

## 7. 活跃度总结

| 入库目标 | 路径 | 状态 | 激活条件 |
|---------|------|:---:|---------|
| design_fingerprint | 定时爬取 | ✅ 活跃 | `agent.fingerprint.crawl.enabled=true` |
| design_fingerprint | 用户私有提炼 | ⚠️ 未激活 | `agent.fingerprint.user-ingest.enabled=true` + 增加调用点 |
| design_fingerprint_vector | 审核通过同步 | ✅ 活跃 | 管理员 APPROVE 自动触发 |
| user_design_event | 行为事件上报 | ✅ 活跃 | 前端改色/改布局时自动调用 |
| user_preference_weight | 行为事件驱动 | ✅ 活跃 | recordEditEvent API → Worker |
| user_preference_weight | Prompt 提取 | ✅ 活跃 | 每次创建应用/增量编辑自动触发 |
| user_preference_weight | 指纹反哺 | ⚠️ 未激活 | 需在 approve() 末尾加调用 |
| user_design_profile.summary | 自动刷新 | ✅ 刚激活 | PreferenceUpdateWorker 每 10 次事件触发 |
| project_brand_profile | — | ❌ 无入口 | 需新增 API + 前端 UI |
| generation_run | Pipeline 完成 | ✅ 活跃 | 每次生成自动写入 |
| chat_history | 对话时 | ✅ 活跃 | 用户消息 + AI 回复完成时 |

**一句话总结**：指纹和偏好写入的主路径已经**全部贯通**，剩余 3 个未激活路径（用户私有提炼、指纹反哺、项目品牌）工作量极小，都是"代码已写好，就差一个调用点"的状态。
