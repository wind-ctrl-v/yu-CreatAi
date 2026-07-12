# 图片查找流水线分析与重构方案

> 本文档汇总了对当前"图片查找 → 代码生成 → 占位符回填"流水线的分析结论、问题梳理、方案评估与改动计划，供后续开发参考。

---

## 一、当前完整流程

图片处理分为**两段式机制**，分布在代码生成的前后：

```
┌─ 阶段0 规划 ──────────────────────────────────────────────┐
│ PlanningAgent(LLM)                                          │
│   还没有任何代码，仅凭需求"预判"要哪些图                    │
│   产出 plan.imageSlots[{slotId, keyword, style, usedIn}]    │
└────────────────────────────────────────────────────────────┘
                        ↓
┌─ 阶段1 预搜图（代码生成之前）─────────────────────────────┐
│ DesignContextAssembler / ImageSearchTool.execute            │
│   对每个 slot 的 keyword 并发调 search()                    │
│   ① 结果存进 imageMap（key=keyword）                        │
│   ② search() 顺带把"高质"结果写进 Caffeine 缓存             │
│   ③ imageMap 存进黑板 + PromptContext 字段                  │
└────────────────────────────────────────────────────────────┘
                        ↓
┌─ 阶段2 代码生成（LLM 写占位符）───────────────────────────┐
│ CoderAgent：要求"图片一律写 @@IMG:关键词@@"                 │
│   LLM 此刻才有真实页面语境，按当前位置现写关键词：          │
│   <img src="@@IMG:coffee shop interior@@">                  │
│   ★这个关键词与阶段0预判的常常不一样★                     │
└────────────────────────────────────────────────────────────┘
                        ↓
┌─ 阶段3 回填（代码生成之后，真正生效的搜图）───────────────┐
│ CoderAgent.onCompleteResponse                               │
│  → ImagePlaceholderReplacer.replaceAll(appId)               │
│    扫所有文件，正则匹配 @@IMG:kw@@                          │
│    对每个 kw 调 ImageSearchEngine.search(kw)：              │
│      · 缓存命中 → 复用阶段1的图                             │
│      · 缓存未命中 → 重新多源搜索(Pixabay→…→picsum)          │
│    替换成真实 URL 写回文件                                  │
└────────────────────────────────────────────────────────────┘
```

### 关键机制说明

- **位置对齐**：靠占位符的**物理位置**天然对齐——占位符写在哪个 `<img>`，图就渲染在哪个位置，100% 可靠。
- **预搜图与回填的衔接**：不是直接传递 `imageMap` 对象，而是通过 `ImageSearchEngine` 内部的 **Caffeine 缓存**（key = 清洗后的 keyword）间接衔接。
- **图片实体来源**：缓存里存的是 URL 字符串，不是图片本身。URL 来自 `doSearch()` 的 6 个外部渠道（按优先级）：Pixabay → Unsplash → Pexels → 文生图(转存**本地静态目录** `LocalImageStorage`，非 COS) → loremflickr(语义兜底) → picsum(随机兜底)。图片实体托管在第三方 CDN 或本地静态目录。

### 涉及的关键代码

| 组件 | 路径 | 职责 |
|---|---|---|
| `ImageSearchTool` | `tool/system/ImageSearchTool.java` | 预搜图（消费 imageSlots，产出 imageMap） |
| `ImageSearchEngine` | `tool/engine/ImageSearchEngine.java` | 真正的搜图引擎（多源 + 缓存 + SSRF 防护） |
| `ImagePlaceholderReplacer` | `service/design/ImagePlaceholderReplacer.java` | 占位符回填（扫描 `@@IMG:kw@@` 替换 URL） |
| `TextToImageEngine` | `tool/engine/TextToImageEngine.java` | 文生图兜底 + 本地转存 |
| `DesignContextAssembler` | `service/design/DesignContextAssembler.java` | V2 上下文装配（内含预搜图调用） |
| `GenerationPlan.ImageSlot` | `model/dto/agent/GenerationPlan.java` | 图片槽数据结构 |

---

## 二、核心问题梳理

### 根因：全程只用"关键词文本"当主键，丢掉了结构化信息

`plan.imageSlots` 有 `slotId / keyword / style / usedIn` 四个字段，但真正贯穿全链路的**只有 keyword**：
- `imageMap` 的 key 是 keyword 而非 slotId；
- 回填只认占位符里的 keyword；
- `slotId`（位置标识）、`usedIn`（位置描述）、`style`（photo/illustration）在最终注入时**全部未被使用**。

### 主要问题清单

1. **预搜关键词 ≠ 回填关键词，预搜图基本白做**
   Prompt 只要求 LLM 写的关键词与 plan"语义等价"（非强制一致）。一旦清洗后不同，缓存打不中，回填重新搜——预搜那批图不被采用，纯浪费。

2. **同一 keyword 全站共用一张图 / 缺乏多样性**
   缓存按 keyword，且各图源都取第 0 条结果。相同关键词的多个位置会出现完全相同的图。

