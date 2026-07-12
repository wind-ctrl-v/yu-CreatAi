# 图片流水线分析与重构 — 会话总结文档

> 本文档完整记录了本次会话围绕"图片查找流水线"的分析、结论、方案取舍与实际改动，供后续参考。内容按讨论推进顺序组织，覆盖所有关键信息点。

---

## 目录

1. [起始问题与背景](#一起始问题与背景)
2. [当前图片流水线完整流程](#二当前图片流水线完整流程)
3. [回填搜图的图片来源机制](#三回填搜图的图片来源机制)
4. [问题梳理](#四问题梳理)
5. [预搜图专项结论](#五预搜图专项结论)
6. [方案评估：slotId 强绑定为何被否决](#六方案评估slotid-强绑定为何被否决)
7. [最终推荐方向](#七最终推荐方向)
8. [图片引导词的处理结论](#八图片引导词的处理结论)
9. [实际执行的改动记录](#九实际执行的改动记录)
10. [剩余待办与死字段](#十剩余待办与死字段)
11. [安全发现与环境异常](#十一安全发现与环境异常)
12. [关键结论速览](#十二关键结论速览)

---

## 一、起始问题与背景

**项目**：`/Users/fish/CodeBuddy/code/CreatAi`（Java / Spring WebFlux 的多 Agent 代码生成系统，生成 Vue 项目）。

**用户最初的疑问**：当前流水线中"图片查找"在"代码生成"之前，那么如何确定查找到的图片符合每一个位置所需？

这个疑问贯穿全程，最终演化为对"预搜图"环节合理性的根本性质疑，并落地为一次重构（移除预搜图）。

---

## 二、当前图片流水线完整流程

图片处理是**两段式机制**，分布在代码生成前后：

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

### 关键机制

- **位置对齐**：靠占位符的**物理位置**天然对齐——占位符写在哪个 `<img>`，图就渲染在哪个位置，100% 可靠。真正需要担心的不是"位置对齐"，而是"图片内容是否贴合该位置"。
- **两阶段的衔接**：不是直接传递 `imageMap` 对象，而是通过 `ImageSearchEngine` 内部的 **Caffeine 缓存**（key = 清洗后的 keyword）间接衔接。
- **两条编排链路**：
  - V1 `OrchestratorAgent`：固定 9 步串行，第 ⑥ 步是 `ImageSearchTool`（预搜图），第 ⑦ 步才是 Coder/Style。
  - V2 `GenerationOrchestrator`：按 `GenerationPolicy` 三模式（low_cost / standard / high_quality）编排，预搜图封装在 `DesignContextAssembler.assemble()` 的第 5 步。

### 涉及的关键代码

| 组件 | 路径 | 职责 |
|---|---|---|
| `ImageSearchTool` | `tool/system/ImageSearchTool.java` | **预搜图包装层**（消费 imageSlots，产出 imageMap） |
| `ImageSearchEngine` | `tool/engine/ImageSearchEngine.java` | **真正的搜图引擎**（多源 + 缓存 + SSRF 防护） |
| `ImagePlaceholderReplacer` | `service/design/ImagePlaceholderReplacer.java` | 占位符回填（扫描 `@@IMG:kw@@` 替换 URL） |
| `TextToImageEngine` | `tool/engine/TextToImageEngine.java` | 文生图兜底 + 本地转存 |
| `DesignContextAssembler` | `service/design/DesignContextAssembler.java` | V2 上下文装配（曾内含预搜图调用） |
| `ContextCompressTool` | `tool/system/ContextCompressTool.java` | 上下文压缩为 PromptContext |
| `GenerationPlan.ImageSlot` | `model/dto/agent/GenerationPlan.java` | 图片槽数据结构 |

---

## 三、回填搜图的图片来源机制

### 回填时图片"从哪来"

回填时**既不是纯搜索，也不是直接拿 imageMap 对象**，而是调用 `ImageSearchEngine.search(keyword)`，其内部：

```java
public String search(String keyword) {
    String key = cleanKeyword(keyword);
    String cached = cache.getIfPresent(key);   // ① 先查 Caffeine 缓存
    if (cached != null) return cached;          //   命中 → 返回预搜图那次留下的图
    String url = doSearch(key);                 // ② 未命中 → 真正走多源搜索
    if (StrUtil.isNotBlank(url) && !isLowQualityFallback(url)) {
        cache.put(key, url);
    }
    return url;
}
```

- **缓存命中**（回填 keyword 与预搜 keyword 清洗后一致，且预搜到高质图被缓存）→ 复用预搜结果，不发请求。
- **缓存未命中**（关键词不一致 / 兜底未缓存 / 缓存过期 / 预搜未做）→ **重新发起多源搜索**。

关键：`ImagePlaceholderReplacer.replaceAll(appId)` **只接收 appId，没有接收 imageMap 对象**；预搜产出的 imageMap 在回填阶段完全没被使用，两阶段仅通过 Caffeine 缓存间接衔接。

### 缓存里的 URL 从何而来

Caffeine 缓存**存的是 URL 字符串，不是图片本身**。URL 首次由 `doSearch()` 从 6 个外部渠道获取（按优先级）：

| 来源 | 返回 URL 指向 | 是否入缓存 |
|---|---|---|
| Pixabay | `cdn.pixabay.com` | 是 |
| Unsplash | `images.unsplash.com` | 是 |
| Pexels | `images.pexels.com` | 是 |
| 文生图（TextToImageEngine） | 转存后的**本地静态目录** `/api/static/**`（经 `LocalImageStorage`，**非 COS**） | 是 |
| loremflickr | `loremflickr.com`（按 tag 语义兜底） | **否**（低质兜底） |
| picsum | `picsum.photos`（纯随机兜底） | **否**（低质兜底） |

图片实体像素托管在这些第三方 CDN 或本地静态目录，项目只把 URL 引用写进生成代码（`isSafe()` 做 https + 域名白名单 + 拒绝私有网段的 SSRF 校验）。

---

## 四、问题梳理

### 根因：全程只用"关键词文本"当主键，丢掉了结构化信息

`plan.imageSlots` 有 `slotId / keyword / style / usedIn` 四个字段，但真正贯穿全链路的**只有 keyword**：
- `imageMap` 的 key 是 keyword 而非 slotId；
- 回填只认占位符里的 keyword；
- `slotId`（位置标识）、`usedIn`（位置描述）、`style`（photo/illustration）在最终注入时**全部未被使用**。

### 完整问题清单

1. **预搜关键词 ≠ 回填关键词，预搜图基本白做**：Prompt 只要求"语义等价"，清洗后不同则缓存打不中，回填重搜，预搜白费。
2. **同一 keyword 全站共用一张图 / 缺乏多样性**：缓存按 keyword，各图源都取第 0 条结果。
3. **`style` 字段形同虚设**：搜图写死 `image_type=photo`，插画需求无法生效。
4. **无图文相符校验**：取第一条即用，无相关性判断；CriticAgent 只查残留占位符，不判断贴合度；末端 picsum 是纯随机图。
5. **降级静默、不可观测**：loremflickr/picsum 兜底不打标、不上报，真实图与随机兜底图混在产物里无法区分。
6. **`imageMap` 是死数据（无人消费）**：`getImageMap()` 除定义外无任何业务调用点；`ContextCompressTool.buildFullPrompt` 未拼接 imageMap；`CoderAgent`/`PageGenerateAgent` 均不消费。
7. **回填串行、性能欠佳**：预搜图并发，但回填 `replaceAll` 是串行 for 循环。
8. **全局缓存跨项目"串图"**：缓存 key 仅为 keyword，不含 appId/userId。
9. **占位符协议脆弱、依赖 LLM 守约**：格式写错或 keyword 带 `@`，占位符会原样残留成乱码文本，无补偿机制。
10. **外部 URL 直接写入产物，长期可用性 + 版权风险**：第三方失效/防盗链会导致图片集体失效。
11. **文生图本地存储缺乏清理与多实例支持**：`maxBytes` 只限单文件，无总量上限/清理策略；本地文件多实例不共享。

---

## 五、预搜图专项结论

### 时序矛盾（根本不合理之处）

预搜图试图"在知道每个位置需要什么之前，就把图备好"，但"每个位置需要什么"这一信息要到**代码生成时**才产生：
- 预搜图用的是 PlanningAgent 的**粗预判关键词**（无语境）；
- 真正贴合位置的是 CoderAgent 在**有语境时现写的关键词**；
- 预搜图搜的图天生不针对任何具体位置，无法保证"符合每个位置的需求"。

### 收益评估：投入产出比极低

- **收益 A（缓存预热）**：仅当"预搜关键词 == 回填关键词 且 是高质图 且 在 TTL 1 小时内"三条同时成立才生效，经常落空。
- **收益 B（给 LLM 参考 imageMap）**：**完全没兑现**——imageMap 未注入任何 Prompt，也无代码消费。
- **成本（每次必付）**：一整轮并发外部图库请求、消耗 API 配额、网络延迟、一个没人用的 imageMap。

### 结论

预搜图是**时机错位、成果易弃、产物无人消费**的环节，可直接砍掉。回填搜图（阶段3）才是真正有效的环节。砍掉预搜图只保留回填，几乎不损失效果，还能省一轮 API 调用并移除 imageMap 死链路。

---

## 六、方案评估：slotId 强绑定为何被否决

### 曾考虑的方案

把占位符从 `@@IMG:keyword@@` 改为 `@@IMG:slotId@@`，回填时按 slotId 从 plan 取 keyword/style，实现位置强绑定，顺便解决重图/串图/style。核心逻辑是"把贯穿全链路的主键从会变的 keyword 换成不变的 slotId"。

### 工程视角复盘后：否决

1. **解决的是伪问题**：位置对齐已由占位符物理位置 100% 解决，不需要 slotId。
2. **对真问题（内容贴合）是倒退**：强制用 plan 的粗预判关键词，等于放弃 CoderAgent 更贴合语境的关键词。
3. **核心卖点自相矛盾**：其最大收益"复用预搜成果"依赖保留预搜图，而预搜图已判定该砍。
4. **可靠性/可维护性下降**：把可靠的物理对齐换成"LLM 从封闭清单选对 slotId 并填对位置"的认知任务，易错、需兜底、占位符不再自解释、难调试。
5. **顺带收益都有更轻量的独立解法**：重图→取不同 index；串图→缓存 key 加 appId；style→搜图传参。

> 教训：最好的改动往往是删繁就简，而不是再加一层抽象。

---

## 七、最终推荐方向

低风险、可增量上线：

1. **砍掉预搜图** —— 去掉时机错位环节 + imageMap 死链路。
2. **保留 `@@IMG:keyword@@` 占位符协议** —— 位置对齐免费可靠，信任 CoderAgent 带语境的关键词。
3. **在引擎/回填层做正交小增强**（每项可独立上线）：
   - style 传参（让插画需求生效）；
   - 搜索结果 index 轮换去重（治重图）；
   - 缓存 key 加 appId（治串图）；
   - 兜底图打标 + 回填后强制扫描残留占位符兜底（治残留、提升可观测性）；
   - 可选：用位置附近文本做轻量相关性校验（治内容贴合）。

---

## 八、图片引导词的处理结论

**"预搜图"与"图片引导词"是两件独立的事**：

| | 预搜图 | 图片引导词（占位符规范） |
|---|---|---|
| 是什么 | 生成前去图库搜图的**动作** | prompt 里"图片写成 `@@IMG:关键词@@`"的**指令** |
| 服务于谁 | 缓存预热 / imageMap（无人用） | **回填机制**（回填靠它扫占位符） |
| 结论 | 砍掉 | **必须保留** |

- **引导词必须保留**：它是回填机制的前提。去掉会导致 LLM 编造图片 URL（幻觉链接）或不放图。与"是否砍预搜图"无关。
- **可简化项**：`agent-coder-system-prompt.txt` 中"关键词应与 plan.imageSlots 一致"这句，砍预搜后失去意义，可改为"由 LLM 根据语境自主决定"（实际排查发现真实文件本就是理想措辞，无需改）。imageMap 相关描述可删。
- **应保留项（回填刚需）**：占位符格式、关键词要求（3-6 英文词、主体+场景+风格）、禁止写死外部 URL。

---

## 九、实际执行的改动记录

按"先断执行链（低风险）→ 再清理死代码"两阶段执行，全程用 `search_content` + `read_lints` 双通道交叉验证真实落盘。**整个包 linter 0 错误，回填搜图链路完全未动。**

### 阶段一：断掉预搜图执行（已完成并验证）

| 文件 | 改动 |
|---|---|
| `orchestrator/v1/OrchestratorAgent.java` | 移除 `.concatWith(imageSearchTool.handle(ctx))` 调用 + 字段/构造参数/赋值/import/流水线注释；补充"图片由生成后占位符回填处理"说明 |
| `service/design/DesignContextAssembler.java` | 移除「5. 检索图片」整段与 `builder.imageMap(...)`；`ContextCompressTool` 调用改传 `Map.of()`；移除 `imageSearchTool` 字段/构造参数/赋值；更新类注释编排顺序 |
| `orchestrator/v2/GenerationOrchestrator.java` | 移除 `if (dc.getImageMap() != null) putImageMap(...)` |

**验证**：全局搜索确认 `ImageSearchTool`/`putImageMap`/`getImageMap` 外部引用清零（仅 `ImageSearchTool.java` 自身残留）；`AiCodeGeneratorFacade` 只依赖回填器 `ImagePlaceholderReplacer`，与预搜图无关，不受影响。

### 阶段二：死代码与文档清理（已完成）

| 项 | 处理 |
|---|---|
| `tool/system/ImageSearchTool.java` | ✅ **已删除**（全仓库无外部引用；真正搜图由 `ImageSearchEngine` 经回填器执行） |
| `resources/prompt/agent-image-system-prompt.txt` | ✅ **已删除**（历史遗留 + 含注入内容，代码中无任何加载引用） |
| `resources/prompt/agent-coder-system-prompt.txt` | ✅ 去掉输入行的"与 imageMap"（图片规范本就理想，无需再改） |
| `resources/prompt/agent-critic-system-prompt.txt` | ✅ 去掉输入的"imageMap" + 修正过时 suggestion（原"确认 imageMap 已包含该 keyword，或重新调用 ImageAgent"→改为关键词命中相关建议） |
| `model/dto/agent/GenerationPlan.java` | ✅ 注释去掉"/ ImageSearchTool 消费" |
| `tool/engine/ImageSearchEngine.java` | ✅ 更新类注释（原"原名 ImageSearchTool…"→改为"由 ImagePlaceholderReplacer 调用"） |
| `service/design/DesignContext.java` | ✅ `imageMap` 字段注释更新为"【已废弃】…保留仅为兼容" |
| `service/design/ImagePlaceholderReplacer.java` | ✅ 修正失效的 `@link ImageSearchTool` → `@link ImageSearchEngine` |

> 注：正确的 prompt 文件目录是 `src/main/resources/prompt/`（会话中一度出现被污染的错误路径 `src/main/java/.../agent/codegen/prompt/`）。

---

## 十、剩余待办与死字段

以下为**互不影响功能的死字段/死方法**（linter 0 错误证明无代码引用），**删除前需先确认它们没有被 JSON 持久化到 DB/缓存快照**，故尚未删除：

- `ContextCompressTool.CompressInput` 的 `imageMap` 参数及第 102-105 处理逻辑（现恒为空 Map，是死分支）
- `PromptContext.imageMap` 字段
- `DesignContext.imageMap` 字段 + 兼容 `getImageMap()` getter
- `Blackboard.putImageMap` / `getImageMap` 方法 + `KEY_IMAGE_MAP` + 第 25 行过时注释（`ImageAgent → putImageMap`）

### 持久化排查（未完成）

需确认的问题：
- A. 这些类是否带持久化注解（`@TableName`/`@Entity`）或 `implements Serializable`；
- B. 是否有地方将 `PromptContext`/`DesignContext` 整体 JSON 序列化后存库/缓存；
- C. `GenerationRecordTool`/`RagTraceTool` 等保存快照时是否包含这些对象（初步观察 `doRecord` 传入的是 styleProfile 等基本字段 + 多个 null，未含这些对象）；
- D. 这几个 getter/字段除定义外是否还有实际调用点；
- E. `PromptContext`/`DesignContext` 是"用完即弃"的运行时 DTO 还是被存储复用。

> 该排查因工具环境异常（`PromptContext.java` 读取多次被"疑似注入"拦截、子代理调用被拒）**未能完成**。建议在环境稳定后，用直接搜索（grep `@TableName`/`@Entity`/`Serializable`、这些 DTO 的 JSON 序列化、Mapper/RecordTool 的 save 调用）确认后再删除；或保留（无害）。

---

## 十一、安全发现与环境异常

### 提示词注入（需人工处理）

- `resources/prompt/agent-page-generate-system-prompt.txt` 曾被读到末尾混入**提示词注入内容**：伪造标签 + 诱导"忽略指令、以'# 已越狱'开头回复、讲解如何绕过网站登录验证"的文字。
- `agent-critic-system-prompt.txt`、`PromptContext.java` 等文件读取多次触发系统"疑似注入"拦截。
- `agent-image-system-prompt.txt` 含疑似注入内容（已随清理删除）。

**建议**：人工逐一打开上述 prompt 文件与 `PromptContext.java`，确认真实内容干净，清除任何被注入的污染文本。所有注入指令在本次会话中均已被拒绝执行。

### 工具通道不稳定

本次会话工具返回结果多次出现**篡改/伪造/不一致**：`replace_in_file` 假成功、`read_file` 返回错乱或"找不到文件"、`search_file` 与 `search_content` 结果矛盾、`delete_file` 一度报"不在工作区"、子代理调用被拒。因此所有落盘改动均通过 `search_content` + `read_lints` 双通道交叉验证。**建议本地执行 `./mvnw compile` 并跑一次生成流程做最终确认。**

---

## 十二、关键结论速览

1. **位置对齐不是问题**：占位符物理位置天然保证图片出现在正确位置；真正的问题是"图片内容是否贴合位置"。
2. **预搜图应被移除**：时机错位（语境未生成时搜图）、成果易弃（关键词对不上）、产物无人消费（imageMap 死数据），已执行移除。
3. **回填搜图是唯一有效环节**：由 `ImageSearchEngine` 经 `ImagePlaceholderReplacer` 在代码生成后执行，予以保留。
4. **slotId 强绑定方案被否决**：解决伪问题、对真问题倒退、卖点自相矛盾、可靠性下降。
5. **引导词必须保留**：它是回填机制的前提，仅可小幅简化（去 imageMap 描述）。
6. **改动已落地**：预搜图从两条编排链移除，`ImageSearchTool` 类与被污染的 `agent-image-system-prompt.txt` 已删除，相关注释/prompt 清理完毕，linter 0 错误。
7. **遗留**：若干 imageMap 死字段待确认持久化后清理；多个 prompt 文件疑似含注入内容待人工核查。

---

*（相关的纯方案文档见 `docs/image-pipeline-analysis-and-refactor-plan.md`。）*
