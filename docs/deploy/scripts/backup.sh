#!/usr/bin/env bash
# =============================================================================
# CreatAi 每日备份脚本（2 核 4G 单机版）
# -----------------------------------------------------------------------------
# 内容：1) mysqldump 逻辑备份（gzip）  2) code_output 生成产物打包（gzip）
# 保留：默认 7 天，超期自动删除
# 安装：上传到 /opt/creat-ai/scripts/backup.sh 并 chmod 755
# 依赖：mysql-client（mysqldump）、tar、gzip、flock
# 凭据：/etc/creat-ai/backup.cnf（chmod 600 root），格式见 docs/deploy/README.md
# 定时：见 README “备份与清理” 一节的 /etc/cron.d/creat-ai
#
# 用法：
#   sudo /opt/creat-ai/scripts/backup.sh
#   sudo RETENTION_DAYS=14 /opt/creat-ai/scripts/backup.sh   # 临时改保留天数
#
# 注意：
#   - 备份目录默认 /opt/creat-ai/backups，权限 700，仅 root 可读（含用户数据）
#   - 脚本可重复执行；同一时间只允许一个实例（flock 防并发）
#   - code_output 打包会排除各项目 node_modules（体积大且可重建）
# =============================================================================
set -euo pipefail

# ---------------------------- 可调变量（按需覆盖） ----------------------------
APP_HOME="${APP_HOME:-/opt/creat-ai}"                          # 应用根目录
CODE_OUTPUT_ROOT="${CODE_OUTPUT_ROOT:-${APP_HOME}/tmp/code_output}"  # 生成产物目录
BACKUP_DIR="${BACKUP_DIR:-${APP_HOME}/backups}"                # 备份输出目录
DB_NAME="${DB_NAME:-yu_ai_code_mother}"                        # 业务库名
DB_CNF="${DB_CNF:-/etc/creat-ai/backup.cnf}"                   # 数据库凭据文件
RETENTION_DAYS="${RETENTION_DAYS:-7}"                          # 备份保留天数
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"                    # mysqldump 路径
MIN_FREE_MB="${MIN_FREE_MB:-5120}"                             # 磁盘剩余告警阈值(MB)

TS="$(date +%Y%m%d-%H%M%S)"
log()  { printf '[backup %s] %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '[backup %s][WARN] %s\n' "$(date '+%F %T')" "$*" >&2; }
die()  { printf '[backup %s][ERROR] %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

# ---------------------------- 0. 前置检查 -------------------------------------
[[ "${EUID}" -eq 0 ]] || die "请使用 root 或 sudo 运行"
[[ -f "${DB_CNF}" ]] || die "缺少数据库凭据文件 ${DB_CNF}（chmod 600，见 README）"
command -v "${MYSQLDUMP_BIN}" >/dev/null 2>&1 || die "未找到 mysqldump，请安装 mysql-client"
command -v tar >/dev/null 2>&1 || die "未找到 tar"
command -v flock >/dev/null 2>&1 || die "未找到 flock（util-linux）"

mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"
umask 077

# 防并发：上一个备份没结束时直接退出
exec 9>"${BACKUP_DIR}/.backup.lock"
flock -n 9 || die "已有备份进程在运行，本次跳过"

# 磁盘余量检查（只告警，不中断；备份文件默认很小）
FREE_MB="$(df -Pm "${BACKUP_DIR}" | awk 'NR==2 {print $4}')"
if [[ -n "${FREE_MB}" && "${FREE_MB}" -lt "${MIN_FREE_MB}" ]]; then
    warn "磁盘剩余 ${FREE_MB}MB，低于阈值 ${MIN_FREE_MB}MB，请及时清理"
fi

# ---------------------------- 1. MySQL 逻辑备份 -------------------------------
DB_DUMP="${BACKUP_DIR}/db-${DB_NAME}-${TS}.sql.gz"
log "开始备份数据库 ${DB_NAME} -> ${DB_DUMP}"
"${MYSQLDUMP_BIN}" \
    --defaults-extra-file="${DB_CNF}" \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    --default-character-set=utf8mb4 \
    "${DB_NAME}" | gzip -c > "${DB_DUMP}"

gzip -t "${DB_DUMP}" || die "数据库备份 gzip 校验失败: ${DB_DUMP}"
log "数据库备份完成: $(du -h "${DB_DUMP}" | awk '{print $1}')"

# ---------------------------- 2. code_output 打包 -----------------------------
if [[ -d "${CODE_OUTPUT_ROOT}" ]]; then
    CODE_ARCHIVE="${BACKUP_DIR}/code-output-${TS}.tar.gz"
    log "开始打包生成产物 ${CODE_OUTPUT_ROOT} -> ${CODE_ARCHIVE}"
    # tar 打包期间可能有生成任务写入，出现文件变化告警时视为“部分成功”，不中断整体备份
    if tar -czf "${CODE_ARCHIVE}" \
        -C "$(dirname "${CODE_OUTPUT_ROOT}")" \
        --exclude='code_output/*/node_modules' \
        --exclude='code_output/*/dist/.vite' \
        "$(basename "${CODE_OUTPUT_ROOT}")"; then
        log "生成产物打包完成: $(du -h "${CODE_ARCHIVE}" | awk '{print $1}')"
    else
        warn "生成产物打包未完全成功（可能有文件正在被写入），已保留部分归档: ${CODE_ARCHIVE}"
    fi
else
    warn "生成产物目录不存在，跳过: ${CODE_OUTPUT_ROOT}"
fi

# ---------------------------- 3. 清理过期备份 ---------------------------------
log "清理 ${RETENTION_DAYS} 天前的备份文件..."
DELETED=0
while IFS= read -r -d '' f; do
    rm -f "${f}"
    DELETED=$((DELETED + 1))
done < <(find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name 'db-*.sql.gz' -o -name 'code-output-*.tar.gz' \) \
    -mtime "+${RETENTION_DAYS}" -print0)
log "已删除 ${DELETED} 个过期备份文件"

# ---------------------------- 4. 结果汇总 -------------------------------------
log "当前备份目录占用: $(du -sh "${BACKUP_DIR}" | awk '{print $1}')"
log "磁盘剩余: $(df -h "${BACKUP_DIR}" | awk 'NR==2 {print $4}')"
log "备份完成"