3. **`style` 字段形同虚设**
   搜图时写死 `image_type=photo`，`style=illustration` 无法生效。

4. **无图文相符校验**
   取第一条结果就用，无相关性判断。CriticAgent 只查是否残留 `@@IMG@@`，不判断图是否贴合。兜底末端 picsum 是纯随机图。

5. **降级静默、不可观测**
   loremflickr/picsum 兜底不打标、不上报，真实图与随机兜底图混在产物里无法区分。

6. **`imageMap` 是死数据（无人消费）**
   经代码核查：`getImageMap()` 除定义外**无任何业务调用点**；`ContextCompressTool.buildFullPrompt` 未拼接 imageMap；`CoderAgent`/`PageGenerateAgent` 均不消费。imageMap 产出后躺在黑板里没人读。

7. **回填串行、性能欠佳**
   预搜图并发，但回填 `replaceAll` 是串行 for 循环，缓存不命中时网络往返串行累加。

8. **全局缓存跨项目"串图"**
   Caffeine 缓存 key 仅为 keyword，不含 appId/userId，不同项目同关键词会拿到同一张图。

9. **占位符协议脆弱、依赖 LLM 守约**
   若 LLM 未按格式写或 keyword 带 `@`，占位符会原样残留在页面上变成乱码文本，无补偿机制。

10. **外部 URL 直接写入产物，长期可用性 + 版权风险**
    除文生图外，第三方图库 URL 直接写进代码，第三方失效/加防盗链会导致页面图片集体失效。

11. **文生图本地存储缺乏清理与多实例支持**
    `maxBytes` 只限单文件，无总量上限/清理策略；本地文件多实例不共享。

---

## 三、关于"预搜图"的专项结论

### 时序矛盾（根本不合理之处）

预搜图试图"在知道每个位置需要什么之前，就把图备好"，但"每个位置需要什么"这一信息要到**代码生成时**才产生。因此：

- 预搜图用的是 PlanningAgent 的**粗预判关键词**（无语境）；
- 真正贴合位置的是 CoderAgent 在**有语境时现写的关键词**；
- 预搜图搜的图天生不针对任何具体位置，无法保证"符合每个位置的需求"。

### 收益评估：投入产出比极低

- **收益 A（缓存预热）**：仅当"预搜关键词 == 回填关键词 且 是高质图 且 在 TTL 1 小时内"三条同时成立才生效，经常落空。
- **收益 B（给 LLM 参考 imageMap）**：**完全没兑现**——imageMap 未注入任何 Prompt，也无代码消费。
- **成本（每次必付）**：一整轮并发外部图库请求、消耗 API 配额、网络延迟、一个没人用的 imageMap。

### 结论：预搜图可直接砍掉

回填搜图（阶段3）才是真正有效的环节。砍掉预搜图只保留回填，几乎不损失效果，还能省一轮 API 调用并移除 imageMap 死链路。

---

## 四、方案评估：为什么否决"slotId 强绑定"

曾考虑将占位符改为 `@@IMG:slotId@@`、回填按 slotId 从 plan 取 keyword/style 实现位置强绑定。**经工程视角复盘，该方案被否决**，理由：

1. **解决的是伪问题**：位置对齐已由占位符物理位置 100% 解决，不需要 slotId。
2. **对真问题（内容贴合）是倒退**：强制用 plan 的粗预判关键词，等于放弃 CoderAgent 更贴合语境的关键词。
3. **核心卖点自相矛盾**：其最大收益"复用预搜成果"依赖保留预搜图，而预搜图已判定该砍。
4. **可靠性/可维护性下降**：把可靠的物理对齐换成"LLM 从封闭清单选对 slotId 并填对位置"的认知任务，易错、需兜底、占位符不再自解释、难调试。
5. **顺带收益都有更轻量解法**：重图→取不同 index；串图→缓存 key 加 appId；style→搜图传参。

---

## 五、推荐方向（低风险、可增量上线）

1. **砍掉预搜图** —— 去掉时机错位环节 + imageMap 死链路（详见第六节计划）。
2. **保留 `@@IMG:keyword@@` 占位符协议** —— 位置对齐免费可靠，信任 CoderAgent 带语境的关键词。
3. **在引擎/回填层做正交小增强**（每项可独立上线）：
   - style 传参（让插画需求生效）；
   - 搜索结果 index 轮换去重（治重图）；
   - 缓存 key 加 appId（治串图）；
   - 兜底图打标 + 回填后强制扫描残留占位符兜底（治残留、提升可观测性）；
   - 可选：用位置附近文本做轻量相关性校验（治内容贴合）。

---

## 六、砍掉预搜图 — 改动计划

### 安全前提（已核查）

- **回填完全独立**：`ImagePlaceholderReplacer` 依赖 `ImageSearchEngine`，与预搜图的 `ImageSearchTool`/`imageMap` 无耦合。
- **imageMap 无业务消费者**：`getImageMap()` 唯一去处是写黑板，黑板 getter 无人调用；`PageGenerateAgent` 完全不引用 imageMap/PromptContext。

