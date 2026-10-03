#!/usr/bin/env python3
"""在指定隔离目录安装真实 Forge 客户端；不读取账户或修改现有安装。"""
import argparse
import hashlib
import json
import platform
import shutil
import subprocess
from pathlib import Path

import requests
import minecraft_launcher_lib

JAVA = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java"


def bounded_calls():
    request = requests.sessions.Session.request
    run = subprocess.run

    def with_timeout(self, method, url, **kwargs):
        kwargs.setdefault("timeout", (15, 60))
        return request(self, method, url, **kwargs)

    def run_with_timeout(*args, **kwargs):
        kwargs.setdefault("timeout", 240)
        return run(*args, **kwargs)

    requests.sessions.Session.request = with_timeout
    subprocess.run = run_with_timeout


def seed_assets(directory):
    # 只复制公开资源缓存，校验散列后再使用；不复制 profile、账户、世界或配置。
    source = Path.home() / ".minecraft/assets"
    index = source / "indexes/5.json"
    if not index.is_file():
        return
    data = json.loads(index.read_text(encoding="utf-8"))
    copied = 0
    for value in data["objects"].values():
        digest = value["hash"]
        original = source / "objects" / digest[:2] / digest
        target = directory / "assets/objects" / digest[:2] / digest
        if target.exists() or not original.is_file():
            continue
        if hashlib.sha1(original.read_bytes()).hexdigest() != digest:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        copied += 1
    print(f"复用经散列核验的公开资源 {copied} 个", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    bounded_calls()
    seed_assets(directory)
    installed = minecraft_launcher_lib.mod_loader.get_mod_loader("forge").install(
        "1.20.1", str(directory), loader_version="47.4.22", java=JAVA,
        callback={"setStatus": lambda value: print(value, flush=True)},
    )
    if installed != "1.20.1-forge-47.4.22":
        raise RuntimeError(f"安装版本异常：{installed}")
    if platform.machine() == "arm64":
        # 1.20.1 官方元数据同时提供 Intel/Apple Silicon 原生库。限定本次隔离安装的架构，
        # 防止启动库把两个 osx 分类同时加入路径并先加载 Intel 动态库。
        version = directory / "versions/1.20.1/1.20.1.json"
        data = json.loads(version.read_text(encoding="utf-8"))
        data["libraries"] = [lib for lib in data["libraries"]
                             if not (lib["name"].startswith("org.lwjgl:")
                                     and lib["name"].endswith(":natives-macos"))]
        version.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (directory / "options.txt").write_text(
        "onboardAccessibility:true\nskipMultiplayerWarning:true\nfullscreen:false\n"
        "enableVsync:false\nrenderDistance:4\nsimulationDistance:5\nnarrator:0\n"
        "lang:zh_cn\nmaxFps:60\nguiScale:2\npauseOnLostFocus:false\n",
        encoding="utf-8",
    )
    (directory / "local-installed-version.txt").write_text(installed + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
