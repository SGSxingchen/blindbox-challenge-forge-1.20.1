#!/usr/bin/env python3
"""有界执行本地八音盒负例与 P5 双端缓存压力；只采纳正式客户端及专服事实。"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time


MOD = Path(__file__).resolve().parents[2]
LOCAL = MOD / "build/local-validation-2026-10-04"
STABILITY = MOD / "build/stability-2026-10-04"
AUDIO_BASE = "https://cdn.jsdelivr.net/gh/SGSxingchen/blindbox-challenge-forge-1.20.1@e33a3fe39cf0fc8beb7bdb2e7f44bc5e5727819f/mod/src/ciTest/resources/ci-audio/"
FIXTURE_SHA = "c4e3576180201d041d37d9337b29ef8709664e78ee2f1116a261148589e7b3bd"
VERIFIED_HEAD = {
    "核对日期": "2026-10-04", "方式": "禁用代理、无 Cookie/认证、HEAD、30 秒超时",
    "URL": AUDIO_BASE + "blindbox-ci-cache-pressure.ogg", "状态码": 200,
    "Content-Type": "audio/ogg", "Content-Length": 14064854, "Content-Encoding": None,
    "ETag": 'W/"d69cd6-G+CNzBayU6RpYm6o+XouJeLUeaI"',
    "本地内容SHA1Base64": "G+CNzBayU6RpYm6o+XouJeLUeaI",
    "源码GitBlob": "651abcc6e5bd1ab450a04faf1b4bcd6d5df6e415",
    "边界": "保存主控已核对前置结果；本轮编排不请求音频正文，下载只由两台正式客户端执行",
}
FATAL = re.compile(r"\bFATAL\b|ModLoadingException|Failed to load mods?|Mixin apply failed|NoClassDefFoundError:|Crash report saved to|Exception in server tick", re.I)
SERVER_FAILURES = ("BLINDBOX_CITEST_P5_MUSIC_CACHE=failed", "CI P5 八音盒缓存压力启动失败", "CI P5 八音盒清理失败", "CI 八音盒负例失败", "CI 八音盒负例缺少 Alice", "CI 八音盒夹具区域不是空气")
CLIENT_FAILURES = ("BLINDBOX_CITEST_CONNECT_FAILED", "在线音频下载或解码失败（仅客户端）")


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def file_hash(path):
    digest = hashlib.sha256()
    deadline = time.monotonic() + 30
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            if time.monotonic() > deadline:
                raise TimeoutError("文件摘要计算超时：" + str(path))
            digest.update(chunk)
    return digest.hexdigest()


def read_log(path):
    if not path.is_file():
        return ""
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("验证日志超过 16 MiB 有界读取上限：" + str(path))
    return path.read_text(encoding="utf-8", errors="replace")


def load_json(path):
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("验证 JSON 超过读取上限：" + str(path))
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--round", type=int, default=9, help="未使用的专服轮次，范围 2–20")
    parser.add_argument("--run-dir", type=Path, help="全新运行证据目录，必须位于 stability-2026-10-04 内")
    parser.add_argument("--baseline-result", type=Path, default=LOCAL / "server/login-stability/验证结果.json")
    args = parser.parse_args()
    if not 2 <= args.round <= 20:
        parser.error("轮次必须在 2 到 20 之间")
    run_dir = (args.run_dir or STABILITY / f"audio-pressure-round-{args.round}").resolve()
    if STABILITY.resolve() not in run_dir.parents:
        parser.error("运行目录必须位于隔离稳定性验证目录内")
    session = LOCAL / f"server/联机第{args.round}轮"
    suffix = hashlib.sha256(str(run_dir).encode("utf-8")).hexdigest()[:8]
    evidence = LOCAL / f"client/stability-audio-{args.round}-{suffix}-evidence"
    if run_dir.exists() or evidence.exists() or session.exists():
        parser.error("运行、marker 或专服轮次目录已存在，禁止覆盖；请指定新的轮次和运行目录")

    formal = MOD / "build/libs/blindboxchallenge-1.0.5-all.jar"
    probe = MOD / "build/libs/blindboxchallenge-1.0.5-citest.jar"
    baseline = args.baseline_result.resolve()
    fixture = MOD / "src/ciTest/resources/ci-audio/blindbox-ci-cache-pressure.ogg"
    client_python = LOCAL / "client/venv/bin/python"
    runtime = LOCAL / "client/runtime"
    for path in (formal, probe, baseline, fixture, client_python, runtime / "options.txt"):
        if not path.is_file():
            parser.error("缺少既有验证前置文件：" + str(path))
    formal_sha = file_hash(formal)
    probe_sha = file_hash(probe)
    baseline_value = load_json(baseline)
    if baseline_value.get("结果") != "通过" or baseline_value.get("正式Jar SHA-256") != formal_sha:
        parser.error("必须先有同一正式 Jar 的基础专服通过结果")
    if file_hash(fixture) != FIXTURE_SHA or fixture.stat().st_size != VERIFIED_HEAD["Content-Length"]:
        parser.error("本地压力夹具与已核不可变提交不一致")

    run_dir.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(exist_ok=False)
    artifacts = run_dir / "固定产物"
    artifacts.mkdir()
    formal_copy, probe_copy, baseline_copy = (artifacts / formal.name, artifacts / probe.name, artifacts / "基础专服结果.json")
    shutil.copy2(formal, formal_copy)
    shutil.copy2(probe, probe_copy)
    shutil.copy2(baseline, baseline_copy)
    if file_hash(formal_copy) != formal_sha or file_hash(probe_copy) != probe_sha:
        raise RuntimeError("复制期间构建产物变化，拒绝启动")
    report = {
        "schema": 1, "任务": "八音盒本地负例与 P5 双端缓存压力", "状态": "执行中", "轮次": args.round,
        "开始时间": utc_now(), "运行目录": str(run_dir), "共享marker目录": str(evidence),
        "夹具基址": AUDIO_BASE, "已核HEAD": VERIFIED_HEAD,
        "文件": {"正式Jar": {"路径": str(formal_copy), "SHA-256": formal_sha},
                 "探针Jar": {"路径": str(probe_copy), "SHA-256": probe_sha},
                 "基础专服结果": {"路径": str(baseline_copy), "SHA-256": file_hash(baseline_copy)},
                 "压力夹具": {"路径": str(fixture), "SHA-256": FIXTURE_SHA, "字节数": fixture.stat().st_size},
                 "编排脚本": {"路径": str(Path(__file__).resolve()), "SHA-256": file_hash(Path(__file__))}},
        "阶段": [], "命令": [], "客户端": [], "清理": [],
        "限制": ["仅本地真实客户端，不运行远程 Actions", "不修改生产代码、网络策略或游戏 tick 超时", "不重试或覆盖失败首连"],
    }
    result_path = run_dir / "音频压力结果.json"
    server = None
    clients = []
    outputs = []
    queue = None
    server_log = session / "客户端联机专服.log"
    p5_requested = False
    main_passed = False

    def save_report():
        temporary = result_path.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(result_path)

    def monitor(ignore_clients=False, ignore_scenario_failure=False):
        if server is not None and server.poll() is not None:
            raise RuntimeError(f"专服包装器提前退出：{server.returncode}")
        text = read_log(server_log)
        if FATAL.search(text):
            raise RuntimeError("专服出现致命日志")
        if not ignore_scenario_failure and any(marker in text for marker in SERVER_FAILURES):
            raise RuntimeError("专服场景已报告失败")
        if ignore_clients:
            return
        for client in clients:
            process, directory = client["process"], client["directory"]
            if process.poll() is not None:
                raise RuntimeError(f"{client['role']} 客户端提前退出：{process.returncode}，保留首次 result.json")
            text = read_log(directory / "console.log")
            if FATAL.search(text) or any(marker in text for marker in CLIENT_FAILURES) or any((directory / "crash-reports").glob("*")):
                raise RuntimeError(client["role"] + " 客户端已报告启动、下载或运行失败")

    def wait(predicate, label, seconds, **monitor_options):
        phase = {"名称": label, "开始时间": utc_now(), "上限秒数": seconds, "状态": "等待中"}
        report["阶段"].append(phase)
        save_report()
        started = time.monotonic()
        try:
            while time.monotonic() - started < seconds:
                monitor(**monitor_options)
                if predicate():
                    phase["状态"] = "通过"
                    phase["耗时秒数"] = round(time.monotonic() - started, 3)
                    save_report()
                    print(label + "：通过", flush=True)
                    return
                time.sleep(.25)
            raise TimeoutError(label)
        except BaseException as error:
            phase["状态"] = "失败"
            phase["耗时秒数"] = round(time.monotonic() - started, 3)
            phase["错误"] = f"{type(error).__name__}: {error}"
            save_report()
            raise

    def wait_marker(marker, seconds=240, **options):
        wait(lambda: marker in read_log(server_log), marker, seconds, **options)

    def command(value):
        if queue is None or server is None or server.poll() is not None:
            raise RuntimeError("专服命令队列不可用")
        with queue.open("a", encoding="utf-8") as output:
            output.write(value + "\n")
        report["命令"].append({"时间": utc_now(), "命令": value})
        save_report()

    def launch_client(role, username, address):
        directory = run_dir / role
        if directory.exists():
            raise RuntimeError("客户端隔离目录已存在")
        output = (run_dir / f"{role}-启动器.log").open("wb", buffering=0)
        outputs.append(output)
        process = subprocess.Popen([
            str(client_python), str(MOD / "scripts/local/run-client-macos.py"), str(runtime), str(directory),
            "--server", address, "--username", username, "--timeout", "1200",
            "--formal", str(formal_copy), "--probe", str(probe_copy),
            "--property", "blindbox.ci.p5AudioBase=" + AUDIO_BASE,
            "--property", "blindbox.ci.p5MusicCacheMarkerDir=" + str(evidence),
        ], cwd=MOD, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        client = {"role": role, "process": process, "directory": directory}
        clients.append(client)
        wait(lambda: (directory / "connected.marker").is_file(), role + " 首次真实联机", 180)
        for artifact, expected in ((formal_copy, formal_sha), (probe_copy, probe_sha)):
            if file_hash(directory / "mods" / artifact.name) != expected:
                raise RuntimeError(role + " 实际加载产物与固定副本不一致")
        report["客户端"].append({"角色": role, "用户名": username, "包装器PID": process.pid, "目录": str(directory), "干净缓存": True})
        save_report()

    def stop_wrapper(process, metadata, expected_directory=None):
        if process.poll() is not None:
            return
        try:
            os.killpg(process.pid, signal.SIGINT)
        except ProcessLookupError:
            process.wait(timeout=5)
            return
        try:
            process.wait(timeout=20)
            return
        except subprocess.TimeoutExpired:
            pass
        java_pid = None
        if metadata.is_file():
            value = load_json(metadata)
            if expected_directory is None or value.get("directory") == str(expected_directory):
                pid = value.get("pid", value.get("专服进程"))
                if isinstance(pid, int) and pid > 1:
                    java_pid = pid
                    try:
                        os.killpg(pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            if java_pid is not None:
                try:
                    os.killpg(java_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)

    save_report()
    try:
        environment = dict(os.environ)
        environment["BLINDBOX_CITEST_P5_MARKER_DIR"] = str(evidence)
        environment["BLINDBOX_CITEST_P5_AUDIO_BASE_URL"] = AUDIO_BASE
        output = (run_dir / "专服启动器.log").open("wb", buffering=0)
        outputs.append(output)
        server = subprocess.Popen([
            sys.executable, str(MOD / "scripts/local/serve-client-validation.py"), "--round", str(args.round),
            "--formal-jar", str(formal_copy), "--baseline-result", str(baseline_copy), "--marker-dir", str(evidence),
        ], cwd=MOD, stdout=output, stderr=subprocess.STDOUT, env=environment, start_new_session=True)
        entry_path = session / "客户端联机入口.json"
        wait(entry_path.is_file, "专服本轮入口就绪", 150)
        entry = load_json(entry_path)
        queue = Path(entry["命令队列"])
        report["专服"] = entry
        for artifact, expected in ((formal_copy, formal_sha), (probe_copy, probe_sha)):
            if file_hash(LOCAL / "server/mods" / artifact.name) != expected:
                raise RuntimeError("专服实际加载 Jar 与本轮固定副本不一致")
        launch_client("alice", "BlindBoxAlice", entry["地址"])
        launch_client("bob", "BlindBoxBob", entry["地址"])
        command("blindboxcitest run_p4_music_negative")
        wait_marker("BLINDBOX_CITEST_P4_MUSIC_NEGATIVE=success", 60)
        command("blindboxcitest start_p5_music_cache_clients")
        p5_requested = True
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_STARTED=success", 60)
        (evidence / "p5-music-cache-enabled.flag").touch()
        for number in range(1, 6):
            wait_marker(f"BLINDBOX_CITEST_P5_MUSIC_CACHE_FILL_{number}=success")
            if number < 5:
                (evidence / f"p5-music-cache-fill-{number + 1}.flag").touch()
        (evidence / "p5-music-cache-eviction-reload.flag").touch()
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_EVICTION_REDOWNLOAD=success")
        (evidence / "p5-music-cache-singleflight.flag").touch()
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_SINGLE_FLIGHT=success")
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_CORRUPTION=ready", 120)
        (evidence / "p5-music-cache-corrupt-retry.flag").touch()
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_CLIENTS=success")
        command("blindboxcitest cleanup_p5_music_cache_clients")
        wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_CLEANUP=success", 60)
        p5_requested = False
        main_passed = True
    except BaseException as error:
        report["错误"] = f"{type(error).__name__}: {error}"
        report["状态"] = "失败"
        if evidence.is_dir():
            (evidence / "p5-music-cache-diagnostic-request.flag").touch()
        print("音频压力验证失败：" + report["错误"], flush=True)
    finally:
        if p5_requested and server is not None and server.poll() is None and queue is not None:
            try:
                command("blindboxcitest cleanup_p5_music_cache_clients")
                wait_marker("BLINDBOX_CITEST_P5_MUSIC_CACHE_CLEANUP=success", 60, ignore_clients=True, ignore_scenario_failure=True)
                report["清理"].append("P5 夹具已归还")
            except BaseException as error:
                report["清理"].append("P5 清理失败：" + str(error))
        for client in clients:
            directory, process = client["directory"], client["process"]
            try:
                if directory.exists():
                    (directory / "release.flag").touch()
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    stop_wrapper(process, directory / "process.json", directory)
                result = load_json(directory / "result.json") if (directory / "result.json").is_file() else {"status": "missing", "exit_code": process.returncode}
                report.setdefault("客户端结果", []).append({"角色": client["role"], "包装器退出码": process.returncode, "结果": result})
                if process.returncode != 0 or result.get("status") != "success" or result.get("formal_sha256") != formal_sha or result.get("probe_sha256") != probe_sha:
                    main_passed = False
            except BaseException as error:
                main_passed = False
                report["清理"].append(client["role"] + " 停止失败：" + str(error))
                try:
                    stop_wrapper(process, directory / "process.json", directory)
                except BaseException as stop_error:
                    report["清理"].append(client["role"] + " 强制收尾失败：" + str(stop_error))
        if server is not None:
            try:
                if server.poll() is None and queue is not None:
                    command("save-all flush")
                    command("stop")
                    try:
                        server.wait(timeout=50)
                    except subprocess.TimeoutExpired:
                        stop_wrapper(server, session / "客户端联机入口.json")
                elif server.poll() is None:
                    stop_wrapper(server, session / "客户端联机入口.json")
                report["专服包装器退出码"] = server.returncode
                server_result_path = session / "客户端探针服结果.json"
                if server_result_path.is_file():
                    report["专服结果"] = load_json(server_result_path)
                if server.returncode != 0 or report.get("专服结果", {}).get("退出码") != 0:
                    main_passed = False
            except BaseException as error:
                main_passed = False
                report["清理"].append("专服停止失败：" + str(error))
                try:
                    stop_wrapper(server, session / "客户端联机入口.json")
                except BaseException as stop_error:
                    report["清理"].append("专服强制收尾失败：" + str(stop_error))
        for output in outputs:
            output.close()
        # 不使用 hosts 替换或 DNS 缓存改写，无需恢复系统或 JVM hosts 文件。
        report["hosts变更"] = "无"
        try:
            final_server_text = read_log(server_log)
            if FATAL.search(final_server_text) or any(marker in final_server_text for marker in SERVER_FAILURES):
                main_passed = False
                report["专服最终日志异常"] = True
        except (OSError, ValueError) as error:
            main_passed = False
            report["专服最终日志错误"] = str(error)
        report["证据文件"] = []
        paths = [server_log, session / "客户端探针服结果.json", *run_dir.glob("*-启动器.log"), run_dir / "专服启动器.log", *evidence.glob("*")]
        for client in clients:
            directory = client["directory"]
            paths.extend([directory / "console.log", directory / "result.json", directory / "process.json", directory / "connected.marker"])
        for path in paths:
            if path.is_file():
                try:
                    report["证据文件"].append({"路径": str(path), "字节数": path.stat().st_size, "SHA-256": file_hash(path)})
                except (OSError, TimeoutError) as error:
                    report["证据文件"].append({"路径": str(path), "摘要错误": str(error)})
                    main_passed = False
        report["结束时间"] = utc_now()
        report["状态"] = "通过" if main_passed and "错误" not in report else "失败"
        save_report()
        print("音频压力结果：" + report["状态"] + "；" + str(result_path), flush=True)
    return 0 if report["状态"] == "通过" else 1


if __name__ == "__main__":
    raise SystemExit(main())
