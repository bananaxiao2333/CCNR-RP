#!/usr/bin/env bash
# 音乐 WAV → OGG 转换（音乐格式已全面切换为 OGG，WAV 体积过大弃用）
# 用法: ./scripts/convert-music-to-ogg.sh [config/ccnr_rp/audio 目录]
# 说明: 自动探测可用编码器（oggenc → ffmpeg libvorbis → ffmpeg 原生 vorbis），把目录内所有 *.wav 转为同名 .ogg（q6），
#       转换成功后删除原 .wav；客户端按清单哈希自动下载 OGG。
#       若提示无可用编码器请安装（macOS: brew install vorbis-tools；Debian: apt install vorbis-tools）。
set -e

AUDIO_DIR="${1:-config/ccnr_rp/audio}"

detect_encoder() {
  if command -v oggenc >/dev/null 2>&1; then echo oggenc; return; fi
  if command -v ffmpeg >/dev/null 2>&1; then
    if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis; then echo ffmpeg-libvorbis; return; fi
    if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q vorbis; then echo ffmpeg-vorbis; return; fi
  fi
  echo none
}

convert_one() {
  local wav="$1" ogg="${wav%.wav}.ogg"
  echo "转换: $(basename "$wav") → $(basename "$ogg")"
  case "$ENC" in
    oggenc) oggenc -q 6 -o "$ogg" "$wav" >/dev/null 2>&1 ;;
    ffmpeg-libvorbis) ffmpeg -y -i "$wav" -c:a libvorbis -q:a 6 "$ogg" >/dev/null 2>&1 ;;
    ffmpeg-vorbis) ffmpeg -y -strict -2 -i "$wav" -c:a vorbis -q:a 6 "$ogg" >/dev/null 2>&1 ;;
  esac
  if [ -s "$ogg" ]; then rm -f "$wav"; else rm -f "$ogg"; echo "  FAILED: $wav"; return 1; fi
}

ENC=$(detect_encoder)
if [ "$ENC" = none ]; then
  echo "ERROR: 未找到 Vorbis 编码器（需 oggenc/libvorbis）。macOS: brew install vorbis-tools" >&2
  exit 1
fi
if [ ! -d "$AUDIO_DIR" ]; then
  echo "OK: $AUDIO_DIR 不存在，无需转换"
  exit 0
fi

converted=0; failed=0
for wav in "$AUDIO_DIR"/*.wav; do
  [ -e "$wav" ] || continue
  if convert_one "$wav"; then converted=$((converted+1)); else failed=$((failed+1)); fi
done
echo "完成：转换 $converted 个，失败 $failed 个（编码器: $ENC）"
[ "$failed" -eq 0 ]
