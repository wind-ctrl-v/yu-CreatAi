# CreatAi 生产部署手册（2 核 4G 单机版）

> 目标机器：**2 vCPU / 4 GiB 内存 / 50 GiB 系统盘 / 200 Mbps 峰值带宽 / Ubuntu 24.04 LTS**
> 单机部署：**MySQL 8 + Redis 7 + Nginx + JDK 21 + Node 20 + headless Chrome**
> 明确不安装 **PostgreSQL**：向量检索使用 `agent.retrieve.vector.store=mysql`（MySQL 表 + JVM 内存快照 + Java 余弦 TopK）。

本目录是完整 runbook 与配套工件，所有路径、配置键、命令均与当前仓库代码一致（核对过 `src/main/resources/application.yml`、
`StaticResourceController`、`AppServiceImpl#deployApp`、`CosClientConfig`、`docs/sql/README.md` 等）。
**本目录内不包含任何真实密钥/账号/密码**，所有敏感值一律用 `CHANGE_ME` 占位。

---

## 0. 架构与端口

```
浏览器 ──80──> Nginx ──/──────────────> /var/www/creat-ai（Vue3 前端 dist，SPA）
                    └──/api/──────────> 127.0.0.1:8123（Spring Boot，context-path=/api）
                                          ├─> MySQL 8   127.0.0.1:3306  业务库 yu_ai_code_mother
                                          ├─> Redis 7   127.0.0.1:6379  Session / 对话记忆快照
                                          ├─> headless Chrome /opt/chrome（同步页截图）
                                          └─> 本地磁盘（生成页面 + 封面截图；COS 仅在需要时可选）
```

| 组件 | 版本 | 监听 | 说明 |
|---|---|---|---|
| Nginx | Ubuntu 24.04 apt（1.24+） | `0.0.0.0:80` | 唯一公网入口 |
| Spring Boot | Java 21 / Boot 3.2.10 | `127.0.0.1:8123` | jar 由本地构建后上传 |
| MySQL | 8.0（apt） | `127.0.0.1:3306` | 主库；**不装 PostgreSQL** |
| Redis | 7.0（apt） | `127.0.0.1:6379` | 登录态 + 记忆快照 |
| Node.js | 20 LTS | 无端口 | 后端 fork `npm` 构建生成的 Vue 项目 |
| Chrome for Testing | 131.x（可升级） | 无端口 | 截图；安装到 `/opt/chrome` |

**磁盘预算（50 GiB 参考）**：系统与软件 ~6 GiB、Chrome ~0.4 GiB、JVM/jar ~0.5 GiB、
MySQL 数据 3–8 GiB、`code_output` 生成产物 10–20 GiB、备份 3–5 GiB、Swap 4 GiB。
`cleanup.sh` + `backup.sh` 是防止 50 GiB 被写满的关键，务必按第 15 节配置 cron。

---

## 1. 交付文件清单与安装位置

| 仓库路径 | 服务器目标位置 | 用途 |
|---|---|---|
| [`docs/deploy/README.md`](README.md) | 无需上传（按本文操作） | 本手册 |
| [`application-prod.yml.example`](application-prod.yml.example) | `/opt/creat-ai/config/application-prod.yml` | 生产外置配置（改名为正式文件并替换 `CHANGE_ME`） |
| [`systemd/creat-ai.service`](systemd/creat-ai.service) | `/etc/systemd/system/creat-ai.service` | 后端系统服务 |
| [`nginx/creat-ai.conf`](nginx/creat-ai.conf) | `/etc/nginx/conf.d/creat-ai.conf` | 站点配置（SPA + `/api/` 反代 + SSE） |
| [`scripts/install-chrome.sh`](scripts/install-chrome.sh) | 服务器上执行（无需固定路径） | 安装 Chrome/chromedriver + 中文字体 |
| [`scripts/backup.sh`](scripts/backup.sh) | `/opt/creat-ai/scripts/backup.sh` | MySQL + code_output 每日备份，保留 7 天 |
| [`scripts/cleanup.sh`](scripts/cleanup.sh) | `/opt/creat-ai/scripts/cleanup.sh` | 清理过期 node_modules 与孤儿项目目录 |
| [`_validate.py`](_validate.py) | 无需上传 | 可选：校验 `application-prod.yml.example`（或线上配置）的键名是否与仓库一致 |

安装顺序（本文第 3–15 节）：

```
系统初始化 → JDK21 → MySQL8 → 基线 schema + 021 → Redis7 → Node20 → Chrome
→ 本地构建 jar/dist 上传 → 外置配置 → systemd → Nginx → 冒烟 → 备份/清理 cron
```

---

## 2. 购买与网络准备

| 项目 | 要求 | 说明 |
|---|---|---|
| CPU/内存 | 2 vCPU / 4 GiB | 必须配 4 GiB Swap（第 3.3 节），构建/截图时会有内存尖峰 |
| 系统盘 | 50 GiB SSD | 按第 0 节预算，配合 cleanup 脚本长期可控 |
| 带宽 | 200 Mbps **峰值** | SSE 与页面流量都很小；注意峰值是突发口径 |
| 镜像 | Ubuntu 24.04 LTS x86_64 | 本文命令均按 24.04 编写（22.04 也兼容） |
| 防火墙/安全组 | 只开放 **22**（先限源 IP）和 **80** | 不要开放 8123/3306/6379；后端只监听回环 |
| 域名 | 一个 A 记录指向公网 IP | 建议先完成备案；`CHANGE_ME_HOST` 即该域名 |
| HTTPS | 可选，后置 | 本文默认只跑 80；如需 443，见第 17.5 节 |

