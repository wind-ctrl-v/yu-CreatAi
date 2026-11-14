# 2C4G 单机部署改造实施计划与实施记录

> 状态：**已实施并通过本地构建/单测验证**（2026-11-14）。
> 目标环境：阿里云 2 核 4G / 50G 盘 / Ubuntu 24.04 单机，**不安装 PostgreSQL**，低流量长期维护。
> 部署步骤见 [README.md](README.md)；生产配置模板见 [application-prod.yml.example](application-prod.yml.example)。

## 1. 目标与验收标准

目标：在 2C4G 单机上"功能全开 + 低流量可用"——多 Agent V2、语义检索（3B）、封面截图、指纹爬取、文生图兜底、一键部署/下载全部保留；构建/截图串行保护；磁盘可控。

验收标准：

1. `agent.retrieve.vector.store=mysql` 时正常启动，机器上无 postgres 进程，语义检索日志出现 `VECTOR_3B` 通道命中。
2. 同时 2 个 Vue 部署请求：第二个排队/快速失败并给出明确提示，实际最多 1 个构建在执行。
3. 并发生成超过上限时返回错误码 42900。
4. 删除应用后 `tmp/code_output/{codeGenType}_{appId}` 目录被清理。
5. 截图线程池实际并发 ≤1，封面可生成且不 OOM。
6. 常驻内存（JVM+MySQL+Redis+Nginx）约 2.8–3.2G；构建/截图峰值 ≤3.7G，日志无 OOM。
7. `mvn -q -DskipTests package` 通过；新增单测通过；既有测试除原本就需要 Chrome/外网的截图集成测试外不回归。
8. 接口路径/契约不变（仅新增一个管理员重同步接口），前端零改动。

## 2. 前提与假设

- 服务器：2C4G / 50G / Ubuntu 24.04；MySQL 8 + Redis 7 同机；不装 PG。
- Embedding 使用 OpenAI 兼容服务（智谱 `embedding-2`，1024 维），维度由配置校验。
- Chrome 使用 npmmirror 的 Chrome for Testing（linux64）+ 匹配 chromedriver；另装中文字体（`fonts-noto-cjk`）。
- 生产配置通过 `/opt/creat-ai/config/application-prod.yml` 外置注入（不进仓库）；仓库只提交 example。
- 前端在本地上传前构建；服务器只跑后端 jar + 用 Node 构建用户生成的 Vue 项目。
- 兼容性原则：**所有新能力默认关闭或保持现有默认值**，生产配置显式开启，便于回滚。
- 代码风格沿用：Lombok、Hutool、`@ConfigurationProperties` + `@Component`、中文注释/日志、既有包结构。
- 本次不做：pnpm、容器化、多实例、前端改动、外部向量服务、OSS/CDN 迁移。

## 3. 变更总览

| 子系统 | 变更 | 默认行为 |
|---|---|---|
| A 向量存储 | PG 与 MySQL 双后端，新增 MySQL 实现（BLOB + 内存快照 + Java 余弦） | `store=pg` 保持现状 |
| B 构建护栏 | 全局构建锁、超时可配、构建后清 `node_modules`（可配） | 锁总是生效，超时默认旧值，清理默认 false |
| C 截图护栏 | 线程池参数外置、Chrome/Driver 路径可配、Chrome 运行逻辑抽成组件 | 默认值等于当前值 |
| D 磁盘清理 | 删除应用时清理代码目录 | 新增（幂等、安全校验） |
| E 并发限制 | 新注解 + AOP，限制并发生成/部署 | 默认 0 = 不限制 |
| F 配置 | `application.yml` 增加新配置项与默认值 | 旧行为不变 |
| G 部署工件 | `docs/deploy/` 全套脚本与手册 | 新增文件 |
| H 数据库 | 新增 MySQL 向量表 DDL | 仅新增表 |
| I 测试 | 新增单测 + 截图集成测试禁用 | — |

## 4. 详细设计

