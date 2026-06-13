# yu-ai-father

基于 **Spring Boot 3** 的后端服务：面向「应用」的 **AI 对话生成代码**、本地构建与预览、打包下载，以及用户与会话管理。对外 API 统一挂在 **`/api` 上下文路径**下。

## 技术栈

| 类别 | 说明 |
|------|------|
| 运行时 | Java **21** |
| 框架 | Spring Boot **3.2.10**（Web、AOP） |
| 持久化 | **MyBatis-Flex** + MySQL（HikariCP） |
| 缓存 / 会话 | **Redis**（Spring Session 存 Redis） |
| 分布式限流 | **Redisson**（配合自定义 `@RateLimit`） |
| AI | **LangChain4j**（OpenAI 兼容接口，流式对话 + 工具调用） |
| 对象存储 | 腾讯云 **COS** |
| 其他 | **Knife4j**（OpenAPI3）、**Hutool**、**Caffeine**、**Selenium**（截图相关能力） |

---

## 核心功能

### 代码生成模式

系统支持三种生成类型，由路由器（`codegen-routing-system-prompt.txt`）根据用户需求自动判断：

| 类型 | 适用场景 |
|------|---------|
| `HTML` | 单页应用（任务清单、计算器、小工具等）|
| `MULTI_FILE` | 多文件前端（无路由跳转需求）|
| `VUE_PROJECT` | **多页面路由跳转**（博客、商城、管理后台等）|

> **路由判断核心原则**：只有明确需要 ≥ 2 个独立页面间路由跳转时才选 VUE_PROJECT，任务清单类单页应用走 HTML。

### 图片语义占位符

Vue 项目生成时，图片 src 使用语义化占位标记替代随机 picsum：

```html
<img src="{{IMG:coffee shop interior warm light}}" alt="咖啡厅内景">
```

代码生成流完成后，系统自动扫描所有文件，将占位符替换为真实语义图片（Unsplash → Pexels → picsum 兜底）。

---

## 多 Agent 流水线（可选）

通过环境变量 `AGENT_ORCHESTRATOR_ENABLED=true` 启用，仅对 `VUE_PROJECT` 生效，默认关闭（不影响存量流程）。

```
用户 Prompt
  └─ ProductAgent    需求文档（RequirementDoc JSON）
  └─ ArchitectAgent  生成计划（GenerationPlan JSON，含 fileStructure / imageSlots）
  └─ ImageAgent      搜图（imageMap：keyword → 真实 URL）
  └─ CoderAgent  ┐  并发执行
  └─ StyleAgent  ┘  生成组件代码 + 全局 CSS
  └─ CriticAgent     质检（score ≥ 80 通过，最多 3 轮反馈修复）
  └─ DeployAgent     npm build → 上传 COS（需 AGENT_DEPLOY_ENABLED=true）
```

各 Agent 通过线程安全的 **Blackboard（黑板）** 传递中间产物，全程以 `Flux<AgentEvent>` 事件流推送给前端 SSE。

---

## 项目结构

```
src/main/java/com/yu/yuaifather/
├── YuAiFatherApplication.java
├── controller/         REST：用户、应用、对话历史、静态资源、健康检查
├── service/            业务实现（AppServiceImpl 为核心入口）
├── mapper/             MyBatis-Flex Mapper
├── model/              实体、VO、DTO
├── ai/
│   ├── core/           AiCodeGeneratorFacade（生成门面）、VueProjectBuilder、StreamHandler
│   ├── tools/          File 工具（写/读/改/删/目录列举）、ToolManager
│   ├── enums/          CodeGenTypeEnum（HTML / MULTI_FILE / VUE_PROJECT）
│   └── model/          HtmlCodeResult、MultiFileCodeResult、SSE 消息模型、DTO
├── agent/              ★ 多 Agent 流水线（本次新增）
│   ├── core/           Agent 接口、AgentContext、AgentEvent、Blackboard
│   ├── orchestrator/   OrchestratorAgent（编排 + Critic 反馈循环）
│   ├── workers/        Product / Architect / Image / Coder / Style / Critic / Deploy
│   ├── tools/          ImageSearchTool（Unsplash / Pexels / picsum，含 SSRF 防护）
│   ├── support/        ImagePlaceholderReplacer、JsonExtractor
│   ├── AgentAiService  LangChain4j AI 服务接口（绑定各 Agent prompt）
│   └── AgentAiServiceFactory
├── config/             Redis 对话记忆、推理流式模型、COS、ImageProperties
├── ratelimiter/        限流注解与 AOP、Redisson 配置
├── exception/          全局异常与错误码
├── manager/            CosManager、部署生命周期
├── constant/           AppConstant（生成目录、部署目录）
└── utils/              工具类（截图等）
```

