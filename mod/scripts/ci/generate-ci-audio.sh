#!/usr/bin/env bash
# 只生成 ciTest 的自制短促正弦波夹具；正式 Jar 不打包本目录中的任一文件。
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${root}/src/ciTest/resources/ci-audio"
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=0.25' -ac 1 -c:a libvorbis -q:a 1 -y "$out/blindbox-ci-tone.ogg"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=0.25' -ac 1 -c:a libmp3lame -b:a 64k -y "$out/blindbox-ci-tone.mp3"
head -c 64 "$out/blindbox-ci-tone.ogg" > "$out/blindbox-ci-broken.ogg"
sha256sum "$out"/*
