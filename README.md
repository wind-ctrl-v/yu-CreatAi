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
| AI | **LangChain4j**（OpenAI 兼容接口，流式对话） |
| 对象存储 | 腾讯云 **COS** |
| 其他 | **Knife4j**（OpenAPI3）、**Hutool**、**Selenium**（截图相关能力） |

## 项目结构（主要包）

```
src/main/java/com/yu/yuaifather/
├── YuAiFatherApplication.java    # 启动类（排除 LangChain4j Redis Embedding 自动配置）
├── controller/                   # REST：用户、应用、对话历史、静态资源、健康检查
├── service/                      # 业务实现
├── mapper/                       # MyBatis-Flex Mapper（启动类 @MapperScan）
├── model/                        # 实体、VO、DTO
├── ai/                           # 代码生成门面、流式处理、工具调用（读写改删文件等）
├── ratelimiter/                  # 限流注解与 AOP、Redisson 配置
├── config/                       # Redis 对话记忆、推理流式模型、COS 等
├── exception/                    # 全局异常与错误码
├── manager/                      # COS、部署生命周期等
├── constant/                     # 应用常量（生成目录、部署目录等）
└── utils/                        # 工具类（如截图）
```

生成与部署使用的本地目录（相对进程工作目录）在 `AppConstant` 中定义：

- 生成输出：`tmp/code_output`
- 部署输出：`tmp/code_deploy`

## 对外接口概览

全局前缀：`http://localhost:<port>/api`（端口与 profile 以配置为准）。

| 前缀 | 说明 |
|------|------|
| `/user` | 用户注册、登录、信息管理（部分接口需登录） |
| `/app` | 应用 CRUD、**SSE 流式生成代码**、部署、ZIP 下载等 |
| `/chatHistory` | 按应用分页查询对话历史；管理员全量分页 |
| `/static/{deployKey}/**` | 本地生成物静态访问（目录访问会重定向，默认 `index.html`） |
| `/health/` | 健康检查，返回业务包装的成功响应 |

流式接口示例：`GET /app/chat/gen/code`（`text/event-stream`），带用户维度限流。

## 运行前准备

1. **JDK 21**、**Maven 3.6+**
2. **MySQL**：创建与 `spring.datasource` 一致的数据库（默认配置里库名为 `yu_ai_code_mother`，以你本地 `application.yml` / profile 为准）
3. **Redis**：与 `spring.data.redis` 配置一致；Session 依赖 Redis
4. **AI 与 COS**：在 **profile 配置文件**（如 `application-local.yml`）中配置 LangChain4j 的 OpenAI 兼容端点与密钥、COS 等。**请勿将真实密钥提交到 Git**；若曾误提交，请在服务商控制台轮换密钥。

默认主配置中 `spring.profiles.active` 为 **`local`**，即会叠加加载 `application-local.yml`。

## 构建与启动

```bash
mvn -q -DskipTests package
mvn spring-boot:run
```

或在 IDE 中运行 `com.yu.yuaifather.YuAiFatherApplication`。

服务端口、上下文路径见 `src/main/resources/application.yml`（当前示例为端口 **8123**，`context-path: /api`）。

## API 文档

已集成 **Knife4j**（扫描包 `com.yu.yuaifather.controller`）。启动后一般在浏览器访问（具体路径以 Knife4j 版本为准）：

- `http://localhost:8123/api/doc.html`

若路径与预期不一致，可查看 SpringDoc/Knife4j 启动日志或访问 `v3/api-docs` 相关端点进行确认。

## 代码生成辅助

工程内包含 **MyBatis-Flex Codegen** 依赖与生成器类（`generator` 包），可按需执行以生成 Mapper/实体等（执行方式以类内 `main` 或项目约定为准）。

## 许可证

未在仓库中统一声明时，以项目所有者约定为准。
