# 项目包结构图

> 根包：`com.yu.yuaifather` | 最后更新：多 Agent 流水线改造后

## 整体分层

```
controller → service → ai / agent → manager
                            ↕
                       model / mapper
```

## 完整包树

```
src/main/java/com/yu/yuaifather/
│
├── YuAiFatherApplication.java
│
├── controller/
│   ├── AppController.java                   应用CRUD、SSE流式生成、部署、ZIP下载
│   ├── ChatHistoryController.java
│   ├── HealthController.java
│   ├── StaticResourceController.java
│   └── UserController.java
│
├── service/
│   ├── AppService.java / ChatHistoryService.java / ProjectDownloadService.java
│   ├── ScreenshotService.java / ServeDeployService.java / UserService.java
│   └── impl/
│       ├── AppServiceImpl.java              ★核心：chatToGenCode 编排生成流程
│       ├── ChatHistoryServiceImpl.java
│       ├── ProjectDownloadServiceImpl.java
│       ├── ScreenshotServiceImpl.java
│       └── UserServiceImpl.java
│
├── model/
│   ├── entity/  App.java / ChatHistory.java / User.java
│   ├── enums/   ChatHistoryMessageTypeEnum.java / UserRoleEnum.java
│   ├── dto/     请求参数对象
│   └── vo/      视图对象
│
├── mapper/      MyBatis-Flex Mapper
│
├── ai/                                      AI代码生成能力层
│   ├── AiCodeGeneratorService.java          LangChain4j AI接口（@SystemMessage绑定prompt）
│   ├── AiCodeGeneratorServiceFactory.java   按appId+类型创建隔离实例（Caffeine缓存）
│   ├── AiCodeGenTypeRoutingService.java     路由判断AI接口
│   ├── AiCodeGenTypeRoutingServiceFactory.java
│   ├── enums/
│   │   └── CodeGenTypeEnum.java             HTML / MULTI_FILE / VUE_PROJECT
│   ├── core/
│   │   ├── AiCodeGeneratorFacade.java       外观：按类型调度→流式处理→图片占位符替换
│   │   ├── bulider/
│   │   │   └── VueProjectBuilder.java       npm install + npm run build
│   │   ├── handler/
│   │   │   ├── JsonMessageStreamHandler.java
│   │   │   ├── SimpleTextStreamHandler.java
│   │   │   └── StreamHandlerExecutor.java   收集AI流式回复→保存对话历史
│   │   ├── parser/
│   │   │   ├── CodeParser.java              策略接口
│   │   │   ├── CodeParserExecutor.java
│   │   │   ├── HtmlCodeParser.java
│   │   │   └── MultiFileCodeParser.java
│   │   └── saver/
│   │       ├── CodeFileSaverTemplate.java   模板方法抽象类
│   │       ├── CodeFileSaverExecutor.java
│   │       ├── HtmlCodeFileSaverTemplate.java
│   │       └── MultiFileCodeFileSaverTemplate.java
│   ├── model/
│   │   ├── HtmlCodeResult.java / MultiFileCodeResult.java
│   │   ├── dto/                             ★新增：多Agent中间产物DTO
│   │   │   ├── RequirementDoc.java          ProductAgent输出（需求文档）
│   │   │   ├── GenerationPlan.java          ArchitectAgent输出（生成计划）
│   │   │   └── CriticReport.java            CriticAgent输出（质检报告）
│   │   └── message/                         SSE消息协议
│   │       ├── AiResponseMessage.java       文字增量
│   │       ├── ToolRequestMessage.java      工具调用请求
│   │       ├── ToolExecutedMessage.java     工具执行结果
│   │       ├── StreamMessage.java / StreamMessageTypeEnum.java
│   └── tools/
│       ├── BaseTool.java
│       ├── FileWriteTool.java / FileReadTool.java / FileModifyTool.java
│       ├── FileDeleteTool.java / FileDirReadTool.java
│       └── ToolManager.java                 收集并暴露所有File工具
│
├── agent/                                   ★多Agent流水线（新增）
│   ├── AgentAiService.java                  各Agent的LangChain4j接口（绑定prompt）
│   ├── AgentAiServiceFactory.java           基于现有ChatModel bean创建实例
│   ├── core/
│   │   ├── Agent.java                       统一接口：Flux<AgentEvent> handle(AgentContext)
│   │   ├── AgentContext.java                请求上下文（appId/userPrompt/Blackboard）
│   │   ├── AgentEvent.java                  5种事件类型
│   │   └── Blackboard.java                  线程安全共享状态（ConcurrentHashMap）
│   ├── orchestrator/
│   │   └── OrchestratorAgent.java           编排：7Agent串联+Critic反馈循环（最多3轮）
│   ├── workers/
│   │   ├── ProductAgent.java                prompt → RequirementDoc JSON
│   │   ├── ArchitectAgent.java              requirementDoc → GenerationPlan JSON
│   │   ├── ImageAgent.java                  imageSlots → imageMap（并发搜图）
│   │   ├── CoderAgent.java                  复用generateVueProjectCodeStream落盘
│   │   ├── StyleAgent.java                  colorTheme → variables.css + global.css
│   │   ├── CriticAgent.java                 静态检查+LLM评分（score≥80通过）
│   │   └── DeployAgent.java                 npm build → 上传COS → deployUrl
│   ├── tools/
│   │   └── ImageSearchTool.java             Unsplash→Pexels→picsum，SSRF防护，Caffeine缓存
│   └── support/
│       ├── ImagePlaceholderReplacer.java    {{IMG:keyword}} → 真实URL
│       └── JsonExtractor.java               LLM JSON输出兜底解析
│
├── config/
│   ├── CorsConfig.java
│   ├── CosClientConfig.java                 COS客户端配置
│   ├── ImageProperties.java                 ★新增：图片服务配置绑定
│   ├── JsonConfig.java
│   ├── ReasoningStreamingChatModelConfig.java
│   └── RedisChatMemoryStoreConfig.java
│
├── ratelimiter/                             分布式限流
│   ├── annotation/   @RateLimit注解
│   ├── aspect/       限流AOP切面
│   ├── config/       Redisson配置
│   └── enums/
│
├── manager/
│   ├── CosManager.java                      COS上传/下载
│   └── ServeLifecycleManager.java           本地部署服务生命周期
│
├── exception/
│   ├── BusinessException.java / ErrorCode.java
│   ├── GlobalExceptionHandler.java / ThrowUtils.java
│
├── constant/
│   ├── AppConstant.java                     CODE_OUTPUT_ROOT_DIR / CODE_DEPLOY_ROOT_DIR
│   └── UserConstant.java
│
├── utils/
│   └── WebScreenshotUtils.java              Selenium页面截图工具
│
└── generator/
    └── MyBatisCodeGenerator.java        MyBatis-Flex代码生成脚本（仅开发期手动运行）
```

