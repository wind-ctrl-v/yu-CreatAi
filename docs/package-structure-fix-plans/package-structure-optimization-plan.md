# CreatAi 包结构优化改动计划

> 生成日期：2026-07-03
> 状态：问题 1/2/3/5 已完成，问题 6 部分完成（微型包合并已完成，枚举命名统一延后），问题 4 待评审（需先做先决条件确认）
> 优先级：见下表
> 配套文档：`docs/rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md`（问题 1 的详细实施记录）

## 问题清单总览

| # | 问题概述 | 涉及包 | 优先级 | 状态 |
|---|---|---|---|---|
| 1 | 用户私有指纹入库 / 向量同步存在双实现，向量同步补丁误落在未被调用的类上 | `worker/`、`service/` | P0 | ✅ 已完成（2026-07-03，见 [rag-fix-plans/06](../rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md)） |
| 2 | `AppController` 承担了应下沉到 Service 层的业务逻辑，文件过重（554 行） | `controller/` | P1 | ✅ 已完成（2026-07-03） |
| 3 | 两套编排器（V1/V2）分散在不同层级的包里，命名易混淆 | `orchestrator/`、`agent/orchestrator/` | P1 | ✅ 已完成（2026-07-03） |
| 4 | 增量编辑场景的文件操作工具游离在统一工具体系之外 | `agent/codegen/fileops/`、`tool/` | P2 | 待评审（未实施，需先做 4.1 先决条件确认） |
| 5 | 向量数据访问层与普通业务 Service 混放，缺少独立的 Repository 分层 | `service/` | P2 | ✅ 已完成（2026-07-03） |
| 6 | 若干微型包（1-2 个文件）拆分粒度过细，枚举命名不统一 | `annotation/`、`aop/`、`ratelimiter/`、`model/enums/` | P3 | 🟡 部分完成（微型包合并已完成；枚举命名统一延后未做） |

**全局排查结论**：针对问题 1 实施记录中提出的"可能存在第三例类似重复"的疑虑，本次已排查所有 Javadoc 含"改名重构而来"关键字的类（`ProjectProfileTool`/`DesignTokenTool`/`DesignRetrieveTool`/`UserPreferenceTool`/`ImageSearchTool`/`DeployTool`/`PreferenceUpdateWorker`），确认其对应的旧类名（`ProjectPreferenceAgent`/`DesignTokenAgent`/`DesignRetrieveAgent`/`UserPreferenceAgent`/`ImageAgent`/`DeployAgent`/`PreferenceUpdateService`）均已被彻底删除、不存在重复实现，仅问题 1（`FingerprintIngestWorker`/`UserFingerprintIngestService` 与 `VectorSyncWorker`/`VectorSyncService`）是历史遗留的两处真实重复，已修复完毕。

---

## 问题 1：用户私有指纹入库 / 向量同步双实现（已完成）

### 问题描述

`worker/FingerprintIngestWorker` 与 `service/UserFingerprintIngestService` 曾是同一功能的两份平行实现；`worker/VectorSyncWorker` 与 `service/VectorSyncService` 同理。前者的问题在于向量同步补丁一度被打在未被生产调用的类上，导致修复未生效。

### 处理结果

已于 2026-07-03 完成：
- 向量同步补丁迁移到生产实际调用的 `UserFingerprintIngestService`。
- 删除孤儿死代码 `FingerprintIngestWorker.java`、`VectorSyncService.java`。
- 保留类的 Javadoc 补充"当前生产唯一实现"声明。

详见 [rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md](../rag-fix-plans/06-duplicate-user-fingerprint-ingest-service.md) 的完整实施记录，本文档不再重复展开。

---

## 问题 2：`AppController` 业务逻辑下沉不彻底（已完成）

### 1. 问题描述

`AppController.java`（554 行）内直接内联了多处应属于 Service 层的业务逻辑，包括但不限于：

- 手工构造 `UserDesignEvent` 并调用 `preferenceUpdateWorker.recordAndUpdate()`（下载接口内，偏好事件记录逻辑）
- 用字符串关键字匹配（`result.contains("修改完成")`/`contains("失败")` 等）判断增量编辑 AI 响应是否成功（业务判定逻辑，且是脆弱的启发式字符串匹配）
- 直接拼装应用代码输出目录路径 `AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName`（路径构造逻辑）

Controller 层理论上应只做"接参数校验 → 调用 Service → 包装 `BaseResponse` 返回"，不应直接操作领域实体、执行字符串启发式判断或拼接路径。

### 2. 影响范围

