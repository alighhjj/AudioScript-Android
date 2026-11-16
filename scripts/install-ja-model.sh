#!/usr/bin/env bash
# 手动安装日文离线模型（ReazonSpeech 日本語 Zipformer）：跳过应用内下载，
# 直接放进应用私有目录，装完重开应用即可离线使用。
#
# 需要 root（Magisk / KernelSU 的 su 即可）。用法：
#   bash scripts/install-ja-model.sh                       # 从 hf-mirror 下载（约 169 MB）
#   bash scripts/install-ja-model.sh /sdcard/Download/模型目录
#   bash scripts/install-ja-model.sh sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2
#
# 脚本会：准备 4 个模型文件 → 逐个校验 sha256 →
# 拷到 /data/data/<包名>/files/asr/ja-reazon/ → 改成应用 uid → 写 .complete 标记。
set -euo pipefail

PKG="${PKG:-com.looplooptech.audioscript}"
MODEL_ID="ja-reazon"

BASE="https://hf-mirror.com/DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/resolve/main"

TOKENS_SHA="2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"
ENCODER_SHA="2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"
DECODER_SHA="58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124"
JOINER_SHA="d38a81d1191c9ed6de6a1719503692e07e3e973e2364adde0abae5eaaded1174"

ENCODER_NAME="encoder-epoch-99-avg-1.int8.onnx"
DECODER_NAME="decoder-epoch-99-avg-1.onnx"
JOINER_NAME="joiner-epoch-99-avg-1.onnx"
TOKENS_NAME="tokens.txt"

SRC="${1:-}"

die() { echo "错误：$*" >&2; exit 1; }
sha() { sha256sum "$1" | awk '{print $1}'; }

[ "$(id -u)" -ne 0 ] ||
  die "请在 Termux 里以普通用户运行（不要加 su），脚本会自己调用 su 完成复制"

command -v su >/dev/null 2>&1 && su -c true 2>/dev/null ||
  die "需要 root：请确认 su 可用（Magisk / KernelSU），或在电脑上用 adb 安装"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/ja-model.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

DIR="$STAGE/model"
mkdir -p "$DIR"

if [ -z "$SRC" ]; then
  echo "==> 从 hf-mirror 下载日文模型（约 169 MB）"
  for name in "$ENCODER_NAME" "$DECODER_NAME" "$JOINER_NAME" "$TOKENS_NAME"; do
    echo "    $name"
    curl -fL --retry 3 --retry-delay 2 -C - -o "$DIR/$name" "$BASE/$name"
  done
elif [ ! -r "$SRC" ]; then
  # /sdcard 经常因为没给 Termux 存储权限而读不到；用 su 复制一份再按普通用户处理。
  su -c "test -e '$SRC'" 2>/dev/null || die "找不到 $SRC"
  echo "==> Termux 读不到 $SRC，先用 su 复制一份"
  su -c "cp -r '$SRC' '$STAGE/src' && chown -R $(id -u):$(id -g) '$STAGE/src'" ||
    die "复制 $SRC 失败"
  su -c "restorecon -R '$STAGE/src'" 2>/dev/null || true
  SRC="$STAGE/src"
elif [ -d "$SRC" ]; then
  DIR="$SRC"
else
  echo "==> 解压 $SRC"
  tar -xjf "$SRC" -C "$STAGE"
  DIR="$STAGE"
fi

pick() {
  find "$DIR" -type f -name "$1" -print -quit
}

ENCODER="$(pick "$ENCODER_NAME")"
DECODER="$(pick "$DECODER_NAME")"
JOINER="$(pick "$JOINER_NAME")"
TOKENS="$(pick "$TOKENS_NAME")"

for f in "$ENCODER" "$DECODER" "$JOINER" "$TOKENS"; do
  [ -n "$f" ] && [ -f "$f" ] || die "缺少 encoder / decoder / joiner / tokens.txt 中的某个文件"
done

echo "==> 校验 sha256"
[ "$(sha "$TOKENS")" = "$TOKENS_SHA" ] || die "tokens.txt sha256 不匹配"
[ "$(sha "$ENCODER")" = "$ENCODER_SHA" ] || die "encoder sha256 不匹配"
[ "$(sha "$DECODER")" = "$DECODER_SHA" ] || die "decoder sha256 不匹配"
[ "$(sha "$JOINER")" = "$JOINER_SHA" ] || die "joiner sha256 不匹配"

DEST="/data/data/$PKG/files/asr/$MODEL_ID"
APP_UID="$(su -c "stat -c %u /data/data/$PKG")" || die "读不到 $PKG 的数据目录"
[ -n "$APP_UID" ] || die "取不到应用 uid"

echo "==> 停止应用，避免文件被占用"
su -c "am force-stop $PKG" >/dev/null 2>&1 || true

echo "==> 复制到 $DEST"
su -c "set -e
mkdir -p '$DEST'
cp -f '$ENCODER' '$DEST/$ENCODER_NAME'
cp -f '$DECODER' '$DEST/$DECODER_NAME'
cp -f '$JOINER' '$DEST/$JOINER_NAME'
cp -f '$TOKENS' '$DEST/$TOKENS_NAME'
chown -R $APP_UID:$APP_UID '$DEST'
chmod 700 '$DEST'
chmod 600 '$DEST'/*.onnx '$DEST'/$TOKENS_NAME
touch '$DEST/.complete'
chown $APP_UID:$APP_UID '$DEST/.complete'
restorecon -R '$DEST' 2>/dev/null || true"

echo "==> 完成"
su -c "ls -lh '$DEST'"
echo
echo "现在重开应用：设置 → 模型 应显示日文模型「已安装」；把源语言切到「日本語」即可，完全离线。"
if su -c "test -d /data/data/$PKG/files/asr/ja-multi" 2>/dev/null; then
  echo
  echo "提示：旧的 8 语种模型还占着空间，确认新版日文识别正常后可以删掉："
  echo "  su -c 'rm -rf /data/data/$PKG/files/asr/ja-multi'"
fi