> 200 Mbps 峰值带宽对本站足够：生成链路的耗时瓶颈是大模型 API 与 Node 构建，不是带宽。
> 真正容易撑爆的是**内存**和**磁盘**，两份运维脚本必须上线。

---

## 3. 系统初始化（root）

以下命令以 `root` 执行；`sudo -i` 进入 root shell 后逐段粘贴。

### 3.1 时区、系统更新、基础工具

```bash
timedatectl set-timezone Asia/Shanghai
timedatectl status

apt-get update -y
apt-get upgrade -y
apt-get install -y curl unzip rsync vim htop jq ca-certificates fontconfig \
    mysql-client redis-tools
```

### 3.2 journald 日志上限（50 GiB 盘必须限制）

```bash
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/creat-ai.conf <<'EOF'
[Journal]
SystemMaxUse=1G
SystemMaxFileSize=128M
EOF
systemctl restart systemd-journald
```

### 3.3 Swap 4 GiB（2C4G 的关键保险）

```bash
# 磁盘紧张可改 2G；只创建一次，重复执行会跳过
if [ ! -f /swapfile ]; then
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
fi
swapon /swapfile || true
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab

# 尽量少换出（仅作 OOM 保险），同时允许回收 inode 缓存
cat > /etc/sysctl.d/99-creat-ai.conf <<'EOF'
vm.swappiness=10
vm.vfs_cache_pressure=50
EOF
sysctl --system
free -h && swapon --show
```

### 3.4 文件句柄与内核参数

```bash
cat >> /etc/security/limits.conf <<'EOF'
creat-ai soft nofile 65535
creat-ai hard nofile 65535
root     soft nofile 65535
root     hard nofile 65535
EOF
```

### 3.5 防火墙（只开 22/80）

```bash
apt-get install -y ufw
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP'
ufw --force enable
ufw status verbose
```

### 3.6 创建运行用户与目录

后端以专用低权限用户 `creat-ai` 运行，目录结构固定如下（配置键、脚本、systemd 都依赖它）：

```bash
# 系统用户，HOME 指向 /opt/creat-ai（npm 缓存落在 /opt/creat-ai/.npm）
useradd --system --create-home --home-dir /opt/creat-ai \
    --shell /usr/sbin/nologin creat-ai || true

mkdir -p /opt/creat-ai/{config,tmp/code_output,logs,scripts,backups,docs}
mkdir -p /var/www/creat-ai
mkdir -p /var/log/creat-ai
mkdir -p /etc/creat-ai

chown -R creat-ai:creat-ai /opt/creat-ai
chown -R root:root /var/www/creat-ai /var/log/creat-ai /etc/creat-ai
chmod 755 /var/www/creat-ai
chmod 755 /var/log/creat-ai
chmod 700 /etc/creat-ai /opt/creat-ai/backups
chmod 644 /opt/creat-ai/docs 2>/dev/null || true

# 确认
ls -ld /opt/creat-ai /opt/creat-ai/tmp/code_output /var/www/creat-ai
```

> `/opt/creat-ai/tmp/code_output` 是生成代码与构建产物的真实目录：
> `AppConstant.CODE_OUTPUT_ROOT_DIR = user.dir + "/tmp/code_output"`，而 systemd 的
> `WorkingDirectory=/opt/creat-ai`，因此路径就是 `/opt/creat-ai/tmp/code_output`。
> `backup.sh` / `cleanup.sh` 的默认路径与该值一致。

---

## 4. 安装 JDK 21

```bash
apt-get install -y openjdk-21-jdk-headless
java -version          # 期望: openjdk version "21.x"
javac -version
update-alternatives --list java
```

服务器只运行 jar，**不需要安装 Maven**：jar 在本地构建（第 10 节）。

---

## 5. 安装与配置 MySQL 8

### 5.1 安装

```bash
apt-get install -y mysql-server
systemctl enable --now mysql
mysql --version        # 期望 8.0.x
```

### 5.2 2C4G 参数（只监听回环）

新建 `/etc/mysql/mysql.conf.d/creat-ai.cnf`：

```ini
[mysqld]
# 只本机可连；应用与备份脚本都走 127.0.0.1
bind-address = 127.0.0.1

# 与 JVM -Duser.timezone=Asia/Shanghai、JDBC serverTimezone=Asia/Shanghai 对齐
default-time-zone = '+08:00'

character-set-server = utf8mb4
collation-server = utf8mb4_general_ci

# 2C4G：缓冲池 256M，给 1.2G JVM + Redis + headless Chrome + npm 构建留出余量
# （不要照抄大内存优化模板的 1G/2G，否则并发构建/截图时容易触发 OOM）
innodb_buffer_pool_size = 256M
innodb_redo_log_capacity = 128M
max_connections = 50

# 慢查询留痕，便于排障
slow_query_log = 1
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 2
```

```bash
systemctl restart mysql
systemctl status mysql --no-pager
```

### 5.3 建库、建业务账号

Ubuntu 的 root 默认是 `auth_socket`，用 `sudo mysql` 免密进入：

```bash
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS yu_ai_code_mother
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 应用账号：仅业务库权限，只允许本机 TCP 连接
CREATE USER IF NOT EXISTS 'creat_ai'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON yu_ai_code_mother.* TO 'creat_ai'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# 验证（需要时把密码换成真实值，命令历史敏感可先在 shell 里 export）
mysql -h 127.0.0.1 -u creat_ai -p -e 'SHOW DATABASES;'
```

### 5.4 备份账号（第 15 节 backup.sh 使用）

