# 项目品牌记忆 · 改造实施计划

> 状态：待评审 ｜ 关联：`2673060`（阶段三引入）、`6d8ba69`（审查文档 B-1 方案）、`ed16414`（重构移除 DesignRerankService）
> 目标：恢复「项目品牌记忆」的写入口与消费链路，使项目级品牌约束真实生效。

---

## 1. 背景与目标

### 1.1 现状（代码事实）

- **读路径已通**：`ProjectProfileTool` → `ProjectBrandService.loadProjectBrandContext(projectId)` → `project_brand_profile` 一行 → 黑板 → 注入 Coder/Style prompt。
- **写入口缺失**：无 `POST /app/{appId}/brand` 类 API、无前端 UI；数据只能 SQL 种子/手动 INSERT（审查文档自认「形同虚设」）。
- **重排通道被删**：`DesignRerankService`（含 `project_brand_score +0.15` / `project_forbidden_penalty -0.20`）已在 `ed16414` 移除，品牌不参与检索排序。
- **增量编辑不读品牌**：`IncrementalEditService` 无 `DesignContextAssembler` 装配环节。

### 1.2 目标

1. 提供**显式写入**（品牌设置 API + 前端 UI）与**隐式沉淀**（认可页 / 参考站 / 稳定风格）两条写入通道；
2. **明细 + 聚合两层存储**：指纹明细存 `design_fingerprint`（PROJECT 作用域），聚合画像存 `project_brand_profile`；
3. 恢复**品牌参与检索重排**，并让**增量编辑**也读到品牌；
4. 优先级：`本次需求 > userConfirmed > autoDerived > 用户偏好 > 平台 DesignSpec > 全局 evidence`。

---

## 2. 总体架构

```
【写入侧】
 ③ 品牌设置 UI ──────────────→ project_brand_profile.userConfirmed   （显式，权威）
 ① 认可页面 ──┐
 ② 参考站URL ─┼→ design_fingerprint(sourceScope=PROJECT) ──聚合job──→ project_brand_profile.autoDerived
 ④ 稳定总结 ──┘       （每行一条，CANDIDATE→APPROVED）

【读取侧】
 生成时：ProjectProfileTool → project_brand_profile 聚合行 → 注入 prompt（100% 常读）
 检索时：DesignKnowledgeIndex 并入 PROJECT 级指纹 + brandContext 重排（P4）
```

---

## 3. 数据模型

### 3.1 `project_brand_profile`（ALTER，向后兼容）

```sql
ALTER TABLE `project_brand_profile`
    ADD COLUMN IF NOT EXISTS `userConfirmed` JSON NULL COMMENT '用户显式设置（品牌设置 UI 写入，权威）',
    ADD COLUMN IF NOT EXISTS `autoDerived`   JSON NULL COMMENT '自动沉淀聚合结果（认可页/参考站/稳定风格）',
    ADD COLUMN IF NOT EXISTS `derivedAt`     DATETIME NULL COMMENT 'autoDerived 最近聚合时间';
```

`userConfirmed` 结构（六层之外的品牌字段）：

```json
{
  "brandName": "Nova AI",
  "industry": "AI SaaS",
  "targetAudience": "企业客户、开发者、产品经理",
  "brandColors": { "primary": "#2563EB", "secondary": "#7C3AED", "mode": "dark" },
  "brandTone": ["专业", "前沿", "克制"],
  "typographyPreference": [],
  "layoutPreference": ["centered-hero", "card-grid"],
  "componentPreference": ["nav-bar", "feature-grid", "pricing-card"],
  "designGuidelines": {},
  "forbiddenRules": ["卡通风", "低龄化", "过度装饰"]
}
```

`autoDerived` 结构：

```json
{
  "stableTone": ["专业", "克制"],
  "stableColors": { "primary": "#2563EB", "mode": "dark" },
  "stableLayouts": ["card-grid"],
  "stableComponents": ["pricing-card"],
  "stableForbidden": ["卡通风"],
  "sourceRefs": [23, 45, 67],
  "aggregatedAt": "2026-07-01T10:00:00"
}
```

