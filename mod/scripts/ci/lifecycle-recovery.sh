#!/usr/bin/env bash
set -euo pipefail

# 只允许 GitHub Hosted Runner 执行。此阶段验证同一世界在 save-all flush 后
# 遭 SIGKILL 并重启时，SavedData/canonical 导出链保持一致；玩家事务矩阵另行扩展。
FORGE_VERSION="47.4.22"
MC_VERSION="1.20.1"
SERVER_DIR="build/ci-lifecycle-server"
EVIDENCE_DIR="build/ci-lifecycle-evidence"
INSTALLER="forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
FORMAL_JAR="$(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*-all.jar' -print)"
CITEST_JAR="build/libs/blindboxchallenge-0.1.0-p1-citest.jar"

test -n "${GITHUB_ACTIONS:-}"
test -n "${FORMAL_JAR}" && test -f "${FORMAL_JAR}"
test -f "${CITEST_JAR}"
PRODUCT_SHA256="$(sha256sum "${FORMAL_JAR}" | awk '{print $1}')"
export BLINDBOX_PRODUCT_SHA256="${PRODUCT_SHA256}"

rm -rf "${SERVER_DIR}" "${EVIDENCE_DIR}"
mkdir -p "${SERVER_DIR}" "${EVIDENCE_DIR}"
curl --fail --location --retry 3 --connect-timeout 20 \
  -o "${SERVER_DIR}/${INSTALLER}" \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
(
  cd "${SERVER_DIR}"
  java -jar "${INSTALLER}" --installServer
  echo 'eula=true' > eula.txt
  mkdir -p mods
  cp "../../${FORMAL_JAR}" mods/
  cp "../../${CITEST_JAR}" mods/
)

SERVER_PID=""
SERVER_FD=""
start_server() {
  local log="$1"
  (
    cd "${SERVER_DIR}"
    rm -f server.stdin
    mkfifo server.stdin
    BLINDBOX_PRODUCT_SHA256="${PRODUCT_SHA256}" setsid ./run.sh nogui < server.stdin > "${log}" 2>&1 &
    echo $! > server.pid
  )
  SERVER_PID="$(cat "${SERVER_DIR}/server.pid")"
  exec {SERVER_FD}>"${SERVER_DIR}/server.stdin"
  for _ in $(seq 1 120); do
    if grep -q 'Done (' "${SERVER_DIR}/${log}"; then
      return 0
    fi
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      cat "${SERVER_DIR}/${log}"
      return 1
    fi
    sleep 1
  done
  cat "${SERVER_DIR}/${log}"
  return 1
}