```bash
sudo mysql <<'SQL'
CREATE USER IF NOT EXISTS 'creat_ai_backup'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME_BACKUP_PASSWORD';
GRANT SELECT, LOCK TABLES, SHOW VIEW, EVENT, TRIGGER, PROCESS ON *.* TO 'creat_ai_backup'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
```

---

## 6. 数据库初始化：基线 schema + 021 向量表

> **为什么不能只跑 docs/sql？** 仓库 `docs/sql/migrations/` 只有**增量迁移**（见 [`docs/sql/README.md`](../sql/README.md)），
> 不含 user/app/chat_history 等初始建表语句。因此生产基线必须从**开发库**导出一份结构快照。
> 导出后开发库当前已包含 001–020 的变更，生产**不要**再重复执行 001–020；只补跑开发库还没包含的增量（当前是 021）。

### 6.1 从开发库导出结构（在开发机执行）

```bash
# 只导结构，不含数据；--set-gtid-purged=OFF 避免导入报 GTID 错误
mysqldump -h <开发库地址> -P 3306 -u root -p \
  --no-data --routines --triggers --single-transaction --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 \
  yu_ai_code_mother > baseline_schema.sql

# 上传到服务器
scp baseline_schema.sql root@<服务器IP>:/tmp/
```

可选：如需把设计指纹种子数据带到生产（**不要**带用户/订单等真实数据）：

```bash
mysqldump -h <开发库地址> -u root -p --no-create-info --skip-triggers \
  --default-character-set=utf8mb4 yu_ai_code_mother design_fingerprint \
  > design_fingerprint_data.sql
```

### 6.2 导入（在服务器执行）

```bash
mysql -h 127.0.0.1 -u root -p yu_ai_code_mother < /tmp/baseline_schema.sql
mysql -h 127.0.0.1 -u root -p yu_ai_code_mother -e 'SHOW TABLES;'
```

### 6.3 执行 021 向量表 DDL（本部署必须）

`store=mysql` 不需要 PostgreSQL/PGvector。**只执行 MySQL 的 021**，PG 脚本
（`docs/sql/postgresql/011_init_pgvector_local.sql`、`docs/sql/postgresql/design_fingerprint_vector.sql`）**不要执行**（见 docs/sql/README 分类说明）。

```bash
# 本地：上传 021 脚本（仓库文件 docs/sql/migrations/021_design_fingerprint_vector_mysql.sql）
scp docs/sql/migrations/021_design_fingerprint_vector_mysql.sql root@<服务器IP>:/tmp/

# 服务器：执行
mysql -h 127.0.0.1 -u root -p yu_ai_code_mother \
  < /tmp/021_design_fingerprint_vector_mysql.sql

# 校验：应看到 design_fingerprint_vector 表
mysql -h 127.0.0.1 -u root -p yu_ai_code_mother -e \
  "SHOW CREATE TABLE design_fingerprint_vector\G"
```

表结构要点（与 `MysqlVectorStore` 一致）：`embedding BLOB` 存 `float[]` 小端序字节、`dim` 记录维度、
`fingerprint_id` 唯一、`is_active` 软开关。普通索引即可，**不需要** MySQL 向量索引。

### 6.4 迁移治理约定

- 已执行的迁移脚本禁止修改，新增变更按 `docs/sql/README.md` 追加到 `docs/sql/migrations/`，编号大于当前最大编号。
- Seed（`docs/sql/seed/` 的 010/012）与 Test（`docs/sql/test/`）类脚本禁止进生产。
- 生产导入基线后，后续版本升级按“先执行向后兼容的 DDL，再启动新 jar”顺序操作（第 16 节）。

---

## 7. 安装与配置 Redis 7

```bash
apt-get install -y redis-server
redis-server --version   # 期望 7.0.x
```

编辑 `/etc/redis/redis.conf`，只改以下行（其余保持默认）：

```ini
# 只监听回环，禁止公网访问
bind 127.0.0.1 -::1

# 必须设置密码，与 application-prod.yml 的 spring.data.redis.password 一致
requirepass CHANGE_ME_REDIS_PASSWORD

# 2C4G 低内存参数：上限 128M（只存 Session 与对话压缩快照，实际占用很小）
maxmemory 128mb
# 登录态（Session）不能被淘汰，否则用户会莫名掉线；
# 写满时应用会报错而不是静默丢 Session，配合监控及时扩容/清理
maxmemory-policy noeviction

supervised systemd
```

> Redis 只存 Session 与对话记忆压缩快照，允许重启后丢失（用户重新登录即可），
> 因此默认 RDB 持久化够用；如希望重启不丢登录态可自行开 `appendonly yes`（会占更多磁盘）。

```bash
systemctl restart redis-server
systemctl enable redis-server
# 验证（密码用真实值）
redis-cli -h 127.0.0.1 -a 'CHANGE_ME_REDIS_PASSWORD' ping   # PONG
redis-cli -h 127.0.0.1 -a 'CHANGE_ME_REDIS_PASSWORD' info memory | grep -E 'maxmemory|used_memory:'
```

---

## 8. 安装 Node.js 20 与 npm 源

生成 Vue 项目时后端会 fork `npm install` / `npm run build`，机器上必须装 Node 20：

```bash
# NodeSource 官方源安装 Node 20 LTS
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs
node -v     # v20.x
npm -v      # 10.x

# 服务进程通过 systemd 环境变量 NPM_CONFIG_REGISTRY 使用 npmmirror（见 creat-ai.service）；
# 管理员手工在服务器上验证时也建议配置：
npm config set registry https://registry.npmmirror.com
npm config get registry
```

