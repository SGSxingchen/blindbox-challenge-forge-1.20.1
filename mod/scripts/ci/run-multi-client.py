#!/usr/bin/env python3
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

import minecraft_launcher_lib

VERSION_ID = "1.20.1-forge-47.4.22"
PLAYERS = (
    ("BlindBoxAlice", "11111111111131118111111111111111"),
    ("BlindBoxBob", "22222222222232228222222222222222"),
)
FATAL = re.compile(r"\bFATAL\b|ModLoadingException|Failed to load mods?|Mixin apply failed|NoClassDefFoundError:|Crash report saved to", re.I)


def stop(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=15)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def launch(directory: Path, username: str, uuid: str, marker: Path, pillow_marker: Path, scissors_marker: Path, pig_marker: Path, release: Path, reconnect_marker: Path,
           ability_role: str, ability_key_marker: Path, ability_tracking_marker: Path, ability_lifecycle_marker: Path,
           ability_recovery_marker: Path, server_recovery_marker: Path):
    options = minecraft_launcher_lib.utils.generate_test_options()
    jvm_arguments = ["-Xms768M", "-Xmx2G", "-Dblindbox.ci.multiplayerSmoke=true",
                     "-Dblindbox.ci.serverAddress=127.0.0.1:25565",
                     f"-Dblindbox.ci.clientMarker={marker}", f"-Dblindbox.ci.clientRelease={release}",
                     f"-Dblindbox.ci.pillowMarker={pillow_marker}",
                     f"-Dblindbox.ci.scissorsMarker={scissors_marker}",
                     f"-Dblindbox.ci.pigMarker={pig_marker}",
                     f"-Dblindbox.ci.reconnectMarker={reconnect_marker}",
                     "-Dblindbox.ci.serverRecovery=true",
                     f"-Dblindbox.ci.serverRecoveryMarker={server_recovery_marker}",
                     f"-Dblindbox.ci.abilityRole={ability_role}"]
    if ability_key_marker is not None:
        jvm_arguments.append(f"-Dblindbox.ci.abilityKeyMarker={ability_key_marker}")
    if ability_tracking_marker is not None:
        jvm_arguments.append(f"-Dblindbox.ci.abilityTrackingMarker={ability_tracking_marker}")
    if ability_lifecycle_marker is not None:
        jvm_arguments.append(f"-Dblindbox.ci.abilityLifecycleMarker={ability_lifecycle_marker}")
    if ability_recovery_marker is not None:
        jvm_arguments.append(f"-Dblindbox.ci.abilityRecoveryMarker={ability_recovery_marker}")
    options.update({
        "username": username, "uuid": uuid, "token": "blindbox-ci-offline-token",
        "executablePath": shutil.which("java") or "java", "gameDirectory": str(directory),
        "disableMultiplayer": False,
        "jvmArguments": jvm_arguments + (["-Dblindbox.ci.reconnect=true"] if username == "BlindBoxAlice" else []),
    })
    command = ["xvfb-run", "-a", "-s", "-screen 0 1024x576x24 +extension GLX",
               *minecraft_launcher_lib.command.get_minecraft_command(VERSION_ID, str(directory), options)]
    env = os.environ.copy()
    env.update({"LIBGL_ALWAYS_SOFTWARE": "1", "MESA_LOADER_DRIVER_OVERRIDE": "llvmpipe", "ALSOFT_DRIVERS": "null"})
    console = directory / "logs" / "ci-multiplayer-console.log"
    console.parent.mkdir(parents=True, exist_ok=True)
    output = console.open("wb", buffering=0)
    process = subprocess.Popen(command, cwd=directory, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    return process, output, console


def wait_for_real_connection(entry):
    """等待一个真实客户端完成稳定联机，再启动下一名客户端。

    Forge 1.20.1 在 Hosted Runner 上偶尔会在两个离线测试身份同一瞬间交换登录包时抛出
    ``ConcurrentModificationException``；这发生在任何业务场景开始前。串行握手不改变“两个
    独立客户端同服”的验收条件，只避免这个与模组功能无关的登录竞态。每名客户端仍必须由
    自身真实网络事件写出联机标志，异常或超时仍会失败，绝不预写标志。
    """
    process, _, console, marker, _, _, _, username, _, directory, _ = entry
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        text = console.read_text(encoding="utf-8", errors="replace") if console.is_file() else ""
        latest = directory / "logs" / "latest.log"
        if latest.is_file():
            text += latest.read_text(encoding="utf-8", errors="replace")
        if FATAL.search(text) or any((directory / "crash-reports").glob("*")):
            raise RuntimeError(f"{username} 日志或崩溃报告异常")
        if process.poll() is not None and not marker.is_file():
            raise RuntimeError(f"{username} 在联机标志前退出：{process.returncode}")
        if marker.is_file():
            return
        time.sleep(1)
    raise RuntimeError(f"{username} 600 秒内未完成真实联机")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("用法：run-multi-client.py <客户端模板目录> <证据目录>")
    template = Path(sys.argv[1]).resolve()
    evidence = Path(sys.argv[2]).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    release = evidence / "release-clients.marker"
    release.unlink(missing_ok=True)
    clients = []
    try:
        for index, (username, uuid) in enumerate(PLAYERS, start=1):
            directory = template.parent / f"ci-client-{index}"
            if directory.exists():
                shutil.rmtree(directory)
            shutil.copytree(template, directory)
            marker = evidence / f"client-{index}-connected.marker"
            pillow_marker = evidence / f"client-{index}-pillow-observed.marker"
            scissors_marker = evidence / f"client-{index}-scissors-observed.marker"
            pig_marker = evidence / f"client-{index}-p3-pig-observed.marker"
            reconnect_marker = evidence / f"client-{index}-reconnected.marker"
            recovery_connection_marker = evidence / f"client-{index}-sigkill-recovered.marker"
            ability_key_marker = evidence / "client-1-p3-ability-key.marker" if username == "BlindBoxAlice" else None
            ability_tracking_marker = evidence / "client-2-p3-ability-tracking.marker" if username == "BlindBoxBob" else None
            ability_lifecycle_marker = evidence / "client-1-p3-ability-lifecycle.marker" if username == "BlindBoxAlice" else None
            ability_recovery_marker = evidence / "client-1-p3-ability-recovered.marker" if username == "BlindBoxAlice" else None
            marker.unlink(missing_ok=True)
            pillow_marker.unlink(missing_ok=True)
            scissors_marker.unlink(missing_ok=True)
            pig_marker.unlink(missing_ok=True)
            reconnect_marker.unlink(missing_ok=True)
            recovery_connection_marker.unlink(missing_ok=True)
            for ability_marker in (ability_key_marker, ability_tracking_marker, ability_lifecycle_marker, ability_recovery_marker):
                if ability_marker is not None:
                    ability_marker.unlink(missing_ok=True)
            clients.append((*launch(directory, username, uuid, marker, pillow_marker, scissors_marker, pig_marker, release, reconnect_marker,
                                    "alice" if username == "BlindBoxAlice" else "bob", ability_key_marker,
                                    ability_tracking_marker, ability_lifecycle_marker, ability_recovery_marker,
                                    recovery_connection_marker), marker, pillow_marker, scissors_marker, pig_marker, username, uuid, directory,
                            recovery_connection_marker))
            # 先由 Alice 完成真实握手和稳定联机，再启动 Bob，规避专服登录层的瞬时并发错误。
            # 两个客户端在业务探针、强杀恢复和最终正常退出阶段仍全程同时在线。
            wait_for_real_connection(clients[-1])

        (evidence / "both-connected.marker").write_text("both-real-clients-connected\n", encoding="utf-8")
        # 由 workflow 在导出 canonical 后写 release；此脚本等待该文件再让客户端正常退出。
        # P3 会在同一真实双客户端会话中完成 Clone、跨维与 save-all flush 后的 SIGKILL 重启；
        # 这只是给完整断言流程留出时间，不改变任何失败判定或客户端正常退出要求。
        wait_release = time.monotonic() + 600
        while time.monotonic() < wait_release and not release.is_file():
            time.sleep(1)
        if not release.is_file():
            raise RuntimeError("未收到服务端 canonical 完成释放标志")
        for process, _, _, _, _, _, _, username, _, _, _ in clients:
            try:
                code = process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f"{username} 未正常退出")
            if code != 0:
                raise RuntimeError(f"{username} 退出码异常：{code}")
        result = {"schema": 1, "status": "success", "suite": "multi-client-connection",
                  "players": [{"name": p[0], "uuid": p[1]} for p in PLAYERS],
                  "assertions": ["two-real-forge-clients", "same-dedicated-server", "distinct-offline-identities", "stable-40-ticks", "alice-real-disconnect-reconnect", "zero-exit"]}
        (evidence / "clients-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    finally:
        for process, output, *_ in clients:
            stop(process)
            output.close()


if __name__ == "__main__":
    main()
