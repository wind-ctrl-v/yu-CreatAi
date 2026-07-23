# CreatAi

> 用一句话，创造属于你的产品。

**CreatAi** 是一个 AI 驱动的全栈网站生成平台。用户只需用自然语言描述需求，系统即可通过大模型实时生成完整的前端项目代码，并支持在线预览、对话式迭代修改、一键部署与打包下载。

---

## 演示截图

| 登录页 | 首页 | 对话生成 |
|--------|------|---------|
| Vercel 极简风格，带打字机动效 | 大标题 + 输入框 + 横向 Chip | 左侧对话 + 右侧实时预览 |

---

## 技术栈

### 后端

| 类别 | 技术 |
|------|------|
| 运行时 | Java **21**（虚拟线程） |
| 框架 | Spring Boot **3.2** · WebFlux（SSE） · Spring AOP |
| 持久化 | **MyBatis-Flex** + MySQL |
| 缓存 / 会话 | **Redis**（Spring Session） |
| 限流 | **Redisson** + 自定义 `@RateLimit` 注解 |
| AI | **LangChain4j**（兼容 OpenAI 协议，支持 DeepSeek 等） |
| 对象存储 | 腾讯云 **COS** |
| 其他 | Knife4j · Hutool · Caffeine · Selenium |

### 前端

| 类别 | 技术 |
|------|------|
| 框架 | **Vue 3** + TypeScript |
| UI | **Ant Design Vue 4** + `@ant-design/icons-vue` |
| 路由 | **Vue Router 4** |
| 状态管理 | **Pinia** |
| 构建 | **Vite 5** + vue-tsc |
| HTTP | **Axios**（`withCredentials` + 响应拦截） |
| 实时流 | 原生 **EventSource**（SSE）|

---

## 核心功能

### 🤖 AI 代码生成（三种模式自动路由）

系统根据用户需求自动判断最优生成类型：

| 类型 | 适用场景 | 说明 |
|------|---------|------|
| `HTML` | 单页小工具（计算器、待办等）| 生成单个 HTML 文件，快速轻量 |
| `MULTI_FILE` | 多文件前端（无路由跳转）| 生成组件化多文件结构 |
| `VUE_PROJECT` | 多页面路由应用（博客、商城、管理后台）| 生成完整 Vue 项目 + 路由配置 |

### 🖼️ 图片语义占位符

生成代码时 AI 输出语义化图片占位符，生成完成后自动替换为真实图片：

```html
<img src="{{IMG:coffee shop interior warm light}}" alt="咖啡厅内景">
<!-- 自动替换为 Unsplash / Pexels 真实图片 URL -->
```

搜图链路：**Unsplash → Pexels → picsum 兜底**，支持并发搜图（可配置并发数）。

### 💬 对话式迭代修改

生成后可在对话框中继续描述修改需求，AI 实时更新代码并刷新预览。支持**可视化元素选择**：在预览页点击元素，AI 精准修改对应组件。

### 🚀 部署 & 下载

- **一键部署**：将生成产物通过后端静态资源服务发布，获得可分享的访问链接
- **ZIP 下载**：下载完整源码压缩包，本地继续开发

---

## 多 Agent 流水线（可选）

通过环境变量 `AGENT_ORCHESTRATOR_ENABLED=true` 启用，默认关闭，仅对 `VUE_PROJECT` 生效。

```
用户 Prompt
  │
  ├─① ProductAgent    ── 解析需求 → 结构化 RequirementDoc（JSON）
  │
  ├─② ArchitectAgent  ── 技术选型 + 规划 → GenerationPlan（文件列表、路由、图片槽）
  │
  ├─③ ImageAgent      ── 并发搜图 → imageMap（keyword → 真实 URL）
  │
  ├─④ CoderAgent  ┐
  │   StyleAgent  ┘ ── 并发执行：逐文件生成代码 + 全局 CSS
  │
  ├─⑤ CriticAgent     ── 质检（静态检查 + LLM 评分，score ≥ 80 通过）
  │                       未通过 → 回到 CoderAgent 修复，最多 3 轮
  │
  └─⑥ DeployAgent     ── （可选）npm build → 上传 COS（需 AGENT_DEPLOY_ENABLED=true）
```

各 Agent 通过线程安全的 **Blackboard（黑板）** 传递中间产物，全程以 `Flux<AgentEvent>` 事件流实时推送给前端 SSE。

**Critic 质检分级**：
- **P0 核心文件**（`package.json` / `index.html` / `src/main.js` / `src/App.vue`）缺失 → 强制重新生成
- **P1 推荐文件**（`vite.config.js` / 全局样式）缺失 → 仅记录警告，不阻塞流水线

---

## 项目结构