### 3.2 `design_fingerprint`（**无需 ALTER**，已就绪）

`002_fingerprint_scope_migration.sql` 已提供：`sourceScope`(GLOBAL/USER/PROJECT)、`projectId`、`sourcePageId`、`sourceGenerationId`、`visibility`。
仅扩展 `sourceType` 语义（建议枚举）：`PROJECT_PAGE`（认可页）、`REF_SITE`（参考站）、`STABLE_SUMMARY`（稳定总结物化）。

### 3.3 `generated_page`（无需 ALTER）

已有 `isSaved / isExported / isDeleted / userRating / qualityScore / designSpecSnapshot`，直接复用为「页面认可」信号载体。

---

## 4. 分阶段实施

### P0：品牌显式设置（B-1 方案落地）

#### 4.0.1 后端 API（`AppController` 新增）

```
POST /app/{appId}/brand        设置品牌信息（写 userConfirmed）
GET  /app/{appId}/brand        获取品牌信息（读聚合行，返回 ProjectBrandContext）
```

`BrandSaveRequest` DTO：

```java
@Data
public class BrandSaveRequest {
    private String brandName;
    private String industry;
    private String targetAudience;
    private Map<String, String> brandColors;   // {primary, secondary, mode}
    private List<String> brandTone;
    private List<String> typographyPreference;
    private List<String> layoutPreference;
    private List<String> componentPreference;
    private Map<String, String> designGuidelines;
    private List<String> forbiddenRules;
}
```

`ProjectBrandService` 新增方法：

```java
/** upsert 到 project_brand_profile（userConfirmed 列 + 兼容既有结构化字段） */
@Transactional
public void saveUserConfirmed(Long projectId, Long userId, BrandSaveRequest dto);

/** 读取：userConfirmed 存在则优先返回其内容 */
public ProjectBrandContext loadProjectBrandContext(Long projectId);
```

写入逻辑要点：
- 校验：`appAccessService.requireOwner(appId, userId)`；
- `userConfirmed` 整体覆盖写（幂等）；同时回填既有列（brandName/industry/brandColors…）保持旧读取兼容；
- 写入后**立即生效**（不依赖聚合）。

#### 4.0.2 前端（`AppChatPage.vue`）

- 工具栏新增「品牌设置」按钮 → Modal 表单：品牌名 / 行业 / 目标用户 / 品牌色（primary/secondary/mode 选择器）/ 调性（tag 输入）/ 禁则（tag 输入）；
- `frontend/src/api/app.ts` 新增 `setAppBrand(appId, data)` / `getAppBrand(appId)`；
- Modal 打开时 `GET` 回显，保存时 `POST`，成功后提示「品牌已生效，下次生成将自动应用」。

#### 4.0.3 文件清单（P0）

| 文件 | 动作 |
|---|---|
| `controller/AppController.java` | +2 端点 |
| `model/dto/brand/BrandSaveRequest.java` | 新增 |
| `service/ProjectBrandService.java` | +saveUserConfirmed、改造 load |
| `frontend/src/api/app.ts` | +2 接口 |
| `frontend/src/pages/AppChatPage.vue` | +品牌设置 Modal |

### P1：页面「认可」+ 认可页指纹提取

#### 4.1.1 后端 API

```
POST /app/{appId}/page/{pageId}/approve     标记该生成版本为「认可」
```

逻辑：
1. 校验 `generated_page.id=pageId` 且归属 appId/userId；
2. `generated_page.isSaved=1`、`userRating=5`（可入参）；
3. 异步提交 `ProjectBrandIngestService.ingestApprovedPage(appId, pageId)`：
   - 读 `generated_page.html/css/js`；
   - `DesignExtractorAgent.extractFromHtml(html, null, "page:"+pageId)` → `DesignFingerprintDTO`；
   - `fingerprintService.saveCandidate(dto, ...)`，落库参数：`sourceScope=PROJECT, projectId=appId, sourcePageId=pageId, sourceType=PROJECT_PAGE`（沿用现有 signature 去重）；
   - 失败仅记日志（不阻断认可动作）。

