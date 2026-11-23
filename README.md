# CreatAi

*AI website generator: describe a page, preview it, and share a local static link.*

基于 Java 21、Spring Boot 3.2.10（Spring MVC，生成接口返回 Reactor SSE）、MyBatis-Flex 1.11、MySQL 8（库名 `yu_ai_code_mother`）、Redis Session、Vue 3 + TypeScript + Vite 5（Vue Router 4、Pinia、Axios、原生 EventSource）的中文全栈 AI 网站生成器（Maven 构件 `yu-ai-father`），支持 `html` / `multi_file` / `vue_project` 生成、SSE 流式预览、对话与点选增量编辑、本地静态部署、封面截图、源码下载和套餐额度；可选 V2 多 Agent 只作用于 `vue_project`，失败回退经典生成，COS 上传由 `AGENT_DEPLOY_ENABLED` 控制且默认 `false`。

## 功能

| 模块 | 功能 |
|------|------|
| 生成 | `html`（单页）、`multi_file`（多文件、无路由）、`vue_project`（带路由，需本机 npm 构建出 `dist`）；SSE 流式预览；质量模式 `low_cost` / `standard` / `high_quality` |
| 编辑 | 继续对话修改；预览 HTML 注入选元素脚本，把标签、选择器和片段交给增量编辑 |
| 部署 | 生成成功后自动本地静态发布（与「部署」按钮同一路径），`deployKey` 为 `{codeGenType}_{appId}`；存在 `dist/` 时以 dist 为根；增量编辑只有写出新页面才自动部署；失败保留已生成代码 |
| 封面 | 部署成功后用 headless Chrome 异步截图，图片落在本机静态目录并写回封面；截图失败只记日志，链接仍可访问 |
| 流水线 | 默认经典生成。`AGENT_ORCHESTRATOR_V2_ENABLED=true` 后仅 `vue_project` 走 V2：规划 → 设计上下文 → 页面生成或代码/样式并发 → 规则检查（`package.json`、`index.html`、`src/main.js`、`src/App.vue`）→ 按模式 LLM 质检 → 仅 `AGENT_DEPLOY_ENABLED=true` 时上传 COS → 保存记录。`html` / `multi_file` 始终经典生成。`generation.pipeline.allow-classic-fallback` 默认开启 |
| 套餐 | `FREE` / `PRO` / `PREMIUM` / `ENTERPRISE` / `ADMIN` 封顶模式与每日额度；请求模式先按复杂度调整，再按套餐封顶，额度用尽向更低模式降级，三种都用尽仍落到 `low_cost` |
| 记忆 | JVM 内存窗口：最近轮次保留全文，更早的消息按规则压缩，超出 `agent.memory.char-budget`（默认 80000）从最早的消息丢弃。Redis 只保存压缩快照，不读回模型上下文 |
| 限流 | 生成与增量编辑 SSE 按用户每 60 秒 5 次。`app.limits.generation.max-concurrent` 与 `app.limits.deploy.max-concurrent` 默认 `0`（不限制） |
| 图片 | 生成代码使用 `@@IMG:english keywords@@`，关键词清洗为小写英文字母、数字和空格。查找顺序：Pixabay → Unsplash → Pexels → 文生图（默认关闭，结果存本机静态目录）→ loremflickr → picsum。返回的搜图 URL 必须是 HTTPS 且主机在白名单内，解析后的私网地址会被拒绝 |
| 账号 | 注册、登录、登出、当前用户、改昵称/头像/简介、套餐能力。登录态是 Redis Session Cookie。普通注册角色为 `user` |
| 精选 | `priority = 99` 的应用出现在首页和「精选应用」。管理员在后台把优先级改为 99 即可 |
| 管理 | 管理员管理用户、应用、对话记录和设计指纹 |
| 工程 | Redisson 限流、Knife4j、Hutool、Caffeine、Selenium。向量检索默认关闭，本地不必安装 PostgreSQL |

首页的视觉风格、布局和差异度会写进创建应用时的提示词，没有单独的接口字段。`low_cost` 使用更短的设计上下文，`high_quality` 会带上完整项目品牌信息。模式始终参与决策并记入当日额度。提示词命中「改颜色」「改一下」等简单修改关键词时降到 `low_cost`，命中「多页面」「整站」「动效」「后台管理」等关键词时升到 `high_quality`，最后不得超过套餐上限。

V2 打开后的编排：

