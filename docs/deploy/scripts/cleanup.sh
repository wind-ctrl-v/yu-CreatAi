#!/usr/bin/env bash
# =============================================================================
# CreatAi 磁盘清理脚本（2 核 4G + 50G 盘单机版）
# -----------------------------------------------------------------------------
# 清理内容：
#   1) code_output 下超过 N 天的 node_modules（生成的 Vue 项目依赖，可重新 npm install）
#   2) 数据库 app 表中已不存在的“孤儿项目目录”（防止删除应用后残留占盘）
# 保留：默认 7 天内不动；孤儿目录与 node_modules 使用同一阈值
# 安装：上传到 /opt/creat-ai/scripts/cleanup.sh 并 chmod 755
# 依赖：mysql-client（读 app 表判断孤儿目录）、find、du
# 凭据：/etc/creat-ai/backup.cnf（与 backup.sh 共用，chmod 600 root）
# 定时：见 README “备份与清理” 一节的 /etc/cron.d/creat-ai
#
# 用法：
#   sudo /opt/creat-ai/scripts/cleanup.sh                 # 实际删除
#   sudo DRY_RUN=1 /opt/creat-ai/scripts/cleanup.sh       # 只打印将删除的内容（首次先演练）
#
# 安全约束：
#   - CODE_OUTPUT_ROOT 必须是绝对路径且不是 /，防止误删
#   - 只删除名字匹配 ^(html|multi_file|vue_project)_<数字>$ 的项目目录
#   - 数据库查询失败时跳过“孤儿目录”清理，仅清理 node_modules
# =============================================================================
set -euo pipefail

# ---------------------------- 可调变量（按需覆盖） ----------------------------
APP_HOME="${APP_HOME:-/opt/creat-ai}"                          # 应用根目录
CODE_OUTPUT_ROOT="${CODE_OUTPUT_ROOT:-${APP_HOME}/tmp/code_output}"  # 生成产物目录
DB_NAME="${DB_NAME:-yu_ai_code_mother}"                        # 业务库名
DB_CNF="${DB_CNF:-/etc/creat-ai/backup.cnf}"                   # 数据库凭据文件
NODE_MODULES_DAYS="${NODE_MODULES_DAYS:-7}"                    # node_modules 保留天数
ORPHAN_DAYS="${ORPHAN_DAYS:-7}"                                # 孤儿项目目录保留天数
DRY_RUN="${DRY_RUN:-0}"                                        # 1=只打印不删除
MYSQL_BIN="${MYSQL_BIN:-mysql}"                                # mysql 客户端路径

log()  { printf '[cleanup %s] %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '[cleanup %s][WARN] %s\n' "$(date '+%F %T')" "$*" >&2; }
die()  { printf '[cleanup %s][ERROR] %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

# ---------------------------- 0. 前置检查 -------------------------------------
[[ "${EUID}" -eq 0 ]] || die "请使用 root 或 sudo 运行"
[[ -d "${CODE_OUTPUT_ROOT}" ]] || die "生成产物目录不存在: ${CODE_OUTPUT_ROOT}"
[[ "${CODE_OUTPUT_ROOT}" == /* ]] || die "CODE_OUTPUT_ROOT 必须是绝对路径"
[[ "${CODE_OUTPUT_ROOT}" != "/" ]] || die "CODE_OUTPUT_ROOT 不能是 /"

# DRY_RUN=1 时只打印，不执行删除
do_rm() {
    if [[ "${DRY_RUN}" == "1" ]]; then
        log "[DRY_RUN] 将删除: $*"
    else
        rm -rf "$@"
    fi
}

log "生成产物目录: ${CODE_OUTPUT_ROOT}"
log "清理前目录占用: $(du -sh "${CODE_OUTPUT_ROOT}" 2>/dev/null | awk '{print $1}')"

# ---------------------------- 1. 清理过期 node_modules ------------------------
log "查找 ${NODE_MODULES_DAYS} 天以上未修改的 node_modules ..."
NM_COUNT=0
while IFS= read -r -d '' nm_dir; do
    # node_modules 位于某个项目目录下，保留项目目录本身的 mtime 判断会让“仍在使用的项目”被误清，
    # 因此这里只按 node_modules 目录自身 mtime（上次 npm install/build 时间）判断。
    do_rm "${nm_dir}"
    NM_COUNT=$((NM_COUNT + 1))
done < <(find "${CODE_OUTPUT_ROOT}" -type d -name node_modules -mtime "+${NODE_MODULES_DAYS}" -prune -print0)

log "共清理 node_modules: ${NM_COUNT} 个（DRY_RUN=${DRY_RUN}）"

# ---------------------------- 2. 清理孤儿项目目录 -----------------------------
# 项目目录命名规则：{codeGenType}_{appId}，例如 html_123456789 / vue_project_987654321
# 先从数据库取全部 app 记录（含逻辑删除），目录在记录里出现即保留，避免误删用户数据
if [[ -f "${DB_CNF}" ]] && command -v "${MYSQL_BIN}" >/dev/null 2>&1; then
    if EXPECTED_KEYS="$("${MYSQL_BIN}" --defaults-extra-file="${DB_CNF}" \
        -N -B -e "SELECT CONCAT(codeGenType, '_', id) FROM app" "${DB_NAME}" 2>/dev/null)"; then
        # 用关联数组做 O(1) 查找
        declare -A KEEP=()
        while IFS= read -r key; do
            [[ -n "${key}" ]] && KEEP["${key}"]=1
        done <<< "${EXPECTED_KEYS}"

        ORPHAN_COUNT=0
        while IFS= read -r -d '' project_dir; do
            name="$(basename "${project_dir}")"
            # 只处理符合项目目录命名规则的目录
            [[ "${name}" =~ ^(html|multi_file|vue_project)_[0-9]+$ ]] || continue
            # 数据库中仍有对应 app 记录：保留
            [[ -n "${KEEP[${name}]:-}" ]] && continue
            # 仅清理超过保留天数的孤儿目录，给“误删恢复”留窗口
            [[ -z "$(find "${project_dir}" -maxdepth 0 -mtime "+${ORPHAN_DAYS}" -print -quit)" ]] && continue
            do_rm "${project_dir}"
            ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
        done < <(find "${CODE_OUTPUT_ROOT}" -mindepth 1 -maxdepth 1 -type d -print0)

        log "共清理孤儿项目目录: ${ORPHAN_COUNT} 个（超过 ${ORPHAN_DAYS} 天且 app 表无记录）"
    else
        warn "查询 app 表失败，跳过孤儿项目目录清理（仅清理 node_modules）"
    fi
else
    warn "数据库凭据文件或 mysql 客户端不可用，跳过孤儿项目目录清理"
fi

# ---------------------------- 3. 结果汇总 -------------------------------------
log "清理后目录占用: $(du -sh "${CODE_OUTPUT_ROOT}" 2>/dev/null | awk '{print $1}')"
log "磁盘剩余: $(df -h "${CODE_OUTPUT_ROOT}" | awk 'NR==2 {print $4}')"
log "清理完成"
