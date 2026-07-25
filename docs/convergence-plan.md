# CreatAi 项目收敛计划

> 生成日期：2026-07-23  
> 文档状态：待评审  
> 适用范围：后端、前端、生成链路、依赖、配置、SQL、测试与项目文档  
> 前置文档：`docs/package-structure-fix-plans/package-structure-optimization-plan.md`

## 1. 收敛目标

本轮收敛不以“减少文件数量”为唯一目标，而是建立清晰、可验证、可持续演进的代码边界：

1. 删除确定无引用、已被替代或不应位于生产源码中的组件。
2. 合并重复的鉴权、AI Service 装配、偏好写入、异步执行和前端列表逻辑。
3. 将当前按技术层堆叠的包结构逐步调整为按业务域组织。
4. 保留 V2 → Classic 降级能力，在 V2 完成闭环验证前不删除 Classic 链路。
5. 修复会阻碍安全重构的 AuthZ、SSRF、iframe 隔离和 SSE 重放问题。
6. 统一代码、配置、数据库脚本、前后端契约和文档的事实来源。
7. 每个批次可独立编译、测试、回滚，不进行“大爆炸式”重构。

## 2. 当前基线

### 2.1 验证结果

| 检查项 | 当前结果 | 说明 |
|---|---|---|
| 后端编译 | 通过 | `./mvnw -q -DskipTests compile` |
| 后端测试 | 通过 | `./mvnw -q test`，但当前仅有 2 个测试类 |
| 后端静态检查 | 14 个警告 | 主要为无用字段/import、弃用 API、未使用方法 |
| 前端静态检查 | 无诊断错误 | 不代表生产构建通过 |
| 前端生产构建 | 失败 | `vite.config.ts` 无法解析 `node:url`，缺少 Node 类型定义 |

### 2.2 已完成事项，不应重复实施

以下迁移已经基本落地：

- 旧 `agent.codegen` 已迁移至独立 `codegen` 包。
- V1 编排器已删除，当前生成入口已收敛为 `GenerationPipelineRouter`。
- 主链路已形成 V2 失败后降级 Classic 的两级结构。
- `DesignContext`、`DesignContextInput`、`DesignContextAssembler` 已迁至 `service.design`。
- `DesignFingerprintVectorRepository` 已迁至 Repository 层。

后续工作应以清理残留引用、修复运行语义和建立业务域边界为主，不再重复上述包迁移。

## 3. 核心问题清单

### 3.1 P0：正确性与安全问题

| 编号 | 问题 | 影响 | 处理方向 |
|---|---|---|---|
| P0-1 | 增量编辑接口仅验证登录，未验证 App 所有权 | 登录用户可能修改他人项目 | 统一通过 `AppAccessService` 做 owner/admin 校验 |
| P0-2 | 外部网页抓取和截图缺少完整 SSRF 防护 | 可能访问内网、环回或云元数据地址 | 建立统一 `SafeUrlValidator`，校验 DNS、IP 和重定向 |
| P0-3 | 生成预览 iframe 同时允许脚本与同源 | 不可信生成代码可能突破页面隔离 | 独立预览域名 + `postMessage`，移除 `allow-same-origin` |
| P0-4 | 代码生成 SSE 断线自动重连会重新执行 GET 请求 | 重复生成、重复扣额度、重复写历史 | 拆分“创建任务”和“订阅事件”，引入幂等 `requestId` |
| P0-5 | `PageGenerateAgent` 复用 `generatePlan()` 且未形成明确文件产物 | V2 可能显示成功但没有可部署文件 | 增加专用生成接口并统一写入文件和 Blackboard |
| P0-6 | `EmbeddingModelConfig` 无条件读取缺失配置 | 运行时可能在 Bean 初始化阶段失败 | 使用条件 Bean 与类型化配置 |
| P0-7 | 公开分享页挂在会主动获取登录态的布局下 | 匿名分享链接可能跳转登录页 | 使用独立 `PublicLayout` 或可选登录态请求 |
| P0-8 | 开发环境请求失败时注入 Mock 管理员 | 掩盖后端故障和真实鉴权问题 | 删除自动 Mock，改为显式、默认关闭的开发开关 |

