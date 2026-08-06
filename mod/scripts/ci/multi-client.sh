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
: "${GITHUB_REPOSITORY:?真实在线音频 CI 缺少 GitHub 仓库名}"
: "${GITHUB_SHA:?真实在线音频 CI 缺少提交 SHA}"
export BLINDBOX_CITEST_P4_AUDIO_BASE_URL="https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/${GITHUB_SHA}/mod/src/ciTest/resources/ci-audio"
test -f "${FORMAL}" && test -f "${CITEST}"
rm -rf "${SERVER_DIR}" "${CLIENT_TEMPLATE}" "${EVIDENCE}" build/ci-client-1 build/ci-client-2
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
 # 能力专项必须在服务端仍以 player.onGround() 判定的真实腾空中等待 C2S；只关闭原版
 # anti-fly 踢人，避免 Hosted Runner 追帧把网络往返误判为飞行。生产能力服务仍拒绝原版飞行。
 printf 'online-mode=false\nallow-flight=true\nserver-port=25565\nview-distance=4\nsimulation-distance=4\nmax-players=4\n' > server.properties
 mkdir -p mods
 cp "../../${FORMAL}" "../../${CITEST}" mods/
 mkfifo server.stdin
 BLINDBOX_CITEST_PILLOW_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_ABILITY_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_SCISSORS_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_PIG_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_P4_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_P4_AUDIO_BASE_URL="${BLINDBOX_CITEST_P4_AUDIO_BASE_URL}" \
   setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
)
SERVER_PID="$(cat "${SERVER_DIR}/server.pid")"
exec 3>"${SERVER_DIR}/server.stdin"
cleanup() {
  [ -f "${HOSTS_BACKUP:-}" ] && sudo cp "${HOSTS_BACKUP}" /etc/hosts 2>/dev/null || true
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
printf 'blindboxcitest run_multi_business\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_MULTI_BUSINESS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_MULTI_BUSINESS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest run_p2_business\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P2_BUSINESS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P2_BUSINESS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest run_p3_business\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_BUSINESS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_BUSINESS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest start_p3_pig_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_PIG_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PIG_STARTED=success' "${SERVER_DIR}/server.log"
# 两份 marker 均由真实客户端跟踪同一对父猪和由正式书本入口产生的幼猪后写入；
# 服务端会逐 UUID 与自身实体账本反查，随后显式清理临时夹具。
for _ in $(seq 1 120); do
  if grep -q 'BLINDBOX_CITEST_P3_PIG=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P3_PIG_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PIG_SERVER=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 120); do
  [ -f "${EVIDENCE}/client-1-p3-pig-observed.marker" ] && [ -f "${EVIDENCE}/client-2-p3-pig-observed.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-p3-pig-observed.marker"
test -f "${EVIDENCE}/client-2-p3-pig-observed.marker"
printf 'blindboxcitest verify_p3_pig_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_PIG_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PIG_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p3_pig_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_PIG_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PIG_CLEANUP=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest run_p3_pillow\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_PILLOW_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PILLOW_STARTED=success' "${SERVER_DIR}/server.log"
# 两个客户端先真实跟踪同一座位及两种投掷物；场景随后触发命中/超时，
# marker 仅在客户端收到 impacted、目标 UUID 与对应回收落物后由客户端自行写入。
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P3_PILLOW_SERVER=failed' "${SERVER_DIR}/server.log"; then
    cat "${SERVER_DIR}/server.log"
    exit 1
  fi
  grep -q 'BLINDBOX_CITEST_P3_PILLOW_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PILLOW_SERVER=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 120); do
  [ -f "${EVIDENCE}/client-1-pillow-observed.marker" ] && [ -f "${EVIDENCE}/client-2-pillow-observed.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-pillow-observed.marker"
test -f "${EVIDENCE}/client-2-pillow-observed.marker"
printf 'blindboxcitest verify_p3_pillow_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_PILLOW_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_PILLOW_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest run_p3_scissors\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_SCISSORS_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_SCISSORS_STARTED=success' "${SERVER_DIR}/server.log"
# marker 只能在两个真实客户端同步到同一投掷、目标、主人 UUID 和返航态后生成；
# 服务端还会在随后核验实际命中、完整 NBT 回收和满包掉落实体。
for _ in $(seq 1 240); do
  if grep -q 'BLINDBOX_CITEST_P3_SCISSORS_SERVER=failed' "${SERVER_DIR}/server.log"; then
    cat "${SERVER_DIR}/server.log"
    exit 1
  fi
  grep -q 'BLINDBOX_CITEST_P3_SCISSORS_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_SCISSORS_SERVER=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 120); do
  [ -f "${EVIDENCE}/client-1-scissors-observed.marker" ] && [ -f "${EVIDENCE}/client-2-scissors-observed.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-scissors-observed.marker"
test -f "${EVIDENCE}/client-2-scissors-observed.marker"
printf 'blindboxcitest verify_p3_scissors_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_SCISSORS_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_SCISSORS_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest start_p3_ability_clients\n' >&3
for _ in $(seq 1 90); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_SYNC_DISPATCHED=success' "${SERVER_DIR}/server.log" && break
  if grep -q 'BLINDBOX_CITEST_P3_ABILITY=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_SYNC_DISPATCHED=success' "${SERVER_DIR}/server.log"
# 两份 marker 均由真实客户端事件写入：Alice 先收 S2C 后点击实际 Space 映射并观察服务器速度，
# Bob 只在远离后重新开始追踪 Alice 时收到 true 快照。服务端随后逐字段反查。
for _ in $(seq 1 150); do
  [ -f "${EVIDENCE}/client-1-p3-ability-key.marker" ] && [ -f "${EVIDENCE}/client-2-p3-ability-tracking.marker" ] && break
  if grep -q 'BLINDBOX_CITEST_P3_ABILITY=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-p3-ability-key.marker"
test -f "${EVIDENCE}/client-2-p3-ability-tracking.marker"
printf 'blindboxcitest verify_p3_ability_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest start_p3_ability_clone\n' >&3
for _ in $(seq 1 90); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLONE=success' "${SERVER_DIR}/server.log" && break
  if grep -q 'BLINDBOX_CITEST_P3_ABILITY=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLONE=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest start_p3_ability_dimension\n' >&3
for _ in $(seq 1 90); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_DIMENSION=success' "${SERVER_DIR}/server.log" && break
  if grep -q 'BLINDBOX_CITEST_P3_ABILITY=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_DIMENSION=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 120); do
  [ -f "${EVIDENCE}/client-1-p3-ability-lifecycle.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-p3-ability-lifecycle.marker"
printf 'blindboxcitest verify_p3_ability_lifecycle_client\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_LIFECYCLE_CLIENT=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_LIFECYCLE_CLIENT=success' "${SERVER_DIR}/server.log"

# 所有上阶段 marker 和服务端 Capability 已完成交叉核验后，才写入 P4 跨维门夹具并 flush。
# 夹具只调用生产方块实体持久化关联；它不是成功 marker，杀后仍须由 Alice 真正按键走进门、
# Bob 在目标维观察同步结果。两个真实客户端必须在同世界新进程启动后自行重连。
printf 'blindboxcitest prepare_p4_door_recovery\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_PREPARED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_PREPARED=success' "${SERVER_DIR}/server.log"
# 只能接受本次命令之后新增的保存日志；长会话此前的自动保存不得伪造 flush 已完成。
FLUSH_LOG_OFFSET="$(wc -c < "${SERVER_DIR}/server.log")"
printf 'save-all flush\n' >&3
for _ in $(seq 1 60); do
  tail -c "+$((FLUSH_LOG_OFFSET + 1))" "${SERVER_DIR}/server.log" | grep -q 'Saved the game' && break
  sleep 1
done
tail -c "+$((FLUSH_LOG_OFFSET + 1))" "${SERVER_DIR}/server.log" | grep -q 'Saved the game'
cp "${SERVER_DIR}/server.log" "${EVIDENCE}/server-before-p3-ability-sigkill.log"
exec 3>&-
kill -KILL -- "-${SERVER_PID}" 2>/dev/null || kill -KILL "${SERVER_PID}" 2>/dev/null || true
wait "${SERVER_PID}" 2>/dev/null || true
(
 cd "${SERVER_DIR}"
 rm -f server.stdin
 mkfifo server.stdin
 BLINDBOX_CITEST_PILLOW_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_ABILITY_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_SCISSORS_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_PIG_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_P4_MARKER_DIR="${EVIDENCE_ABS}" BLINDBOX_CITEST_P4_AUDIO_BASE_URL="${BLINDBOX_CITEST_P4_AUDIO_BASE_URL}" \
   setsid ./run.sh nogui < server.stdin > server.log 2>&1 & echo $! > server.pid
)
SERVER_PID="$(cat "${SERVER_DIR}/server.pid")"
exec 3>"${SERVER_DIR}/server.stdin"
for _ in $(seq 1 120); do
  grep -q 'Done (' "${SERVER_DIR}/server.log" && break
  kill -0 "${SERVER_PID}" 2>/dev/null || { cat "${SERVER_DIR}/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "${SERVER_DIR}/server.log"
for _ in $(seq 1 210); do
  [ -f "${EVIDENCE}/client-1-sigkill-recovered.marker" ] && [ -f "${EVIDENCE}/client-2-sigkill-recovered.marker" ] \
    && [ -f "${EVIDENCE}/client-1-p3-ability-recovered.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-sigkill-recovered.marker"
test -f "${EVIDENCE}/client-2-sigkill-recovered.marker"
test -f "${EVIDENCE}/client-1-p3-ability-recovered.marker"
printf 'blindboxcitest verify_p3_ability_recovery\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_RECOVERY=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_RECOVERY=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p3_ability\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P3_ABILITY_CLEANUP=success' "${SERVER_DIR}/server.log"
# P4 任意门恢复专项复用同一次 flush → SIGKILL → 两客户端自动重连：服务端先反查杀前
# manifest 与双向 BE 关联，再由 Alice 的生产前进键进入门体；任何客户端/服务端事实不符都
# 只能超时或显式失败，脚本绝不创建这两份 marker。
printf 'blindboxcitest start_p4_door_recovery_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_STARTED=success' "${SERVER_DIR}/server.log"
# 这只是允许杀后客户端开始观察的阶段旗标，不是成功 marker；真正结果只能由两客户端事实写入
# 并由服务端读取后输出 CLIENTS=success。
touch "${EVIDENCE}/p4-door-recovery-enabled.flag"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLIENTS=success' "${SERVER_DIR}/server.log"
test -f "${EVIDENCE}/client-1-p4-door-arrived.marker"
test -f "${EVIDENCE}/client-2-p4-door-observed.marker"
printf 'blindboxcitest cleanup_p4_door_recovery_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLEANUP=success' "${SERVER_DIR}/server.log"
rm -f "${EVIDENCE}/p4-door-recovery-enabled.flag"
printf 'blindboxcitest prepare_reconnect\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_RECONNECT_PREPARED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_RECONNECT_PREPARED=success' "${SERVER_DIR}/server.log"
printf 'kick BlindBoxAlice blindbox-ci-reconnect\n' >&3
for _ in $(seq 1 180); do
  [ -f "${EVIDENCE}/client-1-reconnected.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-reconnected.marker"
printf 'blindboxcitest verify_reconnect\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_RECONNECT=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_RECONNECT=success' "${SERVER_DIR}/server.log"
# P4 负例只调用生产会话授权/文本过滤入口，验证换手、旧修订、伪造容器、控制字符与越限拒绝；
# 它不替代下方由真实客户端右键和 Screen 控件完成的成功链路。
printf 'blindboxcitest run_p4_text_negative\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_TEXT_NEGATIVE=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_TEXT_NEGATIVE=success' "${SERVER_DIR}/server.log"
# P4 首批必须从两次正式 Item#use 打开的真实客户端界面完成：Alice 先阅读信件，随后点击编辑
# 信件和死亡笔记的生产输入控件。marker 只记录真实 Screen/点击/服务端关闭；服务端再核验
# NBT 修订、持久排程以及 Bob 的到期死亡，绝不以直发 C2S 或预写文件代替 GUI。
printf 'blindboxcitest start_p4_text_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_TEXT_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_TEXT_STARTED=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_TEXT=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_TEXT_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_TEXT_SERVER=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 90); do
  [ -f "${EVIDENCE}/client-1-p4-text-observed.marker" ] && [ -f "${EVIDENCE}/client-2-p4-death-observed.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-1-p4-text-observed.marker"
test -f "${EVIDENCE}/client-2-p4-death-observed.marker"
printf 'blindboxcitest verify_p4_text_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_TEXT_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_TEXT_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p4_text_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_TEXT_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_TEXT_CLEANUP=success' "${SERVER_DIR}/server.log"
# P4 任意门核心链路由正式 Block#use 潜行配对和 entityInside 进入门体执行；探针还移除
# 目标安全落点确认玩家留在源侧。跨维客户端观察专项会在任意门批次完成时继续扩展。
printf 'blindboxcitest run_p4_door\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_DOOR=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_DOOR=success' "${SERVER_DIR}/server.log"
# 八音盒服务端负例仅调用生产菜单授权和 URL 规则：危险协议、认证信息、私网字面量、错误
# 容器/位置/实例/修订及重放均必须拒绝，且本路径不允许服务端下载任意 URL。
printf 'blindboxcitest run_p4_music_negative\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_NEGATIVE=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_NEGATIVE=success' "${SERVER_DIR}/server.log"
# 八音盒正向路径必须由 Alice 的真实方块右键、生产 MusicBoxScreen 与 C2S 配置开始；两客户端仅在
# SoundEngine 实际 read 到 PCM 后各自写 marker。服务器不会直调播放服务或伪造网络包。
printf 'blindboxcitest start_p4_music_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_STARTED=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_MUSIC=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_OGG_FIRST=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_OGG_FIRST=success' "${SERVER_DIR}/server.log"
# 这是测试阶段开关而非成功 marker：在初次真实 OGG 下载后把 raw 域名解析为回环地址。下一次
# 同 URL 播放只有先命中并复验本地 SHA 缓存才能到达 PCM read；缓存漏失会被生产公网策略拒绝。
HOSTS_BACKUP="${EVIDENCE}/hosts-before-p4-music"
sudo cp /etc/hosts "${HOSTS_BACKUP}"
printf '127.0.0.1 raw.githubusercontent.com\n::1 raw.githubusercontent.com\n' | sudo tee -a /etc/hosts >/dev/null
touch "${EVIDENCE}/p4-music-cache-enabled.flag"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_MUSIC=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_CACHE=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_CACHE=success' "${SERVER_DIR}/server.log"
sudo cp "${HOSTS_BACKUP}" /etc/hosts
rm -f "${HOSTS_BACKUP}"
HOSTS_BACKUP=""
touch "${EVIDENCE}/p4-music-network-restored.flag"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_MUSIC=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_MP3=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_MP3=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 180); do
  if grep -q 'BLINDBOX_CITEST_P4_MUSIC=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_FAILURE=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_FAILURE=success' "${SERVER_DIR}/server.log"
# 新登录的 Bob 只经生产 ConnectScreen 重连；客户端监听 80 tick 期间不得收到历史播放事件。
printf 'kick BlindBoxBob blindbox-p4-music-no-replay\n' >&3
for _ in $(seq 1 180); do
  [ -f "${EVIDENCE}/client-2-reconnected.marker" ] && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
test -f "${EVIDENCE}/client-2-reconnected.marker"
for _ in $(seq 1 120); do
  if grep -q 'BLINDBOX_CITEST_P4_MUSIC=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_CLIENTS=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_CLIENTS=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p4_music_clients\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_MUSIC_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_MUSIC_CLEANUP=success' "${SERVER_DIR}/server.log"
# P4 小黄鸡必须由正式 Item#use 武装；两个客户端真实跟踪同一实体/Fuse 后，服务端等待默认
# 1200 tick 倒计时结束并只接受一次以该实体为 exploder 的 TNT 语义爆炸。
printf 'blindboxcitest start_p4_chicken\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_CHICKEN_STARTED=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_CHICKEN_STARTED=success' "${SERVER_DIR}/server.log"
for _ in $(seq 1 100); do
  if grep -q 'BLINDBOX_CITEST_P4_CHICKEN=failed' "${SERVER_DIR}/server.log"; then cat "${SERVER_DIR}/server.log"; exit 1; fi
  grep -q 'BLINDBOX_CITEST_P4_CHICKEN_SERVER=success' "${SERVER_DIR}/server.log" && break
  kill -0 "${CLIENT_PID}" 2>/dev/null || { cat "${EVIDENCE}/clients-runner.log"; exit 1; }
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_CHICKEN_SERVER=success' "${SERVER_DIR}/server.log"
test -f "${EVIDENCE}/client-1-p4-chicken-observed.marker"
test -f "${EVIDENCE}/client-2-p4-chicken-observed.marker"
printf 'blindboxcitest verify_p4_chicken\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_CHICKEN=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_CHICKEN=success' "${SERVER_DIR}/server.log"
printf 'blindboxcitest cleanup_p4_chicken\n' >&3
for _ in $(seq 1 60); do
  grep -q 'BLINDBOX_CITEST_P4_CHICKEN_CLEANUP=success' "${SERVER_DIR}/server.log" && break
  sleep 1
done
grep -q 'BLINDBOX_CITEST_P4_CHICKEN_CLEANUP=success' "${SERVER_DIR}/server.log"
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
assert state.get('bundles') == [], state.get('bundles')
assert state.get('open_reservations') == [], state.get('open_reservations')
txs=state.get('transactions', [])
assert len(txs)==2, txs
assert {tx['kind'] for tx in txs}=={'PACK','OPEN'}, txs
assert all(tx['stage']=='COMMITTED' for tx in txs), txs
alice=next(p for p in players if p['name']=='BlindBoxAlice')
bob=next(p for p in players if p['name']=='BlindBoxBob')
marker='citest-last-bundle-prize'
assert sum(slot['stack']['count'] for slot in alice['main'] if marker in slot['stack']['canonical_nbt'])==1
assert sum(slot['stack']['count'] for slot in bob['main'] if marker in slot['stack']['canonical_nbt'])==0
assert sum(slot['stack']['count'] for slot in bob['main'] if 'blindboxchallenge:blind_box' in slot['stack']['canonical_nbt'])==1
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
