#!/usr/bin/env python3
"""隔离验证正式 Jar 的专服加载、物品注册和奖池保存重启。"""

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
import time
import zipfile


from validation_runtime import gradle_cache, resolve_java


MOD = Path(__file__).resolve().parents[2]
BASE_ROOT = MOD / "build/local-validation-2026-10-04/server"
ROOT = BASE_ROOT
CACHE = None
JAVA = None
INSTALLER = ROOT / "forge-1.20.1-47.4.22-installer.jar"
RUN_ARGS = ROOT / "libraries/net/minecraftforge/forge/1.20.1-47.4.22/unix_args.txt"
JAR = MOD / "build/libs/blindboxchallenge-1.0.5-all.jar"
RESULT = ROOT / "验证结果.json"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stop_process(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def prepare_libraries():
    """只复制 SHA-1 与安装清单一致的既有缓存，缺项交给官方安装器。"""
    candidates = {}
    for path in CACHE.rglob("*"):
        if path.is_file() and path.suffix in {".jar", ".zip", ".txt"}:
            candidates.setdefault(path.name, []).append(path)
    copied = 0
    with zipfile.ZipFile(INSTALLER) as archive:
        for descriptor in ("install_profile.json", "version.json"):
            metadata = json.loads(archive.read(descriptor))
            for library in metadata.get("libraries", []):
                artifact = library.get("downloads", {}).get("artifact", {})
                relative = artifact.get("path")
                expected = artifact.get("sha1")
                if not relative or not expected:
                    continue
                target = ROOT / "libraries" / relative
                if target.is_file() and hashlib.sha1(target.read_bytes()).hexdigest() == expected:
                    continue
                for source in candidates.get(Path(relative).name, []):
                    if hashlib.sha1(source.read_bytes()).hexdigest() == expected:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, target)
                        copied += 1
                        break
    vanilla = CACHE / "forge_gradle/mcp_repo/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/joined/downloadServer/server.jar"
    if vanilla.is_file():
        target = ROOT / "libraries/net/minecraft/server/1.20.1/server-1.20.1.jar"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(vanilla, target)
    return copied


def install():
    if RUN_ARGS.is_file():
        return "复用当前隔离目录的已安装 Forge"
    copied = prepare_libraries()
    with (ROOT / "安装日志.txt").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            [str(JAVA), "-jar", str(INSTALLER), "--installServer"], cwd=ROOT,
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
        )
        try:
            code = process.wait(timeout=180)
        finally:
            stop_process(process)
    if code != 0 or not RUN_ARGS.is_file():
        raise RuntimeError("正式 Forge 安装失败，见安装日志.txt")
    return f"官方安装器安装完成，复用 {copied} 项校验一致的缓存"


def wait_for(process, log_path, pattern, timeout=20, start=0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        content = log_path.read_text(encoding="utf-8", errors="replace")
        if re.search(pattern, content[start:]):
            return content
        if process.poll() is not None:
            raise RuntimeError(f"专服提前退出，退出码 {process.returncode}，见 {log_path.name}")
        time.sleep(0.2)
    raise TimeoutError(f"专服等待超时：{pattern}，见 {log_path.name}")


def send(process, commands):
    process.stdin.write("\n".join(commands) + "\n")
    process.stdin.flush()


def run_server(number, commands, expected):
    log_path = ROOT / f"专服第{number}次启动.log"
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            [str(JAVA), "-Xms512m", "-Xmx2g", "@user_jvm_args.txt", "@" + str(RUN_ARGS), "nogui"],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", start_new_session=True,
        )
        try:
            content = wait_for(process, log_path, r"Done \(", timeout=120)
            checkpoint = len(content)
            send(process, commands)
            content = wait_for(process, log_path, expected, start=checkpoint, timeout=30)
            if number == 1:
                injected = len(re.findall("已注入确定性测试奖项：", content))
                if injected != len(ITEMS):
                    raise AssertionError(f"预期注册并注入 {len(ITEMS)} 项，实际 {injected} 项")
            checkpoint = len(content)
            send(process, ["save-all flush"])
            content = wait_for(process, log_path, r"Saved the game", start=checkpoint)
            send(process, ["stop"])
            code = process.wait(timeout=40)
            if code != 0:
                raise RuntimeError(f"专服关闭退出码 {code}")
        finally:
            stop_process(process)
    content = log_path.read_text(encoding="utf-8", errors="replace")
    fatal = re.findall(r"^.*(?:FATAL|NoClassDefFoundError|Exception in server tick|Crash report|crash-report).*$", content, re.M)
    if fatal:
        raise AssertionError("专服出现运行错误：" + "\n".join(fatal[:5]))
    if "Stopping server" not in content:
        raise AssertionError("没有正常停止证据")
    return {"日志": str(log_path), "退出码": code, "SHA-256": sha256(log_path), "异常级日志": re.findall(r"^.*\bERROR\b.*$", content, re.M)}