#### P0-1：统一 App 权限校验

需要覆盖：

- `GET /app/incremental-edit-stream`
- `POST /app/incremental-edit`
- 代码生成、部署、下载、更新、删除等现有写操作

建议新增：

```text
AppAccessService
├── requireExisting(appId)
├── requireOwner(appId, userId)
└── requireOwnerOrAdmin(appId, user)
```

Controller 不再自行复制“查询 App → 判空 → 比较 userId”的逻辑。

#### P0-2：SSRF 防护要求

统一 URL 校验组件必须：

- 仅允许 `http`、`https`。
- 拒绝包含用户名、密码的 URL。
- 解析 DNS 后校验全部 IPv4/IPv6 地址。
- 拒绝 loopback、link-local、multicast、保留地址和内网地址。
- 明确拒绝 `9.*`、`10.*`、`11.*`、`21.*`、`30.*` 等内部地址。
- 每次跳转后重新校验目标地址。
- 限制重定向次数、响应大小、连接和读取超时。
- 日志仅记录经过清洗的 URL，不输出凭据和敏感查询参数。

### 3.2 P1：重复职责与结构问题

| 编号 | 问题 | 当前表现 |
|---|---|---|
| P1-1 | `AppController` 职责过多 | CRUD、管理、生成、增量编辑、部署、下载、反馈混在同一类 |
| P1-2 | `AppServiceImpl` 职责过多 | 查询转换、生成入口、部署、截图、删除级联、创建路由混合 |
| P1-3 | AI Service 装配分散 | 多个 Factory 和 Service 重复创建 LangChain4j AiService |
| P1-4 | 偏好/反馈写入边界不清 | 多个 Service/Worker 同时处理事件、权重和标签 |
| P1-5 | 异步方式不统一 | `new Thread()`、虚拟线程和线程池并存 |
| P1-6 | `manager`、`worker`、`service` 包语义过宽 | 基础设施和业务逻辑按技术名称集中堆放 |
| P1-7 | 前端大页面和列表逻辑重复 | `AppChatPage` 过大，应用列表和管理表格存在重复模式 |
| P1-8 | 前后端 DTO 漂移 | 管理端发送 `codeGenType`，后端更新 DTO 不接收 |

### 3.3 P2：依赖、配置、SQL 与文档漂移

- LangChain4j `1.1.0` 与 `1.1.0-beta7` 混用。
- `src/main/java/dev/langchain4j` 下有 8 个同包源码，可能静默覆盖依赖 JAR。
- 本地覆盖源码中仍有调试 `System.out.println`。
- `application.yml` 同时存在 `agent.orchestrator.enabled` 和 `agent.orchestrator.v2.enabled` 双重开关。
- 部分配置只有 `@Value` 默认值，没有集中声明和校验。
- `docs/sql` 混放正式迁移、种子、运维和测试脚本。
- README 和部分设计文档仍描述已删除的 Agent、V1 编排器和旧服务。
- 前端同时存在 `vite.config.ts`、`vite.config.js`、`vite.config.d.ts`。

## 4. 删除、合并与保留决策

### 4.1 第一批确定删除

| 目标 | 原因 | 删除后验证 |
|---|---|---|
| `manager/ServeLifecycleManager.java` | 仅服务于旧本地 serve 启动流程 | 应用启动不再触发 serve 进程 |
| `service/ServeDeployService.java` | 平台相关命令且已被当前部署方式替代 | 部署、静态资源访问正常 |
| `prompt/agent-image-system-prompt.txt` | ImageAgent 已删除且无代码引用 | 全局搜索无引用 |
| `prompt/agent-deploy-system-prompt.txt` | DeployAgent 已由确定性 `DeployTool` 替代 | 部署链路正常 |
| `AppServiceImpl.aiCodeGeneratorFacade` | 字段未使用，实际入口是 `GenerationPipelineRouter` | 后端编译通过 |
| LangChain4j 补丁中的调试输出 | 可能泄露工具调用参数 | 日志无调试输出 |
| 无用 import、无用方法和过时抑制注解 | 降低噪声 | linter 警告减少 |