### 4.1 A：向量存储（PG → 可切换，2C4G 用 MySQL）

接口 `com.yu.yuaifather.repository.VectorStore`：

```java
void upsert(Long fingerprintId, String styleProfile, Double confidence, Integer priority,
            String embeddingText, float[] embedding, String sourceScope, Long ownerUserId,
            Long projectId, String visibility, String sourceType);
List<DesignFingerprintVector> search(float[] queryEmbedding, String styleProfile,
                                     int topK, double minSimilarity, Long userId);
void deactivate(Long fingerprintId);
```
保留 6 参 `upsert` 与 4 参 `search` 的 default 重载。

类结构调整：

| 文件 | 动作 |
|---|---|
| `repository/DesignFingerprintVectorRepository.java` | 改为门面：注入 `VectorStore`，保留原方法签名并转发；条件 `agent.retrieve.vector.enabled=true` 不变，调用方零改动 |
| `repository/PgVectorStore.java` | 新增：原 PG SQL（`?::vector`、`<=>`、`ON CONFLICT`）原样迁入 |
| `repository/MysqlVectorStore.java` | 新增：MySQL 持久化 + 内存快照 + Java 余弦；启动加载、定时重载、写穿、维度校验、max-entries 防 OOM、失败降级为 3A |
| `repository/VectorMath.java` / `VectorSnapshot.java` | 新增：纯函数余弦/TopK 与并发快照（无 Spring 依赖，便于单测） |
| `config/VectorStoreConfiguration.java` | 新增：`@Bean` 按 `agent.retrieve.vector.store` 二选一装配 |
| `config/PgVectorDataSourceConfig.java` | 嵌套 `@Configuration` 实现 `enabled=true` + `store=pg` 双条件 |
| `config/VectorStoreProperties.java` | 新增 `@ConfigurationProperties("agent.retrieve.vector")` |

MySQL 表（`docs/sql/021_design_fingerprint_vector_mysql.sql`）：

```sql
CREATE TABLE IF NOT EXISTS design_fingerprint_vector (
  id BIGINT NOT NULL AUTO_INCREMENT,
  fingerprint_id BIGINT NOT NULL,
  style_profile VARCHAR(32) NOT NULL DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
  confidence DOUBLE NULL,
  priority INT NULL DEFAULT 30,
  embedding_text TEXT NULL,
  embedding BLOB NOT NULL,              -- float32 小端序，长度 = dim*4
  dim SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  norm DOUBLE NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  source_scope VARCHAR(16) NULL,
  owner_user_id BIGINT NULL,
  project_id BIGINT NULL,
  visibility VARCHAR(16) NULL,
  source_type VARCHAR(32) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fingerprint_id (fingerprint_id),
  KEY idx_active_status_priority (is_active, status, priority),
  KEY idx_style_active (style_profile, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

检索行为：过滤 active / APPROVED / styleProfile 精确匹配（非空时）/ 权限 `GLOBAL` 或本人 / 维度一致 → 余弦打分 → `minSimilarity` 阈值 → 定长小顶堆 TopK → 相似度降序返回副本。加载失败不阻塞启动，自动退化为 3A 结构化召回。

配置键（新增，默认值=旧行为）：

```yaml
agent.retrieve.vector:
  store: pg            # 2C4G 生产配 mysql
  reload-interval-ms: 300000
  expected-dim: 0      # 智谱 embedding-2 生产配 1024
  max-entries: 20000
  resync-max: 100