> 注意：**不要**在服务器安装/运行 Maven 构建后端 jar（2C4G 跑 Maven 极慢且易 OOM），后端构建在本地完成。

---

## 9. 安装 headless Chrome（截图用）

`application-prod.yml` 中 `app.screenshot.chrome-binary` / `driver-path` 默认指向
`/opt/chrome/chrome-linux64/chrome` 与 `/opt/chrome/chromedriver-linux64/chromedriver`。

```bash
# 1) 本地把脚本传到服务器
scp docs/deploy/scripts/install-chrome.sh root@<服务器IP>:/tmp/

# 2) 服务器上执行（脚本内含镜像回退与字体检查）
sudo bash /tmp/install-chrome.sh

# 3) 指定版本（Chrome 与 chromedriver 必须同版本）
sudo CHROME_VERSION=131.0.6778.204 bash /tmp/install-chrome.sh
```

脚本行为：apt 安装 Chrome 运行库（Ubuntu 24.04 自动用 `libasound2t64` 等 t64 包名）与
`fonts-noto-cjk`（**中文截图必需**，否则中文变方块）→ 从 npmmirror
`https://registry.npmmirror.com/-/binary/chrome-for-testing/<版本>/linux64/` 下载
`chrome-linux64.zip` 与 `chromedriver-linux64.zip`（失败自动回退 Google 官方源）→ 解压到
`/opt/chrome` → 设置可执行权限 → 核对主版本号 → 打印配置片段。

```bash
# 安装后自检
/opt/chrome/chrome-linux64/chrome --headless=new --no-sandbox --disable-gpu --dump-dom about:blank | head
/opt/chrome/chromedriver-linux64/chromedriver --version
fc-list :lang=zh | head -n 3        # 应看到 Noto Sans CJK
```

版本升级：到 <https://registry.npmmirror.com/-/binary/chrome-for-testing/> 选新版本，
用 `CHROME_VERSION=<新版本>` 重跑脚本即可（Chrome/chromedriver 同版本，脚本会校验）。

---

## 10. 本地构建 jar + 前端 dist 并上传

以下命令在**本地开发机**执行（Windows 用 `mvnw.cmd` / PowerShell 等价命令）。

### 10.1 后端 jar

```bash
# 仓库根目录
./mvnw -q -DskipTests clean package
ls -lh target/yu-ai-father-0.0.1-SNAPSHOT.jar
```

Windows PowerShell：

```powershell
.\mvnw.cmd -q -DskipTests clean package
Get-Item target\yu-ai-father-0.0.1-SNAPSHOT.jar
```

### 10.2 前端 dist

```bash
cd frontend
npm ci
npm run build          # 产物目录：frontend/dist
ls -lh dist
```

### 10.3 上传到服务器

```bash
# jar → 固定文件名 app.jar（systemd 写死了该路径）
scp target/yu-ai-father-0.0.1-SNAPSHOT.jar root@<服务器IP>:/opt/creat-ai/app.jar

# 前端静态文件 → /var/www/creat-ai（首次可直接递归上传）
rsync -avz --delete frontend/dist/ root@<服务器IP>:/var/www/creat-ai/
```

服务器上修正权限：

```bash
chown creat-ai:creat-ai /opt/creat-ai/app.jar
chmod 644 /opt/creat-ai/app.jar
chown -R root:root /var/www/creat-ai
find /var/www/creat-ai -type d -exec chmod 755 {} \;
find /var/www/creat-ai -type f -exec chmod 644 {} \;
```

### 10.4 上传部署工件（配置/服务/脚本/迁移脚本）

```bash
# 在本地仓库根目录执行
scp docs/deploy/application-prod.yml.example root@<服务器IP>:/tmp/
scp docs/deploy/systemd/creat-ai.service     root@<服务器IP>:/tmp/
scp docs/deploy/nginx/creat-ai.conf          root@<服务器IP>:/tmp/
scp docs/deploy/scripts/{backup.sh,cleanup.sh,install-chrome.sh} root@<服务器IP>:/opt/creat-ai/scripts/
chmod 755 /opt/creat-ai/scripts/*.sh   # 在服务器执行，或 scp 后 sudo chmod
# 注：数据库基线 schema（第 6.1 节）与 021 向量表脚本（第 6.3 节）已在上文上传，无需重复
```

---

## 11. 外置配置 /opt/creat-ai/config/application-prod.yml

Spring Boot 会自动加载 `WorkingDirectory` 下 `config/application-prod.yml`，
systemd 已注入 `SPRING_PROFILES_ACTIVE=prod`，**无需**额外 `--spring.config.location`。

```bash
# 在服务器执行
cp /tmp/application-prod.yml.example /opt/creat-ai/config/application-prod.yml
chown creat-ai:creat-ai /opt/creat-ai/config/application-prod.yml
chmod 600 /opt/creat-ai/config/application-prod.yml

# 编辑并替换所有 CHANGE_ME（该文件含数据库/Redis/大模型/COS 凭据，禁止提交 Git）
vim /opt/creat-ai/config/application-prod.yml

# 启动前自检：必须没有任何 CHANGE_ME 残留
grep -n 'CHANGE_ME' /opt/creat-ai/config/application-prod.yml && echo '还有未替换的占位符！'
```

> 可选：在仓库目录执行 `python3 docs/deploy/_validate.py`（无第三方依赖）可校验示例配置的键名是否与
> `src/main/resources/application.yml` 同步；仓库升级后建议跑一次，防止配置键漂移。

必须替换的键（与示例文件一一对应）：

