# 修改计划 05：用户评分事件反馈未实现

> 生成日期：2026-07-02
> 状态：✅ 已实施（2026-07-02）—— 采纳方案 4.3（修正文档，不新增死代码）
> 优先级：低-中

## 1. 问题描述

`PreferenceUpdateWorker` 类级 Javadoc 明确声称支持：

```
正反馈：page_saved(+0.1), page_exported(+0.2), user_rating_5(+0.2)
负反馈：page_deleted(-0.1), page_regenerated(-0.05), user_rating_1_2(-0.2)
```

但 `applyPreferenceUpdate()` 的 `switch(eventType)` 分支中**只实现了** `page_saved`/`page_exported`/`page_deleted`/`page_regenerated`/`color_changed`/`component_removed`/`component_added`/`layout_changed` 八种事件，完全没有 `user_rating_5`/`user_rating_1_2` 分支，命中 `default` 后仅打一行 debug 日志，不做任何权重更新。

进一步排查 `EventTypeEnum` 也**未定义**评分相关枚举值（仅有 `PAGE_SAVED`/`PAGE_EXPORTED`/`PAGE_DELETED`/`COLOR_CHANGED`），说明用户评分这一反馈来源在当前系统中从未被真正打通，属于"文档先行、代码未跟上"的遗留缺口。

## 2. 影响范围

- 用户对生成页面的星级评分（如果前端已有该交互）不会对偏好权重产生任何影响。
- 是"用户偏好闭环"里唯一一条**显式、高置信度**的反馈信号缺失（相比 `page_saved` 等隐式行为，用户主动评分的信号质量更高）。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/model/enums/EventTypeEnum.java`（需新增枚举值）
- `src/main/java/com/yu/yuaifather/worker/PreferenceUpdateWorker.java`（`applyPreferenceUpdate()` 需新增 `case`）
- 前端/Controller 层需确认是否已有评分埋点入口（若没有，需要先确认产品侧是否真的需要该功能，避免为不存在的交互写死代码）

## 4. 修改方案

### 4.1 先确认产品侧真实需求

在动手前需要先确认：前端是否已有"评分"UI（如星级/点赞点踩），且是否已经在某处以某种 `eventType` 字符串上报到后端。若前端根本没有评分入口，本计划应降级为"仅修正 Javadoc 描述，移除虚假承诺"，而不是新增无用代码。

### 4.2（若产品确认需要）新增枚举与事件处理分支

```java
// EventTypeEnum.java
USER_RATING_5("user_rating_5", "用户评分5星"),
USER_RATING_LOW("user_rating_1_2", "用户评分1-2星"),
```

```java
// PreferenceUpdateWorker.applyPreferenceUpdate()
case "user_rating_5" -> updateFromMetadata(userId, metadata, 0.2);
case "user_rating_1_2" -> updateFromMetadata(userId, metadata, -0.2);
```

事件的 `metadata` 需要携带本次生成引用的 `tags`（风格/组件/布局标签），复用现有 `updateFromMetadata()` 逻辑即可，无需新增方法。

### 4.3 若产品侧确认不需要

直接修正 `PreferenceUpdateWorker` 类头 Javadoc，删除 `user_rating_5`/`user_rating_1_2` 两条不存在的描述，避免继续误导后续维护者。

## 5. 验证方式

1. 若实施 4.2：构造一条 `eventType=user_rating_5` 的事件调用 `recordAndUpdate()`，断言相应 `user_preference_weight` 记录权重 `+0.2`。
2. 若实施 4.3：仅需 Review 确认 Javadoc 与实际 `switch` 分支描述一致即可，无需额外测试。

## 6. 风险评估

- **风险低**，且强烈建议**先做 4.1 的产品确认**，避免在没有真实前端埋点的情况下"为不存在的功能补代码"（这类改动本身不产生任何价值，甚至可能引入从未被真实数据触发、无法验证正确性的死代码）。

## 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-02
- **4.1 产品侧确认结论**：搜索 `frontend/` 全部代码（含 `AppChatPage.vue`、`ProfilePage.vue`、`AdminFingerprintPage.vue` 等页面与 `sse.ts`/`request/index.ts` 等工具模块），未发现任何"星级评分/点赞点踩"类 UI 交互组件，也未发现任何处硬编码上报 `eventType=user_rating_5`/`user_rating_1_2` 的调用。后端 `AppController#recordEditEvent`（`/record-edit-event`）虽然是通用透传接口（接受任意 `eventType` 字符串），但目前没有任何调用方传入评分类事件。结论：**前端当前没有评分入口，产品侧未落地该功能**。
- **采纳方案**：按计划 4.3 执行——仅修正 `PreferenceUpdateWorker` 类头 Javadoc，删除 `user_rating_5`/`user_rating_1_2` 两条不存在的描述，并补充说明该信号当前未接入的原因与后续接入指引，不新增 `EventTypeEnum` 枚举值与 `switch` 分支，避免死代码。
- **涉及文件**：`src/main/java/com/yu/yuaifather/worker/PreferenceUpdateWorker.java`（仅类级 Javadoc 变更，无逻辑改动）。
- **后续建议**：若产品侧后续确认需要接入用户主动评分反馈，可按本计划 4.2 的方案（新增 `EventTypeEnum.USER_RATING_5`/`USER_RATING_LOW` 枚举值 + `applyPreferenceUpdate()` 对应 `case` 分支）实施，工作量很小，可作为后续独立小任务处理。
