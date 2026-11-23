# CreatAi

用自然语言生成可预览、可修改、可分享的网站。

*AI website generator: describe a page, preview it, and share a local static link.*

**CreatAi**（Maven 构件 `yu-ai-father`）是一个中文界面的全栈应用。用户写下需求后，系统调用兼容 OpenAI 协议的大模型生成前端代码，在对话页里用 SSE 流式预览，并可以继续用对话或点选元素修改。生成成功后，服务端把结果发布到本机静态目录，返回可分享链接，并异步截取封面；源码也可以打包下载。

仓库里没有产品截图。界面入口是登录 / 注册、首页、对话生成、我的作品、精选应用、个人中心，以及管理员后台。

---

## 能做什么

- **按需求选择产物。** 创建应用时，模型在三种类型里选一种：`html`（单个 HTML 文件）、`multi_file`（多文件前端）、`vue_project`（带路由的 Vue 项目）。
- **对话式修改。** 生成页可以继续描述修改。预览里的 HTML 会注入选元素脚本，点中的标签、选择器和片段会交给增量编辑。
- **本地发布是默认分享路径。** 一次成功的生成结束后，服务端调用与「部署」按钮相同的本地部署：写入 `deployKey`（`{codeGenType}_{appId}`），Vue 项目先执行构建。访问地址是 `{APP_DEPLOY_BASE_URL}/{deployKey}/`，默认 `http://localhost:8123/api/static/html_1/` 这种形式。静态服务在目录里存在 `dist/` 时以 `dist` 为根。增量编辑只有真正写出新页面时才会自动部署。部署失败会保留已生成的代码，并在流里给出警告。
- **封面截图。** 部署成功后用 headless Chrome 截图，图片落在本机静态目录并写回应用封面。截图失败只记日志，不影响链接访问。本机需要可用的 Chrome；本地默认允许 WebDriverManager 准备驱动。
- **下载源码。** 应用所有者可以把生成目录打成 ZIP。
- **精选应用。** 优先级 `priority = 99` 的应用出现在首页和「精选应用」页。管理员在后台把优先级改成 99 即可。
- **质量模式与额度。** 前端可选 `low_cost`（快速）、`standard`（标准）、`high_quality`（高质量）。服务端把该选择当作意图：先按需求复杂度调整，再按套餐封顶，最后按当日额度降级。
- **管理后台。** 管理员可以管理用户、应用、对话记录和设计指纹。普通注册用户的角色是 `user`。

默认生成链路是经典生成器。多 Agent 流水线默认关闭，见下文。

---

## 技术栈

### 后端

| 类别 | 技术 |
|------|------|
| 运行时 | Java 21 |
| 框架 | Spring Boot 3.2.10（Spring MVC；生成接口返回 Reactor SSE） |
| 持久化 | MyBatis-Flex 1.11 + MySQL 8（库名 `yu_ai_code_mother`） |
| 会话 | Spring Session + Redis |
| 限流 | Redisson |
| 模型 | LangChain4j 1.1（OpenAI 兼容接口，示例为 DeepSeek） |
| 截图 | Selenium 4 + WebDriverManager |
| 其他 | Knife4j、Hutool、Caffeine |

进程启动时会创建腾讯云 COS 客户端。可分享链接由本机静态服务提供。只有 `AGENT_DEPLOY_ENABLED=true` 时，V2 流水线末尾的 DeployTool 才会把 Vue `dist` 上传到 COS。该开关默认是 `false`。

向量检索默认关闭，本地不必安装 PostgreSQL。生产手册把向量存在 MySQL 表里。

### 前端

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3 + TypeScript |
| 构建 | Vite 5、vue-tsc |
| 路由 / 状态 | Vue Router 4、Pinia |
| 请求 | Axios（`withCredentials`）、原生 EventSource |
| 界面 | 仓库内组件 `frontend/src/components/ui` |

---

## 生成、套餐与图片

### 三种代码类型

| 值 | 适用情况 | 预览 |
|----|----------|------|
| `html` | 单页小工具、落地页 | 生成后即可通过静态目录预览 |
| `multi_file` | 多文件、无路由的前端 | 同上 |
| `vue_project` | 多页面、需要路由的应用 | 需本机 Node/npm 构建出 `dist` 后才能从静态目录打开 |

