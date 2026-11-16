#!/usr/bin/env bash
# 可选：把日文离线模型直接打进 APK（assets/models/asr-ja-reazon）。
#
# 默认运行时下载即可；只有需要「离线安装、首启即用」的完整包时才执行本脚本。
# 模型来自 sherpa-onnx 官方发布的 ReazonSpeech 日本語 Zipformer，共约 169 MB。
# 用法： bash scripts/fetch-ja-model.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="https://hf-mirror.com/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main"

DEST="$ROOT/app/src/main/assets/models/asr-ja-reazon"

files=(
  "tokens.txt|2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"
  "encoder-epoch-99-avg-1.int8.onnx|2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"
  "decoder-epoch-99-avg-1.onnx|58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124"
  "joiner-epoch-99-avg-1.onnx|d38a81d1191c9ed6de6a1719503692e07e3e973e2364adde0abae5eaaded1174"
)

mkdir -p "$DEST"

for entry in "${files[@]}"; do
  name="${entry%%|*}"
  sha="${entry##*|}"
  target="$DEST/$name"

  if [ -f "$target" ] && [ "$(sha256sum "$target" | awk '{print $1}')" = "$sha" ]; then
    echo "==> 已有 $name"
    continue
  fi

  echo "==> 下载 $name"
  curl -fL --retry 3 --retry-delay 2 -C - -o "$target" "$BASE/$name"

  actual="$(sha256sum "$target" | awk '{print $1}')"
  if [ "$actual" != "$sha" ]; then
    echo "$name sha256 不匹配：$actual" >&2
    rm -f "$target"
    exit 1
  fi
done

echo "==> 完成：日文模型已放入 assets/models/asr-ja-reazon"
du -sh "$DEST"
echo "现在重新构建 APK，设置里的日文模型会显示「已内置」。"