| 模式 | 行为 |
|------|------|
| `low_cost` | 规划 → 精简设计上下文 → 单次页面生成 → 规则检查。不调用 LLM 质检 |
| `standard` | 规划 → 设计上下文 → 代码与样式并发生成 → 规则检查。规则未通过时最多 1 轮 LLM 质检 |
| `high_quality` | 规划 → 完整设计上下文 → 代码与样式并发 → 规则检查 → 最多 3 轮 LLM 质检 |

套餐配置在 `src/main/resources/application.yml` 的 `generation.entitlements`。未写的每日上限视为不限制。`ADMIN` 不记额度。已执行 `docs/sql/migrations/007_user_plan_type.sql` 时，`user.planType` 默认 `FREE`；字段为空时，管理员角色按 `ADMIN`，其他已登录用户按 `PRO`。用户不能在个人资料里自行修改套餐。

| 套餐 | 默认模式 | 最高模式 | 每日上限 |
|------|----------|----------|----------|
| `FREE` | `low_cost` | `low_cost` | 快速 10 次 |
| `PRO` | `standard` | `standard` | 标准 50 次 |
| `PREMIUM` | `standard` | `high_quality` | 标准 100 次，高质量 10 次 |
| `ENTERPRISE` | `high_quality` | `high_quality` | 高质量 100 次 |
| `ADMIN` | `high_quality` | `high_quality` | 不限制 |

## 本地配置

`spring.profiles.active` 已是 `local`。敏感项写在已被 gitignore 的 `src/main/resources/application-local.yml`，或通过环境变量注入。仓库只提交占位示例：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

把示例里的 `CHANGE_ME_*` 换成自己的值。至少要改 MySQL 密码、大模型 API Key，以及 `user.default-password`（管理员新建用户的初始密码）。Redis 无密码时保持密码为空。COS 五项即使不用对象存储也不能空着，否则启动创建客户端会失败；保持 `AGENT_DEPLOY_ENABLED` 为 `false`（默认）就不会上传。不想把某项写进 yml 时，删掉示例里的对应键，继续用 `application.yml` 已经接好的环境变量。

数据库地址默认 `jdbc:mysql://localhost:3306/yu_ai_code_mother`，用户名默认 `root`，密码默认为空。Redis 默认 `localhost:6379`、无密码。大模型地址和密钥没有默认值。示例为 DeepSeek，可换成任意 OpenAI 兼容服务。Vue 项目用的推理流式客户端只读 `langchain4j.open-ai.chat-model` 的 `base-url` / `api-key`，模型名在代码里固定为 `deepseek-chat`；HTML / 多文件的 SSE 使用 `streaming-chat-model` 的 `model-name`。

## 本地开发

JDK 21+、Maven 3.6+（仓库自带 `./mvnw`）、MySQL 8、Redis、Node.js 18+（生产手册使用 Node.js 20）。封面截图需要 Chrome；没有浏览器时部署链接仍然可用，只是封面不会更新。

`docs/sql/migrations/` 只有增量脚本，没有 `user`、`app`、`chat_history` 的初始建表语句。本地库需要先有一份与当前实体一致的基线结构，再按编号执行尚未应用的迁移。分类见 [docs/sql/README.md](docs/sql/README.md)。`seed/`、`dev/`、`test/` 不要放进日常启动。PostgreSQL 脚本只在向量后端为 `pg` 且检索已打开时使用。

```bash
./mvnw spring-boot:run
```

后端监听 `http://localhost:8123`，上下文路径 `/api`。接口文档：<http://localhost:8123/api/doc.html>。健康检查：<http://localhost:8123/api/actuator/health>。

```bash
cd frontend
npm ci
npm run dev
```

前端默认 `http://localhost:5173`。Vite 把 `/api` 代理到 `localhost:8123`，Session Cookie 可以带上。只看界面、不接后端时用 `npm run dev:mock`。

在页面注册账号（账号至少 4 位，密码至少 8 位）后即可从首页输入需求。需要管理员时，把对应用户的 `userRole` 更新为 `admin` 并重新登录。

单机生产（2 vCPU / 4 GiB / 50 GiB / Ubuntu 24.04 LTS，MySQL 8、Redis 7、Nginx、JDK 21、Node.js 20、headless Chrome；不安装 PostgreSQL，向量检索使用 `agent.retrieve.vector.store=mysql`）见 [docs/deploy/README.md](docs/deploy/README.md)。外置配置由 [docs/deploy/application-prod.yml.example](docs/deploy/application-prod.yml.example) 复制到服务器 `/opt/creat-ai/config/application-prod.yml` 后替换占位符。示例里的 V2 流水线是打开的，COS 上传仍然关闭。