生成与部署使用的本地目录（相对进程工作目录）：

| 用途 | 路径 |
|------|------|
| 生成输出 | `tmp/code_output` |
| 部署输出 | `tmp/code_deploy` |

---

## 对外接口

全局前缀：`http://localhost:8123/api`

| 前缀 | 说明 |
|------|------|
| `/user` | 用户注册、登录、信息管理 |
| `/app` | 应用 CRUD、**SSE 流式生成代码**、部署、ZIP 下载等 |
| `/chatHistory` | 按应用分页查询对话历史；管理员全量分页 |
| `/static/{deployKey}/**` | 本地生成物静态访问（默认 `index.html`） |
| `/health/` | 健康检查 |

流式接口：`GET /app/chat/gen/code`（`text/event-stream`），带用户维度限流。

---

## 运行前准备

### 基础依赖

1. **JDK 21**、**Maven 3.6+**
2. **MySQL**：创建数据库（默认库名 `yu_ai_code_mother`）
3. **Redis**：Spring Session 与对话记忆均依赖 Redis

### 环境变量

所有敏感配置**仅**通过环境变量注入，禁止硬编码。

| 变量 | 说明 | 是否必须 |
|------|------|---------|
| `MYSQL_URL` | MySQL 连接串 | 否（默认 localhost） |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库凭证 | 按实际情况 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 | 否（默认 localhost:6379） |
| `UNSPLASH_ACCESS_KEY` | Unsplash 图片搜索 | 否（未配置跳过该提供商） |
| `PEXELS_API_KEY` | Pexels 图片搜索 | 否（未配置跳过该提供商） |
| `AGENT_ORCHESTRATOR_ENABLED` | 是否启用多 Agent 流水线 | 否（默认 false） |
| `AGENT_DEPLOY_ENABLED` | 是否启用 DeployAgent 自动构建上传 | 否（默认 false） |
| `IMAGE_FALLBACK_ENABLED` | 是否启用文生图兜底（搜图无结果时） | 否（默认 false） |

AI 模型端点与密钥在 **profile 配置文件**（`application-local.yml`）中配置，**请勿提交真实密钥到 Git**。

---

## 构建与启动

```bash
# 编译打包（跳过测试）
mvn -q -DskipTests package

# 启动
mvn spring-boot:run

# 或直接运行 jar
java -jar target/yu-ai-father-*.jar
```

也可在 IDE 中运行 `com.yu.yuaifather.YuAiFatherApplication`。

默认 `spring.profiles.active=local`，叠加加载 `application-local.yml`。

---

## 分阶段上线指引

### 第 1 步：启用图片语义替换（P0，推荐立刻上线）

只需配置图片 API Key，**不改任何开关**。Vue 项目生成完成后将自动用真实语义图替换占位符，任务清单类需求自动走 HTML。

```bash
export UNSPLASH_ACCESS_KEY=your_key   # 或 PEXELS_API_KEY
```

### 第 2 步：灰度启用多 Agent 流水线

```bash
export AGENT_ORCHESTRATOR_ENABLED=true
```

此时 VUE_PROJECT 走 7 Agent 流水线 + CriticAgent 质检循环（最多 3 轮）。

### 第 3 步：开启自动部署

```bash
export AGENT_DEPLOY_ENABLED=true   # npm build + 上传 COS
```

---

## API 文档

启动后访问：`http://localhost:8123/api/doc.html`

---

## 安全说明

- **SSRF 防护**：`ImageSearchTool` 对图片 URL 做 https + 白名单域名 + 私有网段拒绝（防 SSRF）
- **目录穿越防护**：`StyleAgent`、`DeployAgent`、`ImagePlaceholderReplacer` 写文件前均做 `startsWith(projectRoot)` 校验
- **密钥安全**：所有外部 API Key 仅经环境变量注入，禁止写入代码或 yml 明文
- **SQL 注入**：使用 MyBatis-Flex 参数绑定，不拼接 SQL

---

## 许可证

未在仓库中统一声明时，以项目所有者约定为准。
