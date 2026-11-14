#!/usr/bin/env bash
# =============================================================================
# 安装 headless Chrome + chromedriver（Chrome for Testing，npmmirror 镜像）
# -----------------------------------------------------------------------------
# 适用系统 : Ubuntu 24.04（22.04 也兼容，依赖包名会自动切换）
# 安装目标 : /opt/chrome/chrome-linux64/chrome
#            /opt/chrome/chromedriver-linux64/chromedriver
#            （与 application-prod.yml.example 中 app.screenshot.* 一致）
#
# 用法：
#   sudo CHROME_VERSION=131.0.6778.204 ./install-chrome.sh
#   sudo ./install-chrome.sh                 # 使用默认版本
#
# 版本选择：
#   1. 浏览器版本查看（镜像目录）：https://registry.npmmirror.com/-/binary/chrome-for-testing/
#   2. Chrome 与 chromedriver 必须严格同版本，脚本会做版本核对并告警。
#   3. 二选一来源：默认 npmmirror（国内快），失败后自动回退 Google 官方源。
#
# 说明：
#   - 截图需要中文字体，脚本会安装 fonts-noto-cjk，否则截图中文会变“豆腐块”。
#   - 安装目录权限设为 root:root 755，业务进程 creat-ai 只读可执行即可。
#   - 已在业务代码中传入 --no-sandbox / --disable-dev-shm-usage，无需额外配置 /dev/shm。
# =============================================================================
set -euo pipefail

# ---------------------------- 可调变量（按需覆盖） ----------------------------
CHROME_VERSION="${CHROME_VERSION:-131.0.6778.204}"   # Chrome for Testing 版本号
INSTALL_DIR="${INSTALL_DIR:-/opt/chrome}"            # 安装根目录
MIRROR_BASE="${MIRROR_BASE:-https://registry.npmmirror.com/-/binary/chrome-for-testing}"
FALLBACK_BASE="${FALLBACK_BASE:-https://storage.googleapis.com/chrome-for-testing-public}"

CHROME_BIN="${INSTALL_DIR}/chrome-linux64/chrome"
DRIVER_BIN="${INSTALL_DIR}/chromedriver-linux64/chromedriver"

TMP_DIR=""
log()  { printf '[install-chrome] %s\n' "$*"; }
warn() { printf '[install-chrome][WARN] %s\n' "$*" >&2; }
die()  { printf '[install-chrome][ERROR] %s\n' "$*" >&2; exit 1; }

cleanup() {
    if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then
        rm -rf "${TMP_DIR}"
    fi
}
trap cleanup EXIT

# ---------------------------- 0. 前置检查 -------------------------------------
[[ "${EUID}" -eq 0 ]] || die "请使用 root 或 sudo 运行本脚本"

if [[ ! -r /etc/os-release ]]; then
    die "无法读取 /etc/os-release，仅支持 Ubuntu/Debian"
fi
# shellcheck disable=SC1091
. /etc/os-release
case "${ID}" in
    ubuntu|debian) ;;
    *) die "当前系统为 ${ID}，仅支持 Ubuntu/Debian" ;;
esac
log "系统: ${PRETTY_NAME}"

command -v curl >/dev/null 2>&1 || {
    apt-get update -y && apt-get install -y curl
}

# ---------------------------- 1. 安装系统依赖 ---------------------------------
# headless Chrome 运行库；Ubuntu 24.04 的 t64 改名（time_t 迁移）需要区分版本
DEPS_COMMON=(
    libnss3 libnspr4 libdrm2 libxkbcommon0
    libxcomposite1 libxdamage1 libxfixes3 libxrandr2
    libgbm1 libpango-1.0-0 libcairo2
    libx11-6 libxcb1 libxext6 libxi6 libxtst6 libxshmfence1 libxss1
    fonts-liberation
)
DEPS_2404=(
    libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64
    libasound2t64 libglib2.0-0t64
)
DEPS_LEGACY=(
    libatk1.0-0 libatk-bridge2.0-0 libcups2
    libasound2 libglib2.0-0
)
# 注意：fonts-noto-cjk 体积较大（约 60MB），但中文截图必需，不要跳过
DEPS_EXTRA=(fonts-noto-cjk unzip)

log "更新 apt 索引并安装 Chrome 运行依赖（可能需要几分钟）..."
apt-get update -y
OS_MAJOR="${VERSION_ID%%.*}"
# Ubuntu 24.04+ / Debian 13+ 使用 time_t 迁移后的 t64 包名；更旧的发行版用传统包名
if { [[ "${ID}" == "ubuntu" && "${OS_MAJOR}" -ge 24 ]]; } || { [[ "${ID}" == "debian" && "${OS_MAJOR}" -ge 13 ]]; }; then
    log "检测到 ${ID} ${VERSION_ID}，使用 t64 依赖包名"
    apt-get install -y "${DEPS_COMMON[@]}" "${DEPS_2404[@]}" "${DEPS_EXTRA[@]}"
