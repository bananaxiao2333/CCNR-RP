#!/usr/bin/env bash
# 音乐 WAV → OGG（Vorbis）转换（音乐格式已全面切换为 OGG，WAV 体积过大弃用）
# 用法: ./scripts/convert-music-to-ogg.sh [config/ccnr_rp/audio 目录]
# 说明: 优先 ffmpeg（-c:a libvorbis，产出必须是 Vorbis 而非 FLAC——客户端用 Vorbis 解码器），
#       缺失时回退 oggenc；转换成功后删除原 .wav，客户端按清单哈希自动下载 OGG。
set -e

AUDIO_DIR="${1:-config/ccnr_rp/audio}"

is_vorbis() { # 检查输出是否真为 Vorbis（ffmpeg 缺 libvorbis 时默认会产出 FLAC-in-OGG）
  [ -s "$1" ] || return 1
  if command -v ffprobe >/dev/null 2>&1; then
    ffprobe -hide_banner "$1" 2>/dev/null | grep -q "Audio: vorbis"
  else
    head -c 4 "$1" | grep -q "OggS"
  fi
}

convert_one() {
  local wav="$1" ogg="${wav%.wav}.ogg"
  echo "转换: $(basename "$wav") → $(basename "$ogg")"
  if command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis; then
    ffmpeg -y -i "$wav" -c:a libvorbis -q:a 6 "$ogg" >/dev/null 2>&1
    if is_vorbis "$ogg"; then rm -f "$wav"; echo "  OK (ffmpeg/libvorbis)"; return 0; fi
    rm -f "$ogg"
  fi
  if command -v oggenc >/dev/null 2>&1; then
    oggenc -q 6 -o "$ogg" "$wav" >/dev/null 2>&1
    if is_vorbis "$ogg"; then rm -f "$wav"; echo "  OK (oggenc)"; return 0; fi
    rm -f "$ogg"
  fi
  echo "  FAILED: $wav（需要 ffmpeg+libvorbis 或 oggenc；macOS: brew install vorbis-tools）"
  return 1
}

if [ ! -d "$AUDIO_DIR" ]; then
  echo "OK: $AUDIO_DIR 不存在，无需转换"
  exit 0
fi

converted=0; failed=0
for wav in "$AUDIO_DIR"/*.wav; do
  [ -e "$wav" ] || continue
  if convert_one "$wav"; then converted=$((converted+1)); else failed=$((failed+1)); fi
done
echo "完成：转换 $converted 个，失败 $failed 个"
[ "$failed" -eq 0 ]