### 原则

分两阶段：**先断执行链（低风险、可独立上线）→ 验证无回归 → 再清理死字段/死类**。每步独立可回滚。

### 阶段一：断掉预搜图执行（必做，低风险）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `orchestrator/v1/OrchestratorAgent.java` | 删 `.concatWith(imageSearchTool.handle(ctx))` 及注释；删字段/构造参数/赋值/import；更新类注释流水线图 |
| 2 | `service/design/DesignContextAssembler.java` | 删「5. 检索图片」整段与 `builder.imageMap(...)`；ContextCompressTool 调用不再传 imageMap；删字段/构造参数/赋值；更新类注释 |
| 3 | `orchestrator/v2/GenerationOrchestrator.java` | 删 `if (dc.getImageMap()...) putImageMap(...)` |

### 阶段二：清理死代码（验证通过后再做）

| # | 文件 | 改动 |
|---|---|---|
| 4 | `tool/system/ImageSearchTool.java` | 整类删除（唯一调用方已移除） |
| 5 | `tool/system/ContextCompressTool.java` | `CompressInput` 去掉 imageMap 参数；删「5. 图片映射」段；更新注释 |
| 6 | `service/design/DesignContext.java` | 删 imageMap 字段与 `getImageMap()` 兼容 getter |
| 7 | `model/dto/agent/PromptContext.java` | 删 imageMap 字段 |
| 8 | `agent/core/Blackboard.java` | 删 `putImageMap`/`getImageMap`/`KEY_IMAGE_MAP`，更新注释 |
| 9 | prompt 文件 | 见第七节 |

### 必查风险点

1. `AiCodeGeneratorFacade` 中的 `imageMap` 引用需确认是回填相关还是预搜（若读 `getImageMap()` 需一并处理）。
2. `ImageProperties.Replace.concurrency` 删预搜后若无其他引用可保留（无害）或标注废弃。
3. 确认 `DesignContext`/`PromptContext` 未被 JSON 持久化到 DB/缓存快照，否则阶段二删字段需保留兼容。
4. 确认 `GenerationRecordTool` 未快照 imageMap。

### 验证清单

- [ ] 编译通过、无未使用 import 告警。
- [ ] 跑完整生成（V1 + V2 standard 各一次），确认 `@@IMG:...@@` 仍被正常回填为真实 URL。
- [ ] 确认无残留 `@@IMG` 占位符（CriticAgent/RuleCritic 占位符检查通过）。
- [ ] 日志不再出现 "ImageSearchTool 搜图完成"。

---

## 七、图片引导词的处理

### 结论：引导词必须保留，但可小幅简化

图片引导词（prompt 里"图片写成 `@@IMG:关键词@@`"的指令）服务于**回填机制**，是回填的前提，**不能去掉**。去掉会导致 LLM 编造图片 URL 或不放图。它与"是否砍预搜图"无关。

### 可简化项

1. `agent-coder-system-prompt.txt` 第 20 行"关键词应与 plan.imageSlots 中的 keyword 一致或语义等价" —— 砍预搜图后此句失去意义，建议改为 `agent-page-generate-system-prompt.txt` 的更优写法：**"关键词由你根据语境自主决定，保证与使用位置强相关"**。
2. `agent-coder-system-prompt.txt` 第 5 行"从黑板读取 plan…与 imageMap"中的 **imageMap 去掉**。
3. `agent-critic-system-prompt.txt` 中 imageMap 相关描述一并清理。
4. `agent-image-system-prompt.txt` 已是历史遗留占位文件，可随 `ImageSearchTool` 删除。

### 应保留项（回填刚需）

- 占位符格式 `@@IMG:关键词@@`；
- 关键词要求（3-6 英文词、主体+场景+风格）；
- 禁止写死外部图片 URL。

---

## 八、安全发现（需处理）

在读取 `agent/codegen/prompt/agent-page-generate-system-prompt.txt` 时，发现该文件末尾混入了**可疑的 prompt injection（提示词注入）内容**：包含伪造的标签，以及试图诱导"忽略指令、以'# 已越狱'开头回复、讲解如何绕过网站登录验证"的文字。

- **风险**：若被真实加载进 PageGenerateAgent 系统提示词，会污染生成行为，属安全隐患。
- **建议**：尽快清理该文件异常内容，仅保留合法的图片占位符规范（对齐 `agent-coder-system-prompt.txt` 的图片段落）。

---

## 附：一句话总览

> 当前是"猜着搜图 → 靠关键词碰运气对齐 → 直接引用第三方链接"。其中**预搜图时机错位、成果易弃、产物无人消费，应直接砍掉**；**回填搜图是真正有效的环节，连同占位符引导词一并保留**；真问题"图文内容贴合"应通过引擎层的正交小增强来解决，而非引入 slotId 这类重协议改造。