`agent-product-system-prompt.txt` 仍被 `AgentAiService` 引用，不在删除范围内。

### 4.2 待迁出生产源码

- `generator/MyBatisCodeGenerator.java`：删除，或移动到独立工具模块，不再参与生产编译。
- `mybatis-flex-codegen`：生成器迁出后再从主依赖删除。
- `vite.config.js`、`vite.config.d.ts`：确认是生成产物后删除，只保留 `vite.config.ts`。

### 4.3 需要合并的能力

#### App 权限

所有 App 写操作只通过 `AppAccessService` 校验，不允许 Controller 各自实现。

#### AI Service 装配

抽取 `AiServiceFactorySupport`，统一：

- ChatModel/StreamingChatModel 选择。
- MemoryProvider 创建。
- 公共超时和日志策略。
- 工具注册公共逻辑。

各业务接口、Prompt 和工具列表继续保留在所属业务域，不创建“万能 AI Service”。

#### 偏好与反馈

目标职责：

```text
PreferenceEventRecorder     只记录行为事件
PreferenceUpdateWorker      唯一权重写入入口
UserPreferenceQueryService  唯一偏好读取入口
PromptPreferenceExtractor   只负责提示词解析
```

`NegativeFeedbackService`、`FingerprintFeedbackService`、`AppBehaviorService` 不再直接修改权重，只提交事件。

#### 异步执行

将直接创建线程的代码统一为：

- 有界 `TaskExecutor`；或
- Spring Application Event + 异步监听器。

必须定义队列容量、拒绝策略、超时、错误日志和事务边界。

### 4.4 明确保留的能力

- 保留 `GenerationPipelineRouter` 作为唯一生成入口。
- 保留 Classic 作为 V2 的稳定降级链路。
- 保留 `Agent` 和 `SystemTool` 两种抽象：前者是 LLM 驱动的流式工作者，后者是确定性工具。
- 保留 `AppCard`，当前首页、我的应用、精选应用均在复用。
- 保留截图能力，但应迁入 media 域并补 SSRF 防护。

不建议强行合并 `Agent` 与 `SystemTool`，两者语义和生命周期不同。

## 5. 后端目标结构

采用按业务域组织、域内再分层的结构：

```text
com.yu.yuaifather
├── shared
│   ├── web
│   ├── security
│   ├── config
│   ├── exception
│   └── infrastructure
├── user
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── app
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── generation
│   ├── application
│   ├── classic
│   ├── agent
│   ├── stream
│   └── infrastructure
├── design
│   ├── application
│   ├── domain
│   ├── retrieval
│   └── infrastructure
├── preference
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
└── media
    ├── image
    ├── screenshot
    └── storage
```

### 5.1 依赖规则

- 固定方向：`api → application → domain`。
- `infrastructure` 实现 application/domain 定义的端口。
- 禁止 `service ↔ agent`、`service ↔ codegen` 双向依赖。
- Mapper、Entity、Worker 跟随所属业务域，不再集中堆放。
- `manager` 顶层包取消，COS、本地图片、截图归入 `media.infrastructure`。
- `worker` 顶层包取消，偏好、向量、指纹任务归入对应业务域。
- 跨域调用通过 application service 或明确端口，不直接依赖对方 Mapper。

### 5.2 App 域拆分

`AppController` 建议拆为：

```text
app/api/
├── AppCommandController
├── AppQueryController
├── AdminAppController
├── GenerationController
└── AppArtifactController
```

职责：

- `AppCommandController`：创建、修改、删除。
- `AppQueryController`：详情、我的应用、精选应用。
- `AdminAppController`：管理员查询和操作。
- `GenerationController`：生成、增量编辑、事件流。
- `AppArtifactController`：部署、下载、预览相关操作。

`AppServiceImpl` 建议拆为：

```text
AppApplicationService
AppQueryService
AppDeploymentService
AppAccessService
```

额外修正：`getAppVOList()` 已批量查询用户，但内部继续调用会单独查询用户的 `getAppVO()`，应改为纯转换，真正消除 N+1 查询。

