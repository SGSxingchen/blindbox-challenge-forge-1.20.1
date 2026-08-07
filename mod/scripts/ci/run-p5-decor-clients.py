#!/usr/bin/env python3
"""Hosted Runner 专用的 P5 独立世界真实 Forge 客户端启动器。"""
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
PLAYERS = (("BlindBoxAlice", "11111111111131118111111111111111"), ("BlindBoxBob", "22222222222232228222222222222222"))
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


def combined_log(directory: Path, console: Path) -> str:
    text = console.read_text(encoding="utf-8", errors="replace") if console.is_file() else ""
    latest = directory / "logs" / "latest.log"
    return text + (latest.read_text(encoding="utf-8", errors="replace") if latest.is_file() else "")


def launch(template: Path, evidence: Path, mode: str, index: int, username: str, uuid: str, release: Path):
    directory = template.parent / f"ci-p5-{mode}-client-{index}"
    if directory.exists():
        shutil.rmtree(directory)
    shutil.copytree(template, directory)
    connected = evidence / f"client-{index}-connected.marker"
    decor_marker = evidence / (f"client-{index}-p5-decor-single-observed.marker" if mode == "single" else f"client-{index}-p5-decor-observed.marker")
    diagnostic = evidence / f"client-{index}-p5-decor-diagnostic.marker"
    for marker in (connected, decor_marker, diagnostic):
        marker.unlink(missing_ok=True)
    arguments = [
        "-Xms768M", "-Xmx2G", "-Dblindbox.ci.multiplayerSmoke=true",
        "-Dblindbox.ci.serverAddress=127.0.0.1:25565",
        f"-Dblindbox.ci.clientMarker={connected}", f"-Dblindbox.ci.clientRelease={release}",
        f"-Dblindbox.ci.p5DecorMarker={decor_marker}", f"-Dblindbox.ci.p5DecorDiagnostic={diagnostic}",
        f"-Dblindbox.ci.p5DecorStageDir={evidence}", f"-Dblindbox.ci.p5DecorRole={username}",
    ]
    if mode == "single":
        arguments.append("-Dblindbox.ci.p5DecorSingle=true")
    else:
        audio_base = os.environ.get("BLINDBOX_CITEST_P5_AUDIO_BASE_URL")
        if not audio_base:
            raise RuntimeError("P5 双客户端缺少受控 HTTPS 音频基址")
        arguments.extend((f"-Dblindbox.ci.p5AudioBase={audio_base}", f"-Dblindbox.ci.p5MusicCacheMarkerDir={evidence}"))
    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update({
        "username": username, "uuid": uuid, "token": "blindbox-ci-offline-token",
        "executablePath": shutil.which("java") or "java", "gameDirectory": str(directory),
        "disableMultiplayer": False, "jvmArguments": arguments,
    })
    command = ["xvfb-run", "-a", "-s", "-screen 0 1024x576x24 +extension GLX",
               *minecraft_launcher_lib.command.get_minecraft_command(VERSION_ID, str(directory), options)]
    environment = os.environ.copy()
    environment.update({"LIBGL_ALWAYS_SOFTWARE": "1", "MESA_LOADER_DRIVER_OVERRIDE": "llvmpipe", "ALSOFT_DRIVERS": "null"})
    console = directory / "logs" / "ci-p5-console.log"
    console.parent.mkdir(parents=True, exist_ok=True)
    output = console.open("wb", buffering=0)
    process = subprocess.Popen(command, cwd=directory, env=environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    return process, output, directory, console, connected, username


def wait_connected(entry):
    process, _, directory, console, connected, username = entry
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        if FATAL.search(combined_log(directory, console)) or any((directory / "crash-reports").glob("*")):
            raise RuntimeError(f"{username} P5 启动日志或崩溃报告异常")
        if connected.is_file():
            return
        if process.poll() is not None:
            raise RuntimeError(f"{username} 在真实联机标志前退出：{process.returncode}")
        time.sleep(1)
    raise RuntimeError(f"{username} 600 秒内未完成真实联机")


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in {"single", "dual"}:
        raise SystemExit("用法：run-p5-decor-clients.py <客户端模板目录> <证据目录> <single|dual>")
    template, evidence, mode = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3]
    evidence.mkdir(parents=True, exist_ok=True)
    release = evidence / "release-clients.marker"
    release.unlink(missing_ok=True)
    for flag in [evidence / "p5-decor-enabled.flag", *[evidence / f"p5-decor-{stage}-{index}.flag" for stage in ("place", "break") for index in (1, 2, 3)],
                 evidence / "p5-music-cache-enabled.flag", evidence / "p5-music-cache-eviction-reload.flag",
                 evidence / "p5-music-cache-singleflight.flag", evidence / "p5-music-cache-corrupt-retry.flag",
                 evidence / "p5-music-cache-diagnostic-request.flag", *[evidence / f"p5-music-cache-fill-{index}.flag" for index in range(1, 6)]]:
        flag.unlink(missing_ok=True)
    for marker in evidence.glob("client-*-p5-music-cache-*.marker"):
        marker.unlink()
    for marker in evidence.glob("client-*-p5-audio-*.diagnostic"):
        marker.unlink()
    players = PLAYERS[:1] if mode == "single" else PLAYERS
    clients = []
    try:
        for index, (username, uuid) in enumerate(players, start=1):
            entry = launch(template, evidence, mode, index, username, uuid, release)
            clients.append(entry)
            wait_connected(entry)
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline and not release.is_file():
            for process, _, directory, console, _, username in clients:
                if FATAL.search(combined_log(directory, console)):
                    raise RuntimeError(f"{username} P5 业务期间出现致命日志")
                if process.poll() is not None:
                    raise RuntimeError(f"{username} 在服务端释放前退出：{process.returncode}")
            time.sleep(1)
        if not release.is_file():
            raise RuntimeError("P5 客户端未收到服务端正常退出释放标志")
        for process, _, _, _, _, username in clients:
            code = process.wait(timeout=90)
            if code != 0:
                raise RuntimeError(f"{username} 退出码异常：{code}")
        (evidence / "clients-result.json").write_text(json.dumps({
            "schema": 1, "status": "success", "suite": f"p5-{mode}-clients",
            "players": [{"name": name, "uuid": uuid} for name, uuid in players],
            "assertions": ["real-forge-client", "same-dedicated-server", "survival-input", "zero-exit"],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    finally:
        for process, output, *_ in clients:
            stop(process)
            output.close()


if __name__ == "__main__":
    main()
