# SQL 脚本治理

`docs/sql` 暂时保留历史路径，后续数据库变更必须按下列类别管理，禁止继续无规则追加脚本。

## 分类

### MySQL schema migration

按编号顺序执行且已执行文件不可修改：

- `001_user_preference.sql`
- `002_fingerprint_scope_migration.sql`
- `003_project_brand.sql`
- `004_fingerprint_quality_fields.sql`
- `005_tool_call_trace.sql`
- `006_generation_run.sql`
- `007_user_plan_type.sql`
- `008_user_quota.sql`
- `020_project_brand_profile.sql`
- `021_design_fingerprint_vector_mysql.sql`：MySQL 向量后端建表（`agent.retrieve.vector.store=mysql` 时使用）
- `design_fingerprint.sql`：历史基础 DDL，应在引入正式迁移工具时补为版本化基线，不得与增量脚本重复执行。

编号 `009` 当前为空缺，不得回填；新迁移使用大于现有最大编号的新版本。

### Seed

仅用于开发/测试环境，执行前必须替换示例用户和雪花 ID：

- `010_seed_design_fingerprint.sql`
- `012_seed_user_preference.sql`

### PostgreSQL / PGvector migration

仅当 `agent.retrieve.vector.store=pg`（默认，保持改造前行为）时需要执行；
`store=mysql` 时**不需要**部署 PostgreSQL，改用上面 MySQL 清单中的
`021_design_fingerprint_vector_mysql.sql`。

- `011_init_pgvector_local.sql`
- `design_fingerprint_vector.sql`

以上脚本运行于 PostgreSQL，禁止混入 MySQL 初始化流程。

### Ops

只允许人工审核后执行，不属于 schema migration：

- `add_more_vectors.sql`
- `batch_sync_vectors.sql`
- `sync_remaining_vectors.sql`
- `optimize_prefs.sql`

### Test

不得在生产环境执行：

- `test_all_channels.sql`
- `test_retrieval.sql`

## 规则

1. schema 变更只能追加版本化脚本，不修改已执行迁移。
2. 所有应用查询继续使用参数绑定，运维脚本不得拼接未校验输入。
3. MySQL 与 PostgreSQL 分开执行，禁止跨数据库事务假设。
4. Seed、Ops、Test 不进入生产自动迁移。
5. 引入 Flyway 前，以本文件列出的顺序作为唯一人工执行清单；引入后迁移以 `src/main/resources/db/migration` 为唯一入口。