| 配置键 | 说明 |
|---|---|
| `spring.datasource.username/password` | 5.3 节创建的 `creat_ai` 账号 |
| `spring.data.redis.password` | 7 节 `requirepass` |
| `langchain4j.open-ai.*.api-key` | 大模型 Key；`embedding-model` 必须是**1024 维**模型（示例为智谱 `embedding-2`） |
| `cos.client.*` | 腾讯云 COS 五项；**必填**，否则 `CosClientConfig` 创建客户端时直接启动失败 |
| `user.default-password` | 后台新建用户的默认密码，必须改成强密码 |
| `cors.allowed-origins` / `app.deploy.base-url` | 换成真实域名（`app.deploy.base-url` 是生成应用的访问前缀，截图会从服务端打开它） |

关键生产参数的语义（示例文件里已写死并附注释）：

- `agent.orchestrator.v2.enabled=true`、`agent.deploy.enabled=false`（2C4G 关闭流水线末尾自动部署）
- `agent.retrieve.enabled=true`、`agent.retrieve.vector.enabled=true`、`store=mysql`、`expected-dim=1024`
- `agent.embedding.use-openai=true`（配合智谱 1024 维 embedding；关闭会回退 1536 维哈希向量，与 expected-dim 冲突）
- `agent.fingerprint.crawl.enabled=true`（定时爬取精选应用指纹）、`agent.fingerprint.external-crawl.enabled=false`
- `codegen.build`：`lock-wait-seconds=300`、`install-timeout-seconds=900`、`build-timeout-seconds=450`、`cleanup-node-modules=true`
- `app.screenshot`：显式 Chrome/chromedriver 路径、`use-web-driver-manager=false`、线程池 `1/1/5`
- `app.limits`：生成并发 `2`、部署并发 `1`
- `server.address=127.0.0.1`、`server.port=8123`、`server.servlet.context-path=/api`

> 若指纹量很大（>2 万条），把 `agent.retrieve.vector.max-entries` 从 20000 下调到 5000–10000，
> 该值是 JVM 内存快照上限（每条 1024 维 float 约 4–5 KB，2 万条约 100 MB+）。

---

## 12. 安装 systemd 服务并首次启动

```bash
# 安装单元文件
install -m 644 /tmp/creat-ai.service /etc/systemd/system/creat-ai.service
systemctl daemon-reload
systemctl enable --now creat-ai

# 观察启动（首次启动要加载向量快照，通常 20–60 秒）
systemctl status creat-ai --no-pager
journalctl -u creat-ai -f
```

服务定义要点（详见 [`systemd/creat-ai.service`](systemd/creat-ai.service)）：

- `WorkingDirectory=/opt/creat-ai`、`SPRING_PROFILES_ACTIVE=prod`
- JVM：`-Xms512m -Xmx1200m -XX:MaxMetaspaceSize=256m`
- 子进程 Node：`NODE_OPTIONS=--max-old-space-size=768`、`NPM_CONFIG_REGISTRY=https://registry.npmmirror.com`
- `Restart=always`、`TimeoutStopSec=60`、日志走 journald

启动成功标志：

```bash
curl -s http://127.0.0.1:8123/api/actuator/health
# 期望 {"status":"UP"}（health 端点开放；prometheus 等敏感端点需管理员登录）
```

常见启动失败：

| 日志关键字 | 原因 / 处理 |
|---|---|
| `BasicCOSCredentials` / `cos.client` | COS 五项未填或 secretId/secretKey 为空（第 11 节） |
| `Access denied for user 'creat_ai'` | MySQL 账号/密码/主机（`127.0.0.1`）不匹配（第 5 节） |
| `NOAUTH` / `WRONGPASS` | Redis `requirepass` 与配置不一致（第 7 节） |
| `expected-dim` / 维度校验失败 | embedding 模型不是 1024 维，或库里有旧维度向量（第 14.4 节） |
| `Address already in use` | 8123 被其他进程占用：`ss -lntp \| grep 8123` |
| 启动很慢或 OOM | 检查 swap 是否生效（第 3.3 节）、`free -h`、`journalctl -k \| grep -i oom` |

首次启动失败排查完可 `systemctl restart creat-ai`。

---

## 13. 配置 Nginx

```bash
apt-get install -y nginx
systemctl enable --now nginx

# 关闭默认站点（避免 80 端口 default_server 抢占域名请求）
rm -f /etc/nginx/sites-enabled/default

# 安装站点配置并替换域名
install -m 644 /tmp/creat-ai.conf /etc/nginx/conf.d/creat-ai.conf
sed -i 's/CHANGE_ME_HOST/your-domain.example.com/g' /etc/nginx/conf.d/creat-ai.conf

nginx -t
systemctl reload nginx
```

配置要点（详见 [`nginx/creat-ai.conf`](nginx/creat-ai.conf)）：

- `root /var/www/creat-ai` + `try_files $uri $uri/ /index.html`（Vue Router history 模式）
- `/api/` 反代 `127.0.0.1:8123`，**`proxy_pass` 结尾不加 `/`**，保留 `/api` 前缀以匹配后端 `context-path=/api`
- `proxy_buffering off` + `X-Accel-Buffering: no` + `proxy_read_timeout 900s`：SSE 生成进度实时下发
- `client_max_body_size 50m`、gzip、静态资源缓存、`/healthz` 探活

验证：

```bash
curl -I http://<域名>/               # 200 text/html
curl -s http://<域名>/api/actuator/health   # {"status":"UP"}
curl -s http://<域名>/api/doc.html -o /dev/null -w '%{http_code}\n'   # 200
```

---

## 14. 首次启动冒烟与向量重同步

### 14.1 冒烟清单