首页还可以选择视觉风格、布局和差异度。这些选项会写进创建应用时的提示词，没有单独的接口字段。

### 质量模式

模式始终会参与决策，并记入当日额度。`low_cost` 使用更短的设计上下文，`high_quality` 会带上完整项目品牌信息。

V2 流水线（见下一节）打开后，三种模式的编排不同：

| 模式 | V2 行为 |
|------|---------|
| `low_cost` | 规划 → 精简设计上下文 → 单次页面生成 → 规则检查。不调用 LLM 质检。 |
| `standard` | 规划 → 设计上下文 → 代码与样式并发生成 → 规则检查。规则未通过时最多 1 轮 LLM 质检。 |
| `high_quality` | 规划 → 完整设计上下文 → 代码与样式并发 → 规则检查 → 最多 3 轮 LLM 质检。 |

请求里的模式只是意图。提示词命中「改颜色」「改一下」等简单修改关键词时降到 `low_cost`，命中「多页面」「整站」「动效」「后台管理」等关键词时升到 `high_quality`，最后不得超过套餐上限。

### 套餐与每日额度

配置在 `src/main/resources/application.yml` 的 `generation.entitlements`。未写的每日上限视为不限制。额度用尽时向更低模式降级；三种都用尽后仍落到 `low_cost`。`ADMIN` 不记额度。

| 套餐 | 默认模式 | 最高模式 | 每日上限 |
|------|----------|----------|----------|
| `FREE` | `low_cost` | `low_cost` | 快速 10 次 |
| `PRO` | `standard` | `standard` | 标准 50 次 |
| `PREMIUM` | `standard` | `high_quality` | 标准 100 次，高质量 10 次 |
| `ENTERPRISE` | `high_quality` | `high_quality` | 高质量 100 次 |
| `ADMIN` | `high_quality` | `high_quality` | 不限制 |

已执行迁移 `docs/sql/migrations/007_user_plan_type.sql` 时，`user.planType` 默认是 `FREE`。字段为空时，管理员角色按 `ADMIN`，其他已登录用户按 `PRO`。用户不能在个人资料里自行修改套餐。

### 图片占位符

生成代码使用 `@@IMG:english keywords@@`。生成结束后按关键词替换成图片 URL。关键词会清洗成小写英文字母、数字和空格。

查找顺序：

1. Pixabay（`PIXABAY_API_KEY`，未配置则跳过；连续失败会短时熔断）
2. Unsplash（`UNSPLASH_ACCESS_KEY`）
3. Pexels（`PEXELS_API_KEY`）
4. 文生图（`IMAGE_FALLBACK_ENABLED=true` 且配置了 `IMAGE_TEXT2IMG_API_KEY` 才启用，默认关闭；图片保存到本机静态目录）
5. loremflickr
6. picsum

返回的搜图 URL 必须是 HTTPS，且主机在白名单内；解析后的私网地址会被拒绝。

---

## 多 Agent 流水线（可选）

环境变量 `AGENT_ORCHESTRATOR_V2_ENABLED=true` 后，**仅 `vue_project`** 走 `GenerationOrchestrator`。默认关闭。V2 失败且 `generation.pipeline.allow-classic-fallback=true`（默认开启）时，降级到经典生成器。`html` 与 `multi_file` 始终走经典生成器。

```
用户提示词
  ├─ PlanningAgent          需求与计划
  ├─ DesignContext          偏好、品牌和检索证据
  ├─ low_cost：PageGenerateAgent
  │  standard / high_quality：CoderAgent 与 StyleAgent
  ├─ RuleCriticTool         空内容、占位符残留、P0 文件
  ├─ CriticAgent            按模式决定是否做 LLM 质检
  ├─ DeployTool             仅 AGENT_DEPLOY_ENABLED=true 时构建并上传 COS
  └─ GenerationRecordTool   保存生成记录
```

规则检查把 `package.json`、`index.html`、`src/main.js`、`src/App.vue` 视为必需文件。中间结果放在 Blackboard 上，进度以 `Flux<AgentEvent>` 推送。