```
CreatAi/
├── frontend/                          # 前端（Vue 3 + TypeScript）
│   └── src/
│       ├── api/                       # Axios 接口封装（user / app / chatHistory）
│       ├── components/                # 公共组件（AppCard 等）
│       ├── layouts/                   # BasicLayout（左侧边栏 + 右侧内容区）
│       ├── pages/                     # 页面（HomePage / AppChatPage / 登录注册）
│       ├── router/                    # 路由守卫（登录鉴权）
│       ├── stores/                    # Pinia 状态（loginUser）
│       ├── styles/                    # 全局样式（Vercel Design Token）
│       ├── types/                     # TypeScript 类型定义
│       └── utils/                     # SSE 工具、可视化编辑器
│
└── src/main/java/com/yu/yuaifather/   # 后端（Spring Boot）
    ├── controller/                    # REST：用户 / 应用 / 对话历史 / 静态资源
    ├── service/                       # 业务逻辑（AppServiceImpl 为核心入口）
    ├── mapper/                        # MyBatis-Flex Mapper
    ├── model/                         # 实体、VO、枚举
    ├── ai/
    │   ├── core/                      # AiCodeGeneratorFacade、StreamHandlerExecutor
    │   ├── tools/                     # 文件工具（写/读/改/删/列目录）
    │   └── model/                     # SSE 消息、代码结果 DTO
    ├── agent/                         # ★ 多 Agent 流水线
    │   ├── core/                      # Agent 接口、AgentContext、AgentEvent、Blackboard
    ├── orchestrator/                 # GenerationOrchestrator（V2 编排 + Critic 循环）
    │   ├── workers/                   # Product / Architect / Image / Coder / Style / Critic / Deploy
    │   ├── tools/                     # ImageSearchTool（含 SSRF 防护）
    │   └── support/                   # ImagePlaceholderReplacer、JsonExtractor
    ├── config/                        # Redis 对话记忆、流式模型、COS、图片配置
    ├── ratelimiter/                   # 限流注解与 AOP
    ├── exception/                     # 全局异常处理与错误码
    └── constant/                      # AppConstant（生成目录、部署目录）
```

---

## 快速开始

### 环境要求

- **JDK 21+** · **Maven 3.6+**
- **MySQL**（默认库名 `yu_ai_code_mother`）
- **Redis**
- **Node.js 18+**（前端开发 / Vue 项目构建）

### 后端启动

**1. 复制并填写本地配置**

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

编辑 `application-local.yml`，填写数据库密码、Redis 密码和 LLM API Key（默认支持 DeepSeek，可替换为任意 OpenAI 兼容服务）：

```yaml
spring:
  datasource:
    password: "your-mysql-password"

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
```

**2. 启动后端**

```bash
./mvnw spring-boot:run
# 或跳过测试打包后运行
./mvnw -q -DskipTests package && java -jar target/yu-ai-father-*.jar
```

后端默认运行在 `http://localhost:8123`，API 前缀 `/api`。

**3. 查看 API 文档**

启动后访问：[http://localhost:8123/api/doc.html](http://localhost:8123/api/doc.html)

### 前端启动

```bash
cd frontend
npm install
npm run dev
# 默认运行在 http://localhost:5173
```

Vite 已配置代理，`/api` 请求自动转发到后端 `localhost:8123`，无需手动处理跨域。

---

## 环境变量参考

所有敏感配置仅通过环境变量注入，**禁止硬编码到代码或 yml 中**。

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_URL` | MySQL 连接串 | `jdbc:mysql://localhost:3306/yu_ai_code_mother` |
| `MYSQL_USERNAME` | 数据库用户名 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | 空 |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | 空 |
| `UNSPLASH_ACCESS_KEY` | Unsplash 搜图 API Key | 空（跳过该提供商） |
| `PEXELS_API_KEY` | Pexels 搜图 API Key | 空（跳过该提供商） |
| `PIXABAY_API_KEY` | Pixabay 搜图 API Key（首选搜图源） | 空（跳过该提供商） |
| `APP_DEPLOY_BASE_URL` | 部署访问基础 URL | `http://localhost:8123/api/static` |
| `CORS_ALLOWED_ORIGINS` | 跨域允许来源（逗号分隔）| `http://localhost:5173,...` |
| `AGENT_ORCHESTRATOR_ENABLED` | 启用多 Agent 流水线 | `false` |
| `AGENT_DEPLOY_ENABLED` | 启用 DeployAgent 自动构建上传 | `false` |
| `IMAGE_FALLBACK_ENABLED` | 搜图无结果时启用文生图兜底 | `false` |
| `IMAGE_TEXT2IMG_BASE_URL` | 文生图端点（OpenAI 兼容 /images/generations） | `https://open.bigmodel.cn/api/paas/v4` |
| `IMAGE_TEXT2IMG_API_KEY` | 文生图服务 API Key（启用兜底时必填） | 空（禁用文生图） |
| `IMAGE_TEXT2IMG_MODEL` | 文生图模型名 | `cogview-3-flash` |

---

## 分阶段上线指引

### 第 1 步：基础功能（默认开启）

无需额外配置，启动即可使用 HTML / MULTI_FILE / VUE_PROJECT 三种模式生成代码。

### 第 2 步：启用语义图片替换

```bash
export UNSPLASH_ACCESS_KEY=your_key   # 推荐
export PEXELS_API_KEY=your_key        # 备选
```

### 第 3 步：灰度启用多 Agent 流水线

```bash
export AGENT_ORCHESTRATOR_ENABLED=true
```

VUE_PROJECT 类型将走 7 Agent 流水线 + Critic 质检循环（最多 3 轮）。

### 第 4 步：开启自动部署到 COS

```bash
export AGENT_DEPLOY_ENABLED=true
# 同时确保 application-local.yml 中 COS 配置已填写
```

---

## 安全说明

- **SSRF 防护**：`ImageSearchTool` 对外部图片 URL 做 HTTPS 校验 + 域名白名单 + 私有网段拒绝
- **路径穿越防护**：所有文件写入操作前均做 `path.startsWith(projectRoot)` 校验
- **SQL 注入防护**：MyBatis-Flex 参数绑定，排序字段白名单过滤
- **接口限流**：SSE 生成接口按用户维度限流（5次/60秒），防止滥用
- **权限校验**：`@AuthCheck` AOP 注解 + Session Cookie，管理员接口单独鉴权
- **密钥安全**：所有 API Key 仅经环境变量注入，禁止写入代码或 yml 明文

---

## License

MIT