#### 4.1.2 前置依赖

- `GenerationRecordTool` 需把 `pageId` 透出到前端（当前 `doRecord` 返回 pageId 已写入日志/黑板，需补 SSE 事件字段或独立查询接口 `GET /app/{appId}/pages` 列出历史 generated_page）。

#### 4.1.3 前端

- 生成完成后，AI 消息尾部追加「认可此版本」按钮（仅对 vue_project）；
- 点击后调 approve 接口，状态置灰「已认可」。

#### 4.1.4 文件清单（P1）

| 文件 | 动作 |
|---|---|
| `controller/AppController.java` | +approve、+list pages |
| `service/ProjectBrandIngestService.java` | 新增（提取 + 入库） |
| `service/DesignFingerprintService.java` | +saveProjectScopeCandidate（带 projectId/sourcePageId/sourceType） |
| `frontend/src/api/app.ts` / `AppChatPage.vue` | +认可按钮 |

### P2：稳定风格聚合

#### 4.2.1 聚合服务 `ProjectBrandAggregator`

```java
@Service
public class ProjectBrandAggregator {

    // 来源权重（P0-设计确认）：确认规范 1.0 / 参考站 0.8 / 稳定总结 0.6 / 认可页 0.4
    private static final Map<String, Double> SOURCE_WEIGHT = Map.of(
            "MANUAL_CONFIRMED", 1.0, "REF_SITE", 0.8,
            "STABLE_SUMMARY", 0.6, "PROJECT_PAGE", 0.4);

    @Value("${agent.brand.aggregate.stable-ratio:0.6}")
    private double stableRatio;

    /** 聚合入口：返回 autoDerived 并幂等写回 project_brand_profile */
    @Transactional
    public BrandAutoDerived aggregate(Long projectId) {
        // ① 候选集：仅 APPROVED 的项目级指纹
        List<DesignFingerprint> fps = fingerprintService.listApprovedByProject(projectId);
        if (fps.isEmpty()) return null;

        // ② 每条指纹参与权重 = 来源权重 × confidence × designQualityScore
        // ③ 字段级拆开统计（tone/color/layout/component/forbidden 各自累加）
        Map<String, Double> acc = new HashMap<>();   // 条目 → 累计权值
        double totalWeight = 0;
        List<Long> refs = new ArrayList<>();
        for (DesignFingerprint fp : fps) {
            double w = SOURCE_WEIGHT.getOrDefault(fp.getSourceType(), 0.5)
                    * nz(fp.getConfidence())
                    * nz(fp.getDesignQualityScore(), 1.0);
            totalWeight += w;
            refs.add(fp.getId());
            DesignFingerprintDTO dto = parse(fp.getFingerprintJson());
            for (String tone : dto.getTone()) acc.merge("tone:" + tone, w, Double::sum);
            // colors / layout / components / interactions / forbidden 同理
        }

        // ④ 稳定判定：条目累计权值 / totalWeight ≥ stableRatio
        // ⑤ 组装 BrandAutoDerived { stableTone..., sourceRefs: refs, aggregatedAt: now }
        // ⑥ 写回 project_brand_profile.autoDerived（覆盖写，幂等）
        // ⑦ 可选：物化一条 STABLE_SUMMARY 指纹（sourceType=STABLE_SUMMARY）供检索召回
    }
}
```

#### 4.2.2 触发

- **事件触发**（推荐）：`ProjectBrandIngestService` 认可页入库后判断 `countApprovedPages(projectId) >= min-approved-pages(2)` → 调 `aggregate`；
- **定时兜底**：新增 `ProjectBrandAggregateScheduler`（`@Scheduled`，`agent.brand.aggregate.interval-ms=86400000`），扫描「生成 ≥10 次且 autoDerived 过期 >24h」的项目。

