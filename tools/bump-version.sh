#!/usr/bin/env bash
# 同步修改 versionName 与 versionCode —— 发布工作流要求两者同时**增大**（不是「都变了」）。
# 比对规则见 tools/compare-version.sh。
#
# 用法：
#   tools/bump-version.sh 0.16.0        # 版本号自增，versionCode 自动 +1
#   tools/bump-version.sh 0.16.0 30     # 版本号与 versionCode 都指定
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle_file="$repo_dir/android/app/build.gradle.kts"

new_name="${1:-}"
[[ -n "$new_name" ]] || { echo "用法：tools/bump-version.sh <版本号> [版本code]" >&2; exit 2; }
[[ "$new_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.]+)?$ ]] \
  || { echo "版本号格式不对：${new_name}（期望 0.16.0 这样）" >&2; exit 2; }

current_code="$(HGTV_GRADLE_FILE="$gradle_file" "$repo_dir/tools/version.sh" code)"
if [[ -n "${2:-}" ]]; then
  new_code="$2"
  [[ "$new_code" =~ ^[0-9]+$ ]] || { echo "版本 code 必须是整数：${new_code}" >&2; exit 2; }
else
  new_code=$((current_code + 1))
fi

# -i.bak 再删备份：macOS（BSD sed）与 Windows Git Bash / Linux（GNU sed）都认这种写法；`-i ''` 只有 BSD 认。
sed -i.bak \
  -e "s/versionCode = System.getenv(\"HGTV_VERSION_CODE\")?\.toInt() ?: [0-9]*/versionCode = System.getenv(\"HGTV_VERSION_CODE\")?.toInt() ?: $new_code/" \
  -e "s/versionName = System.getenv(\"HGTV_VERSION_NAME\") ?: \"[^\"]*\"/versionName = System.getenv(\"HGTV_VERSION_NAME\") ?: \"$new_name\"/" \
  "$gradle_file"
rm -f "$gradle_file.bak"

echo "versionName $("$repo_dir/tools/version.sh" version) / versionCode $("$repo_dir/tools/version.sh" code)"
echo "产物名：$("$repo_dir/tools/version.sh" apk)"
echo
echo "提交后推送 main，Release 工作流会打正式包并创建 tag v$new_name"