- 业务逻辑分散在 Controller 中，单元测试难以覆盖（Controller 层测试通常依赖 MockMvc/完整 Spring 上下文，成本远高于纯 Service 单测）。
- 增量编辑成功与否的判定逻辑（字符串关键字匹配）本身脆弱、易受 AI 输出措辞变化影响，且藏在 Controller 里不易被复用/单独测试。
- 后续若需要在其他入口（如批量任务、内部 API）复用这些逻辑，将被迫复制粘贴 Controller 内的代码，而不是复用 Service 方法。

### 3. 涉及文件

- `src/main/java/com/yu/yuaifather/controller/AppController.java`（待精简）
- 新增：`src/main/java/com/yu/yuaifather/service/AppBehaviorService.java`（或类似命名，承接下沉的逻辑）

### 4. 修改方案

1. 新增 `AppBehaviorService`（或直接在现有 `AppService`/`IncrementalEditService` 补充方法），迁入：
   - `recordPageExportedEvent(Long userId, Long appId)`：封装"构造 `UserDesignEvent` + 调用 `preferenceUpdateWorker.recordAndUpdate()`"逻辑。
   - `judgeIncrementalEditSuccess(String aiResult)`：封装当前的字符串关键字判定逻辑（作为过渡方案；长期看应考虑让 AI 返回结构化 JSON 而非依赖关键字匹配，但这属于另一个独立话题，本次不展开）。
2. `AppController` 对应端点改为直接调用上述 Service 方法，自身只保留参数校验与 `BaseResponse` 包装。
3. 路径拼装逻辑（`sourceDirPath` 构造）迁移到 `ProjectDownloadService` 内部（该 Service 已存在，只是路径构造目前留在了 Controller）。

### 5. 验证方式

1. 迁移后运行现有的下载、增量编辑相关手工测试用例，确认行为不变。
2. 为新增的 `AppBehaviorService` 方法补充单元测试（`recordPageExportedEvent`、`judgeIncrementalEditSuccess` 的边界用例）。
3. 确认 `AppController` 行数明显下降，且不再直接 import `UserDesignEvent`/字符串判定相关逻辑。

### 6. 风险评估

- **风险低**：纯粹的代码搬移，不改变外部接口签名与行为。
- 需要注意：迁移时要保持异常处理策略不变（当前是 try-catch 后仅打 warn 日志、不影响主流程），搬到 Service 后也应保持同样的"失败不影响主流程"语义。

### 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-03
- **改动内容**：
  1. 新增 `service/AppBehaviorService.java`（`@Component`，参照 `PromptPreferenceExtractor` 的轻量风格，未建接口），承接 `recordPageExportedEvent`/`recordEditEvent`/`judgeIncrementalEditSuccess` 三个方法。
  2. `ProjectDownloadService` 接口新增 `resolveAndValidateSourceDir(App app)` 方法，`ProjectDownloadServiceImpl` 实现路径拼装 + 存在性校验逻辑（原在 Controller 内联）。
  3. `AppController` 精简：下载接口改为调用 `projectDownloadService.resolveAndValidateSourceDir()` + `appBehaviorService.recordPageExportedEvent()`；`incremental-edit` 接口改为调用 `appBehaviorService.judgeIncrementalEditSuccess()`；`record-edit-event` 接口改为调用 `appBehaviorService.recordEditEvent()`。移除不再使用的 `File`/`UserDesignEvent`/`PreferenceUpdateWorker` 直接依赖。
- **涉及文件**：新增 `AppBehaviorService.java`；修改 `ProjectDownloadService.java`、`ProjectDownloadServiceImpl.java`、`AppController.java`。
- **验证说明**：当前环境无 JDK/Maven，未能编译验证；已人工核对搬移前后逻辑等价（异常吞并语义、关键字判定规则均未改变）。

---

## 问题 3：编排器包结构分裂（已完成）

### 1. 问题描述

V1 编排器 `OrchestratorAgent` 位于 `agent/orchestrator/` 子包下，V2 编排器 `GenerationOrchestrator` 位于顶层 `orchestrator/` 包下，二者目录深度不一致，命名上容易让人误以为是同一层级的两个实现。此外，顶层 `orchestrator/` 包里还混入了 `DesignContext`/`DesignContextAssembler`/`DesignContextInput` 三个"设计上下文装配器"类，这三者职责上更接近 `service/design/` 领域下的数据组装服务，而不是编排器本身，只是因为"目前只有 `GenerationOrchestrator` 用它"就被放进了编排器包。

### 2. 影响范围