else
    log "检测到 ${ID} ${VERSION_ID}，使用传统依赖包名"
    apt-get install -y "${DEPS_COMMON[@]}" "${DEPS_LEGACY[@]}" "${DEPS_EXTRA[@]}"
fi

# ---------------------------- 2. 下载安装包 -----------------------------------
TMP_DIR="$(mktemp -d /tmp/install-chrome.XXXXXX)"
CHROME_ZIP="${TMP_DIR}/chrome-linux64.zip"
DRIVER_ZIP="${TMP_DIR}/chromedriver-linux64.zip"

# download <url> <目标文件> <说明>
download() {
    local url="$1" dest="$2" label="$3"
    log "下载 ${label}: ${url}"
    if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 \
        --progress-bar -o "${dest}" "${url}"; then
        warn "下载失败: ${url}"
        return 1
    fi
    [[ -s "${dest}" ]] || { warn "下载文件为空: ${dest}"; return 1; }
}

# 先 npmmirror，失败回退官方源
fetch_artifact() {
    local name="$1" dest="$2"
    if ! download "${MIRROR_BASE}/${CHROME_VERSION}/linux64/${name}" "${dest}" "${name} (npmmirror)"; then
        warn "npmmirror 获取 ${name} 失败，回退 Google 官方源"
        download "${FALLBACK_BASE}/${CHROME_VERSION}/linux64/${name}" "${dest}" "${name} (官方源)" \
            || die "Chrome for Testing ${CHROME_VERSION} 下载失败，请检查版本号与网络"
    fi
}

fetch_artifact "chrome-linux64.zip" "${CHROME_ZIP}"
fetch_artifact "chromedriver-linux64.zip" "${DRIVER_ZIP}"

# ---------------------------- 3. 解压安装 -------------------------------------
log "安装到 ${INSTALL_DIR} ..."
mkdir -p "${INSTALL_DIR}"
# 先解压到临时目录，再整体替换，避免升级时残留旧版本文件
EXTRACT_DIR="${TMP_DIR}/extract"
mkdir -p "${EXTRACT_DIR}"
unzip -q -o "${CHROME_ZIP}" -d "${EXTRACT_DIR}"
unzip -q -o "${DRIVER_ZIP}" -d "${EXTRACT_DIR}"

rm -rf "${INSTALL_DIR}/chrome-linux64" "${INSTALL_DIR}/chromedriver-linux64"
mv "${EXTRACT_DIR}/chrome-linux64" "${INSTALL_DIR}/"
mv "${EXTRACT_DIR}/chromedriver-linux64" "${INSTALL_DIR}/"

chmod 755 "${INSTALL_DIR}" "${INSTALL_DIR}/chrome-linux64" "${INSTALL_DIR}/chromedriver-linux64"
chmod 755 "${CHROME_BIN}" "${DRIVER_BIN}"
# 系统依赖库保持 root 只读即可，业务用户无需写权限
chown -R root:root "${INSTALL_DIR}"

# ---------------------------- 4. 版本核对 -------------------------------------
[[ -x "${CHROME_BIN}" ]] || die "Chrome 可执行文件不存在: ${CHROME_BIN}"
[[ -x "${DRIVER_BIN}" ]] || die "chromedriver 可执行文件不存在: ${DRIVER_BIN}"

# `--version` 在 root 下可能受沙箱提示影响，容错处理
CHROME_VER="$("${CHROME_BIN}" --version 2>/dev/null | awk '{print $3}' || true)"
DRIVER_VER="$("${DRIVER_BIN}" --version 2>/dev/null | awk '{print $2}' || true)"
log "Chrome       : ${CHROME_VER:-未知}"
log "chromedriver : ${DRIVER_VER:-未知}"

if [[ -n "${CHROME_VER}" && -n "${DRIVER_VER}" ]]; then
    if [[ "${CHROME_VER%%.*}" != "${DRIVER_VER%%.*}" ]]; then
        warn "Chrome 与 chromedriver 主版本不一致（${CHROME_VER} vs ${DRIVER_VER}），Selenium 会启动失败"
    fi
fi

# 中文字体自检（截图中文不出现方块）
if command -v fc-list >/dev/null 2>&1; then
    if [[ -z "$(fc-list :lang=zh 2>/dev/null | head -n 1)" ]]; then
        warn "未检测到中文字体，截图中文可能显示为方块，请检查 fonts-noto-cjk 是否安装成功"
    else
        log "中文字体自检通过"
    fi
fi

# ---------------------------- 5. 输出配置提示 ---------------------------------
cat <<EOF

[install-chrome] 安装完成，请在 /opt/creat-ai/config/application-prod.yml 中确认：

app:
  screenshot:
    chrome-binary: ${CHROME_BIN}
    driver-path: ${DRIVER_BIN}
    use-web-driver-manager: false

验证命令：
  ${CHROME_BIN} --headless=new --no-sandbox --disable-gpu --dump-dom about:blank
  ${DRIVER_BIN} --version
EOF
