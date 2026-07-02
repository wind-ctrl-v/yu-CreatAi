# 修改计划 06：用户私有指纹入库服务存在双实现，向量同步补丁误落在未被调用的类上

> 生成日期：2026-07-03
> 状态：待评审（未实施）
> 优先级：高

## 1. 问题描述

代码库中同时存在两个几乎完全相同的"用户私有指纹入库"实现：

- `src/main/java/com/yu/yuaifather/worker/FingerprintIngestWorker.java`（`worker` 包）
- `src/main/java/com/yu/yuaifather/service/UserFingerprintIngestService.java`（`service` 包）

二者的方法名（`ingestUserFingerprint`）、内部逻辑（`buildSurfaceFingerprint()` 表层正则提取、`saveAsUserPrivate()` 直接落 `APPROVED`）、常量（`USER_PRIVATE_CONFIDENCE_GATE=0.65`、`USER_PRIVATE_PRIORITY=25`）几乎逐字重复，属于同一功能的两份平行代码。

更关键的是，两者的**依赖注入方与实施状态并不同步**：

- `FingerprintIngestWorker` 的 Javadoc 声称"从 `UserFingerprintIngestService` 改名重构而来"，一度是 `UserFingerprintIngestService` 被标注 `@Deprecated` 后的替代品；本计划 01（`01-private-fingerprint-vector-sync.md`）针对的正是这个类，已补上 `VectorSyncWorker` 注入与 `syncOnApprove()` 调用。
- 但在同一时间窗口内，另一次提交（`d2dfbe7 feat: RAG三阶段计划完成...`）反向操作：移除了 `UserFingerprintIngestService` 的 `@Deprecated` 标注，并将其重新注入到 `GenerationOrchestrator.doRecord()` 中作为**实际生产调用入口**，而 `FingerprintIngestWorker` 未被任何类引用。

复查结果：

```
GenerationOrchestrator.doRecord()
  → fingerprintIngestService.ingestUserFingerprint(pageId, userId, projectId)
     （字段类型为 UserFingerprintIngestService，★ 生产实际调用）

FingerprintIngestWorker.ingestUserFingerprint(...)
     （全代码库搜索，除自身文件外无任何调用点，已成为死代码）
```

结果是：**计划 01 的向量同步补丁被打在了不会被执行的类上**，`UserFingerprintIngestService.saveAsUserPrivate()` 内部仍然只有一行 `designFingerprintService.save(entity)`，没有任何 `VectorSyncWorker` 依赖，入库后依旧不会触发 `syncOnApprove()`。计划 01 描述的原始缺陷（VECTOR_3B 通道永远召回不到用户私有指纹）在生产链路上**实质仍未修复**。

## 2. 影响范围

- `DesignKnowledgeIndex.tryVectorRetrieveWithPersonalization()`（VECTOR_3B 通道）：与计划 01 描述完全一致，继续永远查不到任何用户私有指纹。
- 两份平行代码长期并存会持续误导后续维护者：任何人（包括 AI 编码助手）如果只搜索类名做局部修复，都有较高概率再次"改错文件"而不自知，因为两个类都能正常编译、正常被 Spring 扫描为 `@Service` Bean，编译期和启动期都不会报错。
- `FingerprintIngestWorker` 作为孤儿 Bean 仍会被 Spring 容器实例化（因为有 `@Service` 注解），造成轻微的资源与认知浪费，但不产生功能性影响。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/service/UserFingerprintIngestService.java`（核心改动点：补齐向量同步）
- `src/main/java/com/yu/yuaifather/worker/FingerprintIngestWorker.java`（待删除，或反向迁移调用方）
- `src/main/java/com/yu/yuaifather/orchestrator/GenerationOrchestrator.java`（确认依赖类型，无需改动，仅核对）
- `src/main/java/com/yu/yuaifather/worker/VectorSyncWorker.java`（复用现有方法，无需改动）

## 4. 修改方案

### 4.1 二选一：采纳生产已在用的 `UserFingerprintIngestService`（推荐）

理由：`GenerationOrchestrator` 当前已注入并调用该类，选择它作为唯一实现改动面最小、风险最低，无需改动编排器依赖关系。

**第一步**：将计划 01 中对 `FingerprintIngestWorker` 打的补丁原样迁移到 `UserFingerprintIngestService`：

```java
// UserFingerprintIngestService.java
private final VectorSyncWorker vectorSyncWorker;