| # | 检查项 | 命令 / 操作 | 期望 |
|---|---|---|---|
| 1 | 后端存活 | `curl -s http://127.0.0.1:8123/api/actuator/health` | `{"status":"UP"}` |
| 2 | 首页 | 浏览器打开 `http://<域名>/` | 登录页正常渲染 |
| 3 | 静态资源 | `curl -I http://<域名>/assets/<构建出的js>` | 200，带缓存头 |
| 4 | 登录 | UI 注册并登录 | 登录成功，可进入首页 |
| 5 | 生成 | 首页输入需求生成 HTML 小工具 | SSE 实时输出、预览可用 |
| 6 | 一键部署 | 生成完成后点击部署 | 返回 `http://<域名>/api/static/<deployKey>/`，可访问 |
| 7 | 截图 | 部署后等 10–30s 看应用封面 | 封面更新；`journalctl -u creat-ai` 无 Chrome 报错 |
| 8 | 日志 | `journalctl -u creat-ai -n 100 --no-pager` | 无 ERROR 堆栈 |

命令行版登录（便于无浏览器环境验证）：

```bash
# 注册（密码规则以 UserService 校验为准，建议 8 位以上含字母数字）
curl -s -H 'Content-Type: application/json' \
  -d '{"userAccount":"admin_smoke","userPassword":"CHANGE_ME_ADMIN_PASSWORD","checkPassword":"CHANGE_ME_ADMIN_PASSWORD"}' \
  http://127.0.0.1:8123/api/user/register

# 登录并把 Cookie 存到 /tmp/cookies.txt
curl -s -c /tmp/cookies.txt -H 'Content-Type: application/json' \
  -d '{"userAccount":"admin_smoke","userPassword":"CHANGE_ME_ADMIN_PASSWORD"}' \
  http://127.0.0.1:8123/api/user/login

# 确认登录态
curl -s -b /tmp/cookies.txt http://127.0.0.1:8123/api/user/get/login
```

### 14.2 设置管理员

注册用户默认角色是 `user`；把首个账号提升为管理员（`userRole='admin'`）：

```bash
sudo mysql yu_ai_code_mother -e \
  "UPDATE user SET userRole='admin' WHERE userAccount='admin_smoke'; SELECT id,userAccount,userRole FROM user WHERE userAccount='admin_smoke';"
# 需要重新登录才会刷新 Session 中的角色
```

### 14.3 管理员重同步向量（首次上线必做）

切换 `store=mysql` 后，历史 `APPROVED` 指纹需要补建向量。接口（`DesignFingerprintController`）：

```
POST /api/fingerprint/admin/vector/resync?limit=100     # 管理员鉴权，limit 默认且最大 resync-max=100
```

- 生效前提：已执行 [`docs/sql/migrations/021_design_fingerprint_vector_mysql.sql`](../sql/migrations/021_design_fingerprint_vector_mysql.sql) 建表，
  且生产配置为 `agent.retrieve.vector.enabled=true` + `store=mysql` + `expected-dim=1024`。
- `limit` 单次上限由 `agent.retrieve.vector.resync-max`（环境变量 `AGENT_RETRIEVE_VECTOR_RESYNC_MAX`）控制，默认 100；
  指纹多时重复调用该接口直到补完。

```bash
# 先重新登录让管理员角色生效
curl -s -c /tmp/cookies.txt -H 'Content-Type: application/json' \
  -d '{"userAccount":"admin_smoke","userPassword":"CHANGE_ME_ADMIN_PASSWORD"}' \
  http://127.0.0.1:8123/api/user/login

# 触发重同步（可重复调用，直到 failedIds 为空 / scanned 不再增长）
curl -s -b /tmp/cookies.txt -X POST \
  'http://127.0.0.1:8123/api/fingerprint/admin/vector/resync?limit=100' | jq
# 期望 data 结构: {"enabled":true,"limit":100,"scanned":N,"attempted":N,"failedIds":[...]}
```

校验落库（021 脚本“运维提示”同款 SQL）：

```sql
SELECT COUNT(*) AS total, MAX(dim) AS max_dim
FROM design_fingerprint_vector
WHERE is_active = 1 AND status = 'APPROVED';
-- max_dim 期望 1024（智谱 embedding-2）；若指纹表本身为空，这里为 0 属正常，
-- 此时检索自动回退到 3A 结构检索，需先导入/审核指纹数据再重同步。
```

> 后续新审核通过（APPROVE）的指纹会自动同步向量；定时快照重载间隔 `reload-interval-ms=300000`（5 分钟）。

### 14.4 更换 embedding 模型 / 维度

1. 改 `application-prod.yml` 的 `langchain4j.open-ai.embedding-model.*` 与 `agent.retrieve.vector.expected-dim`；
2. 维度变化时清空旧向量：`TRUNCATE TABLE design_fingerprint_vector;`
3. `systemctl restart creat-ai` 后再次调用第 14.3 节的重同步接口补数据。

---

## 15. 备份与清理（cron）

### 15.1 数据库凭据文件（backup/cleanup 共用）

```bash
# /etc/creat-ai/backup.cnf，权限 600 root（内含备份账号密码，禁止提交仓库）
cat > /etc/creat-ai/backup.cnf <<'EOF'
[client]
host=127.0.0.1
port=3306
user=creat_ai_backup
password=CHANGE_ME_BACKUP_PASSWORD
EOF
chmod 600 /etc/creat-ai/backup.cnf
chown root:root /etc/creat-ai/backup.cnf
```

### 15.2 脚本安装与手工验证

