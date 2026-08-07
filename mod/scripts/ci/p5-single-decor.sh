#!/usr/bin/env bash
# P5 单客户端专项：仅在 GitHub Hosted Runner 中启动一台专服和一台真实 Forge 客户端。
set -euo pipefail
FORGE_VERSION="47.4.22"
MC_VERSION="1.20.1"
SERVER_DIR="build/ci-p5-single-server"
CLIENT_TEMPLATE="build/ci-p5-single-client-template"
EVIDENCE="build/ci-p5-single-evidence"
INSTALLER="forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
FORMAL="$(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*-all.jar' -print)"
CITEST="build/libs/blindboxchallenge-0.1.0-p1-citest.jar"

test -n "${GITHUB_ACTIONS:-}"
test -n "${FORMAL}" && test -f "${FORMAL}" && test -f "${CITEST}"
rm -rf "${SERVER_DIR}" "${CLIENT_TEMPLATE}" "${EVIDENCE}" build/ci-single-p5-client
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
  BLINDBOX_CITEST_P5_MARKER_DIR="${EVIDENCE_ABS}" setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
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
for _ in $(seq 1 120); do
  grep -q 'Done (' "${SERVER_DIR}/server.log" && break
  kill -0 "${SERVER_PID}" 2>/dev/null || { cat "${SERVER_DIR}/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "${SERVER_DIR}/server.log"
python scripts/ci/run-p5-single-decor.py "${CLIENT_TEMPLATE}" "${EVIDENCE}" > "${EVIDENCE}/client-runner.log" 2>&1 & CLIENT_PID=$!
for _ in $(seq 1 600); do
  [ -f "${EVIDENCE}/client-connected.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-connected.marker"
printf 'blindboxcitest start_p5_decor_single\n' >&3
for _ in $(seq 1 90); do
  if grep -q 'BLINDBOX_CITEST_P5_DECOR=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_STARTED=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_STARTED=success' "${SERVER_DIR}/server.log"
touch "${EVIDENCE}/p5-decor-enabled.flag"
for ROUND in 1 2 3; do
  for _ in $(seq 1 90); do
    if grep -q 'BLINDBOX_CITEST_P5_DECOR=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
    grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_PLACE_READY=success" "${SERVER_DIR}/server.log" && break
    kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
    sleep 1
  done
  grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_PLACE_READY=success" "${SERVER_DIR}/server.log"
  touch "${EVIDENCE}/p5-decor-place-${ROUND}.flag"
  for _ in $(seq 1 90); do
    if grep -q 'BLINDBOX_CITEST_P5_DECOR=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
    grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_BREAK_READY=success" "${SERVER_DIR}/server.log" && break
    kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
    sleep 1
  done
  grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_BREAK_READY=success" "${SERVER_DIR}/server.log"
  touch "${EVIDENCE}/p5-decor-break-${ROUND}.flag"
  for _ in $(seq 1 90); do
    if grep -q 'BLINDBOX_CITEST_P5_DECOR=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
    grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_SERVER_DROP=success" "${SERVER_DIR}/server.log" && break
    kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
    sleep 1
  done
  grep -q "BLINDBOX_CITEST_P5_DECOR_ROUND_${ROUND}_SERVER_DROP=success" "${SERVER_DIR}/server.log"
done
for _ in $(seq 1 120); do
  if grep -q 'BLINDBOX_CITEST_P5_DECOR=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P5_DECOR_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P5_DECOR_SERVER=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 120); do
  [ -f "${EVIDENCE}/client-1-p5-decor-single-observed.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/client-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-p5-decor-single-observed.marker"
printf 'blindboxcitest verify_p5_decor_single\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_CLIENT=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_CLIENT=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p5_decor_single\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P5_DECOR_SINGLE_CLEANUP=success' "${SERVER_DIR}/server.log"
touch "${EVIDENCE}/release-client.marker"
wait "${CLIENT_PID}"
printf 'save-all flush\nstop\n' >&3
exec 3>&-
timeout 45s bash -c "while kill -0 ${SERVER_PID} 2>/dev/null; do sleep 1; done"
! grep -Eq 'FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report' "${SERVER_DIR}/server.log"
grep -q 'Saved the game' "${SERVER_DIR}/server.log"
grep -q 'Stopping server' "${SERVER_DIR}/server.log"
cp "${SERVER_DIR}/server.log" "${EVIDENCE}/server.log"
cp -r build/ci-single-p5-client/logs "${EVIDENCE}/client-logs"
sha256sum "${FORMAL}" > "${EVIDENCE}/SHA256SUMS"
trap - EXIT
