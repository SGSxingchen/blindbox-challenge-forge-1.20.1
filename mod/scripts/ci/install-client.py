#!/usr/bin/env python3
import shutil
import sys
from pathlib import Path

import minecraft_launcher_lib

MINECRAFT = "1.20.1"
FORGE = "47.4.22"
VERSION_ID = f"{MINECRAFT}-forge-{FORGE}"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("用法：install-client.py <Minecraft目录>")
    directory = Path(sys.argv[1]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    java = shutil.which("java")
    if java is None:
        raise RuntimeError("找不到 Java 17")
    forge = minecraft_launcher_lib.mod_loader.get_mod_loader("forge")
    installed = forge.install(MINECRAFT, directory, loader_version=FORGE, java=java)
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
