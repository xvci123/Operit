#!/bin/bash
# 在 CI 上从 npm 拉 DSHA 并打核心包（去界面/Office/浏览器/语音）
set -e
OUT="$1"
VER="${2:-0.2.0-rc.2}"
WORK="${3:-$PWD/build/dshpack}"
mkdir -p "$WORK"
if [ ! -d "$WORK/node_modules/@deepseek-ai/dsh" ]; then
  echo "### HERMES: npm install dsh@$VER"
  npm install --prefix "$WORK" --omit=dev --no-audit --no-fund "@deepseek-ai/dsh@$VER" >/dev/null 2>&1 || \
    npm install --prefix "$WORK" --omit=dev --no-audit --no-fund "@deepseek-ai/dsh@$VER"
fi
ROOT="$WORK/node_modules/@deepseek-ai/dsh"
test -d "$ROOT/lib" || { echo "### HERMES: ERROR dsh lib missing"; exit 1; }
mkdir -p "$(dirname "$OUT")"
STAGE="$WORK/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE/dsh"
cp -r "$ROOT/lib" "$STAGE/dsh/lib"
cp "$ROOT/package.json" "$STAGE/dsh/package.json"
mkdir -p "$STAGE/dsh/node_modules/@deepseek-ai"
for d in "$WORK"/node_modules/@deepseek-ai/*; do
  n=$(basename "$d")
  case "$n" in
    dsh-client*|dsh-web-frontend|libreoffice*|dsh-office-to-pdf|dsh-experimental-browser-use*|dsh-experimental-computer-use*|dsh-experimental-speech*) continue ;;
  esac
  cp -r "$d" "$STAGE/dsh/node_modules/@deepseek-ai/$n"
done
# 第三方依赖（如果有）
if [ -d "$WORK/node_modules" ]; then
  for d in "$WORK"/node_modules/*; do
    [ -d "$d" ] || continue
    n=$(basename "$d")
    [ "$n" = "@deepseek-ai" ] && continue
    cp -r "$d" "$STAGE/dsh/node_modules/$n"
  done
fi
cd "$STAGE"
zip -rq9 "$OUT" dsh
echo "### HERMES: dsh-core.zip = $(du -h "$OUT" | cut -f1) （stage $(du -sh dsh | cut -f1)）"
