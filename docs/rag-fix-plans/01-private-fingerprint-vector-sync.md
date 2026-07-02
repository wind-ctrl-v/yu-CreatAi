# 修改计划 01：用户私有指纹同步至向量库

> 生成日期：2026-07-02
> 状态：✅ 已实施（2026-07-02）
> 优先级：高

## 1. 问题描述

用户私有指纹（`source_scope=USER`）在入库时**直接调用 `save()` 落地为 `APPROVED` 状态**，未经过 `DesignFingerprintServiceImpl.updateStatus()` 这条唯一会触发 `VectorSyncWorker.syncOnApprove()` 的路径。

结果：私有指纹**只存在于 MySQL `design_fingerprint` 表**，从未被写入 PGvector 的 `design_fingerprint_vector` 表。

`DesignFingerprintVectorRepository.upsert()` / `search()` 已经原生支持权限字段（`source_scope`/`owner_user_id`/`visibility`）和按用户过滤的查询逻辑，也就是说**表结构和检索 SQL 都已经为私有指纹的向量检索做好了准备，只差同步这一步**。

## 2. 影响范围

- `DesignKnowledgeIndex.tryVectorRetrieve*`（VECTOR_3B 通道）：永远查不到任何用户的私有指纹，个性化召回质量下降。
- 私有指纹目前只能通过 STRUCTURAL_3A 结构化降级通道被检索到（且该通道存在计划 02 描述的权限问题），语义相似度召回能力完全缺失。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/worker/FingerprintIngestWorker.java`（核心改动点）
- `src/main/java/com/yu/yuaifather/worker/VectorSyncWorker.java`（复用现有方法，无需改动）
- `src/main/java/com/yu/yuaifather/service/DesignFingerprintVectorRepository.java`（复用现有方法，无需改动）

## 4. 修改方案

### 4.1 注入 `VectorSyncWorker`（可选依赖）

在 `FingerprintIngestWorker` 构造函数中增加 `VectorSyncWorker` 参数，标注 `@Autowired(required = false)`，与 `DesignFingerprintServiceImpl` 中的做法保持一致（因为 `VectorSyncWorker` 受 `@ConditionalOnBean` 约束，向量功能关闭时该 Bean 不存在，必须允许注入为 `null` 以实现优雅降级）。

```java
private final VectorSyncWorker vectorSyncWorker;

@Autowired
public FingerprintIngestWorker(GeneratedPageMapper generatedPageMapper,
                                DesignFingerprintService designFingerprintService,
                                @Autowired(required = false) VectorSyncWorker vectorSyncWorker) {
    this.generatedPageMapper = generatedPageMapper;
    this.designFingerprintService = designFingerprintService;
    this.vectorSyncWorker = vectorSyncWorker;
}
```

### 4.2 保存成功后触发同步

在 `doIngest()` 方法中，`saveAsUserPrivate()` 返回非空实体后，补充调用：

```java
DesignFingerprint saved = saveAsUserPrivate(dto, userId, projectId, pageId);
if (saved != null) {
    log.info("用户私有指纹入库成功 pageId={} fingerprintId={} styleProfile={}",
            pageId, saved.getId(), dto.getStyleProfile());
    if (vectorSyncWorker != null) {
        vectorSyncWorker.syncOnApprove(saved);
    }
}
```

`syncOnApprove()` 内部已经会读取 `saved.getSourceScope()`/`getOwnerUserId()`/`getProjectId()`/`getVisibility()`/`getSourceType()` 并原样写入 `design_fingerprint_vector`，`saveAsUserPrivate()` 构造实体时这些字段均已正确赋值（`sourceScope("USER")`、`ownerUserId(userId)`、`visibility("PRIVATE")`），因此**无需改动 `VectorSyncWorker`/`DesignFingerprintVectorRepository` 任何代码**。

### 4.3（可选）失败降级/删除时的向量清理

若未来支持用户删除私有指纹或页面被删除，需要同时调用 `vectorSyncWorker.deactivateOnRejectOrArchive(fingerprintId)`，本计划暂不涉及删除链路，仅覆盖新增同步。

## 5. 验证方式

1. 开启开关：`agent.fingerprint.user-ingest.enabled=true`、`agent.retrieve.vector.enabled=true`。
2. 触发一次用户页面保存/导出动作，观察日志中新增 `向量同步成功 fingerprintId=... profile=...`。
3. 直接查询 PGvector：`SELECT * FROM design_fingerprint_vector WHERE owner_user_id = <userId>`，确认记录存在且 `source_scope='USER'`、`visibility='PRIVATE'`。
4. 触发一次带该用户 `userId` 的检索（`DesignRetrieveTool` 传入 `queryUserId`），确认 `tryVectorRetrieveWithPersonalization` 命中该私有指纹（`channel=VECTOR_3B`）。
5. 用另一个用户 ID 检索，确认因 `search()` 中 `(source_scope='GLOBAL' OR owner_user_id=?)` 权限过滤，该私有指纹**不会**被检索到。

## 6. 风险评估

- **风险低**：改动仅新增一处方法调用，复用已有、已在精选指纹链路验证过的 `VectorSyncWorker.syncOnApprove()`，不改变任何现有 SQL/表结构。
- 向量同步失败不阻塞主流程（`syncOnApprove()` 内部已 try-catch 吞异常，仅记录日志），不会引入新的稳定性风险。
- 需要注意：`buildSurfaceFingerprint()` 产出的是"简易正则指纹"，语义文本（`buildSemanticText`）信息量可能偏少，embedding 质量可能不如 LLM 提炼的精选指纹，建议实施后观察实际召回效果，必要时调整 `minSimilarity` 阈值。

## 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-02
- **改动内容**：
  1. `FingerprintIngestWorker` 构造函数新增 `@Autowired(required = false) VectorSyncWorker vectorSyncWorker` 字段注入，与 `DesignFingerprintServiceImpl` 保持一致的优雅降级写法。
  2. `doIngest()` 中 `saveAsUserPrivate()` 返回非空实体后，追加 `vectorSyncWorker.syncOnApprove(saved)` 调用（`vectorSyncWorker != null` 判空保护）。
  3. 未改动 `VectorSyncWorker`/`DesignFingerprintVectorRepository`，方案 4.2 的复用假设成立。
- **涉及文件**：`src/main/java/com/yu/yuaifather/worker/FingerprintIngestWorker.java`
- **待办**：本次未实施 4.3（删除链路的向量清理），如后续支持用户删除私有指纹，需要补充调用 `vectorSyncWorker.deactivateOnRejectOrArchive(fingerprintId)`。