---

## Prompt 文件（`src/main/resources/prompt/`）

```
prompt/
├── codegen-routing-system-prompt.txt        路由判断：决定走HTML/MULTI_FILE/VUE_PROJECT
├── codegen-html-system-prompt.txt           HTML单文件生成
├── codegen-multi-file-system-prompt.txt     多文件生成（HTML+CSS+JS）
├── codegen-vue-project-system-prompt.txt    Vue工程生成（含File工具调用指令）
├── agent-product-system-prompt.txt          ★ ProductAgent：需求文档化
├── agent-architect-system-prompt.txt        ★ ArchitectAgent：技术选型与文件规划
├── agent-image-system-prompt.txt            ★ ImageAgent：图片搜索指令
├── agent-coder-system-prompt.txt            ★ CoderAgent：按plan逐文件生成代码
├── agent-style-system-prompt.txt            ★ StyleAgent：全局CSS变量与样式
├── agent-critic-system-prompt.txt           ★ CriticAgent：质检评分（P0/P1/P2扣分制）
└── agent-deploy-system-prompt.txt           ★ DeployAgent：构建部署指引
```

---

## 关键设计模式

| 模式 | 位置 | 说明 |
|------|------|------|
| 外观（Facade） | `AiCodeGeneratorFacade` | 统一入口屏蔽三种生成类型差异 |
| 策略（Strategy） | `ai/core/parser/` `ai/core/saver/` | 不同类型的解析/保存策略互换 |
| 模板方法（Template Method） | `CodeFileSaverTemplate` | 保存流程固化，子类填写具体写入逻辑 |
| 工厂（Factory） | `AiCodeGeneratorServiceFactory` `AgentAiServiceFactory` | 按类型/appId创建隔离AI服务实例 |
| 黑板（Blackboard） | `agent/core/Blackboard` | 多Agent协作的线程安全共享状态 |
| 编排（Orchestrator） | `OrchestratorAgent` | 控制Agent执行顺序与反馈循环 |

---

## 本次清理说明

| 删除文件 | 原因 |
|---------|------|
| `ai/core/CodeFileSaver.java` | 旧版顶层保存类，已被 `ai/core/saver/` 子包完整替代，无任何引用 |
| `ai/core/CodeParser.java` | 旧版顶层解析类，已被 `ai/core/parser/CodeParser` 接口替代，无任何引用 |
| `AppServiceImpl` 注释块 | 注释掉的旧版 `chatToGenCode` 实现（原157-177行），已由新版取代 |
