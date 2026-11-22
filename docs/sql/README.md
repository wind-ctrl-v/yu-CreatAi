# SQL 脚本治理

`docs/sql` 按用途分子目录。生产 MySQL 只按编号执行 `migrations/` 里尚未应用的增量脚本。禁止把 seed、dev、test、postgresql、legacy 混进生产自动迁移，也禁止继续往 `docs/sql` 根目录无规则追加脚本。

## 目录

| 目录 | 用途 | 能否进生产 |
|---|---|---|
| `migrations/` | MySQL 增量 schema migration，按编号顺序执行，已执行文件不可修改 | 可以 |
| `seed/` | 开发/测试种子数据 | 否 |
| `postgresql/` | PostgreSQL / PGvector，仅 `agent.retrieve.vector.store=pg` | `store=mysql` 时不执行 |
| `dev/` | 人工审核后的一次性运维脚本 | 否 |
| `test/` | 检索/通道试验脚本 | 否 |
| `legacy/` | 历史参考 DDL，不是生产基线 | 否；不得与增量脚本重复执行 |

## MySQL schema migration（`migrations/`）

按编号顺序执行且已执行文件不可修改：

- `migrations/001_user_preference.sql`
- `migrations/002_fingerprint_scope_migration.sql`
- `migrations/003_project_brand.sql`
- `migrations/004_fingerprint_quality_fields.sql`
- `migrations/005_tool_call_trace.sql`
- `migrations/006_generation_run.sql`
- `migrations/007_user_plan_type.sql`
- `migrations/008_user_quota.sql`
- `migrations/013_design_miss_log.sql`
- `migrations/020_project_brand_profile.sql`
- `migrations/021_design_fingerprint_vector_mysql.sql`：MySQL 向量后端建表（`agent.retrieve.vector.store=mysql` 时使用）
- `migrations/022_design_fingerprint_usage_count_compat.sql`：把 `usageCount` / `usage_count` 对齐到实体使用的驼峰列，保留已有计数
- `migrations/023_user_preference_evidence_count_compat.sql`：把偏好权重表的证据列对齐到驼峰列，保留已有证据

编号 `009` 当前为空缺，不得回填；新迁移使用大于现有最大编号的新版本，并放在 `migrations/`。

`legacy/design_fingerprint.sql` 是历史基础 DDL，不是编号迁移，也不是生产基线（生产基线仍从开发库导出结构快照，见 `docs/deploy/README.md`）。应在引入正式迁移工具时另补版本化基线，不得与增量脚本重复执行。

## Seed（`seed/`）

仅用于开发/测试环境，执行前必须替换示例用户和雪花 ID：

- `seed/010_seed_design_fingerprint.sql`
- `seed/012_seed_user_preference.sql`

## PostgreSQL / PGvector（`postgresql/`）

仅当 `agent.retrieve.vector.store=pg`（默认，保持改造前行为）时需要执行；
`store=mysql` 时**不需要**部署 PostgreSQL，改用上面 MySQL 清单中的
`migrations/021_design_fingerprint_vector_mysql.sql`。

- `postgresql/011_init_pgvector_local.sql`
- `postgresql/design_fingerprint_vector.sql`

以上脚本运行于 PostgreSQL，禁止混入 MySQL 初始化流程。

## Dev（`dev/`）

只允许人工审核后执行，不属于 schema migration。向量脚本面向 PostgreSQL；`optimize_prefs.sql` 面向 MySQL 偏好数据：

- `dev/add_more_vectors.sql`（PostgreSQL）
- `dev/batch_sync_vectors.sql`（PostgreSQL）
- `dev/sync_remaining_vectors.sql`（PostgreSQL）
- `dev/optimize_prefs.sql`（MySQL）

## Test（`test/`）

不得在生产环境执行：

- `test/test_all_channels.sql`（PostgreSQL / psql）
- `test/test_retrieval.sql`（MySQL）

## 规则

1. schema 变更只能在 `migrations/` 追加版本化脚本，不修改已执行迁移。
2. 所有应用查询继续使用参数绑定，运维脚本不得拼接未校验输入。
3. MySQL 与 PostgreSQL 分开执行，禁止跨数据库事务假设。
4. Seed、Dev、Test 不进入生产自动迁移。PostgreSQL 脚本仅在 `store=pg` 时单独执行；`legacy/` 不是生产基线。
5. 引入 Flyway 前，以本文件列出的顺序作为唯一人工执行清单；引入后迁移以 `src/main/resources/db/migration` 为唯一入口。