### 5.3 Generation 域收敛

目标主链：

```text
GenerationController
  → GenerationApplicationService
    → GenerationPipelineRouter
      ├── V2 GenerationOrchestrator
      └── Classic AiCodeGeneratorFacade
    → StreamHandlerExecutor
```

要求：

- Controller 不处理路由、降级、历史写入和额度扣减。
- `GenerationPipelineRouter` 是唯一链路选择入口。
- `GenerationApplicationService` 统一鉴权、幂等、历史和任务生命周期。
- `PageGenerateAgent`、`CoderAgent`、`StyleAgent` 最终必须产出统一的 `GeneratedArtifact`。
- 规则检查、部署和运行记录只消费统一产物，不读取临时事件文本。

建议统一产物模型：

```text
GeneratedArtifact
├── generationId
├── appId
├── codeGenType
├── workingDirectory
├── files
├── entryFile
└── metadata
```

## 6. 前端目标结构

```text
frontend/src/
├── app
├── features
│   ├── auth
│   ├── applications
│   ├── generation
│   ├── preference
│   └── admin
└── shared
    ├── api
    ├── components
    ├── composables
    ├── layouts
    ├── types
    └── utils
```

### 6.1 前端具体收敛项

1. 修复构建基线：增加匹配版本的 `@types/node`，配置 Node 类型。
2. 将 `AppChatPage.vue` 拆为：
   - `useGenerationStream`
   - `useIncrementalEdit`
   - `GenerationTimeline`
   - `PreviewFrame`
   - `ChatPanel`
   - `VisualEditorPanel`
3. 登录和注册页共用 `AuthLayout`、表单容器和校验样式。
4. 首页、我的应用、精选应用共用应用列表查询 composable 和分页状态。
5. 管理后台抽取通用分页、搜索、删除确认和错误处理逻辑。
6. 前端 API 按业务域拆分，类型不再全部集中于 `types/index.ts`。
7. 管理端 `codeGenType` 更新必须与后端 DTO 对齐：要么后端支持，要么前端移除编辑能力，禁止静默忽略。
8. 公开分享页使用独立 `PublicLayout`，不触发强制登录流程。
9. 删除请求失败时自动注入 Mock 管理员的逻辑。

## 7. 依赖与配置收敛

### 7.1 LangChain4j

当前风险：

- 核心依赖使用 `1.1.0`。
- Starter、Reactor、Redis Starter 使用 `1.1.0-beta7`。
- `src/main/java/dev/langchain4j` 下存在 8 个与依赖同包同名的源码。

处理顺序：

1. 为每个本地补丁记录来源版本、修改原因和对应问题。
2. 选择并统一 LangChain4j 兼容版本。
3. 用兼容性测试验证流式输出、工具调用、Memory 和 Reactor。
4. 能由升级解决的补丁直接删除。
5. 必须保留的补丁迁至独立 patch 模块，禁止继续散落在主源码。

### 7.2 冗余依赖候选

通过 `mvn dependency:analyze` 和实际运行验证后处理：

- `org.jetbrains:annotations`
- 显式 `lettuce-core`
- 显式 `jackson-datatype-jsr310`
- `com.pgvector:pgvector`
- 显式 `HikariCP`
- `mybatis-flex-codegen`

不得仅依据“没有直接 import”删除运行期或 Starter 依赖。

### 7.3 类型化配置

建议按域建立：

```text
GenerationProperties
AgentProperties
ImageProperties
PreviewProperties
StorageProperties
SecurityProperties
```

要求：

- 使用 `@ConfigurationProperties` 和启动期校验。
- 合并 `agent.orchestrator.enabled` 与 `agent.orchestrator.v2.enabled`。
- 删除没有消费者的配置，或明确接入点。
- 所有凭据只从环境变量读取。
- 模型请求和响应日志在生产环境默认关闭。
- 配置文档列出默认值、作用域和启用条件。

## 8. SQL 与数据演进收敛

当前 `docs/sql` 同时包含正式迁移、种子、运维、同步和测试脚本，缺少唯一执行入口。

建议结构：

```text
db/
├── migration
├── seed
├── ops
└── test
```