- 新成员或 AI 编码助手在搜索"编排器"相关代码时，容易只搜到一个包而漏掉另一个（正如本次评估过程中也是分两次分别读取了 `orchestrator/` 和 `agent/orchestrator/` 才拼出完整图景）。
- `DesignContext*` 三个类若后续被其他非编排器场景复用（例如未来的批量指纹分析任务），会出现"业务逻辑要 import 编排器包"的不自然依赖方向。

### 3. 涉及文件

- `src/main/java/com/yu/yuaifather/orchestrator/`（`GenerationOrchestrator.java`、`DesignContext.java`、`DesignContextAssembler.java`、`DesignContextInput.java`）
- `src/main/java/com/yu/yuaifather/agent/orchestrator/`（`OrchestratorAgent.java`）

### 4. 修改方案

1. 将两个编排器统一收敛到同一层级，建议路径：
   - `orchestrator/v1/OrchestratorAgent.java`
   - `orchestrator/GenerationOrchestrator.java`
2. `DesignContext`/`DesignContextAssembler`/`DesignContextInput` 迁移到 `service/design/` 包下（与 `DesignKnowledgeIndex`、`FingerprintProjector` 等同域类放在一起）。
3. 迁移后需要同步更新所有 import 路径（预计影响 `GenerationOrchestrator` 自身及任何引用 `DesignContext*` 的测试/调用代码）。

### 5. 验证方式

1. 全局搜索 `com.yu.yuaifather.orchestrator.DesignContext` / `com.yu.yuaifather.agent.orchestrator.OrchestratorAgent` 等旧包路径的 import，确认迁移后无残留。
2. 编译通过（当前环境无 JDK，需在具备编译环境时验证）。
3. Spring 组件扫描范围（`@ComponentScan`，若有自定义 base package 配置）需要确认包迁移后仍在扫描范围内（通常以 `com.yu.yuaifather` 为根包扫描，不受影响，但建议显式确认一次）。

### 6. 风险评估

- **中风险**：属于纯粹的包路径迁移（不改变类的行为），但涉及文件数量较多、影响面广，需要一次性完成并确保没有遗漏的旧 import。
- 建议使用 IDE 的"移动包/重命名"重构工具批量完成，而不是手工逐个修改 import，降低出错概率。

### 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-23
- **改动内容**：
  1. `GenerationOrchestrator.java` 归入 `com.yu.yuaifather.orchestrator` 顶层包，取消 `orchestrator.v2` 子包。
  2. V1 编排器已移除，实时生成链路收敛为 V2 → Classic。
  3. `DesignContext.java`/`DesignContextInput.java`/`DesignContextAssembler.java` 保持在 `service.design` 包。
  4. 更新 `GenerationPipelineRouter.java` 的编排器 import，并同步修正文档路径。
- **验证说明**：源码中已无 `com.yu.yuaifather.orchestrator.v2` 或 `orchestrator/v2` 引用；目标包仅保留 `GenerationOrchestrator.java`，并已通过 Maven 编译验证。
- **Spring 组件扫描确认**：`GenerationOrchestrator` 仍位于 `com.yu.yuaifather` 根包下，默认 `@SpringBootApplication` 扫描范围不受影响。

---

## 问题 4：增量编辑工具游离在统一工具体系之外

### 1. 问题描述

当前增量编辑仍使用 `agent/codegen/fileops/` 下基于 LangChain4j 的 `BaseTool`/`ToolManager` 体系。原先未接入运行链路的 `tool/core/AgentTool`、`ToolSchema`、`ToolRegistry` 以及 `tool/agent` 占位工具已在死代码清理中移除，避免保留一套不可达的重复工具注册体系。

### 2. 影响范围

- 增量编辑工具继续由 `ToolManager` 统一装配。
- 当前项目不再维护未接入的 Agent Tool 注册中心。

### 3. 涉及文件

- `src/main/java/com/yu/yuaifather/agent/codegen/fileops/`
- `src/main/java/com/yu/yuaifather/agent/codegen/AiCodeGeneratorServiceFactory.java`

### 4. 修改方案

无需迁移。后续新增工具应直接遵循现有 `BaseTool`/`ToolManager` 装配方式，并在对应的 AI 服务构建处注册。

### 5. 验证方式

1. 若执行迁移：确认增量编辑功能（`IncrementalEditService`）在迁移后行为不变，走一次真实的增量编辑请求验证。
2. 若仅补充文档说明：Review 确认两处 Javadoc 已经互相引用、说明清晰。

### 6. 风险评估

- **风险从低到高不等，取决于先决条件的确认结果**：如果两套工具调用协议本质不同，强行统一可能得不偿失，建议**先做 4.1 的先决条件确认**，很可能最终会得出"保持现状，仅补充文档说明"的结论（类比修复计划 05 的处理方式）。

