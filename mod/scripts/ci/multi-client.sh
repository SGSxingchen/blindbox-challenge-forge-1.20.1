#!/usr/bin/env bash
set -euo pipefail
FORGE_VERSION="47.4.22"
MC_VERSION="1.20.1"
SERVER_DIR="build/ci-multi-server"
CLIENT_TEMPLATE="build/ci-multi-client-template"
EVIDENCE="build/ci-multi-evidence"
INSTALLER="forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
FORMAL="$(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*.jar' ! -name '*-sources.jar' ! -name '*-citest.jar' -print -quit)"
CITEST="build/libs/blindboxchallenge-0.1.0-p1-citest.jar"
test -n "${GITHUB_ACTIONS:-}"
test -f "${FORMAL}" && test -f "${CITEST}"
rm -rf "${SERVER_DIR}" "${CLIENT_TEMPLATE}" "${EVIDENCE}" build/ci-client-1 build/ci-client-2
mkdir -p "${SERVER_DIR}" "${EVIDENCE}"
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
 printf 'online-mode=false\nserver-port=25565\nview-distance=4\nsimulation-distance=4\nmax-players=4\n' > server.properties
 mkdir -p mods
 cp "../../${FORMAL}" "../../${CITEST}" mods/
 mkfifo server.stdin
 setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
)
SERVER_PID="$(cat "${SERVER_DIR}/server.pid")"
exec 3>"${SERVER_DIR}/server.stdin"
cleanup() {
  exec 3>&- 2>/dev/null || true
  kill -KILL -- "-${SERVER_PID}" 2>/dev/null || true
  [ -n "${CLIENT_PID:-}" ] && kill "${CLIENT_PID}" 2>/dev/null || true
}
trap cleanup EXIT
for _ in $(seq 1 120); do
  grep -q 'Done (' "${SERVER_DIR}/server.log" && break
  kill -0 "${SERVER_PID}" 2>/dev/null || { cat "${SERVER_DIR}/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "${SERVER_DIR}/server.log"
python scripts/ci/run-multi-client.py "${CLIENT_TEMPLATE}" "${EVIDENCE}" > "${EVIDENCE}/clients-runner.log" 2>&1 & CLIENT_PID=$!
for _ in $(seq 1 600); do
  [ -f "${EVIDENCE}/both-connected.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/both-connected.marker"
printf 'blindboxcitest export\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_EXPORT=' "${SERVER_DIR}/server.log" && break
  sleep 1
done
test -s "${SERVER_DIR}/citest-results/canonical-state.json"
cp "${SERVER_DIR}/citest-results/canonical-state.json" "${EVIDENCE}/both-online.json"
python3 - "${EVIDENCE}/both-online.json" <<'PY'
import json, pathlib, sys
state=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
players=state.get('players', [])
assert len(players)==2, players
names={p['name'] for p in players}
assert names=={'BlindBoxAlice','BlindBoxBob'}, names
uuids={p['uuid'] for p in players}
assert len(uuids)==2 and all(uuids), uuids
assert all(len(p.get('main', []))==36 for p in players)
PY
touch "${EVIDENCE}/release-clients.marker"
wait "${CLIENT_PID}"
printf 'list\nsave-all flush\nstop\n' >&3
exec 3>&-
timeout 45s bash -c "while kill -0 ${SERVER_PID} 2>/dev/null; do sleep 1; done"
! grep -Eq 'FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report' "${SERVER_DIR}/server.log"
grep -q 'There are 0 of a max of 4 players online' "${SERVER_DIR}/server.log"
grep -q 'Saved the game' "${SERVER_DIR}/server.log"
grep -q 'Stopping server' "${SERVER_DIR}/server.log"
cp "${SERVER_DIR}/server.log" "${EVIDENCE}/server.log"
cp -r build/ci-client-1/logs "${EVIDENCE}/client-1-logs"
cp -r build/ci-client-2/logs "${EVIDENCE}/client-2-logs"
sha256sum "${FORMAL}" > "${EVIDENCE}/SHA256SUMS"
trap - EXIT