规则：

- 选择 Flyway 或 Liquibase 作为唯一 schema 演进入口。
- 正式迁移必须版本化、不可修改已执行版本。
- 种子脚本必须可重复执行或有明确环境限制。
- 向量同步、批处理脚本进入 `ops`，不混入迁移。
- 检索验证和临时测试 SQL 进入 `test`。
- Java Entity、Mapper、DTO 与迁移脚本在同一批次核对。
- 对 `003_project_brand.sql`、`020_project_brand_profile.sql` 做字段重叠和演进关系复核。

## 9. 文档收敛

现有 Markdown 文档建议分为：

```text
docs/
├── current      当前有效架构与操作文档
├── adr          架构决策记录
├── plans        未完成计划
└── archive      已完成计划、旧方案和阶段总结
```

处理规则：

- README 只描述当前可运行架构。
- 已完成计划移入 `archive`，保留结论和完成日期。
- 旧 Agent、V1 编排器、已删除服务引用全部清理。
- 同一主题只保留一个 current 文档，其余建立链接或归档。
- 代码、配置或 SQL 发生结构变化时，同一批次更新 current 文档。

## 10. 分阶段执行计划

### 阶段 0：冻结基线与补测试（1～2 天）

- [x] 修复前端 `node:url` 类型问题，使 `npm run build` 通过。
- [ ] 为 App 所有权校验补单元/集成测试。
- [ ] 为 V2 → Classic 路由和降级补测试。
- [ ] 为生成额度幂等和历史写入补测试。
- [ ] 建立核心 API 和生成链路冒烟清单。

退出标准：后端编译/测试和前端构建全部通过，后续重构有可重复基线。

### 阶段 1：P0 正确性与安全（2～4 天）

- [x] 增量编辑补所有权校验。
- [x] 统一外部 URL 的 SSRF 防护。
- [x] 将预览迁至隔离域或先关闭危险 sandbox 组合。
- [ ] 将生成执行与 SSE 订阅拆分，引入幂等键。
- [x] 修复公开分享页登录跳转。
- [x] 删除自动 Mock 管理员。
- [x] 修复 Embedding 条件配置。

退出标准：所有写接口均有服务端 AuthZ，所有外部 URL 经过统一校验，SSE 重连不重复执行任务。

### 阶段 2：低风险删除（1 天）

- [x] 删除旧 serve 流程。
- [x] 删除无引用 Prompt。
- [x] 删除 `AppServiceImpl` 未使用字段。
- [x] 删除调试输出和无用代码。
- [x] 迁出 MyBatis 生成器。
- [x] 清理 Vite 配置生成物。
- [ ] 全局搜索被删符号，清理 Javadoc 和文档引用。

退出标准：后端编译/测试、前端构建和应用启动均通过。

### 阶段 3：生成链路收敛（3～5 天）

- [x] 修复 `PageGenerateAgent` 专用生成接口。
- [x] 引入统一 `GeneratedArtifact`。
- [x] 统一生成文件保存和 Blackboard 写入。
- [ ] 收敛 AI Service 公共装配。
- [x] 合并双重编排开关。
- [ ] 验证 low_cost、standard、high_quality 三种模式。
- [ ] 验证 V2 失败时 Classic 降级。

退出标准：三种 V2 模式都能生成真实文件、通过规则检查并完成部署；Classic 降级仍有效。

### 阶段 4：偏好与异步任务收敛（3～4 天）

- [ ] 建立唯一事件记录入口。
- [ ] 建立唯一权重写入入口。
- [ ] 合并 `PersonalizedQueryBuilder` 的重叠职责。
- [x] 将 Controller 和 Service 中的直接线程创建迁至统一执行器或事件机制。
- [ ] 用固定用户行为序列验证收敛前后权重结果一致。

退出标准：偏好计算结果行为等价，异步任务有界、可观测、可测试。

### 阶段 5：后端按域整理包结构（5～8 天）

推荐顺序：

1. `preference`
2. `design`
3. `media`
4. `generation`
5. `app`
6. `user` 和 `shared`

每个域单独提交，只移动代码和修复依赖，不夹带功能修改。

