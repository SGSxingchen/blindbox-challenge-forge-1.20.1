#!/usr/bin/env python3
import shutil
import sys
import time
from pathlib import Path

import minecraft_launcher_lib

MINECRAFT = "1.20.1"
FORGE = "47.4.22"
VERSION_ID = f"{MINECRAFT}-forge-{FORGE}"
MAX_INSTALL_ATTEMPTS = 3


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("用法：install-client.py <Minecraft目录>")
    directory = Path(sys.argv[1]).resolve()
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("找不到 Java 17")
    forge = minecraft_launcher_lib.mod_loader.get_mod_loader("forge")
    installed = None
    for attempt in range(1, MAX_INSTALL_ATTEMPTS + 1):
        # Forge 二进制补丁安装若在下载/解压中断会留下不完整客户端；每次都从空目录重试，
        # 最后一次仍直接抛出原始异常，绝不把安装失败降级为通过。
        shutil.rmtree(directory, ignore_errors=True)
        directory.mkdir(parents=True, exist_ok=True)
        try:
            installed = forge.install(MINECRAFT, directory, loader_version=FORGE, java=java)
            break
        except Exception:
            if attempt == MAX_INSTALL_ATTEMPTS:
                raise
            print(f"Forge 客户端安装第 {attempt} 次失败，清理后重试", file=sys.stderr, flush=True)
            time.sleep(attempt * 3)
    if installed != VERSION_ID:
        raise RuntimeError(f"Forge 客户端安装版本异常：{installed}")
    (directory / "options.txt").write_text(
        "onboardAccessibility:true\n"
        "skipMultiplayerWarning:true\n"
        "fullscreen:false\n"
        "enableVsync:false\n"
        "renderDistance:4\n"
        "simulationDistance:5\n"
        "narrator:0\n",
        encoding="utf-8",
    )
    (directory / "ci-installed-version.txt").write_text(installed + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