wait_for_log() {
  local log="$1"
  local pattern="$2"
  for _ in $(seq 1 45); do
    grep -q "${pattern}" "${SERVER_DIR}/${log}" && return 0
    kill -0 "${SERVER_PID}" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

scan_log() {
  local log="$1"
  ! grep -Eq 'FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report|Cannot export canonical CI state' "${SERVER_DIR}/${log}"
}

# 第一次启动：写入唯一 NBT 的 PACK/OPEN 未完成事务夹具，导出后 flush 并强杀整个进程组。
# 夹具包含两个 bundle、两个事务和一个 OPEN reservation；重启前没有玩家登录，
# 因而可精确验证 SavedData 证据持久化，不把登录恢复行为混入本里程碑。
start_server first.log
printf 'blindboxcitest seed_recovery_fixture\nblindboxcitest seed_p4_pending_death_fixture\nblindboxcitest seed_p4_chicken_fixture\nblindboxcitest seed_p4_door_fixture\nblindboxcitest export\nsave-all flush\n' >&"${SERVER_FD}"
wait_for_log first.log 'BLINDBOX_CITEST_FIXTURE=seeded'
wait_for_log first.log 'BLINDBOX_CITEST_P4_PENDING_DEATH=seeded'
wait_for_log first.log 'BLINDBOX_CITEST_P4_CHICKEN_PENDING=seeded'
wait_for_log first.log 'BLINDBOX_CITEST_P4_DOOR_PENDING=seeded'
wait_for_log first.log 'BLINDBOX_CITEST_EXPORT='
wait_for_log first.log 'Saved the game'
test -s "${SERVER_DIR}/citest-results/canonical-state.json"
cp "${SERVER_DIR}/citest-results/canonical-state.json" "${EVIDENCE_DIR}/before-kill.json"
scan_log first.log
exec {SERVER_FD}>&-
kill -KILL -- "-${SERVER_PID}" 2>/dev/null || kill -KILL "${SERVER_PID}" 2>/dev/null || true
wait "${SERVER_PID}" 2>/dev/null || true

# 同一目录、同一世界重启，重新导出并优雅停止。
start_server second.log
printf 'blindboxcitest export\n' >&"${SERVER_FD}"
wait_for_log second.log 'BLINDBOX_CITEST_EXPORT='
test -s "${SERVER_DIR}/citest-results/canonical-state.json"
cp "${SERVER_DIR}/citest-results/canonical-state.json" "${EVIDENCE_DIR}/after-restart.json"
printf 'save-all flush\nstop\n' >&"${SERVER_FD}"
exec {SERVER_FD}>&-
if ! timeout 45s bash -c "while kill -0 ${SERVER_PID} 2>/dev/null; do sleep 1; done"; then
  cat "${SERVER_DIR}/second.log"
  kill -KILL -- "-${SERVER_PID}" 2>/dev/null || true
  exit 1
fi
scan_log second.log
grep -q 'Stopping server' "${SERVER_DIR}/second.log"

python3 - "${EVIDENCE_DIR}/before-kill.json" "${EVIDENCE_DIR}/after-restart.json" "${PRODUCT_SHA256}" <<'PY'
import json, pathlib, sys
before_path = pathlib.Path(sys.argv[1])
after_path = pathlib.Path(sys.argv[2])
expected_sha = sys.argv[3]
before = json.loads(before_path.read_text(encoding='utf-8'))
after = json.loads(after_path.read_text(encoding='utf-8'))
assert before.get('schema') == 1 == after.get('schema')
assert before.get('product_sha256') == expected_sha == after.get('product_sha256')
assert before.get('world') == after.get('world') == 'minecraft:overworld'
assert len(before.get('bundles', [])) == 2, 'fixture must contain exactly two bundles'
assert len(before.get('transactions', [])) == 2, 'fixture must contain PACK and OPEN transactions'
assert {entry.get('kind') for entry in before['transactions']} == {'PACK', 'OPEN'}
assert len(before.get('open_reservations', [])) == 1, 'fixture must contain one OPEN reservation'
serialized = json.dumps(before, ensure_ascii=False, sort_keys=True)
assert 'citest-pack-asset' in serialized and 'citest-open-asset' in serialized
assert len(before.get('death_note_entries', [])) == 1, 'fixture must contain one pending P4 death-note entry'
assert before['death_note_entries'][0]['due_tick'] > before['game_time'], 'P4 death-note fixture must stay pending before SIGKILL'
chickens_before = before.get('clockwork_chickens', [])
chickens_after = after.get('clockwork_chickens', [])
assert len(chickens_before) == len(chickens_after) == 1, 'fixture must contain exactly one pending P4 chicken'
chicken_before, chicken_after = chickens_before[0], chickens_after[0]
assert chicken_before['owner'] == '44444444-4444-4444-4444-444444444444'
assert chicken_before['id'] == chicken_after['id'], 'chicken UUID changed across recovery'
for key in ('owner', 'armed_game_time', 'explosion_power', 'position'):
    assert chicken_before[key] == chicken_after[key], f'chicken {key} changed across recovery'
# 强杀前后的 export 与实体 tick 不完全同相：仍必须以非默认 Fuse 验证恢复，且倒计时只能在
# 两次导出之间已流逝的服务器刻内前进。这样同时拒绝重置为默认值、丢失为较小值和异常跳变。
elapsed_chicken_ticks = int(after['game_time']) - int(before['game_time'])
assert chicken_before['fuse'] == 947, 'fixture must use the non-default recovery Fuse'
assert elapsed_chicken_ticks > 0, 'recovery must advance server time'
# export 命令可能与实体 tick 同刻或相邻刻：允许 0..elapsed 的真实倒计时变化，但绝不允许重置、丢失或跳变。
assert chicken_before['fuse'] - elapsed_chicken_ticks <= chicken_after['fuse'] <= chicken_before['fuse'], \
    'chicken Fuse was reset, lost, or changed beyond recovery ticks'
assert chicken_after['explosion_power'] == 8, 'default chicken power must persist as 8'
assert len(before.get('p4_recovery_doors', [])) == len(after.get('p4_recovery_doors', [])) == 2, 'fixture must contain two P4 door block entities'
assert before['p4_recovery_doors'] == after['p4_recovery_doors'], 'door UUID, partner link, or safety GlobalPos changed across recovery'
for key in ('players', 'bundles', 'transactions', 'open_reservations', 'death_note_entries'):
    assert before.get(key) == after.get(key), f'{key} changed across flushed SIGKILL recovery'
assert int(after.get('game_time', -1)) >= int(before.get('game_time', -1))
result = {
    'schema': 1,
    'status': 'success',
    'scope': 'flushed_pack_open_saveddata_asset_evidence',
    'product_sha256': expected_sha,
    'before': str(before_path),
    'after': str(after_path),
    'assertions': ['two unique-NBT bundles persisted', 'PACK and OPEN receipts persisted', 'OPEN reservation persisted', 'one pending death-note UUID/due-tick entry persisted', 'one pending clockwork chicken UUID/owner/armed tick/Fuse/power persisted', 'two door UUID/partner/safety GlobalPos records persisted', 'no duplicate or lost SavedData asset evidence'],
    'limitations': ['online-player inventory mutation and login recovery are covered by later client milestones'],
}
(pathlib.Path(after_path).parent / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
PY

cp "${SERVER_DIR}/first.log" "${EVIDENCE_DIR}/first.log"
cp "${SERVER_DIR}/second.log" "${EVIDENCE_DIR}/second.log"
printf '%s  %s\n' "${PRODUCT_SHA256}" "$(basename "${FORMAL_JAR}")" > "${EVIDENCE_DIR}/SHA256SUMS"
cat "${EVIDENCE_DIR}/result.json"
