# 对话上下文 Token 优化改造计划

> 生成日期：2026-08-02
> 基于代码库 commit `19d8412`
> 定位：针对**对话历史（情景记忆）**这一层的 token 治理落地计划，只解决一个具体工程问题：**长会话下发给 LLM 的上下文必然超限**。不涉及设计指纹 / 用户偏好等长期记忆层。

---

## 目录

1. [问题陈述](#1-问题陈述)
2. [现状链路梳理](#2-现状链路梳理)
3. [问题清单](#3-问题清单)
4. [方案总览](#4-方案总览)
5. [阶段一：记忆压缩（P0）](#5-阶段一记忆压缩p0)
6. [阶段二：增量编辑链路对齐（P1）](#6-阶段二增量编辑链路对齐p1)
7. [阶段三：防御与可观测（P1P2）](#7-阶段三防御与可观测p1p2)
8. [阶段四（可选）：TokenWindow 双保险](#8-阶段四可选tokenwindow-双保险)
9. [落地顺序与验收](#9-落地顺序与验收)
10. [关键类索引](#10-关键类索引)

---

## 1. 问题陈述

当前对话记忆使用 `MessageWindowChatMemory`，**按消息条数**（`maxMessages=100`）限制窗口，不感知 token。但在代码生成场景下，单条 AI 消息承载的是**完整页面代码或完整文件内容**，量级在数千至上万 token。

粗略估算：

- 一轮对话 ≈ 用户短消息 + 一条含完整代码的 AI 消息（按 1 万 token 估）
- 窗口累积到 100 条 ≈ **50 万 token 量级**
- 而 `deepseek-chat` 上下文窗口约 64K（输入 + 输出共享）

**结论：约 20~30 轮就会撞上 `context_length_exceeded`，且失败后错误消息还会被写回历史，进一步恶化。**

注意 `maxTokens=8192`（`ReasoningStreamingChatModelConfig`）限制的是**输出长度**，对输入侧无任何保护作用。

---

## 2. 现状链路梳理

### 2.1 三层存储结构

```
MySQL chat_history   全量历史（前端展示的事实源，永久）
        ↓ 冷启动加载最近 20 条
Redis (RedisChatMemoryStore)   热记忆，带 TTL 3600s
        ↓ 窗口 100 条
MessageWindowChatMemory   发给 LLM 的上下文
```

### 2.2 记忆构建与隔离

`AiCodeGeneratorServiceFactory` 按 `appId + codeGenType` 缓存 AI 服务实例（Caffeine，最大 1000，写后 30 分钟 / 访问后 10 分钟过期）：

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .id(appId)
        .chatMemoryStore(redisChatMemoryStore)
        .maxMessages(100)
        .build();
chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
```

冷启动加载时 `limit(1, maxCount)` 跳过最新 1 条（本轮用户消息已入库，框架会自动追加，避免重复），并在加载前 `clear()` 防止实例复用叠加。

### 2.3 三条生成链路的上下文形态差异

| 链路 | 修改方式 | AI 看"当前代码"的来源 | 记忆里的大 token 源 |
|---|---|---|---|
| `HTML` / `MULTI_FILE` | 每轮**全量重输出**（提示词强制） | **记忆**里最近一条 AI 全文 | `AiMessage.text` 中的代码块 |
| `VUE_PROJECT`（主链路） | 工具调用改盘上文件 | **磁盘**（`readFile` 现读） | 工具参数 `content` + `readFile` 返回的文件全文 |
| 增量编辑（可视化修改） | 同上 | 同上 | 同上，且每轮固定 readDir + readFile |

这个差异决定了压缩策略必须分链路设计（见 §5.3）。

---

## 3. 问题清单

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| P0-1 | 记忆按条数不按 token，单条可达上万 token | `AiCodeGeneratorServiceFactory:100-105` | 长会话必然超限 |
| P0-2 | `readFile` 返回完整文件内容进记忆 | `FileReadTool:34` | VUE 链路最大 token 源 |
| P0-3 | 工具参数携带文件全文 / 新旧片段进记忆 | `FileWriteTool:25-31`、`FileModifyTool:24-32` | 同上 |
| P1-1 | 增量编辑记忆无持久化，每请求重建实例 | `IncrementalEditService:101,131` | 可视化修改**完全无上下文**，连续修改 AI 记不住上次做了什么 |
| P1-2 | 生成失败时把 `"AI回复失败: ..."` 写入历史 | `SimpleTextStreamHandler:41-45`、`JsonMessageStreamHandler:59-63` | 垃圾消息污染上下文 |
| P2-1 | 冷启动加载 20 条与窗口 100 条不一致 | `ChatHistoryServiceImpl:142` | 热 / 冷实例行为不一致，不可预期 |
| P2-2 | 无 token 用量可观测 | - | 撞上限前无预警 |

---

## 4. 方案总览

核心思路：**记忆里只存"索引摘要"，不存全文**。

安全前提是两个事实源始终完整，压缩掉的信息永远可重新获取：

- **MySQL `chat_history`** 保留全文 → 前端展示不受影响
- **磁盘产物** 保留当前代码 → AI 需要细节时用 `FileReadTool` 现读

因此本方案**不需要调用 LLM**（零额外成本、零延迟、可单测），三条链路的输出格式都由提示词强制约定，结构可预测，规则提取即可。

```
阶段一  记忆压缩          P0  ← 唯一必须做的，token 从线性爆炸变恒定
阶段二  增量编辑链路对齐    P1  ← 修复无上下文缺陷
阶段三  防御与可观测       P1/P2 ← 成本极低
阶段四  TokenWindow 双保险  可选  ← 观察实际用量后按需
```

---

## 5. 阶段一：记忆压缩（P0）

### 5.1 新增组件

| 类 | 职责 |
|---|---|
| `ChatMemoryCompressor`（`@Component`） | 压缩规则引擎，纯函数，易单测 |
| `CompressingChatMemoryStore`（`implements ChatMemoryStore`） | 包装 `RedisChatMemoryStore`，在 `updateMessages` 拦截 |

包装器逻辑极薄：

```java
public class CompressingChatMemoryStore implements ChatMemoryStore {
    private final RedisChatMemoryStore delegate;
    private final ChatMemoryCompressor compressor;

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        delegate.updateMessages(memoryId, compressor.compressAll(messages));
    }
    // getMessages / deleteMessages 直接委托
}
```

**`UserMessage` 一律原样保留**——用户消息短，且承载了"本轮想做什么"的意图，是多轮连贯性的关键载体。只压 `AiMessage` 与 `ToolExecutionResultMessage`。

### 5.2 两个拦截点

```
路径 A（运行时）：AI 流式回复完成
  → LangChain4j 自动 add AiMessage 到 MessageWindowChatMemory
  → 内部调用 chatMemoryStore.updateMessages()
  → 【拦截点 1】CompressingChatMemoryStore

路径 B（冷启动）：Caffeine 未命中，重建实例
  → loadChatHistoryToMemory 从 MySQL 读全文
  → 【拦截点 2】加载时逐条压缩后再 chatMemory.add()
```

MySQL 入库路径（两个 StreamHandler 的 `doOnComplete`）完全不碰。

### 5.3 三条压缩规则

#### R1 代码块占位（`HTML` / `MULTI_FILE` 的 `AiMessage.text`）

复用 `MultiFileCodeParser` 的正则思路切块后：

- **代码块外的解释文字**：保留，截断到前 500 字符（提示词本就允许 AI 写总结，这是现成的自摘要）
- **每个代码块替换为占位卡**，按语言提取：

| 块 | 提取规则 | 保留内容 |
|---|---|---|
| html | `<title>(.*?)</title>` | 页面标题 |
| | `<(header\|nav\|main\|section\|footer)[^>]*class="([^"]*)"` | 语义骨架，上限 20 个 |
| css | `(--[\w-]+)\s*:\s*([^;]+);` | 设计变量全量（主色 / 字号阶梯，即"风格约定"载体） |
| | 顶层 `^\.([\w-]+)` | class 名单，上限 30 个 |
| js | `function\s+(\w+)\s*\(`、`const\s+(\w+)\s*=` | 顶层函数名，上限 30 个 |

每块附加行数与字符数，让 AI 知道规模。压缩后形态：

```
已为你完成咖啡店落地页，采用暗色 Bento 布局。

[html index.html, 186行, 已省略] title=「隅咖啡」
骨架: header.nav-sticky > main > section#hero.split-layout, section.menu-scroll, footer
[css style.css, 412行, 已省略] 变量: --bg:#12100e, --primary:#c8956c, --radius:12px
选择器: .hero, .menu-card, .price-grid, .faq-item ...
[js script.js, 97行, 已省略] 函数: initCarousel, validateForm, bindFaqToggle
```

约 200~300 token，替代原来的上万。

#### R2 工具参数截断（`AiMessage.toolExecutionRequests`）

| 工具 | 处理 |
|---|---|
| `writeFile` | `content` → `[已省略, N字符]` |
| `modifyFile` | `oldContent` / `newContent` 各留前 80 字符 + `...[共N字符]`。**不完全省略**——old→new 片段本身是变更语义的最佳载体 |

> **约束**：必须保留 `toolExecutionRequests` 的 `id` / `name` 结构不变。LangChain4j 发请求时工具调用与工具结果需配对校验，结构破坏会导致模型报错。

#### R3 工具结果截断（`ToolExecutionResultMessage`）

| 工具 | 处理 |
|---|---|
| `readFile` | 完整文件内容 → `[已读取 {path}，N字符，内容已省略]`。磁盘是唯一事实源，记忆里的副本无保留意义 |
| `writeFile` / `modifyFile` | 本就是一句话（`"文件写入成功: path"`），不动 |

#### R3' MySQL 历史文本压缩（路径 B 专用）

VUE 链路在 MySQL 里是 `generateToolExecutedResult` 拼的文本。修改轮格式为（`FileModifyTool:76-88`）`[工具调用] 修改文件 path` + `替换前/替换后` 两个代码块。用正则保留工具行、代码块截断为首行 + 占位，使冷启动重建的记忆与运行时压缩形态对齐。

### 5.4 关键策略：全文窗口 = 1

**仅 `MULTI_FILE` / `HTML` 需要**，因为它们改代码依赖记忆里的当前版本；VUE 靠读盘，可全量压缩。

做法：**压缩时跳过消息列表中最后一条 `AiMessage`**。每轮 `updateMessages` 重写时，"最新"身份自动滚动：

```
轮1: [U1][A1 全文]
轮2: [U1][A1 压缩][U2][A2 全文]                  ← A1 降级
轮3: [U1][A1 压缩][U2][A2 压缩][U3][A3 全文]      ← A2 降级
```

修改轮的变更语义一点不丢：意图在 `U2`（原文不压），变更说明在 `A2` 保留的解释文字，被压掉的代码本体已被 `A3` 取代，是死代码。

最终上下文构成：**最近 1 条 AI 全文（~2 万）+ 其余压缩态（100 × ~300）+ 用户消息原文 ≈ 2~3 万 token，不随轮次增长。**

路径 B 同样应用此规则：MySQL 最近 20 条中最新 AI 消息不压缩。

### 5.5 阈值与降级

- **压缩阈值**：单条 `AiMessage` 超过 1500 字符才处理，短消息原样保留
- **解析失败降级**：正则未匹配 / 格式不规整时**不抛异常**，退化为"前 300 字符 + `...[已省略 N 字符]`"。宁可摘要粗糙，绝不能让压缩失败丢消息或阻断生成
- **不可逆性说明**：记忆里压掉的全文不可恢复，但 MySQL 有全量、磁盘有产物。唯一信息损失是 `MULTI_FILE` "三轮前的代码细节"——而那正是窗口淘汰本来也会丢的

### 5.6 改动清单

| 文件 | 改动 |
|---|---|
| 新增 `ChatMemoryCompressor` | R1 / R2 / R3 / R3' + 阈值 + 降级 |
| 新增 `CompressingChatMemoryStore` | 包装 delegate，拦截 `updateMessages` |
| `AiCodeGeneratorServiceFactory:103` | `.chatMemoryStore()` 改传包装后的 store |
| `ChatHistoryServiceImpl.loadChatHistoryToMemory` | 加载时过压缩器（保留最新 AI 全文） |
| StreamHandler / MySQL 表 / 前端 | **零改动** |

---

## 6. 阶段二：增量编辑链路对齐（P1）

`IncrementalEditService` 当前每次请求都 `AiServices.builder(...)` 新建实例，`MessageWindowChatMemory` **未挂任何 store**（`:101,131`）——记忆当场创建、当场丢弃，**可视化修改是完全无状态的**。

两处改造：

1. **实例缓存化**：仿 `AiCodeGeneratorServiceFactory` 的 Caffeine 模式按 `appId` 缓存 `IncrementalEditStreamAi`，避免每请求重建（顺带省去重复绑定工具的开销）
2. **接入持久化记忆**：`chatMemoryProvider` 改用同一个 `CompressingChatMemoryStore`，与主链路共享 `appId` 记忆，使连续修改能看到"上一次改了什么"

> 增量编辑的上下文压力其实**比主链路更大**（每轮固定 `readDir` + `readFile` 全文 + `modifyFile` 新旧片段），阶段一的 R2 / R3 正好覆盖。

---

## 7. 阶段三：防御与可观测（P1/P2）

1. **错误消息不入历史**：两个 StreamHandler 的 `doOnError` 中 `"AI回复失败: ..."` 改为仅落日志 + SSE 推前端，不写 `chat_history`，避免污染后续上下文
2. **超限自愈**：捕获 `context_length_exceeded`，清空该 `appId` 记忆后带当前消息重试一次，避免用户卡死
3. **配置外化**：`maxMessages`、压缩阈值、加载条数提到 `application.yml`（沿用已有 `agent.memory.*` 前缀）
4. **观测埋点**：压缩前后字符数打点 `log.info("记忆压缩 appId={} {}→{} 字符")`，便于验证效果

---

## 8. 阶段四（可选）：TokenWindow 双保险

阶段一之后 token 已可控。若需更严格，可换 `TokenWindowChatMemory`（`maxTokens` 设 24K，用 `OpenAiTokenizer` 近似 DeepSeek，留安全余量）。与压缩叠加：**压缩控单条重量，token 窗口控总量上限**。

建议先跑阶段一观察实际用量再决定，不必一次上齐。

---

## 9. 落地顺序与验收

```
阶段一（P0，收益最大）→ 阶段三防御（成本极低）→ 阶段二（体验提升）→ 阶段四（按需）
```

### 验收标准

| 项 | 标准 |
|---|---|
| Token 恒定性 | 连续 30 轮对话后，发给 LLM 的上下文稳定在 3 万 token 内，不随轮次增长 |
| 功能无回归 | `MULTI_FILE` 修改轮仍能正确全量重输出；VUE 修改轮工具调用链正常 |
| 前端无感 | 历史列表展示的仍是全文，与改造前一致 |
| 冷热一致 | Caffeine 过期重建后，AI 表现与热实例无明显差异 |
| 降级安全 | 构造格式异常的 AI 消息，压缩器不抛异常、不丢消息 |

### 单测要点

- `ChatMemoryCompressor` 是纯函数（`List<ChatMessage>` → `List<ChatMessage>`），各正则规则直接单测
- `CompressingChatMemoryStore` mock delegate 验证调用与入参
- 边界用例：空代码块、无 title、格式错乱、超长单行、工具参数非法 JSON

---

## 10. 关键类索引

| 类 / 文件 | 作用 | 本计划涉及 |
|---|---|---|
| `codegen/service/AiCodeGeneratorServiceFactory` | 按 appId + 类型构建带记忆的 AI 服务 | 阶段一 |
| `config/RedisChatMemoryStoreConfig` | `RedisChatMemoryStore` Bean | 阶段一（被包装） |
| `service/impl/ChatHistoryServiceImpl` | 历史入库 / 冷启动加载 | 阶段一 |
| `codegen/stream/SimpleTextStreamHandler` | HTML / MULTI_FILE 流收口，全文入库 | 阶段三 |
| `codegen/stream/JsonMessageStreamHandler` | VUE 流收口，工具消息拼装入库 | 阶段三 |
| `codegen/tools/FileReadTool` | 返回完整文件内容 | 阶段一 R3 |
| `codegen/tools/FileWriteTool` | 参数含文件全文 | 阶段一 R2 |
| `codegen/tools/FileModifyTool` | 参数含新旧片段 | 阶段一 R2 |
| `service/IncrementalEditService` | 可视化修改，记忆未持久化 | 阶段二 |
| `codegen/parser/MultiFileCodeParser` | 代码块正则切分，R1 可复用其思路 | 阶段一 R1 |