## 接口清单

上下文路径为 `/api`。登录态是 Redis Session Cookie（前端 Axios / EventSource 使用 `withCredentials`）。`管理员` 表示方法上有 `@AuthCheck(mustRole = admin)`。

### 用户

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/user/register` | 否 | 注册（账号至少 4 位，密码至少 8 位） |
| POST | `/api/user/login` | 否 | 登录，写入 Redis Session |
| GET | `/api/user/get/login` | Session | 当前登录用户 |
| POST | `/api/user/logout` | Session | 登出 |
| GET | `/api/user/plan` | Session | 当前套餐、最高模式、可用模式 |
| POST | `/api/user/update/me` | Session | 更新自己的昵称、头像、简介 |
| GET | `/api/user/get/vo` | 否 | 按 id 获取用户包装信息 |
| POST | `/api/user/add` | 管理员 | 新建用户（初始密码取 `user.default-password`） |
| POST | `/api/user/delete` | 管理员 | 删除用户 |
| POST | `/api/user/update` | 管理员 | 更新用户 |
| GET | `/api/user/get` | 管理员 | 按 id 获取用户 |
| POST | `/api/user/list/page/vo` | 管理员 | 分页查询用户 |
| POST | `/api/user/save` | 管理员 | 保存用户 |
| DELETE | `/api/user/remove/{id}` | 管理员 | 按主键删除用户 |
| PUT | `/api/user/update` | 管理员 | 按主键更新用户 |
| GET | `/api/user/list` | 管理员 | 查询全部用户 |
| GET | `/api/user/getInfo/{id}` | 管理员 | 按主键获取用户 |
| GET | `/api/user/page` | 管理员 | 分页查询用户 |

### 应用

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/app/add` | Session | 创建应用，返回 appId |
| POST | `/api/app/update` | Session | 所有者改应用名称 |
| POST | `/api/app/delete` | Session | 删除应用（所有者或管理员） |
| GET | `/api/app/get/vo` | 否 | 按 id 获取应用详情 |
| POST | `/api/app/my/list/page/vo` | Session | 我的应用，每页最多 20 条 |
| POST | `/api/app/good/list/page/vo` | 否 | 精选应用（`priority = 99`），每页最多 20 条 |
| GET | `/api/app/chat/gen/code` | Session | SSE 生成。查询参数 `appId`、`message`、可选 `mode`。所有者。限流每用户 60 秒 5 次 |
| GET | `/api/app/incremental-edit-stream` | Session | SSE 增量编辑。查询参数 `appId`、`instruction`。所有者。限流每用户 60 秒 5 次 |
| POST | `/api/app/incremental-edit` | Session | 同步增量编辑。所有者。成功后尝试本地部署 |
| POST | `/api/app/deploy` | Session | 手动本地部署，返回访问 URL。所有者 |
| GET | `/api/app/download/{appId}` | Session | 下载生成目录 ZIP。所有者 |
| POST | `/api/app/admin/delete` | 管理员 | 删除任意应用 |
| POST | `/api/app/admin/update` | 管理员 | 更新任意应用（含优先级） |
| POST | `/api/app/admin/list/page/vo` | 管理员 | 分页查询全部应用 |
| GET | `/api/app/admin/get/vo` | 管理员 | 按 id 获取应用详情 |

### 对话

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/chatHistory/app/{appId}` | Session | 某应用对话历史，游标分页。`pageSize` 默认 10，可选 `lastCreateTime` |
| POST | `/api/chatHistory/admin/list/page/vo` | 管理员 | 分页查询全部对话 |

### 偏好

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/preference/feedback/dislike` | Session | 点踩。Body：`appId`、可选 `reason`、`tags` |
| POST | `/api/preference/feedback/event` | Session | 负反馈。`eventType` 为 `undo`、`revert_to_generated`、`abandon`、`reject_suggestion`、`explicit_dislike` |

