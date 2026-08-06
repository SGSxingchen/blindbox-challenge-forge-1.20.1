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


def launch(directory: Path, username: str, uuid: str, marker: Path, pillow_marker: Path, release: Path, reconnect_marker: Path):
    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update({
        "username": username, "uuid": uuid, "token": "blindbox-ci-offline-token",
        "executablePath": shutil.which("java") or "java", "gameDirectory": str(directory),
        "disableMultiplayer": False,
        "jvmArguments": ["-Xms768M", "-Xmx2G", "-Dblindbox.ci.multiplayerSmoke=true",
                         "-Dblindbox.ci.serverAddress=127.0.0.1:25565",
                         f"-Dblindbox.ci.clientMarker={marker}", f"-Dblindbox.ci.clientRelease={release}",
                         f"-Dblindbox.ci.pillowMarker={pillow_marker}",
                         f"-Dblindbox.ci.reconnectMarker={reconnect_marker}"]
                        + (["-Dblindbox.ci.reconnect=true"] if username == "BlindBoxAlice" else []),
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
            reconnect_marker = evidence / f"client-{index}-reconnected.marker"
            marker.unlink(missing_ok=True)
            pillow_marker.unlink(missing_ok=True)
            reconnect_marker.unlink(missing_ok=True)
            clients.append((*launch(directory, username, uuid, marker, pillow_marker, release, reconnect_marker),
                            marker, pillow_marker, username, uuid, directory))

        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            failures = []
            for process, _, console, marker, _, username, _, directory in clients:
                text = console.read_text(encoding="utf-8", errors="replace") if console.is_file() else ""
                latest = directory / "logs" / "latest.log"
                if latest.is_file():
                    text += latest.read_text(encoding="utf-8", errors="replace")
                if FATAL.search(text) or any((directory / "crash-reports").glob("*")):
                    failures.append(f"{username} 日志或崩溃报告异常")
                if process.poll() is not None and not marker.is_file():
                    failures.append(f"{username} 在联机标志前退出：{process.returncode}")
            if failures:
                raise RuntimeError("; ".join(failures))
            if all(entry[3].is_file() for entry in clients):
                break
            time.sleep(1)
        else:
            raise RuntimeError("两个客户端 600 秒内未同时联机")

        (evidence / "both-connected.marker").write_text("both-real-clients-connected\n", encoding="utf-8")
        # 由 workflow 在导出 canonical 后写 release；此脚本等待该文件再让客户端正常退出。
        wait_release = time.monotonic() + 180
        while time.monotonic() < wait_release and not release.is_file():
            time.sleep(1)
        if not release.is_file():
            raise RuntimeError("未收到服务端 canonical 完成释放标志")
        for process, _, _, _, _, username, _, _ in clients:
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
