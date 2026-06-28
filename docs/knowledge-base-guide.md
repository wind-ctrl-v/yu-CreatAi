# 知识库建设指南

项目采用**三阶段演进架构**为 AI 代码生成提供设计知识支持。

## 架构概览

| 阶段 | 名称 | 数据源 | 检索方式 | 成本 |
|------|------|--------|----------|------|
| 一 | 先验静态规范 | `skill/designs/*.md` | 规则匹配（按 styleProfile） | 零 API 调用 |
| 二 | 后验设计指纹 | MySQL + PGvector | 结构化查询 + 向量语义检索 | 入库时消耗 LLM，检索零成本 |
| 三 | 运行时证据召回 | 阶段一 + 阶段二 | 三层融合（向量→结构→静态兜底） | 检索时消耗 Embedding Token |

## 目录结构

```
docs/
├── sql/
│   ├── design_fingerprint.sql          # MySQL 指纹主表 DDL
│   ├── design_fingerprint_vector.sql   # PGvector 向量表 DDL
│   ├── 010_seed_design_fingerprint.sql # 指纹种子数据（6条）
│   ├── 011_init_pgvector_local.sql     # 本地 PGvector 初始化
│   ├── 012_seed_user_preference.sql    # 用户偏好种子数据（画像+权重+事件）
│   ├── 001_user_preference.sql         # 用户偏好表 DDL
│   └── ...
├── rag-design.md                       # RAG 知识库设计文档
└── rag-architecture-analysis.md        # RAG 架构分析文档

src/main/resources/skill/designs/
├── startup.md      # 创业/科技产品
├── default.md      # 中性通用（兜底）
├── ecommerce.md    # 电商
├── enterprise.md   # 企业/后台
├── landing.md      # 通用落地页
├── portfolio.md    # 作品集/个人站
├── dashboard.md    # 仪表盘/数据面板
├── blog.md         # 个人博客（新增）
├── marketing.md    # 营销活动页（新增）
└── education.md    # 教育/课程平台（新增）
```

## 静态设计规范格式

每个 `.md` 文件包含：
- **tone**: 调性标签（如"现代、科技、温暖"）
- **tokens**: CSS 变量定义（颜色、字体、间距、圆角、阴影）
- **layout**: 布局骨架描述
- **preferred**: 推荐使用的模式
- **interaction**: 交互规范
- **forbidden**: 禁止的反模式

## 六层设计指纹结构

```
① tokens        → 颜色比例、字号阶梯、圆角强度、阴影层级
② layout        → 区块顺序、留白节奏、视觉重心
③ components    → 导航/卡片/CTA 等组件关键词
④ interaction   → 动效风格、过渡时长
⑤ antiPatterns  → 刻意避开的反模式
⑥ styleProfile  → 风格归类 + 调性标签
```

## 初始化步骤

### 1. 启动本地 PostgreSQL
```cmd
"C:\Program Files\PostgreSQL\16\bin\pg_ctl.exe" start -D "C:\Program Files\PostgreSQL\16\data"
```

### 2. 初始化向量表（需先安装 pgvector 扩展）
```bash
psql -U fish -d yu_ai_vector -f docs/sql/011_init_pgvector_local.sql
```

### 3. 导入种子指纹数据
```bash
mysql -u root -p yu_ai_code_mother < docs/sql/010_seed_design_fingerprint.sql
```

### 4. 导入用户偏好种子数据
```bash
mysql -u root -p yu_ai_code_mother < docs/sql/012_seed_user_preference.sql
```
> 注意：SQL 中的 `@fish_user_id` 变量需要替换为实际用户 ID。

## 开关配置

`application-local.yml`:
```yaml
agent:
  retrieve:
    enabled: false       # 总开关：是否启用运行时检索
    vector:
      enabled: false     # 子开关：是否启用 PGvector 向量检索
    top-k: 2            # 召回 top-k 条数
```

启用向量检索后，`DesignRetrieveTool` 会在代码生成流水线中自动执行三层召回。