```bash
# 上传 docs/deploy/scripts/*.sh 后
chmod 755 /opt/creat-ai/scripts/backup.sh /opt/creat-ai/scripts/cleanup.sh
sudo /opt/creat-ai/scripts/backup.sh
ls -lh /opt/creat-ai/backups/

# 清理脚本首次务必 DRY_RUN 演练
sudo DRY_RUN=1 /opt/creat-ai/scripts/cleanup.sh
sudo /opt/creat-ai/scripts/cleanup.sh
```

脚本行为：

| 脚本 | 行为 | 保留 |
|---|---|---|
| `backup.sh` | `mysqldump`（gzip）+ `code_output`（gzip，排除 node_modules） | 默认 7 天，`RETENTION_DAYS=14` 可覆盖 |
| `cleanup.sh` | 删除 7 天以上 `node_modules`；删除“app 表已无记录”的孤儿项目目录（仅匹配 `html_/multi_file_/vue_project_<id>`） | 默认 7 天，`NODE_MODULES_DAYS` / `ORPHAN_DAYS` 可覆盖；`DRY_RUN=1` 只打印 |

### 15.3 定时任务

```bash
cat > /etc/cron.d/creat-ai <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# 每天 03:30 备份（MySQL + code_output），保留 7 天
30 3 * * * root /opt/creat-ai/scripts/backup.sh >> /var/log/creat-ai/backup.log 2>&1
# 每周日 04:00 清理过期 node_modules 与孤儿目录
0 4 * * 0 root /opt/creat-ai/scripts/cleanup.sh >> /var/log/creat-ai/cleanup.log 2>&1
EOF
chmod 644 /etc/cron.d/creat-ai

# 备份日志轮转（避免 /var/log 无限增长）
cat > /etc/logrotate.d/creat-ai <<'EOF'
/var/log/creat-ai/*.log {
    weekly
    rotate 4
    compress
    missingok
    notifempty
    copytruncate
}
EOF
```

### 15.4 恢复

```bash
# 数据库
gunzip -c /opt/creat-ai/backups/db-yu_ai_code_mother-<时间戳>.sql.gz | \
  mysql -h 127.0.0.1 -u root -p yu_ai_code_mother

# 生成产物（按需恢复单个项目目录）
tar -xzf /opt/creat-ai/backups/code-output-<时间戳>.tar.gz -C /opt/creat-ai/tmp/
chown -R creat-ai:creat-ai /opt/creat-ai/tmp/code_output
```

> 备份含用户数据，`/opt/creat-ai/backups` 已由脚本设为 700 root；如另存到对象存储请加密。
> 建议每周把最新备份同步到异地（COS/另一台机器），避免单盘故障。

---

## 16. 升级与回滚

### 16.1 升级步骤

```bash
# ---- 本地 ----
./mvnw -q -DskipTests clean package
(cd frontend && npm ci && npm run build)

# ---- 服务器：先备份 ----
sudo /opt/creat-ai/scripts/backup.sh
sudo cp -a /opt/creat-ai/app.jar /opt/creat-ai/app.jar.bak-$(date +%Y%m%d%H%M)
sudo mv /var/www/creat-ai /var/www/creat-ai.bak-$(date +%Y%m%d%H%M)
sudo mkdir -p /var/www/creat-ai

# ---- 上传新版本 ----
scp target/yu-ai-father-0.0.1-SNAPSHOT.jar root@<服务器IP>:/opt/creat-ai/app.jar
rsync -avz --delete frontend/dist/ root@<服务器IP>:/var/www/creat-ai/

# ---- 迁移与重启 ----
# 若本版本包含 docs/sql/migrations 新增迁移：先 scp 到服务器再执行（保持向后兼容），最后重启服务
# scp docs/sql/migrations/<新迁移>.sql root@<服务器IP>:/tmp/
mysql -h 127.0.0.1 -u root -p yu_ai_code_mother < /tmp/<新迁移>.sql
sudo systemctl restart creat-ai
sudo systemctl status creat-ai --no-pager

# ---- 冒烟（第 14.1 节）----
curl -s http://127.0.0.1:8123/api/actuator/health
```

### 16.2 回滚

```bash
sudo systemctl stop creat-ai
# 回滚 jar
sudo cp -a /opt/creat-ai/app.jar.bak-<时间戳> /opt/creat-ai/app.jar
# 回滚前端
sudo rm -rf /var/www/creat-ai
sudo mv /var/www/creat-ai.bak-<时间戳> /var/www/creat-ai
sudo systemctl start creat-ai
curl -s http://127.0.0.1:8123/api/actuator/health
```

数据库 DDL 一般不可逆：升级前必须备份；如新版本迁移不兼容旧 jar，回滚需同时用 15.4 节恢复数据库备份。
`/opt/creat-ai/app.jar.bak-*` 与 `/var/www/creat-ai.bak-*` 确认稳定后及时删除，避免占满 50 GiB。

---

## 17. 日常运维与排障

### 17.1 常用命令

```bash
systemctl status creat-ai --no-pager
journalctl -u creat-ai -f
journalctl -u creat-ai --since '1 hour ago' | grep -iE 'error|exception'

free -h && swapon --show
df -h && du -sh /opt/creat-ai/tmp/code_output /opt/creat-ai/backups /opt/creat-ai/.npm
ps aux --sort=-%mem | head -n 10

ss -lntp | grep -E ':(80|8123|3306|6379)\b'
redis-cli -a 'CHANGE_ME_REDIS_PASSWORD' info memory | grep -E 'used_memory_human|maxmemory_human'
sudo mysql -e 'SHOW PROCESSLIST;'
```