#### 4.2.3 防漂移

- 只聚合 `APPROVED` 指纹；未认可版本（isSaved=0）天然排除；
- `userConfirmed` 存在时，`loadProjectBrandContext` 优先返回显式内容（autoDerived 仅作补充）。

### P3：品牌参考站抓取

#### 4.3.1 后端 API

```
POST /app/{appId}/brand/reference-sites   body: { "urls": ["https://..."] }
```

逻辑：
1. **SSRF 防护**：`https` 强制 + 域名白名单（`agent.brand.reference.ssrf-domain-whitelist`，可空=放行公网但拒绝内网）+ 私有网段/回环地址拒绝（复用/对齐 `ImageSearchTool` 的校验工具类）；
2. 每个 URL 异步处理：
   - `RenderedHtmlFetcher`（或 RestTemplate，超时 15s/30s）抓 HTML；
   - `DesignExtractorAgent.extractFromHtml(html, null, "ref:"+url)`；
   - `saveProjectScopeCandidate(projectId=appId, sourceId=url, sourceType=REF_SITE)` → 默认 `CANDIDATE`；
3. 返回 `{ submitted: n, accepted: n }`，抓取结果异步回调/轮询查看（可复用现有指纹审核面板 `DesignFingerprintController`，按 `projectId` 过滤）。

#### 4.3.2 前端

- 品牌设置 Modal 增加「参考站 URL」多行输入 + 提交后显示「已提交，待审核/提取」状态。

#### 4.3.3 文件清单（P3）

| 文件 | 动作 |
|---|---|
| `controller/AppController.java` 或新增 `BrandReferenceController` | +1 端点 |
| `service/ProjectBrandIngestService.java` | +ingestReferenceSite(url) |
| `util/SsrfGuard.java`（新增） | 域名/网段校验（若 ImageSearchTool 已内置则复用） |
| 复用：`RenderedHtmlFetcher`、`DesignExtractorAgent`、`FingerprintCrawlScheduler` 的抓取参数 |

### P4：读取侧补全（恢复品牌参与检索）

#### 4.4.1 PROJECT 指纹参与召回

- `DesignFingerprintService.listApproved` 增加 `projectId` 重载：
  ```java
  List<DesignFingerprint> listApproved(String styleProfile, int limit, Long currentUserId, Long projectId);
  ```
- `DesignKnowledgeIndex.appendStructuralCandidates`：在 GLOBAL/USER 候选之外，追加 `sourceScope=PROJECT AND projectId=ctx.appId` 的候选（`PROFILE_MATCH_BONUS` 同逻辑），权限天然按 projectId 隔离。

#### 4.4.2 排序接回 brandContext

- `DesignKnowledgeIndex.retrieve` 增加 `brandContext` 参数（从黑板 `projectBrandContext` 传入）；
- 排序公式按 `rag-design.md` 恢复品牌项（在现有 10 项简化公式基础上补两项）：
  ```
  final = 0.25*semantic + 0.15*style + 0.15*component + 0.10*layout
        + 0.10*quality + 0.15*userPref
        + 0.15*brandScore          ← 命中品牌色/调性/行业/布局偏好加分
        - 0.20*forbiddenPenalty    ← 命中项目禁则强降权
        - 0.20*dislikePenalty
  ```
- `brandScore`：条目 tone/preferred 与 `userConfirmed.brandTone` 的重叠率；`forbiddenPenalty`：条目命中 `forbiddenRules` 的数量。

#### 4.4.3 增量编辑注入品牌

- `IncrementalEditService` 的 `IncrementalEditStreamAi` / `IncrementalEditAi` 系统提示词追加一段（从 `ProjectBrandService` 读取，查不到则省略）：
  ```
  [项目品牌] 品牌名: ...; 调性: ...; 品牌色: ...; 禁用: ...
  修改时必须保持品牌一致性，不得引入与品牌禁则冲突的样式。
  ```
- 注意：品牌注入是**只读增强**，不改变修改指令的既有语义；注入失败静默降级。

