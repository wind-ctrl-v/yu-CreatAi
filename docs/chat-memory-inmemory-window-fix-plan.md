# 内存优先对话记忆（CompressingWindowChatMemory）缺陷修复方案

> 生成日期：2026-08-10
> 基于代码库 commit `426415f`（上游改动：用 `CompressingWindowChatMemory` 替换 `MessageWindowChatMemory`）
> 关联文档：`docs/chat-context-token-optimization-plan.md`（记忆压缩原始设计）
> 定位：`426415f` 为了解决「模型读到压缩占位符并当成文件内容写入」，把记忆改成**内存不压缩 + Redis 只写快照**。方向可取，但实现上丢了官方 `MessageWindowChatMemory` 的两处必要保护、撤掉了上下文体积的唯一防线，并让上下文超限自愈兜底失效。本文给出问题定位与分级修复方案。
> 落地状态：P0-1~ P0-4、P1-5 ~ P1-9、P2-10 已全部实施，新增 `CompressingWindowChatMemoryTest`（10 例）覆盖协议保护与分层渲染。

---

## 目录

1. [改动回顾](#1-改动回顾)
2. [问题清单](#2-问题清单)
3. [修复方案总览](#3-修复方案总览)
4. [P0-1 SystemMessage 去重](#4-p0-1-systemmessage-去重)
5. [P0-2 淘汰时的工具消息配对保护](#5-p0-2-淘汰时的工具消息配对保护)
6. [P0-3 上下文与堆内存体积治理](#6-p0-3-上下文与堆内存体积治理)
7. [P0-4 上下文超限自愈失效](#7-p0-4-上下文超限自愈失效)
8. [P1 一致性与开销修复](#8-p1-一致性与开销修复)
9. [P2 注释与可观测对齐](#9-p2-注释与可观测对齐)
10. [落地顺序与验收](#10-落地顺序与验收)
11. [关键类索引](#11-关键类索引)

---

## 1. 改动回顾

`426415f` 的链路变化：

```
改动前： MySQL 全量 → 冷加载(过压缩器) → Redis(压缩) ⇄ MessageWindowChatMemory 每轮回读 → LLM
改动后： MySQL 全量 → 冷加载(全文) → CompressingWindowChatMemory(JVM 内存全文) → LLM
                                ↓ 每次 add 压缩后写
                                       Redis(压缩快照，无人回读)
```

核心诉求是对的：`MessageWindowChatMemory` 每轮从 store 回读的是**已压缩**的消息，`writeFile` 的 content 被替换成占位符后，模型会把占位符当成文件真实内容再写回磁盘，产出空文件 / 残缺文件。切成「内存全文」确实断掉了这条污染路径。

配套改动（均为正向保险，无害）：

- `ChatMemoryCompressor` 占位符文案改为带禁令的提示（`:348`、`:389`）
- `CoderAgent.appendFileWritingRule()` 在提示词尾部追加「先 readFile 再写、必须整文件写入」铁律（`:331-336`，调用点 `:173`）

问题集中在新记忆实现本身、被顺带撤掉的体积防线，以及依赖旧形态的超限自愈逻辑。

---

## 2. 问题清单

编号前缀表示优先级，序号全局唯一（便于交叉引用）。

| 编号 | 严重度 | 问题 | 影响 |
|---|---|---|---|
| P0-1 | 高（必现） | `add()` 未做 `SystemMessage` 去重 | 系统提示词每轮追加一份，窗口内堆积 N 份，token 暴涨、指令位置错乱 |
| P0-2 | 高（必现） | `trimToCapacity()` 直接 `removeFirst()` | 产生孤儿 `ToolExecutionResultMessage`，OpenAI 兼容接口 400 |
| P0-3 | 高 | 内存窗口全文 + `max-messages=100` | 上下文可达数十万 token 触发 `context_length_exceeded`；三个 Caffeine 缓存共 3000 实例挂全文窗口，堆占用 GB 级 |
| P0-4 | 高 | 超限自愈只删 Redis，不动JVM 内存窗口 | `AiCodeGeneratorFacade` 的「清空记忆后重试一次」对新架构零作用，重试必然二次超限 |
| P1-5 | 中 | Redis 快照只写不读，且冷加载 `clear()` 会删掉它 | 每次 add 付出「全窗口压缩 + 全量写 Redis」的成本，收益为零 |
| P1-6 | 中 | 同一 `appId` 的多实例共用同一 Redis key 全量覆盖 | 主链路与增量编辑链路快照互相踩脏 |
| P1-7 | 中 | `IncrementalEditService` 未冷加载、也不回读 Redis | 注释宣称的「共享 appId 记忆」失效，增量编辑每次都是空上下文 |
| P1-8 | 中 | 冷加载逐条 `add`，每条触发一次全窗口压缩 + Redis 写 | 20 条历史 = 20 次全量压缩 + 20 次 Redis 写，实例创建被拖慢 |
| P1-9 | 低 | `snapshot()` 在 `synchronized` 内做网络 IO | 流式高频 add 时持锁阻塞 |
| P2-10 | 低 | 注释 / 配置说明 / 指标语义过期 | 误导后续维护 |

### 2.1 P0-1 证据

`langchain4j 1.1.0` 的 `DefaultAiServices` 每次调用都会先把系统消息塞进记忆，再追加用户消息，最后 `messages.addAll(chatMemory.messages())`（字节码 `DefaultAiServices$1` 偏移 524→565：`hasChatMemory` → `systemMessage.ifPresent(chatMemory::add)` → `chatMemory.add(userMessage)` → `addAll(chatMemory.messages())`）。

官方 `MessageWindowChatMemory.add()` 对此有专门处理：`findSystemMessage(messages)`，若已存在且不 `equals` 则先 `remove` 旧的再 add。新实现没有：

```45:49:src/main/java/com/yu/yuaifather/codegen/memory/CompressingWindowChatMemory.java
    public synchronized void add(ChatMessage message) {
        messages.addLast(message);
        trimToCapacity();
        snapshot();
    }
```

而 `AiCodeGeneratorService` 有 5 处 `@SystemMessage(fromResource = ...)`，`IncrementalEditService` 有 2 处内联 `@SystemMessage`，VUE 系统提示词量级为几 KB。多轮后窗口里会散落多份重复系统提示词。

### 2.2 P0-2 证据

官方 `ensureCapacity(List, int)` 在移除首条后会判断：被移除的若是 `AiMessage.hasToolExecutionRequests()`，则继续移除紧随其后的 `ToolExecutionResultMessage`（字节码中 `hasToolExecutionRequests` → `List.get` → `List.remove` 的连续调用）。这是为了满足 OpenAI 协议约束：`role=tool` 的消息必须紧跟在带 `tool_calls` 的 assistant 消息之后。

新实现只有：

```70:74:src/main/java/com/yu/yuaifather/codegen/memory/CompressingWindowChatMemory.java
    private void trimToCapacity() {
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
    }
```

VUE 链路每轮产生大量 `readFile / writeFile / modifyFile` 工具消息对，窗口打满后淘汰点大概率落在消息对中间，导致请求被服务端拒绝。

### 2.3 P0-3 证据

`max-messages: 100` 这个值是按「压缩后单条 ~300 字符」设定的，配置注释现已失真：

```151:155:src/main/resources/application.yml
  memory:
    # 记忆窗口最大消息条数（压缩后单条 ~300 字符，上下文总量不随轮次增长）
    max-messages: 100
    # 冷启动从 MySQL 加载的历史条数（跳过最新 1 条本轮用户消息）
    load-count: 20
```

改为内存全文后，单条 `writeFile` 的 content 可达数十 KB，100 条窗口的上下文规模回到 `chat-context-token-optimization-plan.md` 第 1 节描述的「50 万 token 量级」原点。同时 `serviceCache`(≤1000) + `editAiCache`(≤1000) + `editStreamAiCache`(≤1000) 每个实例各持有一份全文 `LinkedList`，堆压力不可控。

### 2.4 P0-4 证据

```56:68:src/main/java/com/yu/yuaifather/codegen/service/AiCodeGeneratorFacade.java
        return Flux.defer(() -> doGenerateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId))
                .onErrorResume(error -> {
                    if (!isContextLengthExceeded(error)) {
                        return Flux.error(error);
                    }
                    log.warn("检测到上下文超限，清空记忆后重试一次 appId={} err={}", appId, error.getMessage());
                    try {
                        compressingChatMemoryStore.deleteMessages(appId);
```

旧形态下 `MessageWindowChatMemory.messages()` 每轮从 store 回读，删 Redis 等于清空模型上下文，重试才有意义。新形态下上下文完全来自 `CompressingWindowChatMemory` 的 JVM `LinkedList`，删 Redis 对下一次请求毫无影响 → **超限后重试用的还是同一份超限上下文，必然二次失败**，还多打一次无效模型调用。

---

## 3. 修复方案总览

保留「内存是事实源、不被压缩污染」这个正确方向，补齐两处协议保护，把体积防线从「store 压缩」上移到「渲染时分层压缩」，并让自愈路径重新作用于内存：

```
JVM 内存：全量真实消息（事实源，绝不压缩）
    ↓ messages() 渲染时分层
最近 N 轮（按 UserMessage 边界切分）→ 原样全文（保证 writeFile / readFile 内容真实可用）
更早消息 → 过 ChatMemoryCompressor（占位符只出现在远端历史，模型不会误当成"当前文件内容"）
    ↓ 再过字符预算裁剪（尾部优先保留 + 工具消息配对保护）
LLM 上下文
```

要点：

1. **切分必须按轮次边界，不能按条数**。模型写文件依赖的是**本轮**的 `readFile` 结果与自己刚写的内容，而 VUE 单轮工具消息常达十几条；按固定条数切分会把本轮早期的 `readFile` 结果压成占位符，等于把 `426415f` 修掉的复读风险重新放回来。
2. 压缩结果做**脏标记缓存**，避免 `messages()` 每轮重复跑正则。
3. Redis 定位明确为**纯备份**：不回读、不在冷加载路径被删除、快照频率从「每条 add」降为「每轮结束」。
4. 自愈动作从「删 Redis」改为「作用于内存窗口 + 失效 Caffeine 实例」。

---

## 4. P0-1 SystemMessage 去重

`add(ChatMessage)` 增加与官方一致的语义：

```java
@Override
public synchronized void add(ChatMessage message) {
    if (message instanceof SystemMessage systemMessage) {
        // 与 MessageWindowChatMemory 对齐：同一份系统提示词只保留一条，内容变更则替换
        Optional<SystemMessage> existing = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .findAny();
        if (existing.isPresent()) {
            if (existing.get().equals(systemMessage)) {
                return;                 // 内容一致：直接丢弃，不重复追加
            }
            messages.remove(existing.get());
        }
    }
    messages.addLast(message);
    trimToCapacity();
    onMessagesChanged();
}
```

`add(Iterable)` 必须复用单条逻辑（不要绕过去重），循环结束后统一调用一次 `onMessagesChanged()`。

**`onMessagesChanged()` 的定义（避免阶段化落地时行为断裂）**：置空渲染缓存 `renderedCache`，并按当前的快照策略触发 `snapshot()`。阶段 1 单独上线时它就等价于原来的 `snapshot()`；阶段 2 引入渲染缓存、阶段 3 改快照频率时只改这个方法内部，`add` 路径不动。**不要在阶段 1 里把 `snapshot()` 直接删掉**，否则 Redis 备份会静默停写。

验收：连续 3 轮 add同一 `SystemMessage` 后，`messages()` 中 `SystemMessage` 数量恒为 1。

---

## 5. P0-2淘汰时的工具消息配对保护

`trimToCapacity()` 对齐官方 `ensureCapacity`，并额外保证 `SystemMessage` 不被淘汰：

```java
private void trimToCapacity() {
    while (messages.size() > maxMessages) {
        ChatMessage evicted = removeFirstEvictable();
        if (evicted == null) {
            break;                      // 只剩 SystemMessage，停止淘汰，避免丢提示词
        }
        // 带 tool_calls 的 AiMessage 被淘汰后，其后续 tool 结果消息必须一起淘汰，
        // 否则请求里会出现孤儿 role=tool 消息，服务端直接 400
        if (evicted instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
            while (!messages.isEmpty() && messages.getFirst() instanceof ToolExecutionResultMessage) {
                messages.removeFirst();
            }
        }
    }
}
```

`removeFirstEvictable()`：跳过 `SystemMessage` 取第一条可淘汰消息并移除，全是 `SystemMessage` 时返回 `null`。

另外在 `messages()` 渲染出口加两道**兜底**：

1. 首部（跳过 `SystemMessage` 后）若存在 `ToolExecutionResultMessage`，一并丢弃 —— 防御冷加载、外部 add、预算裁剪等其他路径引入的孤儿消息。
2. `SystemMessage` 提到列表首位 —— 去重逻辑用 `addLast` 追加新版本，会把系统提示词冲到窗口尾部甚至夹在工具消息之间（官方实现也有这个特征），渲染时归位更符合协议习惯。

验收：构造 `[User, Ai(tool_calls), ToolResult, User, ...]` 且 `maxMessages` 卡在 `Ai(tool_calls)` 与 `ToolResult` 之间，断言渲染结果中不存在孤儿 `ToolExecutionResultMessage`。

---

## 6. P0-3 上下文与堆内存体积治理

### 6.1 依赖改造（前置）

`CompressingWindowChatMemory` 当前只注入了 `CompressingChatMemoryStore`（`:28-32`），分层渲染需要直接使用压缩器，因此：

- 构造函数新增 `ChatMemoryCompressor compressor` 参数；
- 两个装配点同步多注入一个 bean：`AiCodeGeneratorServiceFactory:114`、`IncrementalEditService:143` / `:159`；
- 建议顺手把这三处重复的构造逻辑收敛到一个工厂方法（如 `ChatMemoryFactory.create(memoryId)`），避免参数继续膨胀时三处各改一遍。

### 6.2 分层渲染

```java
@Override
public synchronized List<ChatMessage> messages() {
    if (renderedCache != null) {
        return new ArrayList<>(renderedCache);
    }
    List<ChatMessage> snapshot = new ArrayList<>(messages);
    int fullTextFrom = resolveFullTextFrom(snapshot);
    List<ChatMessage> rendered = new ArrayList<>(snapshot.size());
    // 远端历史过压缩器（compressAll 保持 id/name 结构不变，工具调用配对不受影响）
    rendered.addAll(compressor.compressAll(snapshot.subList(0, fullTextFrom)));
    rendered.addAll(snapshot.subList(fullTextFrom, snapshot.size()));
    rendered = normalize(applyCharBudget(rendered));   // 预算裁剪 + 孤儿清理 + System 归位
    renderedCache = rendered;
    return new ArrayList<>(rendered);
}

/** 按轮次边界定位全文段起点：从尾部回溯第 fullTextTurns 个 UserMessage */
private int resolveFullTextFrom(List<ChatMessage> snapshot) {
    int turns = 0;
    for (int i = snapshot.size() - 1; i >= 0; i--) {
        if (snapshot.get(i) instanceof UserMessage && ++turns == fullTextTurns) {
            return i;
        }
    }
    return 0;
}
```

若全文段条数超过 `full-text-max-messages` 兜底上限（极端长轮次），收缩起点时**必须对齐消息对边界**：起点不得落在 `ToolExecutionResultMessage` 上，需前移到其对应的 `AiMessage(tool_calls)` 之前。按 `UserMessage` 定位天然安全，只有兜底收缩才需要这步校正。

新增配置（沿用 `agent.memory.*` 命名空间）：

```yaml
  memory:
    # 内存窗口最大条数（内存存全文，条数需显著下调）
    max-messages: ${AGENT_MEMORY_MAX_MESSAGES:60}
    # 发给模型时保持全文的最近轮次数（以 UserMessage 为轮次边界）
    full-text-turns: ${AGENT_MEMORY_FULL_TEXT_TURNS:2}
    # 全文段条数兜底上限，防止单轮工具消息过多把预算吃光
    full-text-max-messages: ${AGENT_MEMORY_FULL_TEXT_MAX_MESSAGES:24}
    # 发给模型的上下文字符预算，超出则从最早消息开始丢弃
    char-budget: ${AGENT_MEMORY_CHAR_BUDGET:80000}
```

**`char-budget` 取值依据**：字符/token 比随内容差异很大 —— 代码与英文约 3.5~4 字符/token，中文约 1.5~1.7 字符/token；记忆里两者混杂，保守按 **2 字符/token** 折算。`80000` ≈ 40K token，为 64K 上下文窗口与 `maxTokens=8192` 的输出预留了余量。若换更大窗口的模型再上调，不要沿用「1token ≈ 1.5 字符」这种只对纯中文成立的估算。

`applyCharBudget` 从尾部往前累加字符，超预算即停止；`SystemMessage` 始终保留；被截断处产生的孤儿 tool 消息按 §5 规则清理。

**已知叠加效应（不影响正确性，仅影响压缩率读数）**：`ChatMemoryCompressor.compressAll` 自带「全文窗口=1」逻辑（`findLastTextAiMessageIndex`，`:399-406`），会对传入列表的最后一条含正文 `AiMessage` 跳过 R1，因此压缩段实际会多留一条全文。另外切分点若把 `AiMessage(readFile 请求)` 留在压缩段、`ToolResult` 落在全文段，`collectReadFilePaths` 取不到路径，占位文案会退化为「未知文件」—— 按 `UserMessage` 边界切分时不会出现，兜底收缩时按上述规则对齐即可规避。

### 6.3缓存与堆占用

- `renderedCache` 由 `onMessagesChanged()` 在任何 `add / clear` 后置空，保证不会返回过期视图。
- `serviceCache` / `editAiCache` / `editStreamAiCache` 的 `maximumSize=1000` 在「实例持有全文窗口」的新形态下过大，建议下调至 200，写后过期从 30 分钟压到 15 分钟；或改用 `Caffeine.weigher` 按窗口字符数计权。
- `CompressingWindowChatMemory` 增加单条消息硬上限（如 200KB，入内存前先截断），防止单个超大 `writeFile` 直接把窗口顶爆。

验收：`max-messages=60`、`char-budget=80000` 下跑 30 轮 VUE 生成，`messages()` 字符总量稳定在预算内且不随轮次增长。

---

## 7. P0-4 上下文超限自愈失效

自愈动作必须作用在**内存窗口**上，`AiCodeGeneratorFacade` 的重试分支改为：

1. 调用记忆实例的 `clearMemoryOnly()`（见 §8.1）清空 JVM 窗口，而不是 `store.deleteMessages(appId)`；
2. 由于 Facade 拿不到记忆实例，在 `AiCodeGeneratorServiceFactory` 上新增 `invalidate(long appId)`：遍历该 appId 的全部 `codeGenType` 缓存键，逐个 `serviceCache.invalidate(...)`，下次请求重建实例并走 MySQL 冷加载（冷加载条数 `load-count=20` 天然比超限窗口小）；
3. 保留删 Redis 快照的动作（备份已失真，删掉合理），但它不再是自愈的关键步骤。

注意：§6 的分层渲染上线后超限概率大幅下降，但这条兜底仍必须修好—— 否则单条超大消息（如一次性200KB 的 `writeFile`）仍会让会话进入「必然连续失败」的死状态。

验收：注入一个超长历史使首次请求必然超限，断言重试请求的上下文规模明显小于首次，且第二次成功。

---

## 8. P1 一致性与开销修复

### 8.1 P1-5 明确 Redis 为纯备份

- `ChatMemory#clear()` 语义是「清空记忆 + 删除持久化」，冷加载路径复用它会连备份一起删。给 `CompressingWindowChatMemory` 增加 `clearMemoryOnly()`，`ChatHistoryServiceImpl.loadChatHistoryToMemory` 改调它（`ChatHistoryServiceImpl:163`）。
- 真正需要删 Redis 的地方仍调 `clear()`（如应用删除 / 历史清理）。
- `CompressingChatMemoryStore.getMessages()` 目前零调用者，保留但在类注释标注「仅供人工排障/ 未来恢复用」，避免被误认为热路径。

### 8.2 P1-6 memoryId 隔离

主链路按 `appId + codeGenType` 建实例，增量编辑另有两套实例，却都用 `memoryId = appId` 做全量覆盖写，快照互相踩脏。改为携带用途后缀：

- 主链路：`appId + "#" + codeGenType.getValue()`
- 增量编辑：`appId + "#edit"`

**连带修改点（不可漏）**：所有按裸 `appId` 操作 Redis key 的代码都会失配，需要同步改为按前缀批量处理或复用同一 id 生成方法 —— 当前已知的是 `AiCodeGeneratorFacade:63` 的 `compressingChatMemoryStore.deleteMessages(appId)`（§7 会重写这段），以及后续任何「按 appId 清理记忆」的入口。建议把 id 生成收敛为 `ChatMemoryIds.of(appId, purpose)` 单一出口，避免字符串拼接散落。旧 key靠 TTL 自然过期，无需迁移。

### 8.3 P1-7 增量编辑链路补冷加载

`IncrementalEditService.createEditAi / createEditStreamAi`（`:142-149`、`:158-165`）建完记忆后补一次 `chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, historyLoadCount)`，否则「连续可视化修改能看到上一次改了什么」这句注释不成立。若确定该链路不需要历史，则应删掉误导性注释（`IncrementalEditService:46-49`）。

### 8.4 P1-8 / P1-9 快照开销

- 冷加载改用批量 `add(Iterable)`（`ChatHistoryServiceImpl:166-169` 的循环改为 `chatMemory.add(messages)`），把 20 次全窗口压缩 + 20 次 Redis 写降为 1 次。
- `snapshot()` 移出 `synchronized` 临界区：锁内只 `new ArrayList<>(messages)` 取副本，锁外执行压缩与 Redis 写。
- 快照频率从「每条 add」降为「每轮结束或每 N 条」，压缩是 CPU 密集的正则解析，流式场景不应逐条触发。
- `snapshot()` 空列表时当前既不写也不删（`CompressingWindowChatMemory:81`），补 `deleteMessages` 或显式写空，避免残留旧快照。

---

## 9. P2 注释与可观测对齐

| 位置 | 现状 | 应改为 |
|---|---|---|
| `AiCodeGeneratorServiceFactory:115` | 「加载时过压缩器，保留最新一条 AI 全文」 | 冷加载为 MySQL 全文，不过压缩器 |
| `CompressingChatMemoryStore:21` | 类注释仍描述 `MessageWindowChatMemory` | 改为 `CompressingWindowChatMemory` 的快照落盘拦截点 |
| `application.yml:152` | 「压缩后单条 ~300 字符」 | 说明内存存全文，条数 /轮次 / 预算三个参数的新含义 |
| `memory.compress.chars` 指标 | 曾代表模型上下文压缩率 | 现仅代表 Redis 快照压缩率；模型上下文体积需新增 `memory.context.chars` 指标（在 `messages()` 渲染出口打点） |
| `docs/chat-context-token-optimization-plan.md` | 描述的是 store 压缩形态 | 追加一节说明形态已切换为「内存全文 + 渲染时分层压缩」 |

另：`ChatMemoryCompressor:348`、`:389` 的禁令文案与 `CoderAgent` 的写入铁律在分层渲染下仍有意义（远端历史依然会向模型暴露占位符），建议保留。但 HTML / MULTI_FILE 链路的系统提示词里没有对应铁律，而这两条链路恰恰依赖「记忆里的上一版全文」做全量重输出，建议同步补一句同等约束。

---

## 10. 落地顺序与验收

| 阶段 | 内容 | 阻塞级别 |
|---|---|---|
| 1 | P0-1 SystemMessage 去重、P0-2 配对保护（注意保留 `snapshot()` 调用） | 必须先合，否则长会话必然 400 / token爆 |
| 2 | P0-3 依赖改造 + 分层渲染 + 配置调整 + 缓存收紧；P0-4 自愈改造 | 与阶段 1 同批上线为宜 |
| 3 | P1-5 / P1-6 / P1-7 / P1-8 / P1-9 | 下一批 |
| 4 | P2-10 注释、指标、文档 | 随手 |

### 单测清单（新增 `CompressingWindowChatMemoryTest`）

1. 连续多轮 add 同一 `SystemMessage`，`messages()` 中仅 1 条。
2. `SystemMessage` 内容变更后，旧的被替换而非并存。
3. 窗口溢出且淘汰点落在 `Ai(tool_calls)` / `ToolResult` 之间时，渲染结果无孤儿 tool 消息。
4. 窗口溢出至只剩 `SystemMessage` 时不再淘汰。
5. 最近 `full-text-turns` 轮内的消息保持全文（断言 `writeFile` 的 content 未被替换为占位符），且**同一轮内的早期 `readFile` 结果也未被压缩**（这是 P0-3 方案的关键回归点）。
6. 超 `char-budget` 时从最早消息开始丢弃，渲染结果字符数 ≤ 预算。
7. 渲染出口 `SystemMessage` 位于首位。
8. `clearMemoryOnly()` 不触发 `store.deleteMessages`，`clear()` 触发。
9. `store` 抛异常时 `add` 不抛出（快照失败不阻断生成）。
10. `renderedCache` 在 add 后失效（两次 `messages()` 结果随内存变化而变化）。

### 回归验证

- VUE 长会话 30 轮以上：无 `context_length_exceeded`、无 tool 消息 400、生成文件内容非空且完整。
- 单条超大 `writeFile` 触发超限后，自愈重试能成功（P0-4）。
- 连续两次可视化增量编辑，确认第二次能感知第一次的修改。
- Caffeine 过期后（或重启后）继续对话，确认冷加载恢复上下文且 Redis 备份未被误删。
- 观察 `memory.context.chars` 是否随轮次收敛。

---

## 11. 关键类索引

| 类 / 文件 | 职责 | 本次涉及 |
|---|---|---|
| `codegen/memory/CompressingWindowChatMemory.java` | 内存优先窗口记忆 | P0-1、P0-2、P0-3、P1-8、P1-9 主战场 |
| `codegen/memory/CompressingChatMemoryStore.java` | Redis 快照拦截 + 压缩指标 | P1-5、P2-10 |
| `codegen/memory/ChatMemoryCompressor.java` | 纯规则压缩器 | 分层渲染复用；占位符文案 |
| `codegen/service/AiCodeGeneratorFacade.java` | 生成入口 + 超限自愈重试 | P0-4、P1-6 |
| `codegen/service/AiCodeGeneratorServiceFactory.java` | 主链路记忆装配 + 冷加载 | P0-3 注入、P0-4 `invalidate`、P1-6、P2-10 |
| `service/IncrementalEditService.java` | 增量编辑链路记忆装配 | P0-3 注入、P1-6、P1-7 |
| `service/impl/ChatHistoryServiceImpl.java` | MySQL 冷加载 | P1-5（clear 语义）、P1-8（批量 add） |
| `agent/workers/CoderAgent.java` | 写入铁律提示词 | P2-10（其他链路补齐） |
| `src/main/resources/application.yml` | `agent.memory.*` 配置 | P0-3 新增项、P2-10 注释 |
