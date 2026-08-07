#!/usr/bin/env bash
# 默认只生成 P4 的自制短促正弦波夹具；--verify-pressure 只校验已提交的 P5 原创压力 OGG。
# 正式 Jar 不打包本目录中的任一文件。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${root}/src/ciTest/resources/ci-audio"
pressure_file="${out}/blindbox-ci-cache-pressure.ogg"
# 5 份同路径、不同 query 的缓存条目必须可靠超过 64 MiB；同时仍受生产单次下载 16 MiB 上限约束。
pressure_min_bytes=$((13 * 1024 * 1024))
pressure_max_bytes=$((16 * 1024 * 1024))

if (( $# > 1 )); then
  printf '错误：只接受一个可选参数 --pressure。\n' >&2
  exit 2
fi

case "${1:-}" in
  '') ;;
  --verify-pressure) ;;
  --help|-h)
    printf '用法：%s [--verify-pressure]\n' "${0##*/}"
    exit 0
    ;;
  *)
    printf '错误：只接受 --verify-pressure。\n' >&2
    exit 2
    ;;
esac

mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=0.25' -ac 1 -c:a libvorbis -q:a 1 -y "$out/blindbox-ci-tone.ogg"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=0.25' -ac 1 -c:a libmp3lame -b:a 64k -y "$out/blindbox-ci-tone.mp3"
head -c 64 "$out/blindbox-ci-tone.ogg" > "$out/blindbox-ci-broken.ogg"

sha256sum "$out/blindbox-ci-broken.ogg" "$out/blindbox-ci-tone.mp3" "$out/blindbox-ci-tone.ogg"
if [[ "${1:-}" == "--verify-pressure" ]]; then
  test -f "$pressure_file"
  pressure_bytes="$(stat -c '%s' "$pressure_file")"
  if (( pressure_bytes <= pressure_min_bytes || pressure_bytes > pressure_max_bytes )); then
    printf '错误：P5 压力 OGG 大小 %s 不满足严格大于 13 MiB 且不大于 16 MiB。\n' "$pressure_bytes" >&2
    exit 1
  fi
  head -c 4 "$pressure_file" | grep -qx 'OggS'
  sha256sum "$pressure_file"
fi