生成结束后的本地自动部署不读取 `AGENT_DEPLOY_ENABLED`。该开关只控制上面的 COS 上传。

---

## 项目结构

```
yu-CreatAi/
├── frontend/                         # Vue 3 界面
│   ├── src/pages/                    # 首页、对话、作品、精选、个人中心、管理后台
│   ├── src/components/ui/            # 界面组件
│   └── mock/                         # npm run dev:mock 使用的离线数据
├── src/main/java/com/yu/yuaifather/
│   ├── controller/                   # 用户、应用、对话、静态资源、设计指纹
│   ├── codegen/                      # 经典生成：类型路由、解析、保存、SSE、Vue 构建
│   ├── service/generation/           # 模式、额度、V2 编排
│   ├── service/deploy/               # 生成成功后的本地自动部署
│   ├── agent/                        # V2：规划、页面、代码、样式、质检
│   ├── tool/                         # 搜图、规则检查、可选 COS 上传
│   └── media/                        # 封面截图与本地图片
├── src/main/resources/               # application.yml、提示词、设计规范
└── docs/
    ├── deploy/                       # 生产手册、systemd、Nginx、运维脚本
    └── sql/                          # 增量迁移，不含 user/app 的完整基线
```

生成代码写在进程工作目录下的 `tmp/code_output/{codeGenType}_{appId}/`。

---

## 快速开始

### 环境

- JDK 21+、Maven 3.6+（仓库自带 `./mvnw`）
- MySQL 8，库名 `yu_ai_code_mother`
- Redis（登录 Session；限流默认也走 Redis）
- Node.js 18+（前端开发）。后端构建生成的 Vue 项目时，同一台机器还要有 npm。生产手册使用 Node.js 20。
- 封面截图需要 Chrome。没有浏览器时部署链接仍然可用，只是封面不会更新。

`docs/sql/migrations/` 只有增量脚本，**没有** `user`、`app`、`chat_history` 的初始建表语句。本地库需要先有一份与当前实体一致的基线结构，再按编号执行尚未应用的迁移。脚本分类见 [docs/sql/README.md](docs/sql/README.md)。`seed/`、`dev/`、`test/` 不要放进日常启动。PostgreSQL 脚本只在向量后端为 `pg` 且检索已打开时使用。

生产环境如何导出基线并补跑迁移，见 [docs/deploy/README.md](docs/deploy/README.md) 第 6 节。

### 本地配置

`spring.profiles.active` 已是 `local`。敏感项写在 **已被 gitignore 的** `src/main/resources/application-local.yml`，或通过环境变量注入。仓库没有提交这份示例文件，请在本机新建，不要把真实密钥提交进 Git。

```yaml
spring:
  datasource:
    password: "your-mysql-password"
  data:
    redis:
      password: "your-redis-password"

langchain4j:
  open-ai:
    chat-model:
      base-url: https://api.deepseek.com/v1
      api-key: "your-llm-api-key"
      model-name: deepseek-chat
    streaming-chat-model:
      base-url: https://api.deepseek.com/v1
      api-key: "your-llm-api-key"
      model-name: deepseek-chat

# 启动时会创建 COS 客户端，五项都不能是空。
# 不使用对象存储时保留下面的占位值，并保持 AGENT_DEPLOY_ENABLED 为 false。
cos:
  client:
    host: https://example.invalid
    secretId: local-placeholder
    secretKey: local-placeholder
    region: ap-guangzhou
    bucket: local-placeholder
```

数据库地址默认 `jdbc:mysql://localhost:3306/yu_ai_code_mother`，用户名默认 `root`，密码默认为空。Redis 默认 `localhost:6379`、无密码。大模型地址和密钥没有默认值，不填写就无法生成。可换成任意 OpenAI 兼容服务。同一批配置项也可以用 Spring Boot 的环境变量覆盖，不必写进 yml。

### 启动

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

---

## 环境变量

下列变量都在 `src/main/resources/application.yml` 里有默认值。大模型密钥和 `cos.client.*` 不在该文件中，按上一节放到 `application-local.yml`。