@Autowired
public UserFingerprintIngestService(GeneratedPageMapper generatedPageMapper,
                                     DesignFingerprintService designFingerprintService,
                                     @Autowired(required = false) VectorSyncWorker vectorSyncWorker) {
    this.generatedPageMapper = generatedPageMapper;
    this.designFingerprintService = designFingerprintService;
    this.vectorSyncWorker = vectorSyncWorker;
}
```

```java
// doIngest()
DesignFingerprint saved = saveAsUserPrivate(dto, userId, projectId, pageId);
if (saved != null) {
    log.info("用户私有指纹入库成功 pageId={} fingerprintId={} styleProfile={}",
            pageId, saved.getId(), dto.getStyleProfile());
    if (vectorSyncWorker != null) {
        vectorSyncWorker.syncOnApprove(saved);
    }
}
```

**第二步**：删除 `FingerprintIngestWorker.java`，并同步清理：
- `docs/rag-fix-plans/01-private-fingerprint-vector-sync.md` 中"涉及文件"与"实施记录"对该文件的引用，改为指向 `UserFingerprintIngestService`（保留计划 01 的问题诊断本身不变，仅更正"改动落在了哪个文件"这一事实）。
- `docs/rag-comprehensive-analysis.md` 中链路 B 流程图里的类名引用。

### 4.2 二选一：反向迁移到 `FingerprintIngestWorker`（不推荐）

若坚持以 `worker` 包命名规范作为长期方向，则需要：
1. 将 `UserFingerprintIngestService` 当前的构造逻辑（若有差异）合并进 `FingerprintIngestWorker`；
2. 修改 `GenerationOrchestrator` 的构造函数与字段类型，从 `UserFingerprintIngestService` 换回 `FingerprintIngestWorker`；
3. 删除 `UserFingerprintIngestService.java`。

**不推荐**的原因：这会改动 `GenerationOrchestrator` 的构造签名，涉及主编排链路，且与最近一次提交（`d2dfbe7`）的方向相反，容易在未来再次被"改回去"，形成反复拉扯。

### 4.3 命名与文档统一

无论采纳 4.1 或 4.2，都需要**在删除旧类的同时更新所有引用该类名的文档**（`rag-comprehensive-analysis.md`、`rag-fix-plans/01-*.md`、`rag-fix-plans/README.md` 如有提及），避免文档与代码再次出现"名字对不上"的漂移。

## 5. 验证方式

1. 全局搜索确认删除后不再有任何编译期引用（`grep -r "FingerprintIngestWorker"` 或 `grep -r "UserFingerprintIngestService"`，取决于最终保留哪个）。
2. 开启开关：`agent.fingerprint.user-ingest.enabled=true`、`agent.retrieve.vector.enabled=true`、`agent.orchestrator.v2.enabled=true`（因为触发入口在 `GenerationOrchestrator.doRecord()`，V1 `OrchestratorAgent` 不会触发该链路）。
3. 走一次 V2 生成流程使其完整跑完 `doRecord()`，观察日志中出现"用户私有指纹入库成功"与"向量同步成功 fingerprintId=..."两条日志。
4. 查询 PGvector：`SELECT * FROM design_fingerprint_vector WHERE owner_user_id = <userId>`，确认记录存在。
5. 复核 `GenerationOrchestrator` 的 Spring Bean 依赖列表（或直接看构造函数），确认只依赖唯一保留下来的那个类，不存在编译警告或未使用的 Bean。

## 6. 风险评估

- **风险低**：4.1 方案本质是把计划 01 已经验证过的改动搬到另一个结构几乎相同的类上，逻辑不新增、不修改，只是"搬对位置"。
- **需要注意**：删除 `FingerprintIngestWorker` 前应再次确认没有测试类、Mock 配置或其他隐藏引用依赖它（当前全局搜索未发现，但删除类属于不可逆操作，建议删除后跑一次全量编译确认）。
- **流程性风险（更重要）**：本计划的根因不是代码逻辑错误，而是"两次独立的修改在同一时间窗口内对同一问题做出了方向相反的决策"，暴露出当缺乏统一的"当前生效实现"标注时，后续修改极易踩坑。建议在保留下来的那个类的 Javadoc 中明确标注"当前生产唯一入口，请勿再创建平行实现"，并删除另一个类，而不是仅靠 `@Deprecated` 注解（历史已证明 `@Deprecated` 标注可能被后续提交静默移除）。
