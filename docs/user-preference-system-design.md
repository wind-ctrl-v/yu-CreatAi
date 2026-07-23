# CreatAi 用户偏好系统设计详解

> 生成日期：2026-07-04
> 最近更新：2026-07-04（偏好提取统一重构：全部走 LLM 单一路径，删除前端规则上报）
> 基于代码库 commit `60d68e0`

---

## 目录

1. [系统总览](#1-系统总览)
2. [数据模型](#2-数据模型)
3. [偏好收集链路](#3-偏好收集链路)
4. [偏好消费链路](#4-偏好消费链路)
5. [偏好更新规则详解](#5-偏好更新规则详解)
6. [用户设计画像自动刷新](#6-用户设计画像自动刷新)
7. [配置开关体系](#7-配置开关体系)
8. [当前问题与改进方向](#8-当前问题与改进方向)

---

## 1. 系统总览

用户偏好系统是一个**收集 → 存储 → 消费**的三阶段闭环，核心目标是：追踪用户在设计上的行为信号（保存/导出/改色/换布局等），沉淀为可量化的偏好权重，在后续生成时影响 RAG 检索和 prompt 注入。

```
┌─────────────── 收集 ───────────────┐    ┌──── 存储 ────┐    ┌──────────── 消费 ────────────┐
│                                   │    │               │    │                              │
│ 路径1: 前端行为事件上报            │───→│ user_design_  │───→│ 消费点1: 个性化查询文本构造   │
│   (保存/导出/改色/换布局/加组件)   │    │ event         │    │   (PersonalizedQueryBuilder)  │
│                                   │    │ user_pref_    │    │                              │
│ 路径2: LLM 提示词偏好提取         │───→│ weight        │───→│ 消费点2: 个性化排序           │
│   (从修改指令文本提取偏好标签)     │    │ user_design_  │    │   (DesignKnowledgeIndex)       │
│                                   │    │ profile       │    │                              │
│ 路径3: 下载行为记录               │───→│               │───→│ 消费点3: 个性化检索排序         │
│   (导出时 +0.2 权重)              │    │               │    │   (DesignKnowledgeIndex)       │
│                                   │    │               │    │                              │
│                                   │    │               │───→│ 消费点4: Prompt 上下文压缩    │
│                                   │    │               │    │   (ContextCompressTool)       │
└───────────────────────────────────┘    └───────────────┘    └──────────────────────────────┘
```

---

## 2. 数据模型

### 2.1 三张核心表

#### `user_design_profile`（用户设计画像）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT | 雪花 ID |
| `userId` | BIGINT | 用户 ID（唯一键） |
| `profileSummary` | TEXT | 自然语言偏好摘要（自动刷新） |
| `preferredStyles` | JSON | 偏好风格列表 `["minimal","tech"]` |
| `dislikedStyles` | JSON | 反感风格列表 |
| `preferredColors` | JSON | 偏好颜色列表 `["blue","green"]` |
| `dislikedColors` | JSON | 反感颜色列表 |
| `preferredLayouts` | JSON | 偏好布局列表 `["centered-hero","card-grid"]` |
| `dislikedLayouts` | JSON | 反感布局列表 |
| `preferredComponents` | JSON | 偏好组件列表 `["nav-bar","article-card"]` |
| `dislikedComponents` | JSON | 反感组件列表 |
| `preferredInteractions` | JSON | 偏好交互列表 `["subtle-hover","fade-transition"]` |
| `dislikedInteractions` | JSON | 反感交互列表 |

**特点**：一个用户只有一条记录，存的是"分类列表"（JSON 数组），用于 `UserDesignContext` 的 `preferred/disliked` 字段。

#### `user_preference_weight`（偏好权重，精细量化）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT | 雪花 ID |
| `userId` | BIGINT | 用户 ID |
| `preferenceType` | VARCHAR(64) | 偏好类型：`style`/`color`/`layout`/`component`/`interaction` |
| `preferenceKey` | VARCHAR(128) | 具体标签值：`blue`/`minimal`/`card-grid` 等 |
| `weight` | DOUBLE | 权重（正=喜欢，负=反感，0=中性） |
| `evidenceCount` | INT | 证据计数（被更新次数） |
| `lastEvidenceAt` | DATETIME | 最后一次证据时间 |

**唯一键**：`(userId, preferenceType, preferenceKey)`——同一用户同一标签只有一行，权重是累加的。

**特点**：这是细粒度的"标签级"权重表，每次用户行为都会原子更新对应标签的 weight。一个用户可能有几十条权重记录。

#### `user_design_event`（用户行为事件流，只写不删）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT | 雪花 ID |
| `userId` | BIGINT | 用户 ID |
| `projectId` | BIGINT | 项目 ID |
| `generationId` | BIGINT | 关联的生成记录 ID |
| `pageId` | BIGINT | 关联的页面 ID |
| `eventType` | VARCHAR(64) | 事件类型（见下文枚举） |
| `targetType` | VARCHAR(64) | 目标类型（扩展用） |
| `targetId` | BIGINT | 目标 ID |
| `metadata` | JSON | 事件元数据 |
| `createTime` | DATETIME | 创建时间 |

**特点**：只写不删，记录用户完整行为链路，用于审计和后续离线分析。

### 2.2 EventType 枚举

```java
public enum EventTypeEnum {
    PAGE_GENERATED("page_generated", "页面生成"),
    PAGE_REGENERATED("page_regenerated", "页面重新生成"),
    PAGE_SAVED("page_saved", "页面保存"),
    PAGE_EXPORTED("page_exported", "页面导出"),
    PAGE_DELETED("page_deleted", "页面删除"),
    COLOR_CHANGED("color_changed", "颜色修改"),
    LAYOUT_CHANGED("layout_changed", "布局修改"),
    COMPONENT_ADDED("component_added", "组件添加"),
    COMPONENT_REMOVED("component_removed", "组件移除"),
    FEEDBACK_SUBMITTED("feedback_submitted", "反馈提交");
}
```

### 2.3 三张表的关系

```
user_design_event（事件流）
    │
    │ 触发规则更新
    ↓
user_preference_weight（权重表）
    │
    │ 每 10 次事件触发刷新
    ↓
user_design_profile（画像表）
    │
    │ 生成时被加载为
    ↓
UserDesignContext（运行时 DTO）
```

- `user_design_event` 是**原始事件流**，只用于审计和触发权重更新
- `user_preference_weight` 是**量化权重**，用于检索排序和个性化查询
- `user_design_profile` 是**分类画像**，用于 prompt 注入和自然语言摘要

---

## 3. 偏好收集链路

> **2026-07-04 重构**：偏好提取已统一为 LLM 单一路径，删除了原先的"前端规则上报"路径。
> 以下描述的是重构后的设计。

### 3.1 统一入口：LLM 偏好提取

```
前端 AppChatPage.vue → handleSend()
  └── doIncrementalEdit(instruction)
      → GET /app/incremental-edit-stream?appId=&instruction=
          ↓
      AppController.incrementalEditStream()
          ├── 主流程：IncrementalEditService → AI 修改源文件（SSE）
          └── 异步（虚拟线程）：
              PromptPreferenceExtractor.extractAndRecord(userId, instruction)
                  ├── 调用 LLM，System Prompt 要求输出正负向偏好 JSON：
                  │   {
                  │     "positive": {"colors":["蓝色"],"styles":["清爽"],...},
                  │     "negative": {"colors":[],"styles":["渐变"],...}
                  │   }
                  ├── 正向标签 → updateSingleWeight(userId, type, tag, +0.15)
                  └── 负向标签 → updateSingleWeight(userId, type, tag, -0.15)
```

**已删除的旧路径**：
- ❌ 前端 `recordElementEditEvent()` / `recordTextEditEvent()` / `isModifyIntent()`（正则判定 eventType）
- ❌ 前端 `recordEditEvent()` API 调用
- ❌ 后端 `POST /app/record-edit-event` 接口
- ❌ 后端 `AppBehaviorService.recordEditEvent()` 方法

### 3.2 保留的入口：下载导出偏好记录

```
AppController.downloadAppCode()
  → AppBehaviorService.recordPageExportedEvent(userId, appId)
  → PreferenceUpdateWorker.recordAndUpdate(event)
      → updatePageLifecycleWeight(userId, metadata, "page_exported", +0.2)
          → upsertWeight(userId, "style", "page_exported", +0.2)  ← 合成标签兜底
```

此路径不走 LLM，作为弱正反馈信号保留。

---

## 4. 偏好消费链路

### 4.1 消费点 1：个性化查询文本构造

**类**：`PersonalizedQueryBuilder`
**触发位置**：`DesignKnowledgeIndex.tryVectorRetrieveWithPersonalization()`
**开关**：`agent.personalization.enabled=true` + `agent.retrieve.vector.enabled=true`

```java
queryText = queryBuilder.build(requirement, plan, userContext,
        userPreferenceService.loadPreferenceWeights(queryUserId));
```

输出文本格式：

```
[设计风格: startup]
[需求摘要: 做 SaaS 落地页]
[项目概述: ...]
[目标用户: ...]
[核心功能: ...]
[用户偏好: 极简, 蓝色, card布局]      ← 来自 UserDesignContext.preferred*
[用户不喜欢: 渐变, 紫色]              ← 来自 UserDesignContext.disliked*
[高频偏好: blue, card, minimal]       ← Top-5 正权重标签
[高频反感: purple, gradient]          ← Top-5 负权重标签
```

这个文本会被 embedding 后去 PGvector 检索，偏好标签影响向量相似度计算。

### 4.2 消费点 2：个性化排序

**类**：`DesignKnowledgeIndex.calculatePersonalizedScore()`
**开关**：`agent.personalization.enabled=true`

每条召回指纹按以下公式排序：

```
personalizedScore = 0.40 * semanticScore      // queryTags 命中率
                  + 0.20 * styleScore          // tone 与用户偏好风格重叠率
                  + 0.15 * componentScore       // preferredPatterns 与用户偏好组件重叠率
                  + 0.15 * userPrefScore         // UserPreferenceService 计算的偏好权重
                  + 0.10 * confidence           // 指纹置信度
                  - 0.20 * dislikePenalty        // 用户反感标签惩罚
```

其中 `userPrefScore` 和 `dislikePenalty` 的计算：

```java
userPrefScore = userPreferenceService.calculatePreferenceScore(uid, "style", item.getTone())
             + userPreferenceService.calculatePreferenceScore(uid, "component", item.getPreferredPatterns())
             + userPreferenceService.calculatePreferenceScore(uid, "layout", item.getLayoutPatterns());

dislikePenalty = userPreferenceService.calculateDislikePenalty(uid, "style", item.getTone())
              + userPreferenceService.calculateDislikePenalty(uid, "component", item.getPreferredPatterns())
              + userPreferenceService.calculateDislikePenalty(uid, "layout", item.getLayoutPatterns());
```

### 4.3 消费点 3：独立重排服务

**实现**：`DesignKnowledgeIndex.calculatePersonalizedScore()`
**开关**：`agent.personalization.enabled=true`

10 项加权计分公式中的两项涉及用户偏好：

```
finalScore = ...
  + 0.15 * user_preference_score      ← 偏好加分
  - 0.20 * user_dislike_penalty      ← 反感扣分
  ...
```

### 4.4 消费点 4：Prompt 上下文压缩

**类**：`ContextCompressTool`
**触发位置**：V2 编排器的 `assembleFlux()` → `DesignContextAssembler.assemble()` → `ContextCompressTool`

将用户偏好压缩为自然语言摘要，注入到 CoderAgent/StyleAgent 的 prompt：

```
## 用户偏好
偏好风格:minimal,modern,clean,tech;偏好颜色:light-green,blue,slate-gray;
偏好布局:centered-hero,card-grid,sticky-nav;偏好组件:nav-bar,article-card,feature-grid;
反感风格:corporate,playful,maximalist;反感颜色:neon,bright-yellow,hot-pink;
```

**字符限制**：`context.max-user-preference-chars=300`（超出截断）

### 4.5 偏好加载入口

**类**：`UserPreferenceTool`（System Tool，零 LLM）
**开关**：`agent.personalization.enabled=true`

```java
UserDesignContext context = userPreferenceService.loadUserDesignContext(userId);
ctx.getBlackboard().putUserDesignContext(context);
```

在生成流水线的早期阶段（V1: Step 2.55 / V2: `assembleFlux`）加载用户偏好，写入 Blackboard 供后续所有消费点使用。

---

## 5. 偏好更新规则详解

### 5.1 `applyPreferenceUpdate()` 的 switch 分支

| eventType | 权重操作 | delta |
|---|---|---|
| `page_saved` | `updateFromMetadata(userId, metadata, +0.1)` | +0.1 |
| `page_exported` | `updateFromMetadata(userId, metadata, +0.2)` | +0.2 |
| `page_deleted` | `updateFromMetadata(userId, metadata, -0.1)` | -0.1 |
| `page_regenerated` | `updateFromMetadata(userId, metadata, -0.05)` | -0.05 |
| `color_changed` | `handleColorChanged(userId, fromTag, toTag)` | fromTag -0.2, toTag +0.2 |
| `component_added` | `handleComponentAdded(userId, componentName)` | +0.1 |
| `component_removed` | `handleComponentRemoved(userId, componentName)` | -0.2 |
| `layout_changed` | `handleLayoutChanged(userId, from, to)` | from -0.1, to +0.1 |
| `style_changed` | **未实现**（default 分支忽略） | — |
| `interaction_changed` | **未实现**（default 分支忽略） | — |

### 5.2 `updateFromMetadata()` 的 tags 解析

```java
List<String> tags = metadata.getOrDefault("tags", []);
if (tags.isEmpty()) {
    tags = metadata.get("styleTags") + metadata.get("colorTags") + metadata.get("componentTags");
}
for (String tag : tags) {
    // tag 格式："type:key"，如 "style:minimal"
    upsertWeight(userId, parts[0], parts[1], delta);
}
```

**问题**：前端 `recordEditEvent` 上报的 metadata 格式是 `toTag=xxx` 或 `from=xxx`，不是 `tags: ["style:minimal"]` 这种格式，所以 `updateFromMetadata()` 实际上会因为 tags 为空而直接 return。

### 5.3 原子权重更新

```java
// UserPreferenceWeightMapper.atomicUpsertWeight()
@Update("UPDATE user_preference_weight SET weight = weight + #{delta}, " +
        "evidence_count = evidence_count + 1, last_evidence_at = NOW() " +
        "WHERE user_id = #{userId} AND preference_type = #{type} AND preference_key = #{key}")
int atomicUpsertWeight(userId, type, key, delta);
```

利用 MySQL UPDATE 的行锁保证并发安全。如果记录不存在（affected=0），先 INSERT，并发冲突时重试原子更新。

### 5.4 LLM 提示词提取的权重更新

```java
// PromptPreferenceExtractor.recordGroup()
preferenceUpdateWorker.updateSingleWeight(userId, type, tag, 0.15);
```

固定 delta=+0.15，只加分不减分。

---

## 6. 用户设计画像自动刷新

### 触发条件

每累计 10 次事件更新（`eventMapper.selectCountByQuery() % 10 == 0`）触发一次。

### 刷新逻辑

```java
private void maybeRefreshProfileSummary(Long userId) {
    // 查询 Top-5 正权重标签
    List<UserPreferenceWeight> topLikes = weightMapper.selectListByQuery(
        new QueryWrapper().eq("user_id", userId).orderBy("weight", false).limit(5));

    // 查询 Top-3 负权重标签
    List<UserPreferenceWeight> topDislikes = weightMapper.selectListByQuery(
        new QueryWrapper().eq("user_id", userId).orderBy("weight", true).limit(3));

    // 拼接自然语言摘要
    String summary = "偏爱: blue、card、minimal、startup、clean | 反感: purple、gradient、neon";

    // 写回 user_design_profile.profileSummary
    profile.setProfileSummary(summary);
    profileMapper.update(profile);
}
```

**注意**：只刷新 `profileSummary` 字段，不更新 `preferredStyles`/`dislikedStyles` 等 JSON 列表字段——这些字段当前只能通过种子数据或手动修改数据库设置，没有自动更新机制。

---

## 7. 配置开关体系

### 收集侧（无开关，始终执行）

```yaml
# 偏好收集不需要任何开关，只要前端上报事件就会触发
# 但前端只在"增量编辑"和"下载导出"时上报
# "完整生成"不会上报偏好事件
```

### 消费侧（全部默认关闭）

```yaml
agent:
  personalization:
    enabled: false    # ★ 消费点 1/2 的总开关
  rerank:
    enabled: false   # ★ 消费点 3 的开关
  retrieve:
    vector:
      enabled: false  # ★ 消费点 1 的前置条件
```

**关键结论**：默认配置下，偏好权重被持续收集但从未被读取使用。只有显式开启上述三个开关，偏好数据才真正参与生成决策。

### Prompt 注入限制

```yaml
context:
  max-user-preference-chars: 300    # 消费点 4 的截断长度
  max-evidence-count: 3
```

---

## 8. 当前问题与改进方向

### 🔴 P0：数据链路断裂

#### 8.1 `page_exported` 事件的权重更新是空操作

前端 `AppBehaviorService.recordPageExportedEvent()` 构造的事件 **没有 metadata**，`updateFromMetadata()` 因 tags 为空直接 return，导致 `page_exported` 的 +0.2 权重规则**从未真正执行**。

#### 8.2 `style_changed`/`interaction_changed` 前端上报但后端不处理

前端 `recordTextEditEvent()` 会判定并上报这两种 eventType，但 `PreferenceUpdateWorker.applyPreferenceUpdate()` 的 switch 里**没有这两个 case**，会被 default 分支忽略。

#### 8.3 metadata 格式不匹配

前端上报的 metadata 是 `toTag=蓝色` 这种 query string 格式，但 `updateFromMetadata()` 期望的是 `{"tags": ["color:blue"]}` 这种 JSON 数组格式，导致解析失败。

### 🟡 P1：增量编辑不消费偏好

增量编辑走 `IncrementalEditService`，完全不经过 `DesignKnowledgeIndex`，不检索指纹也不读取偏好权重。用户偏好在"修改页面"时完全不被参考。

### 🟡 P1：画像字段不自动更新

`user_design_profile` 的 `preferredStyles`/`dislikedStyles` 等 JSON 列表字段只能通过种子数据或手动修改设置，自动刷新只更新 `profileSummary` 自然语言摘要。

### 🟡 P1：偏好事件乐观触发

前端在发送修改指令的瞬间就上报偏好事件（`recordElementEditEvent`），不等后端 `incrementalEdit` 是否成功。如果修改失败，"用户改了颜色"的偏好记录依然被写入，产生数据噪音。

### 🟢 P2：权重无衰减机制

权重只增不减（除了显式删除/换色），没有时间衰减。用户一年前喜欢蓝色，现在改成喜欢绿色，蓝色的旧权重仍然很高（只是不再增长），会持续影响检索排序。

### 🟢 P2：偏好标签未归一化

同一个"蓝色"可能被记为 `blue`/`Blue`/`BLUE`/`蓝色`/`蓝`，权重分散在多条记录上，无法有效聚合。`upsertWeight` 用 `equalsIgnoreCase` 做匹配，但 LLM 提取的中文标签和前端上报的英文标签之间没有归一化映射。

---

## 附：关键类索引

### 收集侧

| 类 | 包 | 职责 |
|---|---|---|
| `AppBehaviorService` | `service` | 构造事件并触发记录（从 Controller 下沉） |
| `PreferenceUpdateWorker` | `worker` | 偏好权重更新核心逻辑（规则驱动 + 原子 upsert） |
| `PromptPreferenceExtractor` | `service` | 从修改指令文本用 LLM 提取偏好标签 |
| `UserPreferenceWeightMapper` | `mapper` | 原子权重更新 SQL |

### 存储侧

| 类 | 包 | 职责 |
|---|---|---|
| `UserDesignProfile` | `model.entity` | 用户画像实体 |
| `UserPreferenceWeight` | `model.entity` | 偏好权重实体 |
| `UserDesignEvent` | `model.entity` | 行为事件实体 |
| `EventTypeEnum` | `model.enums` | 事件类型枚举 |

### 消费侧

| 类 | 包 | 职责 |
|---|---|---|
| `UserPreferenceService` | `service` | 偏好加载 + 权重计算 |
| `UserPreferenceTool` | `tool.system` | 生成时加载偏好到 Blackboard |
| `PersonalizedQueryBuilder` | `service` | 个性化查询文本构造 |
| `DesignKnowledgeIndex` | `service.design` | 个性化排序 |
| `ContextCompressTool` | `tool.system` | 偏好压缩为 prompt 摘要 |
| `UserDesignContext` | `model.dto.agent` | 运行时偏好上下文 DTO |
