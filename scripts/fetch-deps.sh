#!/usr/bin/env bash
# 拉取构建所需的两个大二进制依赖（不入源码包）：
#   1) sherpa-onnx Android AAR（含 arm64-v8a / armeabi-v7a 的 JNI 库）
#   2) 中英双语流式 Zipformer 模型（int8 encoder/joiner + fp32 decoder）
# 用法： bash scripts/fetch-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR_VER="1.13.0"
MODEL="sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16"
# 国内网络可换成镜像前缀，例如 https://gh-proxy.com/
GH="${GH_PROXY:-}"

mkdir -p "$ROOT/app/libs" "$ROOT/app/src/main/assets/models/asr" "$ROOT/.cache"

echo "==> 下载 sherpa-onnx-${AAR_VER}.aar"
curl -fL --retry 3 -o "$ROOT/app/libs/sherpa-onnx-${AAR_VER}.aar" \
  "${GH}https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VER}/sherpa-onnx-${AAR_VER}.aar"

echo "==> 下载识别模型（约 40 MB）"
curl -fL --retry 3 -o "$ROOT/.cache/asr-model.tar.bz2" \
  "${GH}https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"

echo "==> 解出所需的 4 个文件"
tar -xjf "$ROOT/.cache/asr-model.tar.bz2" -C "$ROOT/app/src/main/assets/models/asr" --strip-components=1 \
  "${MODEL}/encoder-epoch-99-avg-1.int8.onnx" \
  "${MODEL}/decoder-epoch-99-avg-1.onnx" \
  "${MODEL}/joiner-epoch-99-avg-1.int8.onnx" \
  "${MODEL}/tokens.txt"

rm -rf "$ROOT/.cache"
echo "==> 完成，可以执行 ./gradlew :app:assembleDebug"
