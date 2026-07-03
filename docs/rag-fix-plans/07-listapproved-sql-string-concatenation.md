# 修改计划 07：`listApproved()` 权限过滤条件存在 SQL 字符串拼接

> 生成日期：2026-07-03
> 状态：✅ 已实施（2026-07-03）
> 优先级：高（安全/SQLi 规范）

## 1. 问题描述

计划 02 上线后，`DesignFingerprintServiceImpl.listApproved()` 因 mybatis-flex `QueryWrapper.and(Consumer<QueryWrapper>)` 重载在实际编译环境中存在歧义（原实施记录已承认判断失误），被另一位协作者改写为原始 SQL 片段拼接：

```java
qw.and("(visibility IS NULL OR visibility = 'PUBLIC' OR (visibility = 'PRIVATE' AND owner_user_id = "
        + currentUserId + "))");
```

`currentUserId` 被直接拼接进 SQL 字符串，而非通过参数绑定传递。当前风险等级为**低**（`currentUserId` 类型是 `Long`，来源于 `userContext.getUserId()`/JWT 会话，不是外部可控字符串，拼接结果只能是纯数字），但这**违反项目安全规范"SQLi 应做值绑定"的强制要求**，且是一个容易被后续开发者复制的坏模式——一旦类似写法被用于处理字符串类型参数（如 `styleProfile`、`visibility` 本身若来自用户输入），就会成为真实的注入点。

## 2. 影响范围

- `DesignFingerprintServiceImpl.listApproved(String styleProfile, int limit, Long currentUserId)`，仅此一处拼接。
- 该方法是 `STRUCTURAL_3A` 结构化召回通道的核心查询，调用方为 `DesignKnowledgeIndex` 的两个 `retrieve(...)` 重载。

## 3. 涉及文件

- `src/main/java/com/yu/yuaifather/service/impl/DesignFingerprintServiceImpl.java`

## 4. 修改方案

### 4.1 采用 mybatis-flex 原生的模板化参数绑定

反编译 `mybatis-flex-core-1.11.0.jar` 确认 `QueryWrapper` 存在如下方法签名（`javap`/常量池核实，非猜测）：

```
and(String, Object...)  → QueryWrapper
or (String, Object...)  → QueryWrapper
```

即支持 `"... = ?"` 占位符模板 + 可变参数的方式做参数绑定（底层走 `PreparedStatement`，非字符串拼接）。据此将拼接改为：

```java
if (currentUserId != null) {
    qw.and("(visibility IS NULL OR visibility = 'PUBLIC' OR (visibility = 'PRIVATE' AND owner_user_id = ?))",
            currentUserId);
} else {
    qw.and("(visibility IS NULL OR visibility = 'PUBLIC')");
}
```

无 `currentUserId` 参与拼接的分支不受影响，保持原样。

### 4.2（不采纳）改回 Consumer 嵌套写法

计划 02 最初采用的 `qw.and(w -> w.isNull(...).or(sub -> ...))` 写法在实际编译环境下报重载歧义错误，已被验证不可行，不再作为候选方案。

## 5. 验证方式

1. 构造用户 A、B 各一条 `visibility=PRIVATE` 的 `APPROVED` 指纹，以用户 B 触发 `listApproved(profile, limit, B)`，断言不包含用户 A 的记录（回归计划 02 的权限验证场景，确认参数绑定未改变查询语义）。
2. 抽查生成的 SQL 日志（开启 mybatis-flex SQL 打印），确认 `owner_user_id = ?` 以占位符形式出现，参数值以绑定参数形式传递，而非拼接进 SQL 文本。
3. 建议后续补充单元测试覆盖该方法的三种分支（`currentUserId=null`/`currentUserId=A`/无匹配）。

## 6. 风险评估

- **风险极低**：改动范围仅一个方法内的两行代码，查询语义完全不变，仅将参数传递方式从字符串拼接改为占位符绑定。
- 需注意：`and(String, Object...)` 的可变参数个数必须与模板中 `?` 的个数一致，本次模板只有一个 `?`，与传入的单个 `currentUserId` 一一对应，无歧义风险。

## 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-03
- **改动内容**：将 `qw.and("... owner_user_id = " + currentUserId + "))")` 的字符串拼接写法替换为 `qw.and("... owner_user_id = ?))", currentUserId)` 的占位符参数绑定写法。`currentUserId` 为空时的分支（仅公共指纹）不受影响。
- **涉及文件**：`DesignFingerprintServiceImpl.java`（第 148-160 行附近）。
- **验证说明**：已反编译 `mybatis-flex-core-1.11.0.jar` 确认 `QueryWrapper.and(String, Object...)` 方法签名真实存在（class 文件常量池方法表核实，非文档推测）；当前环境无可用 JDK/Maven，未能执行编译与集成测试，建议具备编译环境后按第 5 节验证方式确认。
