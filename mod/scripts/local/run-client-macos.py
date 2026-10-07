#!/usr/bin/env python3
"""隔离运行正式 Jar 与独立观察探针；结果仅代表本地真实客户端。"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import time
import uuid
from pathlib import Path

import minecraft_launcher_lib

from validation_runtime import client_platform_arguments, resolve_java
VERSION = "1.20.1-forge-47.4.22"
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
        process.wait(timeout=15)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runtime", type=Path)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--server")
    parser.add_argument("--username", default="BlindBoxClient")
    parser.add_argument("--formal", type=Path)
    parser.add_argument("--probe", type=Path)
    parser.add_argument("--property", action="append", default=[])
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--java", type=Path, help="Java 17 可执行文件；默认 JAVA_HOME/bin/java 或 PATH")
    args = parser.parse_args()
    java = resolve_java(args.java)
    runtime = args.runtime.resolve()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "mods").mkdir(exist_ok=True)
    (directory / "config").mkdir(exist_ok=True)
    source = Path(__file__).resolve().parents[2] / "build/libs"
    formal = args.formal.resolve() if args.formal else source / "blindboxchallenge-1.0.5-all.jar"
    probe = args.probe.resolve() if args.probe else source / "blindboxchallenge-1.0.5-citest.jar"
    for jar in (formal, probe):
        shutil.copyfile(jar, directory / "mods" / jar.name)
    # 散列必须对应本次实际加载的隔离文件；后续重建不能改变既有进程的证据。
    formal_sha256 = hashlib.sha256((directory / "mods" / formal.name).read_bytes()).hexdigest()
    probe_sha256 = hashlib.sha256((directory / "mods" / probe.name).read_bytes()).hexdigest()
    shutil.copyfile(runtime / "options.txt", directory / "options.txt")
    (directory / "config/fml.toml").write_text("earlyWindowControl=false\n", encoding="utf-8")
    marker = directory / "connected.marker" if args.server else directory / "main-menu.marker"
    release = directory / "release.flag"
    marker.unlink(missing_ok=True)
    release.unlink(missing_ok=True)
    arguments = ["-Xms768M", "-Xmx2G", *client_platform_arguments(), f"-Dblindbox.ci.clientMarker={marker}"]
    if args.server:
        arguments.extend(["-Dblindbox.ci.multiplayerSmoke=true", f"-Dblindbox.ci.serverAddress={args.server}",
                          f"-Dblindbox.ci.clientRelease={release}", "-Dblindbox.ci.connectionDiagnostics=true"])
    else:
        arguments.append("-Dblindbox.ci.clientSmoke=true")
    arguments.extend(f"-D{value}" for value in args.property)
    options = minecraft_launcher_lib.utils.generate_test_options()
    offline_id = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + args.username).encode("utf-8")).digest(), version=3)
    options.update({"username": args.username, "uuid": offline_id.hex,
                    "token": "blindbox-local-offline-test", "executablePath": str(java),
                    "gameDirectory": str(directory), "disableMultiplayer": not bool(args.server),
                    "customResolution": True, "resolutionWidth": "1024", "resolutionHeight": "720",
                    "jvmArguments": arguments})
    command = minecraft_launcher_lib.command.get_minecraft_command(VERSION, str(runtime), options)
    console = directory / "console.log"
    started = time.monotonic()
    status = "failed"
    problem = None
    with console.open("wb", buffering=0) as output:
        process = subprocess.Popen(command, cwd=directory, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        (directory / "process.json").write_text(json.dumps({"pid": process.pid, "username": args.username,
                                                           "server": args.server, "directory": str(directory)}, indent=2) + "\n", encoding="utf-8")
        try:
            while time.monotonic() - started < args.timeout:
                text = console.read_text(encoding="utf-8", errors="replace")
                fatal = FATAL.search(text)
                if fatal:
                    problem = f"客户端日志异常：{fatal.group(0)}"
                    break
                connection_failure = next((line for line in text.splitlines() if "BLINDBOX_CITEST_CONNECT_FAILED" in line), None)
                if connection_failure:
                    problem = connection_failure
                    break
                if any((directory / "crash-reports").glob("*")):
                    problem = "客户端生成崩溃报告"
                    break
                code = process.poll()
                if code is not None:
                    if marker.is_file() and code == 0:
                        status = "success"
                    else:
                        problem = f"客户端退出，代码={code}，观察标志存在={marker.is_file()}"
                    break
                time.sleep(1)
            else:
                problem = "客户端达到隔离运行超时上限"
        finally:
            stop(process)
    result = {"schema": 1, "status": status, "mode": "real-local-multiplayer" if args.server else "real-local-title-screen",
              "platform": "macOS arm64", "forge": VERSION, "username": args.username,
              "formal_sha256": formal_sha256, "probe_sha256": probe_sha256,
              "exit_code": process.returncode, "marker": str(marker), "problem": problem,
              "limitations": ["本地验证，未执行 Hosted Runner 门禁", "独立观察探针不包含于正式 Jar"]}
    (directory / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    if status != "success":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
