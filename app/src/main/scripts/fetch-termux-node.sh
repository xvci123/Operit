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
# 注意：libz.so.1 / libcares.so 等是符号链接，必须跟随（-L）复制出真实内容
find -L "$WORK/x" -path '*usr/lib*' -name 'lib*.so*' \( -type f -o -type l \) 2>/dev/null | while read -r f; do
  cp -L "$f" "$ASSETS_DST/lib/$(basename "$f")" 2>/dev/null || true
done
echo "### HERMES: libs -> $(ls "$ASSETS_DST/lib" | tr '\n' ' ')"
ls -l "$ASSETS_DST/lib" | head -20
echo "### HERMES: termux node ready"