| 变量 | 说明 | 默认 |
|------|------|------|
| `MYSQL_URL` | JDBC 连接串 | `jdbc:mysql://localhost:3306/yu_ai_code_mother` |
| `MYSQL_USERNAME` | 数据库用户 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | 空 |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `REDIS_DATABASE` | Redis 库号 | `0` |
| `PIXABAY_API_KEY` | Pixabay，搜图第一顺位 | 空（跳过） |
| `UNSPLASH_ACCESS_KEY` | Unsplash | 空（跳过） |
| `PEXELS_API_KEY` | Pexels | 空（跳过） |
| `IMAGE_FALLBACK_ENABLED` | 搜图失败后是否文生图 | `false` |
| `IMAGE_TEXT2IMG_BASE_URL` | 文生图端点 | 智谱 OpenAI 兼容地址 |
| `IMAGE_TEXT2IMG_API_KEY` | 文生图密钥 | 空 |
| `IMAGE_TEXT2IMG_MODEL` | 文生图模型 | `cogview-3-flash` |
| `APP_DEPLOY_BASE_URL` | 静态站点前缀，截图也会打开这个地址 | `http://localhost:8123/api/static` |
| `CORS_ALLOWED_ORIGINS` | 允许的浏览器来源，逗号分隔 | `localhost:5173` 等 |
| `AGENT_ORCHESTRATOR_V2_ENABLED` | V2 流水线，仅 `vue_project` | `false` |
| `AGENT_DEPLOY_ENABLED` | V2 末尾上传 COS | `false` |
| `GENERATION_MODE` | 无请求模式时的默认模式 | `standard` |
| `AGENT_PERSONALIZATION_ENABLED` | 是否注入用户设计偏好 | `true` |
| `AGENT_RETRIEVE_ENABLED` | 设计指纹检索 | `false` |
| `AGENT_RETRIEVE_VECTOR_ENABLED` | 向量检索 | `false` |
| `AGENT_RETRIEVE_VECTOR_STORE` | `pg` 或 `mysql` | `pg` |

向量检索打开且 `AGENT_RETRIEVE_VECTOR_STORE=pg` 时，才需要 `PGVECTOR_URL`、`PGVECTOR_USERNAME`、`PGVECTOR_PASSWORD`。

---

## 生产部署

官方支持的单机规格写在 [docs/deploy/README.md](docs/deploy/README.md)：**2 vCPU / 4 GiB 内存 / 50 GiB 系统盘 / Ubuntu 24.04 LTS**，安装 MySQL 8、Redis 7、Nginx、JDK 21、Node.js 20 和 headless Chrome。该手册明确不安装 PostgreSQL，向量检索使用 `agent.retrieve.vector.store=mysql`。

生产密钥放在服务器上的 `/opt/creat-ai/config/application-prod.yml`（由示例复制后替换占位符），不要提交进 Git。示例里的 V2 流水线是打开的，COS 上传仍然关闭。

配套文件：

- [docs/deploy/application-prod.yml.example](docs/deploy/application-prod.yml.example)
- [docs/deploy/systemd/creat-ai.service](docs/deploy/systemd/creat-ai.service)
- [docs/deploy/nginx/creat-ai.conf](docs/deploy/nginx/creat-ai.conf)
- [docs/deploy/scripts/](docs/deploy/scripts/)（Chrome 安装、备份、清理）

---

## 安全说明

- 登录态是 Redis Session Cookie。管理员接口使用 `@AuthCheck`。
- 生成和增量编辑的 SSE 按用户限流，每 60 秒 5 次。
- 应用列表和用户列表的排序字段有白名单；查询参数由 MyBatis-Flex 绑定。
- 写文件和静态资源读取都会确认目标路径仍在项目目录内。
- 搜图 URL 限制为 HTTPS 白名单主机，并拒绝私网地址。封面截图的目标必须与 `app.deploy.base-url` 同源。
- 密钥放在 gitignore 的 `application-local.yml` 或环境变量中。打包前如果工作区里有这份本地文件，它会被打进 jar；生产用 `prod` profile 不会加载它，构建前仍应移走，避免开发凭据随制品扩散。

---

## 许可证

仓库根目录没有 `LICENSE` 文件，这里不声明开源许可证。
