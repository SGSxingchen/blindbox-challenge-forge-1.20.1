#!/usr/bin/env python3
"""只在 Hosted Runner 使用的一服一真实 Forge 客户端 P5 装饰方块启动器。"""
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
    if latest.is_file():
        text += latest.read_text(encoding="utf-8", errors="replace")
    return text


def main():
    if len(sys.argv) != 3:
        raise SystemExit("用法：run-p5-single-decor.py <客户端模板目录> <证据目录>")
    template = Path(sys.argv[1]).resolve()
    evidence = Path(sys.argv[2]).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    release = evidence / "release-client.marker"
    connected = evidence / "client-connected.marker"
    decor_marker = evidence / "client-1-p5-decor-single-observed.marker"
    diagnostic = evidence / "client-1-p5-decor-single-diagnostic.marker"
    for marker in (release, connected, decor_marker, diagnostic):
        marker.unlink(missing_ok=True)
    for flag in [evidence / "p5-decor-enabled.flag", *[evidence / f"p5-decor-place-{index}.flag" for index in (1, 2, 3)],
                 *[evidence / f"p5-decor-break-{index}.flag" for index in (1, 2, 3)]]:
        flag.unlink(missing_ok=True)

    directory = template.parent / "ci-single-p5-client"
    if directory.exists():
        shutil.rmtree(directory)
    shutil.copytree(template, directory)
    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update({
        "username": "BlindBoxAlice",
        "uuid": "11111111111131118111111111111111",
        "token": "blindbox-ci-offline-token",
        "executablePath": shutil.which("java") or "java",
        "gameDirectory": str(directory),
        "disableMultiplayer": False,
        "jvmArguments": [
            "-Xms768M", "-Xmx2G", "-Dblindbox.ci.multiplayerSmoke=true",
            "-Dblindbox.ci.serverAddress=127.0.0.1:25565",
            f"-Dblindbox.ci.clientMarker={connected}",
            f"-Dblindbox.ci.clientRelease={release}",
            f"-Dblindbox.ci.p5DecorMarker={decor_marker}",
            f"-Dblindbox.ci.p5DecorDiagnostic={diagnostic}",
            f"-Dblindbox.ci.p5DecorStageDir={evidence}",
            "-Dblindbox.ci.p5DecorRole=BlindBoxAlice",
            "-Dblindbox.ci.p5DecorSingle=true",
        ],
    })
    command = ["xvfb-run", "-a", "-s", "-screen 0 1024x576x24 +extension GLX",
               *minecraft_launcher_lib.command.get_minecraft_command(VERSION_ID, str(directory), options)]
    environment = os.environ.copy()
    environment.update({"LIBGL_ALWAYS_SOFTWARE": "1", "MESA_LOADER_DRIVER_OVERRIDE": "llvmpipe", "ALSOFT_DRIVERS": "null"})
    console = directory / "logs" / "ci-p5-single-console.log"
    console.parent.mkdir(parents=True, exist_ok=True)
    output = console.open("wb", buffering=0)
    process = subprocess.Popen(command, cwd=directory, env=environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            log = combined_log(directory, console)
            if FATAL.search(log) or any((directory / "crash-reports").glob("*")):
                raise RuntimeError("P5 单客户端日志或崩溃报告出现启动错误")
            if connected.is_file():
                break
            if process.poll() is not None:
                raise RuntimeError(f"P5 单客户端在真实联机标志前退出：{process.returncode}")
            time.sleep(1)
        else:
            raise RuntimeError("P5 单客户端 600 秒内未完成真实联机")

        deadline = time.monotonic() + 600
        while time.monotonic() < deadline and not release.is_file():
            if FATAL.search(combined_log(directory, console)):
                raise RuntimeError("P5 单客户端业务期间出现致命日志")
            if process.poll() is not None:
                raise RuntimeError(f"P5 单客户端在释放前退出：{process.returncode}")
            time.sleep(1)
        if not release.is_file():
            raise RuntimeError("P5 单客户端未收到服务端正常退出释放标志")
        code = process.wait(timeout=90)
        if code != 0:
            raise RuntimeError(f"P5 单客户端退出码异常：{code}")
        (evidence / "client-result.json").write_text(json.dumps({
            "schema": 1,
            "status": "success",
            "suite": "p5-single-decor",
            "assertions": ["one-real-forge-client", "same-dedicated-server", "survival-blockitem-place-break-pickup", "zero-exit"],
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    finally:
        stop(process)
        output.close()


if __name__ == "__main__":
    main()