---

## 问题 5：向量数据访问层包归属不当（已完成）

### 1. 问题描述

`DesignFingerprintVectorRepository` 基于 `JdbcTemplate` 直连 PGvector，本质是数据访问层（Repository 模式），却和 `AppService`/`UserService`/`DesignFingerprintService` 等业务 Service 混放在 `service/` 根目录下。它确实不适合放进 `mapper/`（因为不是 MyBatis-Flex 映射器接口，是手写原生 SQL），但当前的放置位置让"业务服务"与"数据访问层"的边界变得模糊。

### 2. 影响范围

- 纯粹是代码组织/可读性问题，不影响功能正确性。
- 长期看，如果后续再引入其他"原生 SQL 访问层"类（比如未来可能的其他向量库/搜索引擎接入层），会继续堆积在 `service/` 根目录下，加剧混乱。

### 3. 涉及文件

- `src/main/java/com/yu/yuaifather/service/DesignFingerprintVectorRepository.java`

### 4. 修改方案

1. 新建 `repository/` 包（与 `mapper/`、`service/` 同级）。
2. 将 `DesignFingerprintVectorRepository` 迁移到 `repository/` 包下。
3. 更新所有引用该类的 import（`DesignKnowledgeIndex`、`VectorSyncWorker`、`DesignFingerprintServiceImpl` 等）。

### 5. 验证方式

1. 全局搜索确认 import 路径全部更新。
2. 编译通过（需在具备编译环境时验证）。

### 6. 风险评估

- **风险极低**：纯包迁移，不改变任何逻辑，唯一工作量是更新 import。建议用 IDE 重构工具批量完成。

### 7. 实施记录

- **状态**：已实施
- **完成时间**：2026-07-03
- **改动内容**：`DesignFingerprintVectorRepository.java` 从 `service/` 迁移到新建的 `repository/` 包（包名改为 `com.yu.yuaifather.repository`），并在类头 Javadoc 补充包结构说明。更新 2 处引用（`VectorSyncWorker.java`、`DesignKnowledgeIndex.java`）以及 1 处 Javadoc `{@link}`/`{@see}` 引用（`DesignFingerprintVector.java`）。
- **涉及文件**：新增 `repository/DesignFingerprintVectorRepository.java`；删除 `service/DesignFingerprintVectorRepository.java`；修改 `VectorSyncWorker.java`、`DesignKnowledgeIndex.java`、`DesignFingerprintVector.java`。
- **验证说明**：全局搜索确认无 `com.yu.yuaifather.service.DesignFingerprintVectorRepository` 残留引用；`repository` 包在 Spring 默认组件扫描范围内（同问题 3 的扫描确认）。

---

## 问题 6：微型包过度拆分与枚举命名不统一（部分完成）

### 1. 问题描述

- `annotation/`（仅 `AuthCheck.java` 1 个文件）与 `aop/`（仅 `AuthInterceptor.java` 1 个文件）实际是同一功能（鉴权注解 + 对应拦截器）的一体两面，却分成了两个包。
- `ratelimiter/` 内部又拆了 `annotation/`（1 文件）、`aspect/`（1 文件）、`config/`（1 文件）、`enums/`（1 文件）四层子包，每层仅 1 个文件，粒度过细。
- `model/enums/` 下枚举命名不统一：`FingerprintStatus`（无 `Enum` 后缀）与 `EventTypeEnum`/`CodeGenTypeEnum`（有后缀）、`GenerationMode`/`PlanType`/`PipelineType`/`UserRoleEnum`（有的有后缀、有的没有）混杂。

### 2. 影响范围

- 纯代码组织问题，不影响功能，但增加了新成员理解项目结构的认知成本（"为什么鉴权要分两个包""为什么限流要分四层子包"）。

### 3. 涉及文件

- `src/main/java/com/yu/yuaifather/annotation/AuthCheck.java`
- `src/main/java/com/yu/yuaifather/aop/AuthInterceptor.java`
- `src/main/java/com/yu/yuaifather/ratelimiter/**`（4 个子包）
- `src/main/java/com/yu/yuaifather/model/enums/*.java`（8 个枚举文件）

### 4. 修改方案