### 设计指纹

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/fingerprint/admin/list/page` | 管理员 | 分页查询指纹。可按 styleProfile、status、sourceType、tone 过滤，每页最多 50 条 |
| GET | `/api/fingerprint/admin/get` | 管理员 | 指纹详情。查询参数 `id` |
| POST | `/api/fingerprint/admin/approve` | 管理员 | 审核通过（CANDIDATE → APPROVED） |
| POST | `/api/fingerprint/admin/reject` | 管理员 | 拒绝指纹 |
| POST | `/api/fingerprint/admin/archive` | 管理员 | 归档（APPROVED → ARCHIVED） |
| POST | `/api/fingerprint/admin/vector/resync` | 管理员 | 重同步向量。可选查询参数 `limit`，默认且不超过 `resync-max` |
| POST | `/api/fingerprint/admin/ingest-external` | 管理员 | 抓取外部页面 HTML 并入库为待审核指纹。Body：`url`、可选 `styleProfileHint` |

### 静态资源

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/static/{deployKey}/**` | 否 | 本地静态站点。目录访问会补上斜杠，`/` 返回 `index.html`。HTML 注入选元素脚本。存在 `dist/` 时以其为根 |

### 健康与监控

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/api/health/` | 否 | 返回 `ok` |
| GET | `/api/actuator/health` | 否 | Actuator 健康检查，不展示详情 |
| GET | `/api/actuator/info` | 管理员 | 应用信息 |
| GET | `/api/actuator/prometheus` | 管理员 | Prometheus 指标 |

## 环境变量

下列变量都在 `src/main/resources/application.yml` 里有默认值。空表示未配置时跳过或使用空字符串。大模型密钥、`cos.client.*` 和 `user.default-password` 不在该文件中，见本节后的配置键。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/yu_ai_code_mother` | JDBC 连接串 |
| `MYSQL_USERNAME` | `root` | 数据库用户 |
| `MYSQL_PASSWORD` | 空 | 数据库密码 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `REDIS_DATABASE` | `0` | Redis DB |
| `PGVECTOR_URL` | `jdbc:postgresql://localhost:5432/yu_ai_vector` | 仅向量后端为 `pg` 且检索打开时需要 |
| `PGVECTOR_USERNAME` / `PGVECTOR_PASSWORD` | `postgres` / 空 | 同上 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:5174,http://localhost:3000` | 允许的浏览器来源，逗号分隔 |
| `LANGCHAIN4J_CHAT_MODEL_TIMEOUT` | `300s` | 同步 ChatModel 超时 |
| `LANGCHAIN4J_CHAT_MODEL_MAX_RETRIES` | `2` | 同步 ChatModel 重试次数 |
| `LANGCHAIN4J_STREAMING_CHAT_MODEL_TIMEOUT` | `300s` | 流式模型超时 |
| `PIXABAY_API_KEY` | 空 | Pixabay，搜图第一顺位；未配置则跳过，连续失败会短时熔断 |
| `UNSPLASH_ACCESS_KEY` | 空 | Unsplash，未配置则跳过 |
| `PEXELS_API_KEY` | 空 | Pexels，未配置则跳过 |
| `IMAGE_FALLBACK_ENABLED` | `false` | 搜图失败后是否文生图 |
| `IMAGE_TEXT2IMG_BASE_URL` | `https://open.bigmodel.cn/api/paas/v4` | 文生图端点 |
| `IMAGE_TEXT2IMG_API_KEY` | 空 | 文生图密钥 |
| `IMAGE_TEXT2IMG_MODEL` | `cogview-3-flash` | 文生图模型 |
| `APP_DEPLOY_BASE_URL` | `http://localhost:8123/api/static` | 静态站点前缀，截图也会打开这个地址 |
| `APP_SCREENSHOT_CHROME_BINARY` | 空 | headless Chrome 路径 |
| `APP_SCREENSHOT_DRIVER_PATH` | 空 | chromedriver 路径 |
| `APP_SCREENSHOT_USE_WDM` | `true` | 是否允许 WebDriverManager 准备驱动 |
| `APP_SCREENSHOT_EXECUTOR_CORE` / `APP_SCREENSHOT_EXECUTOR_MAX` / `APP_SCREENSHOT_EXECUTOR_QUEUE` | `2` / `4` / `20` | 截图线程池 |
| `APP_LIMITS_GENERATION_MAX_CONCURRENT` | `0` | 生成并发上限，`0` 表示不限制 |
| `APP_LIMITS_DEPLOY_MAX_CONCURRENT` | `0` | 部署并发上限，`0` 表示不限制 |
| `CODE_OUTPUT_ROOT_DIR` | `${user.dir}/tmp/code_output` | 生成代码与静态资源根目录 |
| `CODEGEN_BUILD_LOCK_WAIT_SECONDS` | `300` | Vue 构建串行锁等待 |
| `CODEGEN_BUILD_INSTALL_TIMEOUT_SECONDS` | `300` | npm install 超时 |
| `CODEGEN_BUILD_TIMEOUT_SECONDS` | `180` | npm run build 超时 |
| `CODEGEN_BUILD_CLEANUP_NODE_MODULES` | `false` | 构建后是否删除 `node_modules` |
| `GENERATION_MODE` | `standard` | 请求未带模式时的默认模式 |
| `AGENT_ORCHESTRATOR_V2_ENABLED` | `false` | V2 流水线，仅 `vue_project` |
| `AGENT_DEPLOY_ENABLED` | `false` | V2 末尾上传 COS。本地自动部署不读取此开关 |
| `AGENT_PERSONALIZATION_ENABLED` | `true` | 是否注入用户设计偏好 |
| `AGENT_RERANK_ENABLED` | `true` | 检索重排 |
| `AGENT_RETRIEVE_ENABLED` | `false` | 设计指纹检索 |
| `AGENT_RETRIEVE_TOP_K` | `2` | 召回条数（最大不超过 3） |
| `AGENT_RETRIEVE_VECTOR_ENABLED` | `false` | 向量检索 |
| `AGENT_RETRIEVE_VECTOR_STORE` | `pg` | `pg` 或 `mysql`。生产手册使用 `mysql` |
| `AGENT_RETRIEVE_VECTOR_MIN_SIMILARITY` | `0.7` | 向量最低相似度 |
| `AGENT_RETRIEVE_VECTOR_RELOAD_MS` | `300000` | MySQL 向量快照重载间隔，`0` 关闭定时重载 |
| `AGENT_RETRIEVE_VECTOR_EXPECTED_DIM` | `0` | 期望向量维度，`0` 为自动探测 |
| `AGENT_RETRIEVE_VECTOR_MAX_ENTRIES` | `20000` | 内存快照最大条目 |
| `AGENT_RETRIEVE_VECTOR_RESYNC_MAX` | `100` | 管理员重同步单次上限 |
| `AGENT_RETRIEVE_SYNTH_ENABLED` | `false` | 检索未命中时是否用 LLM 合成设计规范 |
| `AGENT_RETRIEVE_SYNTH_MISS_THRESHOLD` | `2` | 触发合成的未命中次数 |
| `AGENT_FINGERPRINT_EXTERNAL_CRAWL_ENABLED` | `false` | 外部种子页面爬取 |
| `AGENT_FINGERPRINT_EXTERNAL_CRAWL_INTERVAL_MS` | `86400000` | 外部爬取间隔 |
| `AGENT_FINGERPRINT_EXTERNAL_CRAWL_URLS` | 空 | 外部种子 URL |
| `AGENT_FINGERPRINT_EXTERNAL_CRAWL_RENDER_ENABLED` | `true` | 外部爬取是否渲染页面 |
| `AGENT_FINGERPRINT_EXTERNAL_CRAWL_REQUEST_INTERVAL_MS` | `5000` | 外部请求间隔 |
| `AGENT_MEMORY_MAX_MESSAGES` | `60` | JVM 内存窗口最大消息条数 |
| `AGENT_MEMORY_FULL_TEXT_TURNS` | `2` | 发给模型时保持全文的最近轮次 |
| `AGENT_MEMORY_FULL_TEXT_MAX_MESSAGES` | `24` | 全文段条数上限 |
| `AGENT_MEMORY_CHAR_BUDGET` | `80000` | 上下文字符预算，超出则从最早消息丢弃 |
| `AGENT_MEMORY_HARD_MAX_MESSAGE_CHARS` | `200000` | 单条消息入内存前的硬截断 |
| `AGENT_MEMORY_SNAPSHOT_EVERY` | `5` | 每累积多少条消息向 Redis 落一次压缩快照 |
| `DESIGN_EXTRACTOR_VISION_ENABLED` | `false` | 指纹截图视觉解析。默认只走 HTML 摘要 |
| `VITE_API_BASE_URL` | `/api` | 前端接口基址，见 `frontend/.env.development` |