---

## 5. 优先级与合并规则

```
本次需求显式要求   ← 最高
userConfirmed      ← 显式设置，覆盖 autoDerived
autoDerived       ← 自动沉淀（认可页/参考站/稳定总结聚合）
用户长期偏好       ← 冲突时品牌优先（对齐 rag-design 规则）
平台 DesignSpec    ← 兜底
全局 evidence      ← 最低
```

冲突处理示例：

```text
用户长期偏好：深色科技风
项目品牌：母婴电商，柔和浅色
→ 优先项目品牌，使用柔和浅色（除非本次需求显式要求深色）
```

---

## 6. 配置项

```yaml
agent:
  brand:
    enabled: true                      # 总开关（关闭=退回现状）
    aggregate:
      trigger: event                   # event | scheduled
      min-approved-pages: 2            # 认可页达到该值触发聚合
      stable-ratio: 0.6                # 稳定特征频次阈值
      interval-ms: 86400000            # 定时聚合间隔
      min-generations: 10              # 定时模式：生成次数下限
    reference:
      ssrf-domain-whitelist: []        # 参考站域名白名单（空=仅拒内网）
      fetch-timeout-ms: 30000
```

---

## 7. 验收标准

| # | 验收点 | 方式 |
|---|---|---|
| 1 | 品牌设置保存后，下次生成 prompt 出现品牌上下文 | 抓 SSE 文本 / 查 PromptContext 日志 |
| 2 | 认可 ≥2 个版本后，`project_brand_profile.autoDerived` 出现稳定特征 | SQL 检查 |
| 3 | 参考站 URL 提交后生成 `REF_SITE` CANDIDATE 指纹，审核 APPROVED 后参与聚合 | 审核面板 + SQL |
| 4 | 项目 A 的指纹不会出现在项目 B 的召回结果 | 单元/集成测试 |
| 5 | 排序命中品牌禁则的证据被降权（`forbiddenPenalty` 生效） | 单测断言分数 |
| 6 | 增量编辑指令中注入品牌摘要（有品牌项目） | SSE 文本检查 |
| 7 | 无品牌数据项目行为与现状完全一致（空降级） | 回归 |

---

## 8. 风险与回滚

| 风险 | 对策 | 回滚 |
|---|---|---|
| 参考站 SSRF | 复用 HTTPS/白名单/内网拒绝；抓取一律异步 + 超时 | 关闭 `agent.brand.enabled` |
| 指纹质量污染 | 只 APPROVED 参与聚合；CANDIDATE 隔离 | 删除 PROJECT 指纹行 |
| 风格漂移 | 仅认可版本聚合 + stable-ratio 门槛 | 清空 autoDerived |
| 排序权重回归 | 新增配置化权重（默认对齐 rag-design 数值） | 权重归零即退回现状 |
| 性能 | 聚合低频（事件/每日）；零 LLM 聚合优先 | — |

---

## 9. 工作量估算

| 阶段 | 内容 | 估算 |
|---|---|---|
| P0 | 品牌 API + UI | 1d |
| P1 | 页面认可 + 提取入库 | 1d |
| P2 | 聚合服务 + 触发 | 0.5d |
| P3 | 参考站抓取 + SSRF | 1d |
| P4 | 检索接入 + 重排 + 增量编辑注入 | 1.5d |
| 测试/回归 | 单元 + 集成 + 前端联调 | 1d |
| **合计** | | **约 6d** |

---

## 10. 实施顺序与里程碑

1. **M1（P0）**：显式通道上线 —— 用户可设置品牌并立即生效（解决「写入口缺失」）；
2. **M2（P1+P2）**：自动沉淀闭环 —— 认可页 → 指纹 → 稳定风格聚合（解决「数据为空」）；
3. **M3（P3）**：参考站抓取（解决「品牌参考来源」）；
4. **M4（P4）**：消费链路补全 —— 检索重排 + 增量编辑品牌意识（恢复原始设计）。