### 17.2 磁盘（50 GiB 最容易踩的坑）

```bash
df -h /
du -sh /opt/creat-ai/* /var/log/* 2>/dev/null | sort -h | tail
journalctl --disk-usage
journalctl --vacuum-size=500M          # 手动压缩 journal
sudo /opt/creat-ai/scripts/cleanup.sh  # 清理 node_modules + 孤儿目录
find /opt/creat-ai/backups -type f -mtime +7 -ls
```

### 17.3 常见故障对照表

| 现象 | 排查 | 处理 |
|---|---|---|
| 页面 502 | `ss -lntp \| grep 8123`；`systemctl status creat-ai` | 后端未起/崩溃：看 journal 日志；确认 `server.address` 与端口 |
| 刷新前端子路由 404 | Nginx `location /` 是否 `try_files ... /index.html` | 按 13 节修正并 `nginx -t && reload` |
| 生成进度“憋住”，最后一次性出现 | `/api/` 是否 `proxy_buffering off` | 按 13 节修正；SSE 必须关缓冲、超时 900s |
| 上传/请求体 413 | `client_max_body_size` | 已配 50m；如业务调整同步改 |
| 截图失败 `chrome not found` | `ls /opt/chrome/chrome-linux64/chrome` | 重跑 install-chrome.sh |
| 截图失败 driver 版本不匹配 | 两个 `--version` 主版本 | 用同一 `CHROME_VERSION` 重装 |
| 截图中文方块 | `fc-list :lang=zh` | 安装 `fonts-noto-cjk` |
| 生成 Vue 项目 npm 报错 | journal 中 npm 输出 | 确认 `NPM_CONFIG_REGISTRY` 生效、npm 缓存可写、磁盘未满 |
| 构建提示“当前有构建任务正在执行” | `codegen.build.lock-wait-seconds` | 串行保护（2C4G 有意为之），等待或降低并发 |
| 进程被 OOM kill | `journalctl -k \| grep -i oom` | 确认 swap 生效；降低 `app.limits.generation.max-concurrent`、`max-entries`；跑 cleanup |
| MySQL `Too many connections` | Hikari 上限 10、`max_connections=100` | 查是否有连接泄漏/慢 SQL（slow.log） |
| 时区相差 8 小时 | JDBC `serverTimezone`、JVM `-Duser.timezone`、MySQL `default-time-zone` | 三处统一 Asia/Shanghai / +08:00 |
| 登录后刷新掉线 | Redis 被清理或密码变化 | `noeviction` + 密码三处一致；检查 Redis 内存占用 |

### 17.4 监控

- 存活探测：`GET /healthz`（Nginx 已配）或 `GET /api/actuator/health`
- 指标：`GET /api/actuator/prometheus`（**需管理员登录**，勿把该端口暴露公网）
- 建议告警：磁盘使用率 > 80%、内存可用 < 300 MiB、`systemctl is-active creat-ai`、备份日志出现 ERROR。

### 17.5 可选：HTTPS

若后续启用 HTTPS：安全组/防火墙放行 443，`apt install certbot python3-certbot-nginx`，
`certbot --nginx -d <域名>` 自动签发并改写 80→443 跳转；随后把 `cors.allowed-origins`
和 `app.deploy.base-url` 改为 `https://`。本方案默认只跑 80，故 `server.servlet.session.cookie.secure`
保持关闭（否则 HTTP 下无法登录）。

---

## 18. 安全清单（上线前逐项确认）

- [ ] 仓库中不含真实密钥：`git grep -n 'CHANGE_ME'` 只应出现在 docs/deploy 示例里；确认没有把正式
      `application-prod.yml`、`backup.cnf`、Cookie 文件提交进 Git。
- [ ] `/opt/creat-ai/config/application-prod.yml` 权限 600、属主 `creat-ai`；`/etc/creat-ai/backup.cnf` 600 root。
- [ ] `user.default-password` 已改为强密码；首个管理员账号密码单独设置并妥善保存。
- [ ] 防火墙只开 22/80；`server.address=127.0.0.1`；MySQL/Redis 均 `bind 127.0.0.1` 且有密码。
- [ ] COS 密钥使用最小权限子账号，只授权目标 bucket。
- [ ] 备份目录 700 root；异地备份已规划。
- [ ] Ubuntu 安全更新：`apt install unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades`。
- [ ] 定期轮换：大模型 API Key、COS 密钥、MySQL/Redis 密码（改完记得 `systemctl restart creat-ai`）。
- [ ] 打包前检查本地 `src/main/resources/application-local.yml`：Spring Boot 会把它一起打进 jar
      （该文件已在 `.gitignore` 中，但本地存在就会被 `package` 收录）。生产用 `prod` profile 不会加载它，
      仍建议构建前临时移走/改用环境变量，避免开发库凭据随 jar 泄露。

---

## 19. 相关文档

- 本次改造的实施计划与验收记录：[`2c4g-retrofit-plan.md`](2c4g-retrofit-plan.md)
- 数据库迁移治理：[`docs/sql/README.md`](../sql/README.md)（生产增量在 `migrations/`；PG 脚本在 `postgresql/`，仅 `store=pg` 时使用）
- 本地开发启动方式：[根 README](../../README.md#快速开始)
- 多 Agent 流水线开关：[根 README](../../README.md#多-agent-流水线可选)
- 本目录工件：[`application-prod.yml.example`](application-prod.yml.example)、
  [`systemd/creat-ai.service`](systemd/creat-ai.service)、
  [`nginx/creat-ai.conf`](nginx/creat-ai.conf)、
  [`scripts/`](scripts/)
