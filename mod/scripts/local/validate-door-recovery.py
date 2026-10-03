#!/usr/bin/env python3
"""隔离验证任意门边界、同世界强杀恢复和两台真实客户端跨维观察。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import subprocess
import time
import uuid


MOD = Path(__file__).resolve().parents[2]
BASE = MOD / "build/local-validation-2026-10-04"
JAVA = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java")
FORGE_ARGS = Path("libraries/net/minecraftforge/forge/1.20.1-47.4.22/unix_args.txt")
FATAL = re.compile(r"\bFATAL\b|NoClassDefFoundError|Exception in server tick|Crash report saved to|ModLoadingException|Mixin apply failed", re.I)
DOOR_FAILURE = re.compile(r"BLINDBOX_CITEST_P4_DOOR_RECOVERY=failed|CI 任意门探针失败|Cannot (?:prepare|start|cleanup) P4 cross-dimension door", re.I)
MAX_LOG_TAIL = 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def log_tail(path, after=0):
    if not path.is_file():
        return ""
    with path.open("rb") as source:
        source.seek(max(after, max(0, path.stat().st_size - MAX_LOG_TAIL)))
        return source.read(MAX_LOG_TAIL).decode("utf-8", errors="replace")


def read_marker(path):
    if path.stat().st_size > 65536:
        raise ValueError(f"观察证据超过大小上限：{path.name}")
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if not key or not separator or key in values:
            raise ValueError(f"观察证据格式非法：{path.name}")
        values[key] = value
    return values


def stop_group(process, timeout=10):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)


def execute(args):
    run_dir = args.run_dir.absolute()
    # 包括悬空链接在内的既有目录均拒绝；失败证据也不得被下一次运行覆盖。
    if run_dir.exists() or run_dir.is_symlink():
        raise FileExistsError(f"运行目录已存在，拒绝覆盖：{run_dir}")
    if args.server_template == run_dir.resolve() or args.server_template in run_dir.resolve().parents:
        raise ValueError("运行目录不得放在只读专服模板内")
    run_dir.mkdir(parents=True, exist_ok=False)
    run_dir = run_dir.resolve()
    server_dir = run_dir / "server"
    evidence = run_dir / "evidence"
    artifacts = run_dir / "artifacts"
    server_dir.mkdir()
    evidence.mkdir()
    artifacts.mkdir()
    report = {"schema": 1, "status": "failed", "运行目录": str(run_dir), "检查": [], "服务端进程": [],
              "客户端": [], "命令": [], "清理错误": [], "边界": [
                  "仅本地真实专服和两客户端，不代表 Hosted Runner 或发布门禁",
                  "仅固定两门、已 save-all flush、目标区块由 Bob 正常加载的同世界恢复",
                  "恢复夹具直接写生产方块实体关联；配对入口另由服务端边界回归覆盖",
                  "不覆盖未 flush 掉电、未加载伙伴传送或任意门拓扑恢复"]}
    clients = []
    server = None
    server_log = None
    server_output = None
    client_outputs = []
    expected_kill = False

    def command(value):
        if server is None or server.poll() is not None or server.stdin is None:
            raise RuntimeError("专服已退出，无法发送命令")
        data = (value + "\n").encode("utf-8")
        if len(data) > 2048 or not select.select([], [server.stdin.fileno()], [], 2)[1]:
            raise TimeoutError("专服命令管道不可写或命令过长")
        if os.write(server.stdin.fileno(), data) != len(data):
            raise RuntimeError("专服命令未完整写入")
        report["命令"].append({"服务端轮次": len(report["服务端进程"]), "命令": value})

    def monitor():
        if server is None or server.poll() is not None:
            raise RuntimeError(f"专服提前退出：{None if server is None else server.returncode}")
        text = log_tail(server_log)
        if FATAL.search(text) or DOOR_FAILURE.search(text):
            raise RuntimeError(f"专服产生实际失败，见 {server_log.name}")
        for process, directory, _ in clients:
            if process.poll() is not None:
                raise RuntimeError(f"客户端提前退出：{directory.name}，代码 {process.returncode}")
            console = log_tail(directory / "console.log")
            if FATAL.search(console) or "BLINDBOX_CITEST_CONNECT_FAILED" in console:
                raise RuntimeError(f"客户端首次连接或运行失败，不重试：{directory.name}")
            if any((directory / "crash-reports").glob("*")):
                raise RuntimeError(f"客户端出现崩溃报告：{directory.name}")

    def wait(predicate, label, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            monitor()
            if predicate():
                report["检查"].append(label)
                print(label, flush=True)
                return
            time.sleep(0.25)
        raise TimeoutError(f"等待超时：{label}（{seconds} 秒）")

    def wait_log(marker, label, seconds=60, after=0):
        wait(lambda: marker in log_tail(server_log, after), label, seconds)

    def wait_file(name, label, seconds=120):
        wait(lambda: (evidence / name).is_file(), label, seconds)

    def start_server(number):
        nonlocal server, server_log, server_output
        server_log = run_dir / ("server-before-kill.log" if number == 1 else "server-after-restart.log")
        server_output = server_log.open("xb", buffering=0)
        environment = dict(os.environ)
        environment["BLINDBOX_CITEST_P4_MARKER_DIR"] = str(evidence)
        environment["BLINDBOX_PRODUCT_SHA256"] = report["formal_sha256"]
        server = subprocess.Popen(
            [str(args.java), "-Xms512m", "-Xmx2g", "-Dblindbox.ci.connectionDiagnostics=true",
             "@user_jvm_args.txt", "@" + str(FORGE_ARGS), "nogui"],
            cwd=server_dir, stdin=subprocess.PIPE, stdout=server_output, stderr=subprocess.STDOUT,
            env=environment, start_new_session=True,
        )
        report["服务端进程"].append({"轮次": number, "pid": server.pid, "日志": str(server_log), "退出码": None})
        wait_log("Done (", f"第 {number} 次专服真实启动完成", 120)

    def launch_client(name, role, number):
        directory = run_dir / ("client-" + role)
        recovery_marker = evidence / f"client-{number}-sigkill-recovered.marker"
        output = (run_dir / f"client-{role}-runner.log").open("xb", buffering=0)
        client_outputs.append(output)
        process = subprocess.Popen(
            [str(args.client_python), str(MOD / "scripts/local/run-client-macos.py"),
             str(args.client_runtime), str(directory), "--server", report["地址"], "--username", name,
             "--formal", str(artifacts / args.formal_jar.name), "--probe", str(artifacts / args.probe_jar.name),
             "--timeout", "900", "--property", f"blindbox.ci.p4DoorMarkerDir={evidence}",
             "--property", "blindbox.ci.serverRecovery=true", "--property",
             f"blindbox.ci.serverRecoveryMarker={recovery_marker}"],
            cwd=MOD, stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
        )
        clients.append((process, directory, name))
        wait(lambda: (directory / "connected.marker").is_file(), f"{name} 首次真实连接成功", 120)

    def check_fields(name, expected):
        fields = read_marker(evidence / name)
        for key, value in expected.items():
            if fields.get(key) != value:
                raise AssertionError(f"真实观察字段不符：{name} / {key}")

    try:
        for path in (args.formal_jar, args.probe_jar, args.java, args.client_python,
                     args.server_template / FORGE_ARGS, args.client_runtime / "options.txt"):
            if not path.is_file():
                raise FileNotFoundError(f"缺少既有验证文件：{path}")
        if args.formal_jar.name == args.probe_jar.name:
            raise ValueError("正式 Jar 与探针 Jar 必须是不同文件")
        for source, key in ((args.formal_jar, "formal_sha256"), (args.probe_jar, "probe_sha256")):
            expected = sha256(source)
            snapshot = artifacts / source.name
            shutil.copyfile(source, snapshot)
            if sha256(snapshot) != expected:
                raise RuntimeError("复制期间构建产物发生变化，拒绝混用")
            report[key] = expected
        report["原始正式Jar"] = str(args.formal_jar)
        report["原始探针Jar"] = str(args.probe_jar)
        libraries = (args.server_template / "libraries").resolve()
        (server_dir / "libraries").symlink_to(libraries, target_is_directory=True)
        report["只读依赖引用"] = str(libraries)
        for name in ("server.properties", "eula.txt", "ops.json", "user_jvm_args.txt"):
            shutil.copyfile(args.server_template / name, server_dir / name)
        config = args.server_template / "config/fml.toml"
        if config.is_file():
            (server_dir / "config").mkdir()
            shutil.copyfile(config, server_dir / "config/fml.toml")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        replacements = {"server-ip": "127.0.0.1", "server-port": str(port), "level-name": "world"}
        properties = server_dir / "server.properties"
        lines = []
        for line in properties.read_text(encoding="utf-8").splitlines():
            key = line.partition("=")[0]
            lines.append(key + "=" + replacements[key] if key in replacements else line)
        known_keys = {line.partition("=")[0] for line in lines}
        lines.extend(key + "=" + value for key, value in replacements.items() if key not in known_keys)
        properties.write_text("\n".join(lines) + "\n", encoding="utf-8")
        report["地址"] = f"127.0.0.1:{port}"
        report["服务端配置SHA-256"] = sha256(properties)
        (server_dir / "mods").mkdir()
        for source in artifacts.iterdir():
            shutil.copyfile(source, server_dir / "mods" / source.name)
        start_server(1)
        launch_client("BlindBoxAlice", "alice", 1)
        launch_client("BlindBoxBob", "bob", 2)
        command("blindboxcitest run_p4_door")
        wait_log("BLINDBOX_CITEST_P4_DOOR=success", "任意门服务端边界回归通过")
        wait_log("BLINDBOX_CITEST_P4_DOOR_UNLOADED_NEIGHBOR=success", "未知邻区块拒绝且恢复后原链接可用")
        command("blindboxcitest prepare_p4_door_recovery")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_PREPARED=success", "杀前跨维门持久夹具准备完成")
        wait_file("p4-door-recovery-before.properties", "杀前实际门关联证据已生成")
        manifest = read_marker(evidence / "p4-door-recovery-before.properties")
        if manifest.get("source_dimension") != "minecraft:overworld" or manifest.get("target_dimension") != "minecraft:the_nether":
            raise AssertionError("杀前门维度证据不符")
        for key in ("source_id", "target_id"):
            uuid.UUID(manifest[key])
        flush_offset = server_log.stat().st_size
        command("save-all flush")
        wait_log("Saved the game", "本次显式 flush 已完成", after=flush_offset)
        os.killpg(server.pid, signal.SIGKILL)
        kill_code = server.wait(timeout=15)
        report["服务端进程"][-1]["退出码"] = kill_code
        report["服务端进程"][-1]["预期强杀"] = True
        if kill_code != -signal.SIGKILL:
            raise AssertionError(f"首轮未获得真实 SIGKILL 退出码：{kill_code}")
        expected_kill = True
        server.stdin.close()
        server_output.close()
        server_output = None
        # 只重启原目录，保留 world、配置、端口与两份 Jar；不改存档，不重建夹具。
        start_server(2)
        for number in (1, 2):
            marker = f"client-{number}-sigkill-recovered.marker"
            wait_file(marker, f"客户端 {number} 已经真实重连并稳定 40 tick", 210)
            if (evidence / marker).read_text(encoding="utf-8") != "multiplayer-sigkill-recovered-40-ticks\n":
                raise AssertionError("强杀恢复客户端证据格式不符")
        command("blindboxcitest start_p4_door_recovery_clients")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_STARTED=success", "杀后持久关联复验与跨维场景启动")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_FIXTURE_SERVER_READY=success", "服务端已确认双方输入前站位", 120)
        (evidence / "p4-door-recovery-fixture-observe.flag").touch(exist_ok=False)
        wait_file("client-1-p4-door-fixture-ready.marker", "Alice 客户端实际观察源门起点")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_FIXTURE_SYNCED=success", "服务端复验第一份客户端同步证据", 120)
        (evidence / "p4-door-recovery-fixture-settle.flag").touch(exist_ok=False)
        wait_file("client-1-p4-door-fixture-settled.marker", "Alice 客户端完成无输入稳定观察")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_FIXTURE_SETTLED=success", "服务端复验第二份客户端稳定证据", 120)
        (evidence / "p4-door-recovery-enabled.flag").touch(exist_ok=False)
        wait_file("client-1-p4-door-arrived.marker", "Alice 真实前进键穿门并观察下界到达", 180)
        wait_file("client-2-p4-door-observed.marker", "Bob 真实观察下界同步后的 Alice", 180)
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLIENTS=success", "服务端最终核验双端到达、安全落点、速度和持久反链", 180)
        alice_id = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:BlindBoxAlice").digest(), version=3))
        bob_id = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:BlindBoxBob").digest(), version=3))
        for name in ("client-1-p4-door-fixture-ready.marker", "client-1-p4-door-fixture-settled.marker"):
            check_fields(name, {"schema": "1", "observer_uuid": alice_id, "dimension": "minecraft:overworld", "source": manifest["source_position"]})
        check_fields("client-1-p4-door-arrived.marker", {"schema": "1", "observer_uuid": alice_id,
            "dimension": "minecraft:the_nether", "arrival": manifest["target_position"], "source_and_target_synced": "true"})
        check_fields("client-2-p4-door-observed.marker", {"schema": "1", "observer_uuid": bob_id, "alice_uuid": alice_id,
            "dimension": "minecraft:the_nether", "arrival": manifest["target_position"],
            "target_door_and_safety_synced": "true", "observer_near_target": "true"})
        command("blindboxcitest prepare_p4_text_handoff")
        wait_log("BLINDBOX_CITEST_P4_TEXT_HANDOFF_PREPARED=success", "安全交接平台准备完成")
        command("blindboxcitest cleanup_p4_door_recovery_clients")
        wait_log("BLINDBOX_CITEST_P4_DOOR_RECOVERY_CLEANUP=success", "任意门恢复场景已归还")
        for name in ("p4-door-recovery-fixture-observe.flag", "p4-door-recovery-fixture-settle.flag", "p4-door-recovery-enabled.flag"):
            (evidence / name).unlink()
        command("blindboxcitest export")
        wait_log("BLINDBOX_CITEST_EXPORT=", "实际服务端状态已导出")
        canonical = server_dir / "citest-results/canonical-state.json"
        if not canonical.is_file() or canonical.stat().st_size == 0:
            raise AssertionError("实际服务端状态导出缺失")
        shutil.copyfile(canonical, evidence / "canonical-state.json")
        # 放行正常退出也只写原启动器约定的阶段文件，不写任何观察成功 marker。
        for _, directory, _ in clients:
            (directory / "release.flag").touch(exist_ok=False)
        for process, directory, name in clients:
            if process.wait(timeout=40) != 0:
                raise AssertionError(f"客户端未正常退出：{name}")
            result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
            report["客户端"].append(result)
            if result.get("status") != "success" or result.get("exit_code") != 0:
                raise AssertionError(f"客户端结果未通过：{name}")
            if result.get("formal_sha256") != report["formal_sha256"] or result.get("probe_sha256") != report["probe_sha256"]:
                raise AssertionError(f"客户端实际加载的 Jar 摘要不符：{name}")
        clients_finished = list(clients)
        clients.clear()
        command("blindboxcitest release_p4_text_handoff")
        wait_log("BLINDBOX_CITEST_P4_TEXT_HANDOFF_RELEASED=success", "安全交接平台已归还")
        flush_offset = server_log.stat().st_size
        command("save-all flush")
        wait_log("Saved the game", "重启后最终 flush 已完成", after=flush_offset)
        command("stop")
        restart_code = server.wait(timeout=40)
        report["服务端进程"][-1]["退出码"] = restart_code
        if restart_code != 0 or "Stopping server" not in log_tail(server_log):
            raise AssertionError("重启后的服务端未正常停止")
        for path in (run_dir / "server-before-kill.log", server_log,
                     *(directory / "console.log" for _, directory, _ in clients_finished)):
            with path.open(encoding="utf-8", errors="replace") as source:
                for line in source:
                    if FATAL.search(line) or DOOR_FAILURE.search(line) or "BLINDBOX_CITEST_CONNECT_FAILED" in line:
                        raise AssertionError(f"原始日志含实际失败：{path}")
        report["status"] = "success"
    except BaseException as error:
        report["错误"] = f"{type(error).__name__}: {error}"
        print(report["错误"], flush=True)
    finally:
        # 客户端 Java 拥有独立进程组，不能仅终止其 Python 包装器而留下游戏进程。
        for process, directory, _ in clients:
            try:
                metadata = directory / "process.json"
                if process.poll() is None:
                    pid = int(json.loads(metadata.read_text(encoding="utf-8"))["pid"]) if metadata.is_file() else None
                    if pid is not None:
                        try:
                            os.killpg(pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                    # 先让既有包装器观察真实 Java 退出并落盘 result.json，再考虑终止包装器。
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        if pid is not None:
                            try:
                                os.killpg(pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                        stop_group(process)
                result_path = directory / "result.json"
                if result_path.is_file():
                    result = json.loads(result_path.read_text(encoding="utf-8"))
                    if result not in report["客户端"]:
                        report["客户端"].append(result)
            except Exception as error:
                report["清理错误"].append(f"客户端清理：{type(error).__name__}: {error}")
        if server is not None:
            try:
                if server.poll() is None:
                    try:
                        command("stop")
                        server.wait(timeout=15)
                    except (OSError, RuntimeError, TimeoutError, subprocess.TimeoutExpired):
                        stop_group(server)
                if report["服务端进程"]:
                    report["服务端进程"][-1]["退出码"] = server.returncode
                if server.stdin is not None and not server.stdin.closed:
                    server.stdin.close()
            except Exception as error:
                report["清理错误"].append(f"专服清理：{type(error).__name__}: {error}")
        if server_output is not None:
            server_output.close()
        for output in client_outputs:
            output.close()
        report["实际执行首轮强杀"] = expected_kill
        report["原始服务端日志SHA-256"] = {path.name: sha256(path) for path in run_dir.glob("server-*.log")}
        if report["清理错误"]:
            report["status"] = "failed"
        (run_dir / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False), flush=True)
    return 0 if report["status"] == "success" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path, help="必须不存在的独立运行目录")
    parser.add_argument("--formal-jar", type=Path, default=MOD / "build/libs/blindboxchallenge-1.0.5-all.jar")
    parser.add_argument("--probe-jar", type=Path, default=MOD / "build/libs/blindboxchallenge-1.0.5-citest.jar")
    parser.add_argument("--server-template", type=Path, default=BASE / "server")
    parser.add_argument("--client-runtime", type=Path, default=BASE / "client/runtime")
    parser.add_argument("--client-python", type=Path, default=BASE / "client/venv/bin/python")
    parser.add_argument("--java", type=Path, default=JAVA)
    args = parser.parse_args()
    for name in ("formal_jar", "probe_jar", "server_template", "client_runtime", "client_python", "java"):
        setattr(args, name, getattr(args, name).absolute() if name == "client_python" else getattr(args, name).resolve())
    try:
        return execute(args)
    except Exception as error:
        print(f"未进入运行流程：{type(error).__name__}: {error}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
