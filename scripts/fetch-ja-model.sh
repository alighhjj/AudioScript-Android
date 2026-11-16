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
  python3 - "$DEST" <<'PY'
import glob, os, sys

LANG_ID = 6
TENSOR = b"decoder.embedding.weight"

def varint(b, i):
    r = s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7F) << s
        if not c & 0x80:
            return r, i
        s += 7

def fields(b, start, end):
    i = start
    while i < end:
        key, i = varint(b, i)
        f, w = key >> 3, key & 7
        if w == 0:
            v, i = varint(b, i); yield f, w, v, i, i
        elif w == 2:
            n, i = varint(b, i); yield f, w, (i, i + n), i, i + n; i += n
        elif w == 5:
            yield f, w, None, i, i + 4; i += 4
        elif w == 1:
            yield f, w, None, i, i + 8; i += 8
        else:
            raise ValueError(w)

def patch(path):
    buf = bytearray(open(path, "rb").read())
    graph = next((v for f, w, v, s, e in fields(buf, 0, len(buf)) if f == 7), None)
    if graph is None:
        return False
    for f, w, v, s, e in fields(buf, graph[0], graph[1]):
        if f != 5:
            continue
        name, dims, raw = b"", [], None
        for f2, w2, v2, s2, e2 in fields(buf, v[0], v[1]):
            if f2 == 1:
                if w2 == 0:
                    dims.append(v2)
                else:
                    j = s2
                    while j < e2:
                        x, j = varint(buf, j); dims.append(x)
            elif f2 == 8:
                name = bytes(buf[s2:e2])
            elif f2 == 9:
                raw = (s2, e2)
        if name != TENSOR or raw is None or len(dims) != 2:
            continue
        rows, cols = dims
        row = cols * 4
        if raw[1] - raw[0] != rows * row:
            continue
        base = raw[0]
        buf[base:base + row] = buf[base + LANG_ID * row:base + (LANG_ID + 1) * row]
        open(path, "wb").write(buf)
        return True
    return False

ok = [p for p in glob.glob(os.path.join(sys.argv[1], "decoder*.onnx")) if patch(p)]
if not ok:
    sys.exit("没有找到 decoder.embedding.weight，语言标签补丁失败")
print("    patched:", ", ".join(os.path.basename(p) for p in ok))
PY
else
  echo "! 没有 python3，跳过语言标签补丁；日文识别质量会略低于预期" >&2
fi

echo "==> 完成：日文模型已放入 assets/models/asr-ja"
du -sh "$DEST"
echo "现在重新构建 APK，设置里的日文模型会显示「已内置」。"
