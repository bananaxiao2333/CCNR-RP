#!/usr/bin/env bash
# 下载 Corpse（henkelmax，Forge 1.20.1）官方 jar 到 libs/（编译期可选依赖，不提交到 git）
# 用法: ./scripts/fetch-corpse.sh
# 说明: 自动解析 Modrinth API 上最新的 forge 1.20.1 版本；文件名浮动，build.gradle 用 fileTree 匹配。
set -e
cd "$(dirname "$0")/.."
mkdir -p libs

if ls libs/corpse-forge-1.20.1-*.jar >/dev/null 2>&1; then
  echo "OK: libs/ 已存在 Corpse 依赖:"
  ls libs/corpse-forge-1.20.1-*.jar
  exit 0
fi

# Modrinth API 要求 User-Agent；loaders/game_versions 双重过滤
echo "Resolving latest forge-1.20.1 version from Modrinth API..."
JSON=$(curl -fsSL -H "User-Agent: ccnr-rp-fetch/1.0" "https://api.modrinth.com/v2/project/corpse/version?loaders=%5B%22forge%22%5D&game_versions=%5B%221.20.1%22%5D")

URL=$(echo "$JSON" | python3 -c "import json,sys; pairs=[(v,f) for v in json.load(sys.stdin) for f in v.get('files',[])]; m=[f['url'] for v,f in pairs if v.get('version_number','').startswith('forge-1.20.1-')]; print(m[0] if m else '')")

if [ -z "$URL" ]; then
  echo "ERROR: Modrinth API 未找到 forge-1.20.1 的 Corpse 版本" >&2
  exit 1
fi

echo "Downloading from: $URL"
curl -fsSL --retry 3 -o "libs/corpse-forge-1.20.1-1.0.23.jar" "$URL"
echo "OK: libs/corpse-forge-1.20.1-1.0.23.jar"
