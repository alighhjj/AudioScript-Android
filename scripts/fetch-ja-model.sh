#!/usr/bin/env bash
# 可选：把日文流式模型直接打进 APK（assets/models/asr-ja）。
#
# 默认运行时下载即可；只有需要「离线安装、首启即用」的完整包时才执行本脚本。
# 模型约 247 MB（tar.bz2），解压后只保留 int8 encoder/joiner + fp32 decoder + tokens。
# 用法： bash scripts/fetch-ja-model.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
SHA="28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4"
GH="${GH_PROXY:-}"

DEST="$ROOT/app/src/main/assets/models/asr-ja"
CACHE_DIR="$ROOT/.cache"
CACHE="$CACHE_DIR/ja-model.tar.bz2"
TMP="$CACHE_DIR/ja-model"

mkdir -p "$DEST" "$CACHE_DIR"

if [ ! -f "$CACHE" ]; then
  echo "==> 下载日文模型（约 247 MB）"
  curl -fL --retry 3 -o "$CACHE" "${GH}${URL}"
fi

echo "==> 校验 sha256"
ACTUAL="$(sha256sum "$CACHE" | awk '{print $1}')"
if [ "$ACTUAL" != "$SHA" ]; then
  echo "sha256 不匹配：$ACTUAL" >&2
  echo "删除 $CACHE 后重试" >&2
  exit 1
fi

echo "==> 解压并挑选推理所需的文件"
rm -rf "$TMP"
mkdir -p "$TMP"
tar -xjf "$CACHE" -C "$TMP"

pick() {
  local pattern="$1"
  local exclude="${2:-}"
  if [ -n "$exclude" ]; then
    find "$TMP" -type f -name "$pattern" ! -name "$exclude" -print -quit
  else
    find "$TMP" -type f -name "$pattern" -print -quit
  fi
}

ENCODER="$(pick 'encoder*.int8.onnx')"
[ -n "$ENCODER" ] || ENCODER="$(pick 'encoder*.onnx')"
DECODER="$(pick 'decoder*.onnx' '*.int8.onnx')"
[ -n "$DECODER" ] || DECODER="$(pick 'decoder*.onnx')"
JOINER="$(pick 'joiner*.int8.onnx')"
[ -n "$JOINER" ] || JOINER="$(pick 'joiner*.onnx')"
TOKENS="$(pick 'tokens.txt')"

for f in "$ENCODER" "$DECODER" "$JOINER" "$TOKENS"; do
  if [ -z "$f" ] || [ ! -f "$f" ]; then
    echo "模型包内缺少必要文件" >&2
    exit 1
  fi
done

rm -f "$DEST"/*.onnx "$DEST/tokens.txt"
cp -f "$ENCODER" "$DEST/"
cp -f "$DECODER" "$DEST/"
cp -f "$JOINER" "$DEST/"
cp -f "$TOKENS" "$DEST/"
rm -rf "$TMP"

# 多语种模型需要用 <JA> 替换 <SOS> 作为首帧上下文，而 sherpa-onnx 只会用 <blk> 起头。
# 与运行时的 core/JaModelPatch.kt 做同一件事：把 embedding 第 0 行换成第 6 行（<JA>）。
if command -v python3 >/dev/null 2>&1; then
  echo "==> 写入日文语言标签（<blk> embedding → <JA>）"
  for decoder in "$DEST"/decoder*.onnx; do
    python3 "$ROOT/scripts/patch-ja-decoder.py" "$decoder"
  done
else
  echo "! 没有 python3，跳过语言标签补丁；日文识别质量会略低于预期" >&2
fi

echo "==> 完成：日文模型已放入 assets/models/asr-ja"
du -sh "$DEST"
echo "现在重新构建 APK，设置里的日文模型会显示「已内置」。"
