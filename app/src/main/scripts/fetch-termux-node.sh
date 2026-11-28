#!/bin/bash
# 取 Termux 的 Android 原生 Node（aarch64）及其 4 个依赖库
# 用法: fetch-termux-node.sh <jniLibs输出目录> <assets输出目录>
set -e
JNI_DST="$1"; ASSETS_DST="$2"
BASE="https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main"
WORK="$(mktemp -d)"
mkdir -p "$JNI_DST" "$ASSETS_DST/lib"

pick() {  # pick <pool-subdir> <pkg-prefix>
  local url="$BASE/$1/"
  curl -sL --max-time 30 "$url" | grep -oE "${2}[^\"/]*_aarch64\\.deb" | sort -u | tail -1
}

fetch() { # fetch <pool-subdir> <pkg-prefix>
  local sub="$1" pre="$2" name
  name=$(pick "$sub" "$pre")
  if [ -z "$name" ]; then echo "### HERMES: WARN no package for $pre"; return 0; fi
  echo "### HERMES: fetch $name"
  curl -sL -o "$WORK/$name" "$BASE/$sub/$name"
  dpkg-deb -x "$WORK/$name" "$WORK/x"
}

fetch n/nodejs nodejs
fetch z/zlib zlib
fetch c/c-ares c-ares
fetch s/sqlite sqlite
fetch l/libffi libffi

NODE="$(find "$WORK/x" -path '*bin/node' -type f | head -1)"
if [ -z "$NODE" ]; then echo "### HERMES: ERROR node binary not found"; exit 1; fi
cp "$NODE" "$JNI_DST/libnodebin.so"
chmod +x "$JNI_DST/libnodebin.so"
echo "### HERMES: node $(stat -c%s "$JNI_DST/libnodebin.so") bytes"

# 依赖库（保持原名，运行时复制到私有目录再设 LD_LIBRARY_PATH）
for f in $(find "$WORK/x" -path '*usr/lib*' -name 'lib*.so*' -type f 2>/dev/null); do
  cp "$f" "$ASSETS_DST/lib/$(basename "$f")"
done
ls -l "$ASSETS_DST/lib" | head -20
echo "### HERMES: termux node ready"
