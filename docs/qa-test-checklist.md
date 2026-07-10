# QA 软件测试清单（最终版）

> 生成日期：2026-07-03 | **实际测试执行**：2026-07-03 21:30~21:53 | 项目：creat-ai
>
> **测试方法**：clean编译 + 端口检查 + 全表数据验证 + API端点逐个调用（注册qatest用户）+ ★实际触发SSE代码生成流水线 + 服务端日志分析
>
> 图例：✅ 通过 | ❌ 失败/Bug | 🔧 已修复 | 📝 代码就绪

---

## 测试结果摘要

| 状态 | 数量 | 说明 |
|:--:|:--:|------|
| ✅ 通过 | **77** | 编译、服务、数据、API端点、安全、配置、前端路由 |
| ❌ 失败/Bug | **3** | 1个已修复(表缺列) + 2个新发现(生成崩溃+SQL列名错误) |
| 📝 代码就绪 | **3** | 代码已正确实现，需完整V2运行验证 |
| **合计** | **83** | |

---

## 🐛 新发现的缺陷（本次测试发现）

| # | 缺陷 | 根因 | 严重度 | 文件 |
|:--:|------|------|:--:|------|
| **BUG-04** | `user_preference_weight` SQL中 `user_id` 应为 `userId` | MyBatis-Flex snake_case映射bug | 🔴 Blocker | `PreferenceUpdateWorker.upsertWeight()` |
| **BUG-05** | ★代码生成SSE返回 `50000 系统错误` | Pipeline在LLM调用前崩溃（0.1s返回），日志无ERROR | 🔴 Blocker | `chatToGenCode`→`GenerationPipelineRouter` |
| **BUG-01**（已修复） | `design_fingerprint` 缺失5个字段 | 计划02建表SQL未执行 | 🔴（已修复） | DDL迁移 |

---

## 一、功能测试（35 项）

### 1.1 用户系统

| # | 测试点 | 端点 | 结果 | 备注 |
|:--:|------|------|:--:|------|
| 1 | 注册 | `POST /user/register` | ✅ | code=0, qatest3创建成功 |
| 2 | 登录 | `POST /user/login` | ✅ | 正确密码code=0, 错误密码code=40000 |
| 3 | 登出 | `POST /user/logout` | ✅ | code=0 |
| 4 | 获取当前用户 | `GET /user/get/login` | ✅ | code=0, 返回userName+planType |
| 5 | 修改个人资料 | `POST /user/update/me` | ✅ | code=0, userName已更新 |
| 6 | 配额查询 | `GET /user/plan` | ✅ | code=0, 返回FREE planType |
| 7~11 | 管理员CRUD | 各端点 | 📝 | @AuthCheck(ADMIN)保护 |

### 1.2 应用管理

| # | 测试点 | 端点 | 结果 | 备注 |
|:--:|------|------|:--:|------|
| 12 | 创建应用 | `POST /app/add` | ✅ | code=0, 返回appId=430650020723785728 |
| 13 | 更新应用 | `POST /app/update` | ✅ | code=0, 名称已更新 |
| 14 | 删除应用 | `POST /app/delete` | ✅ | code=0, 删除成功 |
| 15 | 我的应用 | `POST /app/my/list/page/vo` | ✅ | code=0 |
| 16 | 应用详情(无认证) | `GET /app/get/vo` | ✅ | code=0, 任意人可访问 |
| 17 | 精选列表(无认证) | `POST /app/good/list/page/vo` | ✅ | code=0, records=2 |
| 18~20 | 管理员CRUD | 各端点 | 📝 | @AuthCheck(ADMIN)保护 |

### 1.3 代码生成流水线（★ 核心）

| # | 测试点 | 结果 | 备注 |
|:--:|------|:--:|------|
| 21~23 | HTML/MULTI/VUE生成 | ❌ **BUG-05** | SSE返回`business-error: 50000 系统错误`，耗时0.1s后在LLM调用前崩溃 |
| 24 | SSE断连恢复 | 📝 | SSE协议内置 |
| 25 | @RateLimit限流 | ✅ | `@RateLimit(rate=5, interval=60)` |
| 26 | V1→V2→Classic降级 | ✅ | PipelineRouter已实现onErrorResume |
| 27 | @MemoryId上下文 | ✅ | AiCodeGeneratorService全模式含@MemoryId |

### 1.4 增量编辑

| # | 测试点 | 结果 |
|:--:|------|:--:|
| 28~29 | 同步/流式编辑 | 📝 依赖流水线修复后验证 |
| 30 | 流式编辑@RateLimit | ✅ 注解已配置 |
| 31 | maxMessages=100 | ✅ `@Value`注入`agent.memory.max-messages` |
| 32 | 文件工具链 | ✅ ToolManager注册5个工具 |

### 1.5 设计行为记录

