#!/usr/bin/env bash
set -euo pipefail

# 本脚本只由 GitHub Hosted Runner 执行；本机开发环境不得运行 Gradle/Forge/Minecraft。
FORGE_VERSION="47.4.22"
MC_VERSION="1.20.1"
SERVER_DIR="build/ci-forge-server"
INSTALLER="forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
JAR="$(find build/libs -maxdepth 1 -type f -name 'blindboxchallenge-*-all.jar' -print)"

test -n "${JAR}" && test -f "${JAR}"
[[ "${JAR}" != *-citest.jar ]]
mkdir -p "${SERVER_DIR}"
curl --fail --location --retry 3 --connect-timeout 20 \
  -o "${SERVER_DIR}/${INSTALLER}" \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/${MC_VERSION}-${FORGE_VERSION}/forge-${MC_VERSION}-${FORGE_VERSION}-installer.jar"
(
  cd "${SERVER_DIR}"
  java -jar "${INSTALLER}" --installServer
  echo 'eula=true' > eula.txt
  mkdir -p mods
  cp "../../${JAR}" mods/
  # Forge 安装器生成 run.sh；日志中的 Done 是真正启动而非仅安装成功。
  mkfifo server.stdin
  timeout 120s ./run.sh nogui < server.stdin > server.log 2>&1 &
  pid=$!
  exec 3>server.stdin
  for _ in $(seq 1 90); do
    if grep -q 'Done (' server.log; then
      printf 'list\nsave-all flush\nstop\n' >&3
      exec 3>&-
      if ! timeout 30s bash -c "while kill -0 ${pid} 2>/dev/null; do sleep 1; done"; then
        cat server.log
        kill "${pid}" || true
        wait "${pid}" || true
        exit 1
      fi
      ! grep -Eq 'FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report' server.log
      grep -q 'There are ' server.log
      grep -q 'Saved the game' server.log
      grep -q 'Stopping server' server.log
      exit 0
    fi
    if ! kill -0 "${pid}" 2>/dev/null; then
      cat server.log
      exit 1
    fi
    sleep 1
  done
  cat server.log
  exec 3>&- || true
  kill "${pid}" || true
  wait "${pid}" || true
  exit 1
)
