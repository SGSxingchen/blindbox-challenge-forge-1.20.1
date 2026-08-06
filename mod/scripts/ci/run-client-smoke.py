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
FATAL_PATTERNS = [re.compile(value, re.IGNORECASE) for value in (
    r"\bFATAL\b", r"ModLoadingException", r"Failed to load mods?", r"Loading errors encountered",
    r"Mixin apply failed", r"MixinApplyError", r"InvalidMixinException", r"NoClassDefFoundError:",
    r"ExceptionInInitializerError", r"Crash report saved to", r"This crash report has been saved to",
)]


def combined_text(*paths: Path) -> str:
    return "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in paths if path.is_file())


def fatal(text: str) -> str | None:
    for pattern in FATAL_PATTERNS:
        match = pattern.search(text)
        if match:
            return match.group(0)
    return None


def stop_group(process: subprocess.Popen[bytes]) -> None:
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


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("用法：run-client-smoke.py <Minecraft目录>")
    directory = Path(sys.argv[1]).resolve()
    logs = directory / "logs"
    crashes = directory / "crash-reports"
    logs.mkdir(parents=True, exist_ok=True)
    crashes.mkdir(parents=True, exist_ok=True)
    marker = directory / "ci-client-main-menu.marker"
    marker.unlink(missing_ok=True)
    console = logs / "ci-client-console.log"
    latest = logs / "latest.log"

    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update({
        "username": "BlindBoxClient",
        "uuid": "460538cb5d8a3b74a2ea330721ad0bc2",
        "token": "blindbox-ci-offline-token",
        "executablePath": shutil.which("java") or "java",
        "gameDirectory": str(directory),
        "disableMultiplayer": True,
        "jvmArguments": [
            "-Xms1G", "-Xmx3G", "-Dblindbox.ci.clientSmoke=true",
            f"-Dblindbox.ci.clientMarker={marker}",
        ],
    })
    command = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24 +extension GLX",
               *minecraft_launcher_lib.command.get_minecraft_command(VERSION_ID, str(directory), options)]
    environment = os.environ.copy()
    environment.update({"LIBGL_ALWAYS_SOFTWARE": "1", "MESA_LOADER_DRIVER_OVERRIDE": "llvmpipe", "ALSOFT_DRIVERS": "null"})

    failure = None
    return_code = None
    with console.open("wb", buffering=0) as output:
        process = subprocess.Popen(command, cwd=directory, env=environment, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        started = time.monotonic()
        try:
            while True:
                text = combined_text(console, latest)
                problem = fatal(text)
                reports = [path for path in crashes.rglob("*") if path.is_file()]
                return_code = process.poll()
                if problem:
                    failure = f"客户端日志检测到异常：{problem}"
                    break
                if reports:
                    failure = "客户端生成了崩溃报告"
                    break
                if return_code is not None:
                    if marker.is_file() and return_code == 0:
                        break
                    failure = f"客户端在主菜单标志前退出：{return_code}"
                    break
                if marker.is_file():
                    try:
                        return_code = process.wait(timeout=45)
                    except subprocess.TimeoutExpired:
                        failure = "主菜单标志出现后客户端未正常退出"
                    if return_code not in (None, 0):
                        failure = f"客户端退出码异常：{return_code}"
                    break
                if time.monotonic() - started >= 600:
                    failure = "客户端 600 秒内未稳定到达主菜单"
                    break
                time.sleep(1)
        finally:
            if failure:
                stop_group(process)

    text = combined_text(console, latest)
    print(text)
    if failure or fatal(text) or not marker.is_file():
        raise SystemExit(failure or "客户端最终证据检查失败")
    result = {
        "schema": 1, "status": "success", "suite": "single-client-main-menu",
        "assertions": ["real-forge-client", "xvfb-llvmpipe", "title-screen-stable-20-ticks", "no-fatal", "no-crash-report", "zero-exit"],
        "limitations": ["本里程碑仅验证客户端物理端初始化；真实联机打包与开盒在后续业务矩阵验证"],
    }
    (directory / "ci-client-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