```

存量向量重建：管理员接口 `POST /api/fingerprint/admin/vector/resync?limit=100`（响应 `enabled/limit/scanned/attempted/failedIds`，可重复调用直到补完）。

### 4.2 B：构建护栏

新增 `config/BuildProperties`（`codegen.build.*`）：`lock-wait-seconds=300`、`install-timeout-seconds=300`、`build-timeout-seconds=180`、`cleanup-node-modules=false`。

`VueProjectBuilder`：`buildProject()` 用公平 `ReentrantLock` 串行（`tryLock(lockWaitSeconds)` 失败抛"当前有构建任务正在执行，请稍后再试"），超时读配置，构建成功后按开关删除 `node_modules`；命令仍为 `npm`，不改生成项目内容。

### 4.3 C：截图 / Chrome 护栏

新增 `config/ScreenshotProperties`（`app.screenshot.*`）：`chrome-binary`、`driver-path`、`use-web-driver-manager`、`executor.core/max/queue`（默认 2/4/20，与现状一致）。

- 新增 `media/screenshot/ChromeRuntime`：统一解析驱动/浏览器路径（显式配置 → WebDriverManager → 带提示异常）。
- `WebScreenshotUtils` 由静态工具类改为 Spring Bean；删除 Windows 硬编码 chromedriver 路径；`ScreenshotServiceImpl`/`RenderedHtmlFetcher` 同步改造；SSRF/同源校验不变。

### 4.4 D：删除应用清理磁盘

新增 `codegen/ProjectDirCleaner`：`clean(dirName)` 基于 `AppConstant.CODE_OUTPUT_ROOT_DIR` 做 `normalize + startsWith` 校验后删除，失败仅 warn。
`AppServiceImpl.removeById` 在事务内先取 `App` 记录，删除成功后 best-effort 清理 `{codeGenType}_{appId}` 目录（不影响事务与返回值）。

### 4.5 E：并发限制

新增 `ratelimiter/ConcurrencyLimit` + `ConcurrencyLimitAspect`：

- 上限来自 `app.limits.<key>.max-concurrent`（0=不限制，默认保持旧行为）；
- 名额耗尽立即抛 `TOO_MANY_REQUEST`（42900）并打点 `concurrency.rejected{key}`；
- 返回 `Flux` 的 SSE 在流终止（`doFinally`）后释放名额，其它在 `finally` 释放。
- 应用：`chatToGenCode`/`incrementalEditStream`（generation）、`deployApp`（deploy）。

### 4.6 配置汇总（新增项默认值 = 旧行为）

```yaml
codegen.build:
  lock-wait-seconds: 300
  install-timeout-seconds: 300
  build-timeout-seconds: 180
  cleanup-node-modules: false
app.screenshot:
  chrome-binary: ""
  driver-path: ""
  use-web-driver-manager: true
  executor: { core-pool-size: 2, max-pool-size: 4, queue-capacity: 20 }
app.limits:
  generation: { max-concurrent: 0 }
  deploy:     { max-concurrent: 0 }