下列键写在示例文件或 `@Value` 默认值里，`application.yml` 没有对应的 `${...}`。示例中的密钥都是 `CHANGE_ME_*` 占位：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `spring.datasource.password` | `CHANGE_ME_MYSQL_PASSWORD` | 示例文件中的库密码，会覆盖 `MYSQL_PASSWORD` |
| `langchain4j.open-ai.chat-model.base-url` | `https://api.deepseek.com/v1` | 同步模型与 Vue 推理流式客户端的地址 |
| `langchain4j.open-ai.chat-model.api-key` | `CHANGE_ME_LLM_API_KEY` | 同步模型与 Vue 推理流式客户端的密钥 |
| `langchain4j.open-ai.chat-model.model-name` | `deepseek-chat` | 同步调用的模型名。Vue 推理流不读此键 |
| `langchain4j.open-ai.streaming-chat-model.base-url` | `https://api.deepseek.com/v1` | HTML / 多文件 SSE 的地址 |
| `langchain4j.open-ai.streaming-chat-model.api-key` | `CHANGE_ME_LLM_API_KEY` | HTML / 多文件 SSE 的密钥 |
| `langchain4j.open-ai.streaming-chat-model.model-name` | `deepseek-chat` | HTML / 多文件 SSE 的模型名 |
| `cos.client.host` / `secretId` / `secretKey` / `region` / `bucket` | `CHANGE_ME_*` | 启动时创建 COS 客户端，五项都不能空。不上传时保留占位 |
| `user.default-password` | `CHANGE_ME_DEFAULT_USER_PASSWORD` | 管理员在后台新建用户时的初始密码 |
| `agent.embedding.use-openai` | `false` | 代码默认走 SHA-256 哈希兜底。生产示例设为 `true` 后使用下面的 embedding 模型 |
| `langchain4j.open-ai.embedding-model.base-url` | 空 | 生产示例为 `https://open.bigmodel.cn/api/paas/v4` |
| `langchain4j.open-ai.embedding-model.api-key` | `CHANGE_ME_EMBEDDING_API_KEY` | 语义向量密钥，仅 `use-openai=true` 时使用 |
| `langchain4j.open-ai.embedding-model.model-name` | `text-embedding-3-small` | 代码默认。生产示例为 `embedding-2`（1024 维，需与 `AGENT_RETRIEVE_VECTOR_EXPECTED_DIM` 一致） |

