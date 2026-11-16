#!/usr/bin/env bash
# 手动安装日文模型：跳过应用内 247 MB 下载，把已经下载好的模型直接放进应用私有目录。
#
# 需要 root（Magisk / KernelSU 的 su 即可）。用法：
#   bash scripts/install-ja-model.sh /sdcard/Download/sherpa-onnx-...tar.bz2
#   bash scripts/install-ja-model.sh /path/to/解压后的目录
#
# 脚本会：校验 sha256 → 解压（如果是压缩包）→ 打语言标签补丁 →
# 拷到 /data/data/<包名>/files/asr/ja-multi/ → 改成应用 uid → 写 .complete 标记。
# 装完重开应用，设置 → 模型里会显示「已安装」。
set -euo pipefail

PKG="${PKG:-com.looplooptech.audioscript}"
MODEL_ID="ja-multi"

ARCHIVE_SHA="28044b67324f7f831689f0a3761473dd2ade380e93aa53f1dbcd479ef71c40d4"
TOKENS_SHA="784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab"
ENCODER_SHA="f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e"
JOINER_SHA="db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917"
DECODER_SHA="7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48"
DECODER_PATCHED_SHA="1d822929242004647cd9c41881599c52918254a8dbfd502933f9b20b61c962a2"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-}"

die() { echo "错误：$*" >&2; exit 1; }
sha() { sha256sum "$1" | awk '{print $1}'; }

# 用 root 跑脚本时 PATH 会变短，command -v 找不到 Termux 里的 python3。
find_python() {
  command -v python3 2>/dev/null && return 0
  for candidate in "${PREFIX:-}/bin/python3" /data/data/com.termux/files/usr/bin/python3 /usr/bin/python3; do
    [ -x "$candidate" ] && { echo "$candidate"; return 0; }
  done
  return 1
}
PYTHON="$(find_python || true)"

[ -n "$SRC" ] || die "用法：bash scripts/install-ja-model.sh <tar.bz2 或解压目录>"
[ "$(id -u)" -ne 0 ] ||
  die "请在 Termux 里以普通用户运行（不要加 su），脚本会自己调用 su 完成复制"

command -v su >/dev/null 2>&1 && su -c true 2>/dev/null ||
  die "需要 root：请确认 su 可用（Magisk / KernelSU），或在电脑上用 adb 安装"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/ja-model.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

# /sdcard 经常因为没给 Termux 存储权限而读不到；用 su 复制一份到临时目录再按普通用户处理。
if [ ! -r "$SRC" ]; then
  su -c "test -e '$SRC'" 2>/dev/null || die "找不到 $SRC"
  echo "==> Termux 读不到 $SRC，先用 su 复制一份"
  su -c "cp -r '$SRC' '$STAGE/src' && chown -R $(id -u):$(id -g) '$STAGE/src'" ||
    die "复制 $SRC 失败"
  su -c "restorecon -R '$STAGE/src'" 2>/dev/null || true
  SRC="$STAGE/src"
fi

[ -e "$SRC" ] || die "找不到 $SRC"
SRC="$(cd "$(dirname "$SRC")" && pwd)/$(basename "$SRC")"

if [ -d "$SRC" ]; then
  DIR="$SRC"
else
  echo "==> 校验压缩包 sha256"
  ACTUAL="$(sha "$SRC")"
  [ "$ACTUAL" = "$ARCHIVE_SHA" ] ||
    die "压缩包 sha256 不匹配（$ACTUAL），可能没下载完整"
  echo "==> 解压"
  tar -xjf "$SRC" -C "$STAGE"
  DIR="$STAGE"
fi

pick() {
  if [ -n "${2:-}" ]; then
    find "$DIR" -type f -name "$1" ! -name "$2" -print -quit
  else
    find "$DIR" -type f -name "$1" -print -quit
  fi
}

ENCODER="$(pick 'encoder*.int8.onnx')"
[ -n "$ENCODER" ] || ENCODER="$(pick 'encoder*.onnx')"
DECODER="$(pick 'decoder*.onnx' '*.int8.onnx')"
JOINER="$(pick 'joiner*.int8.onnx')"
[ -n "$JOINER" ] || JOINER="$(pick 'joiner*.onnx')"
TOKENS="$(pick 'tokens.txt')"

for f in "$ENCODER" "$DECODER" "$JOINER" "$TOKENS"; do
  [ -n "$f" ] && [ -f "$f" ] || die "目录里缺少 encoder / decoder / joiner / tokens.txt 中的某个文件"
done

echo "==> 校验模型文件"
[ "$(sha "$TOKENS")" = "$TOKENS_SHA" ] || die "tokens.txt sha256 不匹配"
[ "$(sha "$ENCODER")" = "$ENCODER_SHA" ] || die "encoder sha256 不匹配"
[ "$(sha "$JOINER")" = "$JOINER_SHA" ] || die "joiner sha256 不匹配"

DECODER_CURRENT="$(sha "$DECODER")"
if [ "$DECODER_CURRENT" = "$DECODER_PATCHED_SHA" ]; then
  echo "==> decoder 已带日文语言标签补丁"
elif [ "$DECODER_CURRENT" = "$DECODER_SHA" ]; then
  if [ -n "$PYTHON" ]; then
    echo "==> 写入日文语言标签（<blk> embedding → <JA>）"
    "$PYTHON" "$ROOT/scripts/patch-ja-decoder.py" "$DECODER"
    [ "$(sha "$DECODER")" = "$DECODER_PATCHED_SHA" ] || die "decoder 补丁后 sha256 不符"
  else
    echo "! 没有找到 python3，跳过语言标签补丁：模型能用，但首帧上下文不是训练时的 <JA>" >&2
  fi
else
  die "decoder sha256 不匹配（$DECODER_CURRENT）"
fi

DEST="/data/data/$PKG/files/asr/$MODEL_ID"
APP_UID="$(su -c "stat -c %u /data/data/$PKG")" || die "读不到 $PKG 的数据目录"
[ -n "$APP_UID" ] || die "取不到应用 uid"

echo "==> 停止应用，避免文件被占用"
su -c "am force-stop $PKG" >/dev/null 2>&1 || true

echo "==> 复制到 $DEST"
su -c "set -e
mkdir -p '$DEST'
cp -f '$ENCODER' '$DECODER' '$JOINER' '$TOKENS' '$DEST/'
chown -R $APP_UID:$APP_UID '$DEST'
chmod 700 '$DEST'
chmod 600 '$DEST'/*.onnx '$DEST'/tokens.txt
touch '$DEST/.complete'
chown $APP_UID:$APP_UID '$DEST/.complete'
restorecon -R '$DEST' 2>/dev/null || true"

echo "==> 完成"
su -c "ls -lh '$DEST'"
echo
echo "现在重开应用：设置 → 模型 应显示日文模型「已安装」；把源语言切到「日本語」即可，完全离线。"