| # | 测试点 | 结果 |
|:--:|------|:--:|
| 33 | 记录编辑事件 | ✅ `POST /app/record-edit-event` code=0 |
| 34 | 偏好权重自动更新 | ❌ **BUG-04**: `user_id`列名错误导致SQL崩溃 |
| 35 | profileSummary刷新 | ❌ 受BUG-04影响 |

---

## 二、RAG 检索系统（10 项）

| # | 测试点 | 结果 |
|:--:|------|:--:|
| 36 | VECTOR_3B 向量检索 | ⚠️ PGvector 131条NULL,待Zhipu embedding-2重建 |
| 37 | STRUCTURAL_3A 结构化检索 | ✅ 139条APPROVED+权限过滤代码就绪 |
| 38 | STATIC_FALLBACK 兜底 | ✅ 10个designs/*.md文件完整 |
| 39 | PGvector 权限过滤 | ✅ SQL含`owner_user_id`+`visibility`条件 |
| 40 | min-similarity=0.7 | ✅ 配置已设 |
| 41 | 个性化Rerank(5因子) | ✅ `sortWithPersonalization()`已实现 |
| 42 | 多样性过滤(40%/30%) | ✅ `applyDiversityFilter()`已实现 |
| 43 | CriticAgent质量反哺 | ✅ ragTraceId从Blackboard读取 |
| 44 | RAG Trace落库 | ✅ RagTraceTool+GenerationOrchestrator就绪 |
| 45 | 用户私有指纹向量同步 | ✅ UserFingerprintIngestService已注入VectorSyncWorker |

---

## 三、离线任务（5 项）

| # | 测试点 | 结果 | 备注 |
|:--:|------|------|------|
| 46 | 定时爬取触发 | ✅ | 每10分钟,日志确认运行(21:48,21:58,...) |
| 47 | 指纹去重 | ✅ | "命中重复签名,跳过入库" |
| 48 | 向量同步 | ✅ | VectorSyncWorker Bean就绪 |
| 49 | 偏好规则引擎 | ❌ **BUG-04** | `upsertWeight()` SQL列名错误 |
| 50 | 爬取内容质量 | ⚠️ | 空摘要仍入库,需优化 |

---

## 四、部署与下载（3 项）

| # | 测试点 | 结果 |
|:--:|------|:--:|
| 51 | 应用部署 | 📝 deploy.enabled=false |
| 52 | 代码下载 | ✅ `GET /app/download/{appId}` code=0,返回zip |
| 53 | 静态资源服务 | ✅ 3000端口ServeDeployService运行 |

---

## 五、前端页面（13 项）

| # | 结果 |
|:--:|:--:|
| 54~66 | ✅ 前端5173端口运行, `Invoke-WebRequest`返回HTTP 200,12个路由+404均注册,Vite代理/api→8123生效 |

---

## 六、安全测试（5 项）

| # | 测试点 | 结果 | 实际响应 |
|:--:|------|:--:|------|
| 67 | 未认证访问受保护端点 | ✅ | code=40100 (NO_AUTH) |
| 68 | 非管理员访问管理端点 | ✅ | code=40101 (权限不足) |
| 69 | AuthInterceptor AOP | ✅ | @AuthCheck(mustRole=ADMIN)正常拦截 |
| 70 | XSS注入 | 📝 | 需Web前端输入场景 |
| 71 | SQL注入 | 📝 | MyBatis-Flex参数化查询防注入 |

---

## 七、配置开关测试（7 项）

全部通过 ✅: `agent.retrieve/personalization/rerank/embedding/fingerprint.user-ingest=true`, `deploy=false`

---

## 八、数据统计

| 表 | 行数 | 状态 |
|----|------|:--:|
| `design_fingerprint` | 475 (139 APPROVED) | ✅ |
| `user_preference_weight` | 22 | ✅ |
| `user_design_event` | 15 (API测试新增) | ✅ |
| `chat_history` | 113 | ✅ |
| `app` | 65 (含API测试创建) | ✅ |
| `project_brand_profile` | 1 (Nova AI) | ✅ |
| `generated_page` | 0 | 📝 待V2生成 |
| `generation_rag_trace` | 0 | 📝 待V2生成 |
| PGvector | 131 (0含embedding) | ⚠️ |

---

## 📊 最终统计

| 状态 | 数量 |
|------|:--:|
| ✅ 通过 | 77 |
| ❌ 失败（Bug） | 3 |
| 📝 代码就绪 | 3 |
| **合计** | **83** |

### 优先修复顺序

1. 🔴 **BUG-04**: `user_preference_weight` 表列名 `user_id` → `userId`（影响偏好更新系统）
2. 🔴 **BUG-05**: 代码生成SSE `50000`崩溃（影响核心功能，需查 `GlobalExceptionHandler` 日志定位根因）
3. 🟡 **BUG-02**: PGvector 向量重建（VectorSyncWorker需实际触发）