退出标准：顶层 `manager`、`worker` 和过宽 `service` 包完成归位，不存在已禁止的双向包依赖。

### 阶段 6：前端模块化（3～5 天）

- [ ] 拆分 `AppChatPage.vue`。
- [ ] 抽取认证布局。
- [ ] 抽取应用列表查询和分页能力。
- [ ] 抽取管理后台通用表格逻辑。
- [ ] 按 feature 拆分 API 和类型。
- [ ] 对齐前后端 DTO。

退出标准：大页面职责清晰，重复请求/列表逻辑复用，生产构建通过。

### 阶段 7：依赖、SQL、配置和文档收尾（2～4 天）

- [ ] 统一 LangChain4j 版本并处置本地覆盖源码。
- [ ] 删除确认无用的 Maven 依赖。
- [ ] 完成类型化配置迁移。
- [ ] 建立唯一数据库迁移入口。
- [ ] 整理 current/adr/plans/archive 文档。
- [ ] 更新 README 和部署说明。

退出标准：依赖来源明确、配置可校验、数据库可从空库顺序迁移、当前文档与代码一致。

## 11. 提交与回滚策略

每个提交只包含一种性质的修改：

1. 安全或正确性修复。
2. 纯删除。
3. 纯移动和改包名。
4. 行为保持的重复代码抽取。
5. 功能变更。
6. SQL 或配置变更。

禁止在同一提交中同时进行大范围移动、行为修改和数据库变更。

每阶段保留原入口直到新入口通过验证；迁移调用方后再删除旧实现。生成链路必须始终保留可用的 Classic 降级路径。

## 12. 验收标准

### 12.1 自动验证

- [x] `./mvnw clean test` 通过。
- [x] `cd frontend && npm run build` 通过。
- [ ] 后端无新增 linter error，警告数量持续下降。
- [ ] 全局搜索被删类名、Prompt 和旧包名无残留。
- [ ] 数据库可以从空库按迁移顺序初始化。

### 12.2 安全验证

- [ ] 所有 App 写操作验证 owner 或 admin 权限。
- [ ] 外部 URL 拒绝内网、环回、保留地址和恶意重定向。
- [ ] 预览内容无法访问主站 Cookie、Storage 和 DOM。
- [ ] 日志不输出 API Key、Cookie、完整工具参数或敏感 URL 查询参数。
- [ ] SSE 重连不会重复创建任务、扣额度或写历史。

### 12.3 功能冒烟

- [ ] 登录、注册、退出和匿名分享。
- [ ] 创建、查询、更新、删除 App。
- [ ] Classic 生成。
- [ ] V2 三种模式生成和失败降级。
- [ ] 增量编辑。
- [ ] 下载、部署、预览和截图。
- [ ] 偏好事件、负反馈、权重更新和个性化检索。
- [ ] 管理端用户、应用、聊天和设计指纹操作。

### 12.4 架构验收

- [ ] Controller 不直接创建线程或实现复杂业务编排。
- [ ] `GenerationPipelineRouter` 是唯一生成链路选择入口。
- [ ] 偏好权重只有一个写入入口。
- [ ] App 权限只有一个公共校验入口。
- [ ] `Agent` 与 `SystemTool` 边界有明确文档和调用约束。
- [ ] 不存在 `service ↔ agent`、`service ↔ codegen` 双向包依赖。
- [ ] `src/main/java/dev/langchain4j` 不再静默覆盖依赖，或已被隔离为有版本记录的补丁模块。

## 13. 预计周期

| 阶段 | 预计时间 |
|---|---:|
| 基线与测试 | 1～2 天 |
| P0 正确性与安全 | 2～4 天 |
| 低风险删除 | 1 天 |
| 生成链路收敛 | 3～5 天 |
| 偏好与异步收敛 | 3～4 天 |
| 后端包结构迁移 | 5～8 天 |
| 前端模块化 | 3～5 天 |
| 依赖、SQL、配置、文档收尾 | 2～4 天 |

整体预计 3～5 周。优先确保每阶段均可运行、可验证、可回滚，不以一次性完成全部目录调整为目标。