生产单元 `docs/deploy/systemd/creat-ai.service` 里的环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `prod` | 加载外置 `application-prod.yml` |
| `TZ` | `Asia/Shanghai` | 进程时区 |
| `HOME` | `/opt/creat-ai` | npm 缓存与配置目录 |
| `NODE_OPTIONS` | `--max-old-space-size=768` | 限制后端 fork 出的 Node 堆 |
| `NPM_CONFIG_REGISTRY` | `https://registry.npmmirror.com` | 生成 Vue 项目时的 npm 源 |

## 运维与可观测性

- `GET /api/health/` 返回 `ok`。`GET /api/actuator/health` 保持开放，且 `show-details` 为 `never`。`/api/actuator/info` 与 `/api/actuator/prometheus` 需要管理员 Session。
- 生成和增量编辑的 SSE 按用户限流，每 60 秒 5 次。并发上限见 `APP_LIMITS_GENERATION_MAX_CONCURRENT`、`APP_LIMITS_DEPLOY_MAX_CONCURRENT`，默认不限制。
- 生成代码写在 `tmp/code_output/{codeGenType}_{appId}/`。可分享链接为 `{APP_DEPLOY_BASE_URL}/{deployKey}/`。封面与文生图落在本机静态目录，访问路径为 `/api/static/_generated_images/...`。
- 日志级别行带 `requestId`、`generationId`、`appId`（无 MDC 时为空）。
- 应用列表和用户列表的排序字段有白名单；查询参数由 MyBatis-Flex 绑定。写文件和静态资源读取都会确认目标路径仍在项目目录内。封面截图的目标必须与 `app.deploy.base-url` 同源。
- 打包前如果工作区里有 `application-local.yml`，它会被打进 jar。生产用 `prod` profile 不会加载它；构建前仍应移走，避免开发凭据随制品扩散。
- 单机生产步骤、systemd、Nginx，以及 Chrome 安装、备份、清理脚本见 [docs/deploy/README.md](docs/deploy/README.md)。

## 许可证

本项目采用 [MIT License](LICENSE)。