```

### 4.7 G：部署工件（docs/deploy/）

| 文件 | 内容 |
|---|---|
| `README.md` | 2C4G 完整 runbook：购买/系统初始化（swap、时区、ufw 22/80）→ MySQL/Redis/Node/Chrome → 基线 schema+021 → 构建上传 → 外置配置 → systemd → Nginx → 冒烟+向量重同步 → 备份/清理 cron → 升级回滚 → 排障 |
| `application-prod.yml.example` | 无真实密钥；全功能开关、store=mysql、expected-dim=1024、构建/截图/并发护栏参数 |
| `systemd/creat-ai.service` | `/opt/creat-ai`、prod profile、JVM 512m/1200m/Metaspace 256m、NODE_OPTIONS、Restart=always、journal |
| `nginx/creat-ai.conf` | SPA fallback、`/api/` 反代、SSE 关缓冲、read_timeout 900s、gzip |
| `scripts/install-chrome.sh` | npmmirror chrome-for-testing + chromedriver + fonts-noto-cjk |
| `scripts/backup.sh` / `scripts/cleanup.sh` | mysqldump+code_output 保留 7 天 / 清旧 node_modules 与孤儿目录 |
| `_validate.py` | 配置键自检工具（无第三方依赖，可选） |

### 4.8 H：数据库

- 新增 `docs/sql/021_design_fingerprint_vector_mysql.sql`；更新 `docs/sql/README.md`（迁移清单 + PG 脚本仅 `store=pg` 使用）。
- 不改动任何既有 MySQL 表结构。

### 4.9 I：测试

新增（不依赖外部服务）：`VectorMathTest`、`VectorSnapshotTest`、`DesignFingerprintVectorRepositoryTest`（手写 Fake，不依赖 Mockito）、`MysqlVectorStoreCodecTest`、`ProjectDirCleanerTest`、`PropertiesBindingTest`、`VectorStoreConfigurationTest`；`WebScreenshotUtilsTest` 改为手工执行（`@Disabled`）。

## 5. 实施顺序与分工

| 阶段 | 任务 | 依赖 |
|---|---|---|
| P1 | 配置骨架：`application.yml` 新增键 + 4 个 properties 类 | 无 |
| P2-1 | A 向量存储（门面/双实现/装配/DDL/重同步接口） | P1 |
| P2-2 | B+C+D 构建/截图/清理护栏 | P1 |
| P2-3 | E 并发限制 | P1 |
| P2-4 | G 部署工件 | 无 |
| P3 | I 测试 + 全量验证 | P2 完成 |

冲突约定：`application.yml` 只由 P1 修改；`docs/sql/README.md` 只由 P2-1；根 `README.md` 只由 P2-4。

## 6. 兼容性与回滚

- 新行为均有开关且默认保持现状（`store` 默认 pg、并发上限默认 0、构建清理默认 false、超时/线程池默认旧值）。
- 回滚：生产配置改回 `store=pg`，或回滚 jar；新增 MySQL 表为附加表，不影响旧逻辑。
- 无接口/前端契约变更；唯一新增为管理员 resync 接口；无既有表结构变更。
- 删除磁盘目录不可逆，但仅作用于 `CODE_OUTPUT_ROOT_DIR/<codeGenType>_<appId>` 且带越界校验。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 向量维度不一致（PG 脚本 1536 vs 智谱 1024） | MySQL 表不限制维度 + `expected-dim` 校验 + 文档写明；PG 旧路径保持 1536 |
| 2C4G 构建超时 | 生产配 900/450 + 全局串行 + npmmirror |
| Chrome 国内下载失败 | npmmirror chrome-for-testing + 可配置本地 driver/chrome 路径 |
| 内存快照超限 OOM | `max-entries` 上限 + 超限 ERROR + 只加载上限条数 |
| 重同步耗时 | 单次 100 条、同步执行、文档要求维护窗口执行 |
| 构建锁导致第二个部署等待 | `lock-wait-seconds` 可配，超时给出明确业务提示 |
| 现有 @SpringBootTest 依赖本地 DB | 验证采用定向测试 + 打包构建 |

## 8. 实施结果（2026-11-14）

| 项 | 结果 |
|---|---|
| 打包 | `mvn -q -DskipTests package` 成功，`yu-ai-father-0.0.1-SNAPSHOT.jar` 约 123 MB |
| 单测 | 新增 7 个测试类、27 个用例全部通过（余弦/TopK、快照过滤、门面委托、BLOB 编解码、磁盘清理、配置绑定、后端装配） |
| 装配验证 | 默认与 `store=pg` → `PgVectorStore`；`store=mysql` → `MysqlVectorStore`；`enabled=false` → 不装配 |
| 默认兼容 | `store=pg` 路径与改造前 SQL/行为一致（单测 + 代码审查） |
| 未验证 | 完整 Spring Boot 启动（需 MySQL/Redis 全链路）、Linux 侧 `bash -n`/`nginx -t`/systemd/Chrome 安装，需在目标服务器按手册验证 |

已知历史遗留（非本次引入）：PG 模式下 `pgVectorJdbcTemplate` 会让 Spring Boot 自动配置的 `jdbcTemplate` 退避，`ToolCallTraceService` 可能拿到 PG 模板；`store=mysql` 生产模式不存在该问题。