1. 合并 `annotation/` 与 `aop/` 为单一 `auth/` 包（或将 `AuthCheck` 迁入 `aop/`，二者选一，保持鉴权相关代码聚合）。
2. 将 `ratelimiter/annotation`、`ratelimiter/aspect`、`ratelimiter/config`、`ratelimiter/enums` 四层子包扁平化为单层 `ratelimiter/`。
3. 枚举命名统一（建议方向：**全部不加 `Enum` 后缀**，因为包名本身已经是 `enums`，类名再加后缀是冗余信息），将 `EventTypeEnum`→`EventType`、`CodeGenTypeEnum`→`CodeGenType`、`UserRoleEnum`→`UserRole`、`ChatHistoryMessageTypeEnum`→`ChatHistoryMessageType`，同步更新所有引用点。

### 5. 验证方式

1. 全局搜索确认重命名后的枚举类无残留旧类名引用（包括 `@Value`/数据库映射等隐式引用点，需格外小心枚举值的持久化格式不受类名变化影响——只要不改变枚举的 `value` 字段本身即可，类名变化不影响数据库存储的字符串值）。
2. 编译通过（需在具备编译环境时验证）。

### 6. 风险评估

- **风险低**：包合并/扁平化零逻辑风险；枚举重命名需要注意**类名变化不能影响持久化数据**——由于这些枚举都是通过显式的 `value` 字段（字符串）做数据库映射，而不是直接持久化 Java 类名，因此重命名是安全的，但仍建议改动后抽查一次数据库里已有的枚举值字符串，确认没有任何地方（如 SQL 迁移脚本、前端硬编码）直接依赖了 Java 类名而非 `value` 字段。
- 本条优先级最低，建议与其他优先级更高的改动合并到同一次重构窗口一起做，避免为了这类纯风格问题单独开一次 PR。

### 7. 实施记录

- **状态**：部分实施
- **完成时间**：2026-07-03
- **已完成（方案 4.1、4.2）**：
  1. 合并 `annotation/AuthCheck.java` + `aop/AuthInterceptor.java` → 新建 `auth/` 包（`AuthCheck.java`、`AuthInterceptor.java`），更新 4 处引用（`UserController`、`DesignFingerprintController`、`ChatHistoryController`、`AppController`）。原 `annotation/`、`aop/` 空目录已清理。
  2. `ratelimiter/annotation`、`ratelimiter/aspect`、`ratelimiter/config`、`ratelimiter/enums` 四层子包扁平化为单层 `ratelimiter/`（`RateLimit.java`、`RateLimitType.java`、`RateLimitAspect.java`、`RedissonConfig.java`），更新 1 处引用（`AppController`）。原 4 个空子目录已清理。
- **未实施（方案 4.3，枚举命名统一）**：本次**未**执行 `EventTypeEnum`→`EventType` 等枚举重命名。原因：
  - 该项改动收益纯粹是命名风格一致性，不修复任何缺陷，价值相对最低；
  - 涉及 4 个枚举类的重命名，需要同步更新所有引用点（`switch`/`equals`/`import` 等），改动面比包合并大得多，而当前环境无 JDK/Maven 无法编译验证，风险收益比不佳；
  - 与本计划第 6 节风险评估的建议一致："本条优先级最低……避免为了这类纯风格问题单独开一次 PR"。
  - 建议：留待下次有编译环境验证条件、且顺带做其他改动的窗口再一并处理，不单独排期。
- **涉及文件**：新增 `auth/AuthCheck.java`、`auth/AuthInterceptor.java`、`ratelimiter/RateLimit.java`、`ratelimiter/RateLimitType.java`、`ratelimiter/RateLimitAspect.java`、`ratelimiter/RedissonConfig.java`；删除对应 6 个旧文件；修改 5 个引用方文件。
- **验证说明**：全局搜索确认无 `com.yu.yuaifather.annotation.`/`com.yu.yuaifather.aop.`/`com.yu.yuaifather.ratelimiter.(annotation|aspect|config|enums)` 残留引用。

---

## 建议实施顺序（已按此顺序执行）

1. **问题 1**：✅ 已完成，无需再做。
2. **问题 2**（`AppController` 精简）：✅ 已完成。
3. **问题 5**（Repository 包迁移）：✅ 已完成，与问题 2 一起做。
4. **问题 3**（编排器包统一）：✅ 已完成。
5. **问题 4**（fileops 工具统一）：待评审，需先做 4.1 的先决条件确认，可能最终结论是"保持现状仅补充文档"，优先级可以放低。
6. **问题 6**（微型包合并 + 枚举命名）：🟡 微型包合并已完成；枚举命名统一延后未做（原因见问题 6 实施记录）。

**本次未处理事项**（供下次窗口参考）：
- 问题 6 的枚举命名统一（`EventTypeEnum`→`EventType` 等）未做。
- 本次清理已通过 `./mvnw clean -DskipTests compile` 验证。