ITEMS = re.findall(r'ITEMS\.register\("([a-z0-9_]+)"', (MOD / "src/main/java/cn/blindboxchallenge/registry/ModItems.java").read_text(encoding="utf-8"))


def main():
    global ROOT, RUN_ARGS, RESULT, JAR, JAVA, CACHE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-dir", type=Path, default=BASE_ROOT, help="必须位于本地专服验证目录内的独立目录")
    parser.add_argument("--formal-jar", type=Path, default=JAR, help="本轮固定使用的正式 Jar")
    parser.add_argument("--java", type=Path, help="Java 17 可执行文件；默认 JAVA_HOME/bin/java 或 PATH")
    parser.add_argument("--gradle-cache", type=Path, default=gradle_cache(), help="只读复用的 Gradle caches 目录")
    arguments = parser.parse_args()
    JAVA = resolve_java(arguments.java)
    CACHE = arguments.gradle_cache.resolve()
    ROOT = arguments.server_dir.resolve()
    if ROOT != BASE_ROOT.resolve() and BASE_ROOT.resolve() not in ROOT.parents:
        parser.error("专服目录必须位于隔离的 server 验证目录内")
    JAR = arguments.formal_jar.resolve()
    RUN_ARGS = ROOT / "libraries/net/minecraftforge/forge/1.20.1-47.4.22/unix_args.txt"
    RESULT = ROOT / "验证结果.json"
    ROOT.mkdir(parents=True, exist_ok=True)
    if RESULT.is_file() or (ROOT / "临时世界").exists():
        raise RuntimeError("本轮验证证据或临时世界已经存在，禁止覆盖或混用，请另选隔离目录")
    if ROOT != BASE_ROOT:
        # 官方安装的库和 JVM 参数只读复用；配置、日志、模组与临时世界均在新的独立目录。
        for name in ("libraries", "user_jvm_args.txt"):
            if not (ROOT / name).exists():
                (ROOT / name).symlink_to(BASE_ROOT / name, target_is_directory=(name == "libraries"))
    report = {
        "日期": datetime.date.today().isoformat(), "环境": "本地隔离正式映射 Jar + Forge 47.4.22 + Java 17 专服",
        "范围": "专服加载、全部物品命令解析与奖池保存重启；没有 Minecraft 客户端参与", "正式Jar": str(JAR),
        "正式Jar SHA-256": sha256(JAR), "物品数": len(ITEMS), "结果": "未完成", "启动记录": [],
    }
    try:
        if len(ITEMS) != 67:
            raise AssertionError(f"物品注册清单数量变化：{len(ITEMS)}")
        report["安装"] = install()
        (ROOT / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (ROOT / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=临时世界\n"
            "level-type=minecraft:normal\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\n"
            "max-players=2\nmax-tick-time=60000\nallow-nether=false\nenable-status=false\n",
            encoding="utf-8",
        )
        (ROOT / "mods").mkdir(exist_ok=True)
        unexpected_mods = [path.name for path in (ROOT / "mods").glob("*.jar") if path.name != JAR.name]
        if unexpected_mods:
            raise RuntimeError("基础验证目录不得加入探针或其它模组：" + ", ".join(unexpected_mods))
        shutil.copy2(JAR, ROOT / "mods" / JAR.name)
        commands = ["list", "blindbox pool count"] + [f"blindbox pool inject blindboxchallenge:{item} 1" for item in ITEMS] + ["blindbox pool count"]
        report["启动记录"].append(run_server(1, commands, rf"盲盒奖池条目数：{len(ITEMS)}(?:\s|$)"))
        report["启动记录"].append(run_server(2, ["list", "blindbox pool count"], rf"盲盒奖池条目数：{len(ITEMS)}(?:\s|$)"))
        config = ROOT / "临时世界/serverconfig/blindboxchallenge-server.toml"
        snapshot = ROOT / "基础专服生成配置.toml"
        shutil.copy2(config, snapshot)
        report["专服生成配置"] = {"文件": str(snapshot), "SHA-256": sha256(snapshot)}
        report["结果"] = "通过"
        report["验证事实"] = ["两次正常启动并正常停止", "67 项物品均被正式 Jar 注册且可解析", "67 条奖池数据保存后重启保留", "嵌入 GeckoLib 与 JLayer 加载，没有专服客户端类加载崩溃"]
    except BaseException as error:
        report["结果"] = "失败"
        report["错误"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        RESULT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
