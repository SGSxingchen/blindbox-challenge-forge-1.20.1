#!/usr/bin/env python3
"""限时启动本地探针服，供独立真实客户端执行既有观察场景。"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

spec = importlib.util.spec_from_file_location("local_server_validation", Path(__file__).with_name("validate-server.py"))
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)
ROOT = validation.ROOT
CITEST = validation.MOD / "build/libs/blindboxchallenge-1.0.5-citest.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--round", type=int, default=1, help="隔离验证轮次；第二轮起使用全新临时世界")
    parser.add_argument("--formal-jar", type=Path, default=validation.JAR, help="本轮固定使用的正式 Jar")
    parser.add_argument("--baseline-result", type=Path, default=validation.RESULT, help="同一正式 Jar 的基础专服验证结果")
    parser.add_argument("--marker-dir", type=Path, help="隔离客户端的场景证据目录")
    arguments = parser.parse_args()
    if arguments.round < 1 or arguments.round > 20:
        parser.error("轮次必须在 1 到 20 之间")
    evidence_name = "evidence" if arguments.round == 1 else f"round-{arguments.round}-evidence"
    client_evidence = arguments.marker_dir or validation.MOD / "build/local-validation-2026-10-04/client" / evidence_name
    client_evidence = client_evidence.resolve()
    client_root = (validation.MOD / "build/local-validation-2026-10-04/client").resolve()
    if client_root not in client_evidence.parents:
        parser.error("场景证据必须位于隔离客户端验证目录内")
    session = ROOT if arguments.round == 1 else ROOT / f"联机第{arguments.round}轮"
    session.mkdir(parents=True, exist_ok=True)
    queue = session / "客户端验证命令.txt"
    ready = session / "客户端联机入口.json"
    report_path = session / "客户端探针服结果.json"
    log_path = session / "客户端联机专服.log"
    if log_path.exists() or report_path.exists() or ready.exists():
        raise RuntimeError("本轮验证证据已存在，禁止覆盖；请使用新的轮次")
    process = None
    formal_jar = arguments.formal_jar.resolve()
    report = {
        "环境": "本地正式映射 all.jar 与独立 citest 探针；不触发远程工作流",
        "轮次": arguments.round,
        "正式Jar": str(formal_jar),
        "正式Jar SHA-256": validation.sha256(formal_jar),
        "探针Jar SHA-256": validation.sha256(CITEST), "日志": str(log_path), "结果": "未完成", "命令": [],
    }
    try:
        basic = json.loads(arguments.baseline_result.read_text(encoding="utf-8"))
        if basic["结果"] != "通过" or basic["正式Jar SHA-256"] != report["正式Jar SHA-256"]:
            raise RuntimeError("必须先完成同一成品的基础专服验证")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        properties = ROOT / "server.properties"
        lines = properties.read_text(encoding="utf-8").splitlines()
        world = "临时世界" if arguments.round == 1 else f"联机第{arguments.round}轮临时世界"
        if arguments.round > 1 and (ROOT / world).exists():
            raise RuntimeError("本轮临时世界已存在，禁止混用旧世界；请使用新的轮次")
        replacements = {"server-port": str(port), "level-name": world, "allow-flight": "false"}
        rewritten = []
        for line in lines:
            key = line.partition("=")[0]
            rewritten.append(key + "=" + replacements.pop(key) if key in replacements else line)
        rewritten.extend(key + "=" + value for key, value in replacements.items())
        properties.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        target_formal = ROOT / "mods" / validation.JAR.name
        if formal_jar != target_formal.resolve():
            shutil.copy2(formal_jar, target_formal)
        shutil.copy2(CITEST, ROOT / "mods" / CITEST.name)
        client_evidence.mkdir(parents=True, exist_ok=True)
        queue.write_text("", encoding="utf-8")
        environment = dict(os.environ)
        environment["BLINDBOX_CITEST_P4_MARKER_DIR"] = str(client_evidence)
        environment["BLINDBOX_CITEST_ABILITY_MARKER_DIR"] = str(client_evidence)
        started = time.monotonic()
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(
                [str(validation.JAVA), "-Xms512m", "-Xmx2g",
                 "-Dblindbox.ci.packingStageDir=" + str(client_evidence / "packing"),
                 "-Dblindbox.ci.packingMarker=" + str(client_evidence / "packing/alice-packing.properties"),
                 "@user_jvm_args.txt", "@" + str(validation.RUN_ARGS), "nogui"],
                cwd=ROOT, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", env=environment, start_new_session=True,
            )
            validation.wait_for(process, log_path, r"Done \(", timeout=120)
            entry = {"地址": f"127.0.0.1:{port}", "专服进程": process.pid, "命令队列": str(queue), "日志": str(log_path), "最长运行秒数": 1200}
            ready.write_text(json.dumps(entry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(entry, ensure_ascii=False), flush=True)
            consumed = 0
            while process.poll() is None and time.monotonic() - started < 1200:
                if queue.stat().st_size > 131072:
                    raise ValueError("命令队列超过本地验证上限")
                commands = queue.read_text(encoding="utf-8").splitlines()
                if len(commands) < consumed:
                    raise ValueError("命令队列被截断")
                for command in commands[consumed:]:
                    if not command or len(command) > 2048:
                        raise ValueError("空命令或命令过长")
                    validation.send(process, [command])
                    report["命令"].append(command)
                    consumed += 1
                    if command == "stop":
                        process.wait(timeout=40)
                        break
                time.sleep(0.25)
            if process.poll() is None:
                report["停止原因"] = "达到本地验证最长运行时间"
                validation.send(process, ["save-all flush", "stop"])
                process.wait(timeout=40)
            report["退出码"] = process.returncode
            report["结果"] = "专服已正常停止" if process.returncode == 0 else "专服异常退出"
    except BaseException as error:
        report["结果"] = "失败"
        report["错误"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        if process is not None:
            validation.stop_process(process)
        if log_path.is_file():
            report["日志 SHA-256"] = validation.sha256(log_path)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
