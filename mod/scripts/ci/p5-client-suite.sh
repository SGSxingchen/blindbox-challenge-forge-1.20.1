#!/usr/bin/env bash
# P5 独立干净世界：single 仅装饰回归，dual 同时覆盖装饰与缓存压力。
set -euo pipefail

MODE="${1:-}"
case "${MODE}" in single|dual) ;; *) echo '用法：p5-client-suite.sh <single|dual>' >&2; exit 2 ;; esac
FORGE_VERSION="47.4.22"
MC_VERSION="1.20.1"
SERVER_DIR="build/ci-p5-${MODE}-server"
CLIENT_TEMPLATE="build/ci-p5-${MODE}-client-template"
EVIDENCE="build/ci-p5-${MODE}-evidence"
INSTALLER="forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
test -n "${GITHUB_ACTIONS:-}"
mapfile -t FORMAL_JARS < <(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*-all.jar' -print | sort)
mapfile -t CITEST_JARS < <(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*-citest.jar' -print | sort)
[ "${#FORMAL_JARS[@]}" -eq 1 ] && [ "${#CITEST_JARS[@]}" -eq 1 ]
FORMAL="${FORMAL_JARS[0]}"
CITEST="${CITEST_JARS[0]}"
if [ "${MODE}" = dual ]; then
  : "${GITHUB_REPOSITORY:?P5 双客户端缺少 GitHub 仓库名}"
  : "${GITHUB_SHA:?P5 双客户端缺少提交 SHA}"
  export BLINDBOX_CITEST_P5_AUDIO_BASE_URL="https://cdn.jsdelivr.net/gh/${GITHUB_REPOSITORY}@${GITHUB_SHA}/mod/src/ciTest/resources/ci-audio"
  jar tf "${CITEST}" | grep -qx 'ci-audio/blindbox-ci-cache-pressure.ogg'
fi

rm -rf "${SERVER_DIR}" "${CLIENT_TEMPLATE}" "${EVIDENCE}" "build/ci-p5-${MODE}-client-1" "build/ci-p5-${MODE}-client-2"
mkdir -p "${SERVER_DIR}" "${EVIDENCE}"
EVIDENCE_ABS="$(cd "${EVIDENCE}" && pwd)"
python scripts/ci/install-client.py "${CLIENT_TEMPLATE}"
mkdir -p "${CLIENT_TEMPLATE}/mods" "${CLIENT_TEMPLATE}/config"
cp "${FORMAL}" "${CITEST}" "${CLIENT_TEMPLATE}/mods/"
printf 'earlyWindowControl=false\n' > "${CLIENT_TEMPLATE}/config/fml.toml"
curl --fail --location --retry 3 --connect-timeout 20 -o "${SERVER_DIR}/${INSTALLER}" \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
(
  cd "${SERVER_DIR}"
  java -jar "${INSTALLER}" --installServer
  echo 'eula=true' > eula.txt
  printf 'online-mode=false\nallow-flight=false\nserver-port=25565\nview-distance=4\nsimulation-distance=4\nmax-players=2\n' > server.properties
  mkdir -p mods
  cp "../../${FORMAL}" "../../${CITEST}" mods/
  mkfifo server.stdin
  if [ "${MODE}" = dual ]; then
    BLINDBOX_CITEST_P5_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_P5_AUDIO_BASE_URL="${BLINDBOX_CITEST_P5_AUDIO_BASE_URL}" \
      setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
  else
    BLINDBOX_CITEST_P5_MARKER_DIR="${EVIDENCE_ABS}" setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
  fi
)
SERVER_PID="$(cat "${SERVER_DIR}/server.pid")"
exec 3>"${SERVER_DIR}/server.stdin"
CLIENT_PID=""
cleanup() {
  exec 3>&- 2>/dev/null || true
  kill -KILL -- "-${SERVER_PID}" 2>/dev/null || true
  [ -n "${CLIENT_PID}" ] && kill "${CLIENT_PID}" 2>/dev/null || true
}
trap cleanup EXIT
client_alive() {
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
}
wait_log() {
  local expected="$1" rounds="$2" failed="${3:-}"
  for _ in $(seq 1 "${rounds}"); do
    if [ -n "${failed}" ] && grep -q "${failed}" "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; return 1; fi
    grep -q "${expected}" "${SERVER_DIR}/server.log" && return 0
    client_alive
    sleep 1
  done
  grep -q "${expected}" "${SERVER_DIR}/server.log"
}
wait_file() {
  local file="$1" rounds="$2"
  for _ in $(seq 1 "${rounds}"); do
    [ -f "${file}" ] && return 0
    client_alive
    sleep 1
  done
  test -f "${file}"
}

for _ in $(seq 1 120); do
  grep -q 'Done (' "${SERVER_DIR}/server.log" && break
  kill -0 "${SERVER_PID}" 2>/dev/null || { cat "${SERVER_DIR}/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "${SERVER_DIR}/server.log"
python scripts/ci/run-p5-decor-clients.py "${CLIENT_TEMPLATE}" "${EVIDENCE}" "${MODE}" > "${EVIDENCE}/clients-runner.log" 2>&1 & CLIENT_PID=$!
wait_file "${EVIDENCE}/client-1-connected.marker" 600
if [ "${MODE}" = dual ]; then wait_file "${EVIDENCE}/client-2-connected.marker" 600; fi

if [ "${MODE}" = single ]; then
  START='BLINDBOX_CITEST_P5_DECOR_SINGLE_STARTED=success'
  VERIFIED='BLINDBOX_CITEST_P5_DECOR_SINGLE_CLIENT=success'
  CLEANED='BLINDBOX_CITEST_P5_DECOR_SINGLE_CLEANUP=success'
  START_COMMAND='start_p5_decor_single'
  VERIFY_COMMAND='verify_p5_decor_single'
  CLEAN_COMMAND='cleanup_p5_decor_single'
  DECOR_MARKERS=("${EVIDENCE}/client-1-p5-decor-single-observed.marker")
else
  START='BLINDBOX_CITEST_P5_DECOR_STARTED=success'
  VERIFIED='BLINDBOX_CITEST_P5_DECOR_CLIENTS=success'
  CLEANED='BLINDBOX_CITEST_P5_DECOR_CLEANUP=success'
  START_COMMAND='start_p5_decor_clients'
  VERIFY_COMMAND='verify_p5_decor_clients'
  CLEAN_COMMAND='cleanup_p5_decor_clients'
  DECOR_MARKERS=("${EVIDENCE}/client-1-p5-decor-observed.marker" "${EVIDENCE}/client-2-p5-decor-observed.marker")
fi
printf 'blindboxcitest %s\n' "${START_COMMAND}" >&3
wait_log "${START}" 90 'BLINDBOX_CITEST_P5_DECOR=failed'
touch "${EVIDENCE}/p5-decor-enabled.flag"
for round in 1 2 3; do
  wait_log "BLINDBOX_CITEST_P5_DECOR_ROUND_${round}_PLACE_READY=success" 90 'BLINDBOX_CITEST_P5_DECOR=failed'
  touch "${EVIDENCE}/p5-decor-place-${round}.flag"
  wait_log "BLINDBOX_CITEST_P5_DECOR_ROUND_${round}_BREAK_READY=success" 90 'BLINDBOX_CITEST_P5_DECOR=failed'
  touch "${EVIDENCE}/p5-decor-break-${round}.flag"
  wait_log "BLINDBOX_CITEST_P5_DECOR_ROUND_${round}_SERVER_DROP=success" 90 'BLINDBOX_CITEST_P5_DECOR=failed'
done
wait_log 'BLINDBOX_CITEST_P5_DECOR_SERVER=success' 120 'BLINDBOX_CITEST_P5_DECOR=failed'
for marker in "${DECOR_MARKERS[@]}"; do wait_file "${marker}" 120; done
printf 'blindboxcitest %s\n' "${VERIFY_COMMAND}" >&3
wait_log "${VERIFIED}" 60
printf 'blindboxcitest %s\n' "${CLEAN_COMMAND}" >&3
wait_log "${CLEANED}" 60
rm -f "${EVIDENCE}/p5-decor-enabled.flag" "${EVIDENCE}"/p5-decor-place-*.flag "${EVIDENCE}"/p5-decor-break-*.flag

if [ "${MODE}" = dual ]; then
  p5_audio_failure() {
    touch "${EVIDENCE}/p5-music-cache-diagnostic-request.flag"
    for _ in $(seq 1 20); do [ -f "${EVIDENCE}/client-1-p5-audio-postfailure.diagnostic" ] && break; sleep 1; done
    printf '%s\n' 'P5 独立世界缓存压力未达到当前严格阶段；以下仅为非成功诊断：' >&2
    for diagnostic in "${EVIDENCE}"/client-*-p5-music-cache-input-stalled.marker "${EVIDENCE}"/client-*-p5-audio-*.diagnostic; do
      [ -f "${diagnostic}" ] || continue
      printf '%s\n' "--- ${diagnostic} ---" >&2; cat "${diagnostic}" >&2
    done
    tail -n 240 "${SERVER_DIR}/server.log" >&2
    exit 1
  }
  printf 'blindboxcitest start_p5_music_cache_clients\n' >&3
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_STARTED=success' 60
  touch "${EVIDENCE}/p5-music-cache-enabled.flag"
  for round in $(seq 1 5); do
    wait_log "BLINDBOX_CITEST_P5_MUSIC_CACHE_FILL_${round}=success" 240 'BLINDBOX_CITEST_P5_MUSIC_CACHE=failed' || p5_audio_failure
    [ "${round}" -lt 5 ] && touch "${EVIDENCE}/p5-music-cache-fill-$((round + 1)).flag"
  done
  touch "${EVIDENCE}/p5-music-cache-eviction-reload.flag"
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_EVICTION_REDOWNLOAD=success' 240 'BLINDBOX_CITEST_P5_MUSIC_CACHE=failed' || p5_audio_failure
  touch "${EVIDENCE}/p5-music-cache-singleflight.flag"
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_SINGLE_FLIGHT=success' 240 'BLINDBOX_CITEST_P5_MUSIC_CACHE=failed' || p5_audio_failure
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_CORRUPTION=ready' 120 'BLINDBOX_CITEST_P5_MUSIC_CACHE=failed' || p5_audio_failure
  touch "${EVIDENCE}/p5-music-cache-corrupt-retry.flag"
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_CLIENTS=success' 240 'BLINDBOX_CITEST_P5_MUSIC_CACHE=failed' || p5_audio_failure
  printf 'blindboxcitest cleanup_p5_music_cache_clients\n' >&3
  wait_log 'BLINDBOX_CITEST_P5_MUSIC_CACHE_CLEANUP=success' 60
  rm -f "${EVIDENCE}"/p5-music-cache-*.flag
fi

touch "${EVIDENCE}/release-clients.marker"
wait "${CLIENT_PID}"
printf 'save-all flush\nstop\n' >&3
exec 3>&-
timeout 45s bash -c "while kill -0 ${SERVER_PID} 2>/dev/null; do sleep 1; done"
! grep -Eq 'FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report' "${SERVER_DIR}/server.log"
grep -q 'Saved the game' "${SERVER_DIR}/server.log"
grep -q 'Stopping server' "${SERVER_DIR}/server.log"
cp "${SERVER_DIR}/server.log" "${EVIDENCE}/server.log"
for index in 1 2; do
  directory="build/ci-p5-${MODE}-client-${index}"
  [ -d "${directory}/logs" ] && cp -r "${directory}/logs" "${EVIDENCE}/client-${index}-logs"
done
sha256sum "${FORMAL}" > "${EVIDENCE}/SHA256SUMS"
trap - EXIT
