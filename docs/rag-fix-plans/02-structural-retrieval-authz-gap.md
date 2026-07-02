# 修改计划 02：结构化召回通道（STRUCTURAL_3A）权限隔离缺口

> 生成日期：2026-07-02
> 状态：待评审（未实施）
> 优先级：高（安全/AuthZ）

## 1. 问题描述

`DesignKnowledgeIndex` 在向量通道未命中时会降级走结构化召回通道 `STRUCTURAL_3A`：

```java
List<DesignFingerprint> fps = fingerprintService.listApproved(styleProfile, Math.max(k * 3, 9));
```

而 `DesignFingerprintServiceImpl.listApproved()` 的查询条件仅有：

```java
QueryWrapper qw = QueryWrapper.create().eq("status", FingerprintStatus.APPROVED.getValue());
if (styleProfile != null && !styleProfile.isBlank()) {
    qw.eq("styleProfile", styleProfile);
}
```

**没有任何 `ownerUserId`/`visibility`/`sourceScope` 过滤**。这意味着只要某用户 A 的私有指纹（`visibility=PRIVATE`, `ownerUserId=A`）状态为 `APPROVED`，且命中相同 `styleProfile`，任何其他用户 B 在触发 STRUCTURAL_3A 降级通道时都可能召回到用户 A 的私有指纹内容，构成跨用户数据越权访问（违反 AuthZ 安全原则）。

## 2. 影响范围

- `DesignKnowledgeIndex.retrieve()` / `retrieve(...userContext)` 两个重载方法中的 STRUCTURAL_3A 分支。
- 触发条件：向量检索关闭（`agent.retrieve.vector.enabled=false`）或向量检索未命中（如 embedding 服务异常、相似度不达阈值）时必然走到此分支。
- 结合计划 01（私有指纹将开始同步入向量库）后，触发窗口会缩小（多数场景优先走 VECTOR_3B 且已带权限过滤），但只要向量通道降级，风险依然存在，因此仍需独立修复。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/service/DesignFingerprintService.java`（接口签名变更）
- `src/main/java/com/yu/yuaifather/service/impl/DesignFingerprintServiceImpl.java`（核心改动点）
- `src/main/java/com/yu/yuaifather/service/design/DesignKnowledgeIndex.java`（调用方，需传入当前用户上下文）

## 4. 修改方案

### 4.1 扩展 `listApproved` 签名，增加当前用户上下文参数

```java
// DesignFingerprintService.java
List<DesignFingerprint> listApproved(String styleProfile, int limit, Long currentUserId);
```

为兼容既有调用点，可保留旧签名作为 `default` 方法转调新签名（`currentUserId = null` 表示不做私有过滤，仅限内部系统级调用使用，业务侵入点必须传真实用户 ID）。

### 4.2 查询条件补充权限过滤

```java
QueryWrapper qw = QueryWrapper.create()
        .eq("status", FingerprintStatus.APPROVED.getValue());
if (styleProfile != null && !styleProfile.isBlank()) {
    qw.eq("styleProfile", styleProfile);
}
// ★ 权限过滤：GLOBAL 全局公共指纹 or 当前用户自己的私有指纹
if (currentUserId != null) {
    qw.and(w -> w.eq("visibility", "PUBLIC")
                 .or(sub -> sub.eq("visibility", "PRIVATE").eq("ownerUserId", currentUserId)));
} else {
    // 未传用户上下文时，仅返回公共指纹，杜绝误用导致的越权
    qw.eq("visibility", "PUBLIC");
}
```

> 注：MySQL 侧 `design_fingerprint` 表需确认 `visibility` 字段默认值——精选来源（`EXTRACTED_FINGERPRINT`/`SELF_FED_FINGERPRINT`）当前入库时未显式设置 `visibility`，需要一并确认其默认是否为 `PUBLIC`（若为空，上述 `eq("visibility","PUBLIC")` 会漏检，需要改为 `visibility IS NULL OR visibility='PUBLIC'`，实施时需先核实历史数据）。

### 4.3 调用方传参

`DesignKnowledgeIndex` 中两处调用 `listApproved` 需要改为传入当前请求的 `userId`（`retrieve(...)` 方法已有 `userContext`/`queryUserId` 参数可复用，具体取值方式与 `tryVectorRetrieveWithPersonalization` 中获取 `queryUserId` 的逻辑保持一致）。

## 5. 验证方式

1. 构造两个测试用户 A、B，分别为其生成一条 `visibility=PRIVATE` 且 `styleProfile` 相同的 `APPROVED` 指纹。
2. 关闭向量检索开关（`agent.retrieve.vector.enabled=false`）强制走 STRUCTURAL_3A。
3. 以用户 B 的身份触发检索，断言返回结果中**不包含**用户 A 的私有指纹 ID。
4. 以用户 A 的身份触发检索，断言**能**召回自己的私有指纹。
5. 补充单元测试覆盖 `listApproved(styleProfile, limit, currentUserId)` 的三种场景：`currentUserId=null`、`currentUserId=A`、无权限匹配。

## 6. 风险评估

- **中风险**：涉及接口签名变更，需要排查所有调用点（目前已知仅 `DesignKnowledgeIndex` 两处，需二次确认无其他调用方，如后台管理列表页等）。
- 需先核实历史数据 `visibility` 字段的默认值分布，避免上线后存量公共指纹因 `visibility` 为空被误判为不可见（可能需要先跑一次数据修复脚本，将存量 `sourceScope!='USER'` 的记录统一补齐 `visibility='PUBLIC'`）。
- 建议与计划 01 一并上线，形成"向量通道 + 结构化降级通道"两条链路权限口径一致的闭环。
