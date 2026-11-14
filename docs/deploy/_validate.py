#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CreatAi 生产配置自检脚本（docs/deploy 辅助工具，无第三方依赖）

作用：
  校验 application-prod.yml.example（或指定的生产外置配置）里的配置键是否都能在仓库配置中
  找到对应项，防止部署后因键名拼写错误/版本升级导致配置静默失效、启动时才报错。

用法：
  # 1) 校验仓库里的示例文件（CI/提交前检查）
  python3 docs/deploy/_validate.py

  # 2) 校验线上配置（在仓库目录执行，把服务器配置拉回本地对比键名）
  scp root@<服务器IP>:/opt/creat-ai/config/application-prod.yml /tmp/application-prod.yml
  python3 docs/deploy/_validate.py /tmp/application-prod.yml

校验规则：
  - 示例/生产配置中的自定义键必须存在于 src/main/resources/application.yml 或
    application-local.yml（本地开发配置，用于对齐 cos.client 等键）；
  - spring.* / server.* / logging.* / management.* / langchain4j.* 视为 Spring Boot
    标准键，仓库未显式声明也允许（如 hikari、forward-headers-strategy）；
  - user.default-password 由代码 @Value 默认值兜底，单独放行；
  - 目标文件里若还有 CHANGE_ME，视为未完成配置（示例文件本身除外）。
退出码：0 = 通过；1 = 存在错误。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = ROOT / "docs/deploy/application-prod.yml.example"
BASE_CONFIG = ROOT / "src/main/resources/application.yml"
LOCAL_CONFIG = ROOT / "src/main/resources/application-local.yml"

STANDARD_PREFIXES = ("spring.", "server.", "logging.", "management.", "langchain4j.")
MANUAL_ALLOWED = {"user", "user.default-password"}


def parse_yaml_keys(path: Path):
    """极简 YAML 键路径解析（只关心 key 层级，足够校验本项目配置）。"""
    paths, duplicates, issues = set(), [], []
    stack = []  # [(indent, key)]
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "\t" in line:
            issues.append(f"{path}:{lineno} 含 Tab 字符（YAML 不允许）")
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent % 2 != 0:
            issues.append(f"{path}:{lineno} 缩进不是 2 的倍数: {line!r}")
        text = line.strip()
        if text.startswith("- "):  # 列表项及其子节点跳过（本项目配置不依赖列表键校验）
            while stack and stack[-1][0] >= indent:
                stack.pop()
            continue
        matched = re.match(r"^([^:#][^:]*):(\s*(.*))?$", text)
        if not matched:
            issues.append(f"{path}:{lineno} 无法解析: {line!r}")
            continue
        key = matched.group(1).strip()
        rest = (matched.group(3) or "").strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        parent = ".".join(k for _, k in stack)
        full = f"{parent}.{key}" if parent else key
        if full in paths:
            duplicates.append(f"{path}:{lineno} 重复键: {full}")
        paths.add(full)
        if rest == "":
            stack.append((indent, key))
    return paths, duplicates, issues


def main(argv):
    target = Path(argv[1]).resolve() if len(argv) > 1 else EXAMPLE
    check_placeholders = target != EXAMPLE
    errors = []

    if not target.is_file():
        print(f"[FAIL] 文件不存在: {target}")
        return 1

    base, _, base_issues = parse_yaml_keys(BASE_CONFIG)
    local, _, _ = parse_yaml_keys(LOCAL_CONFIG)
    target_keys, duplicates, issues = parse_yaml_keys(target)
    errors += base_issues + issues + duplicates

    known = base | local
    for key in sorted(target_keys):
        if key in known or key in MANUAL_ALLOWED:
            continue
        if key.startswith(STANDARD_PREFIXES):
            continue
        errors.append(f"配置键在仓库中不存在: {key}")

    if check_placeholders:
        text = target.read_text(encoding="utf-8")
        leftover = [
            f"{target}:{i} 仍含 CHANGE_ME 占位符"
            for i, line in enumerate(text.splitlines(), 1)
            if "CHANGE_ME" in line and not line.lstrip().startswith("#")
        ]
        errors += leftover

    print(f"[validate] 目标文件: {target}")
    print(f"[validate] 解析到配置键: {len(target_keys)} 个；对照仓库配置键: "
          f"{len(base)} + {len(local)} 个")
    if errors:
        print("[FAIL] 发现问题:")
        for err in errors:
            print("  -", err)
        return 1
    print("[PASS] 配置键与仓库一致，未发现结构问题")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
