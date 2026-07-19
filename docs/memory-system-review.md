# CreatAi 记忆系统现状审查与优化建议

> 审查日期：2026-07-19
> 定位：组件级现状审查，补齐 `memory-system-architecture-evolution.md`（2026-07-10）第 5 行引用的缺失文档。
> 与架构演进文档的关系：架构演进文档回答"应该怎么分层"，本文回答"现在跑得怎么样、哪里有病"。
> 审查方式：全量实读代码 + 文档交叉核对，关键结论均已复核源码。

---

## 目录

1. [记忆系统全景](#1-记忆系统全景)
2. [各层现状与数据流](#2-各层现状与数据流)
3. [问题清单（按严重程度）](#3-问题清单按严重程度)
4. [优化建议（分优先级）](#4-优化建议分优先级)
5. [与架构演进文档的对齐关系](#5-与架构演进文档的对齐关系)
6. [关键类索引](#6-关键类索引)

---

## 1. 记忆系统全景

代码中实际存在 **5 类记忆载体**，与架构演进文档 §2 的盘点一致，按功能归为 3 层：

| 载体 | 记忆层 | 存储 | 关键类 | 生命周期 |
|---|---|---|---|---|
| `Blackboard` | 工作记忆 | JVM 内存 | `agent/core/Blackboard.java` | 单次生成，用完即弃 |
| 对话历史 | 情景记忆 | Redis + MySQL `chat_history` + Caffeine | `AiCodeGeneratorServiceFactory`、`ChatHistoryServiceImpl` | Redis TTL 1h；窗口 100 条；Caffeine 写后 30min / 访问后 10min |
| 用户偏好 | 语义记忆（用户） | MySQL 三表 | `UserPreferenceService`、`PreferenceUpdateWorker`、`PromptPreferenceExtractor` | 永久 + 读取侧 30 天半衰期衰减 |
| 项目品牌 | 语义记忆（项目） | MySQL `project_brand_profile` | `ProjectBrandService`（**只读**） | 项目生命周期，**无写入口** |
| 设计指纹（RAG） | 语义记忆（全局） | MySQL `design_fingerprint` + PGvector | `DesignKnowledgeIndex`、`VectorSyncWorker` | 治理状态机驱动，无 TTL |

---

## 2. 各层现状与数据流

### 2.1 情景记忆（对话）

**装配**（`agent/codegen/AiCodeGeneratorServiceFactory.java`）：
- Caffeine 缓存 AI 服务实例：`maximumSize(1000)` + 写后 30min / 访问后 10min 过期（:62-69），缓存键 = `appId + "_" + codeGenType`（:89-91）。
- 每个服务实例持有 `MessageWindowChatMemory.id(appId)` + Redis store + `maxMessages(100)`（:100-105，**硬编码**）。
- 创建时回载 DB 最近 20 条历史（:107）。
- `chatMemoryProvider(memoryId -> chatMemory)` 忽略 memoryId（:113/:125）。
- 默认 Bean 用 `appId=0` 启动即建（:49-52）。

**写入路径**：
| 时机 | 位置 |
|---|---|
| 生成前写用户消息 | `AppServiceImpl.java:193` |
| 流式完成写 AI 消息 | `SimpleTextStreamHandler.java:39`、`JsonMessageStreamHandler.java:60` |
| 流式失败写"AI回复失败：..." | 同上两文件的 :43-44 / :64-65 |
| 增量编辑写用户/AI 消息 | `AppController.java:375-376`、`:413-415` |
| 删除应用时删历史 | `AppServiceImpl.java:318` |

**读取路径**：仅服务实例创建（缓存未命中）时回载一次；之后由 LangChain4j 框架自动向 Redis 追加。`loadChatHistoryToMemory`（`ChatHistoryServiceImpl.java:136-169`）先 `clear()` 再按时间正序回载，`limit(1, maxCount)` 跳过最新 1 条（注释：排除刚写入的当前用户消息）。

### 2.2 语义记忆（用户偏好）

- **读**：`UserPreferenceService.loadPreferenceWeights`（`UserPreferenceService.java:108-113`）按 userId 全量拉取权重行，内存匹配；时间衰减在**读取侧**（:184-195，半衰期 30 天，`exp` 衰减，因子 < 0.05 归零）。**无缓存**。
- **写**：`PromptPreferenceExtractor`（LLM 提取正/负向标签，Δ=±0.15 / 负反馈 -0.20）与 `PreferenceUpdateWorker`（行为事件弱信号）→ 均汇聚到 `upsertWeight` → `atomicUpsertWeight` 原子 UPDATE，0 行则 INSERT，唯一键冲突重试（`PreferenceUpdateWorker.java:238-257`，并发设计本身合理）。
- **画像摘要**：每 10 次事件触发一次 `profileSummary` 重算，虚拟线程异步（`PreferenceUpdateWorker.java:305-323`）。
- **三表索引**（`docs/sql/001_user_preference.sql`）：uk(userId,preferenceType,preferenceKey)、uk_user_id、idx(userId,eventType) 等，对现有查询模式**充足**。

### 2.3 语义记忆（项目品牌）

- `ProjectBrandService` 唯一业务方法 `loadProjectBrandContext`（`ProjectBrandService.java:43-69`），**全库无 insert/update 写入口**——与架构演进文档 §5.2"待补 upsert\*"的判断一致。
- `forbiddenRules` 字段已存在（实体 `ProjectBrandProfile.java:70-71`、DTO、装配链路齐全），但因无写入侧，实际恒为空。

### 2.4 语义记忆（全局指纹 RAG）

- 检索：`DesignKnowledgeIndex` 多通道召回 + 个性化排序。
- 向量同步：`VectorSyncWorker.syncOnApprove` 审核通过时同步 embedding 到 PGvector。
- `DesignKnowledgeIndex.vectorRetrieve`（:302-308）是**返回空结果的桩方法**，向量通道尚未真正接入检索。

### 2.5 工作记忆

- `Blackboard`（`agent/core/Blackboard.java:29`）承载单次生成的中间产物（`designSpec`/`designEvidence`/`userDesignContext`/`projectBrandContext`/`criticReport` 等键均在 :34-41），状态健康，无突出问题。

---

## 3. 问题清单（按严重程度）

### P0 — 疑似链路级故障，建议最优先核实

#### P0-1 偏好权重写入链路：蛇形 SQL vs 驼峰列名，疑似静默全灭

- `UserPreferenceWeightMapper.java:22-24` 手写 SQL 使用**蛇形**列名：`user_id` / `preference_type` / `preference_key` / `evidence_count` / `last_evidence_at`。
- 而 DDL（`docs/sql/001_user_preference.sql:35-45`）、实体 `@Column("userId")` 等、种子脚本全部为**驼峰**列名。`user_id` 与 `userId` 是不同列名（与大小写敏感性无关）。
- 若实际库表按 DDL 建（驼峰），该 UPDATE 必抛 `Unknown column 'user_id'`，`upsertWeight` 第一行即抛、走不到 INSERT 分支；异常被 `PreferenceUpdateWorker.recordAndUpdate`（:75-77）和 `PromptPreferenceExtractor.extractAndRecordInternal`（:186-188）吞掉只记 warn——**所有偏好权重写入静默失败**。
- 同理 `PreferenceUpdateWorker.java:309/332/339` 的 `QueryWrapper.eq("user_id", ...)`（QueryWrapper 按字面拼列名）会导致 profileSummary 刷新同样静默失败。
- **核实方法**：对实际数据库执行 `SHOW CREATE TABLE user_preference_weight`，并 grep 近期日志中的 `偏好更新失败` / `Unknown column`。

#### P0-2 向量维度 1536 vs 1024 不匹配，本地配置下向量通道整体静默失效

- PG 表定义 `embedding vector(1536)`（`docs/sql/design_fingerprint_vector.sql:20`、`011_init_pgvector_local.sql:24`）。
- 但 `application-local.yml:39-46` 配智谱 embedding-2（注释自承 1024 维）且 `:91-93 use-openai: true` → 1024 维向量写入 vector(1536) 必被 PG 拒绝。
- 异常链路：`upsert` 无 try/catch（`DesignFingerprintVectorRepository.java:63-96`）→ 异常上抛至 `VectorSyncWorker` 被吞（见 P1-2）。
- 注：`EmbeddingModelConfig.java:25` 的哈希兜底是 1536 维（与表匹配），是 local 配置切智谱后才失配。

### P1 — 功能缺陷 / 数据污染

#### P1-1 HTML 与 VUE_PROJECT 服务共享同一 memory id，双向交叉污染

- 缓存键区分了 codeGenType（`AiCodeGeneratorServiceFactory.java:89-91`），但 memory id 只有 appId（:100-105）→ 两类服务指向**同一 Redis key**。
- 创建 VUE 服务时 `loadChatHistoryToMemory` 的 `clear()`（`ChatHistoryServiceImpl.java:152`）会清掉 HTML 服务正在使用的记忆，反之亦然。
- 修复方向：memory id 改为 `appId_codeGenType`（与缓存键对齐）。

#### P1-2 向量同步"三重静默"，故障完全无感知

1. `VectorSyncWorker.syncOnApprove` 全方法 try/catch → 仅 log.error（`VectorSyncWorker.java:58-61`，`deactivateOnRejectOrArchive` 同 :68-70），无重试、无补偿、无状态回写。
2. `DesignFingerprintVectorRepository.search` 自查自吞，任何异常返回空列表（:161-168）——上层把"PG 故障"误判为"无命中"。
3. `DesignKnowledgeIndex` 再 catch 回退 STRUCTURAL_3A 通道（:94-96、:164-166），降级对外界无感知。

建议：至少区分"故障"与"无命中"（检索异常上抛或记录指标），同步失败回写指纹状态 + 补一个对账重试任务。

#### P1-3 错误消息入库污染对话上下文

- 流式失败时把 `"AI回复失败：..."` 作为 AI 消息写入 `chat_history`（`SimpleTextStreamHandler.java:43-44`、`JsonMessageStreamHandler.java:64-65`），下次回载时作为正常 AI 回复进入 prompt，污染后续生成。
- 建议：失败消息不入库，或加类型标记并在回载时过滤。

#### P1-4 增量编辑的记忆与主生成链路互不可见

- `IncrementalEditService`（:99-106、:129-136）每次请求新建 `MessageWindowChatMemory`，**无 chatMemoryStore（纯 JVM 内存）、不回载历史、不持久化**，请求结束记忆即弃。
- 它写入 `chat_history` 的消息主生成链路可见，但它自己看不到主链路历史——多轮增量编辑上下文完全丢失，与 `AppController.java:361` "刷新页面不丢失对话"的注释矛盾。

### P2 — 性能与一致性

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| P2-1 | 偏好权重重复查询（N+1） | `UserPreferenceService.java:129,155` + `DesignKnowledgeIndex.java:425-438` | `calculatePreferenceScore` 与 `calculateDislikePenalty` 各自全量查权重表；个性化排序每个候选 item 最多 6 次调用 → 单次检索对同一 userId 反复全量查库。应入口加载一次权重 Map 内存计算（可配合短期缓存） |
| P2-2 | 应用删除不失效记忆 | `AppServiceImpl.java:306-322` | 只删 `chat_history`；Redis 记忆残留至 1h TTL、Caffeine 服务实例残留至 30min。建议删除时同步 evict |
| P2-3 | TTL / 窗口 / 回载量三者矛盾 | `application.yml:29`、`Factory:104,107` | 窗口 100 但冷启动只回载 20 条且 `clear()` 丢弃 Redis 其余内容 → 每次缓存重建记忆"缩水"为 20 条，100 窗口永远填不满；Redis 对话 TTL 1h 与"100 条窗口"的设计意图也不一致 |
| P2-4 | 回载排除最新 1 条依赖隐式时序 | `ChatHistoryServiceImpl.java:139-142` | 依赖"先写用户消息、后建服务"（`AppServiceImpl.java:193`）的约定，未来新增调用点极易踩坑导致当前消息重复入记忆 |
| P2-5 | 每条事件全量 count 判刷新 | `PreferenceUpdateWorker.java:305-310` | 每条行为事件都 `selectCountByQuery(user_id)`；count 与 insert 非原子，并发下漏/重触发（现靠幂等覆盖写兜底）。可改为权重表计数或 Redis 计数器 |
| P2-6 | 裸 `new Thread()` | `AppController.java:90` | 偏好提取用平台线程，与全项目虚拟线程/池化方针不一致（同文件 :383 用的是 `Thread.ofVirtual()`） |

### P3 — 代码质量 / 文档卫生

| # | 问题 | 位置 |
|---|---|---|
| P3-1 | `doRefreshProfileSummary` try 嵌套畸形（内外双层 catch 重复）；`addAllTags`（:285-290）死代码 | `PreferenceUpdateWorker.java:328-378` |
| P3-2 | `memory-system-architecture-evolution.md:5` 引用 `memory-system-review.md`，但该文件此前**不存在**（悬空引用，本文档已补齐） | docs |
| P3-3 | `rag-fix-plans/README.md:8` 仍称已删除的 `UserFingerprintIngestService` 为生产链路；`application-local.yml:100-101` 的 `agent.fingerprint.user-ingest.enabled` 成为孤儿配置 | docs / yml |
| P3-4 | `EmbeddingModelConfig.java:19` 注释称 use-openai 切换 "text-embedding-3-small"，实际切的是智谱 embedding-2，易误导 | config |
| P3-5 | `RedisChatMemoryStoreConfig` password 空串未判空，对无密码 Redis 可能触发 AUTH 失败 | `RedisChatMemoryStoreConfig.java:23-30` |

---

## 4. 优化建议（分优先级）

### 第一梯队：先确认"记忆到底写没写进去"（P0）

1. **核实并修复列名不一致**（P0-1）：对实际库 `SHOW CREATE TABLE user_preference_weight`；统一口径（建议沿用项目驼峰约定改手写 SQL 与 QueryWrapper 列名），修完补一条权重写入的冒烟验证。
2. **核实向量维度**（P0-2）：确认线上 PG 列维度与 embedding 模型实际维度，不一致则 `ALTER TABLE ... ALTER COLUMN embedding TYPE vector(1024)` 并重建向量索引；同时核对各环境 `use-openai` 配置。
3. 这两个问题共同点是"**异常被吞、静默失败**"——修复后建议给两条链路各加一个最低限度的可观测手段（失败计数指标或告警日志升格）。

### 第二梯队：记忆正确性（P1）

4. memory id 改为 `appId_codeGenType`，消除 HTML/VUE 交叉污染（P1-1）。
5. 向量链路去静默化：检索异常与"无命中"分流、同步失败回写状态 + 对账重试（P1-2）。
6. 失败消息不进 `chat_history`（或标记过滤）（P1-3）。
7. 增量编辑记忆与主链路统一：同一 Redis store + 回载，或明确产品语义为"无记忆单轮"并修正注释（P1-4）。

### 第三梯队：性能与生命周期治理（P2）

8. 偏好权重"一次加载、内存计算"改造，顺带评估加短期 Caffeine 缓存（P2-1）。
9. 建立记忆失效矩阵：应用删除 → 同步清 `chat_history` + Redis key + Caffeine 实例（P2-2）。
10. 对齐三个参数：Redis TTL、memory 窗口、冷启动回载量（P2-3）；回载"排除最新 1 条"改为显式参数语义（P2-4）。
11. profileSummary 刷新计数改权重表/Redis 计数（P2-5）；偏好提取线程方式统一为虚拟线程（P2-6）。

### 第四梯队：文档与配置卫生（P3）

12. 清理孤儿配置 `agent.fingerprint.user-ingest.enabled`、更新 `rag-fix-plans/README.md` 与 `EmbeddingModelConfig` 注释（P3-3/3-4）。
13. `RedisChatMemoryStoreConfig` password 判空（P3-5）；`PreferenceUpdateWorker` 嵌套 try 与死代码清理（P3-1）。

---

## 5. 与架构演进文档的对齐关系

架构演进文档（2026-07-10）给出的三阶段路径仍然成立，但本次审查发现的问题**改变了优先级排序**：

| 架构文档路径 | 本次审查结论 |
|---|---|
| ① 记忆正名（Blackboard / ProjectBrandProfile 纳入分层） | 仍然有效、低成本，但**应先做 P0 核实**——分类再对，写不进去也白搭 |
| ② 统一仲裁 `MemoryPriorityResolver` | 仍待启动；注意 `forbiddenRules` 因项目品牌**无写入口**实际恒空，仲裁规则短期只有"prompt > 用户 > 全局"在真实生效 |
| ③ 固化管线 `doConsolidate` | 方向正确，但 **P0-1 意味着现有唯一固化通道（PromptPreferenceExtractor → 权重表）可能一直在空转**，新管线开工前必须先修好旧通道 |

**一句话总结**：架构方向（三层模型 + 固化通道）无需推翻；当务之急是先核实并修复两个"静默全灭"级故障（偏好权重写入、向量同步），让已有的记忆链路真正跑通，再谈编排与仲裁。

---

## 6. 关键类索引

### 情景记忆
| 类 | 位置 | 备注 |
|---|---|---|
| `AiCodeGeneratorServiceFactory` | `agent/codegen/` | Caffeine + MessageWindowChatMemory 装配 |
| `RedisChatMemoryStoreConfig` | `config/` | TTL 3600s |
| `ChatHistoryServiceImpl` | `service/impl/` | 持久化 + 回载 |

### 语义记忆（用户）
| 类 | 位置 | 备注 |
|---|---|---|
| `UserPreferenceService` | `service/` | 读取侧衰减，无缓存 |
| `PromptPreferenceExtractor` | `service/` | LLM 提取正/负向标签 |
| `PreferenceUpdateWorker` | `worker/` | 行为事件 → 权重 |
| `UserPreferenceWeightMapper` | `mapper/` | **P0-1 蛇形 SQL 所在** |

### 语义记忆（项目 / 全局）
| 类 | 位置 | 备注 |
|---|---|---|
| `ProjectBrandService` | `service/` | 只读，无写入口 |
| `DesignKnowledgeIndex` | `service/design/` | 多通道召回 + 个性化排序 |
| `VectorSyncWorker` | `worker/` | 审核通过 → PGvector 同步 |
| `DesignFingerprintVectorRepository` | `repository/` | **P0-2 / P1-2 所在** |

### 工作记忆
| 类 | 位置 | 备注 |
|---|---|---|
| `Blackboard` | `agent/core/` | 单次生成共享状态，健康 |
