#!/usr/bin/env python3
"""质量工作流的稳定静态契约。

这里只检查资源闭合、双端隔离和禁止绕过真实交互的安全边界；玩法正确性
由 Hosted Runner 的专服、恢复、单客户端和双客户端场景裁决。
"""

from __future__ import annotations

import hashlib
import json
import re
import runpy
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MOD = ROOT / "mod"
ASSETS = MOD / "src/main/resources/assets/blindboxchallenge"
DATA = MOD / "src/main/resources/data/blindboxchallenge"
P5_IDS = ("abstract_white_figurine", "floor_art_panel", "neutral_trophy")


def fail(message: str) -> None:
    raise SystemExit(f"质量静态契约失败：{message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def read(relative: str) -> str:
    return (MOD / relative).read_text(encoding="utf-8")


def canonical_resource_bytes(path: Path) -> bytes:
    data = path.read_bytes()
    return data if path.suffix.lower() == ".png" else data.replace(b"\r\n", b"\n")


def source_without_comments(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", source)


def require_absent(source: str, needles: tuple[str, ...], scope: str) -> None:
    code = source_without_comments(source)
    found = [needle for needle in needles if needle in code]
    require(not found, f"{scope} 出现禁止的绕过路径：{', '.join(found)}")


def check_p5_resources() -> None:
    blocks = read("src/main/java/cn/blindboxchallenge/registry/ModBlocks.java")
    items = read("src/main/java/cn/blindboxchallenge/registry/ModItems.java")
    for identifier in P5_IDS:
        require(re.search(rf'BLOCKS\.register\s*\(\s*"{identifier}"', blocks) is not None, f"缺少方块注册：{identifier}")
        require(re.search(rf'ITEMS\.register\s*\(\s*"{identifier}"', items) is not None, f"缺少方块物品注册：{identifier}")
        require(f"ModBlocks.{identifier.upper()}.get()" in items, f"方块物品未关联注册方块：{identifier}")

        state = json.loads((ASSETS / "blockstates" / f"{identifier}.json").read_text(encoding="utf-8"))
        require(state.get("variants", {}).get("", {}).get("model") == f"blindboxchallenge:block/{identifier}", f"方块状态未闭合：{identifier}")
        item_model = json.loads((ASSETS / "models/item" / f"{identifier}.json").read_text(encoding="utf-8"))
        require(item_model.get("parent") == f"blindboxchallenge:block/{identifier}", f"物品模型未闭合：{identifier}")
        block_model = json.loads((ASSETS / "models/block" / f"{identifier}.json").read_text(encoding="utf-8"))
        require(bool(block_model.get("elements")), f"方块模型无几何元素：{identifier}")
        texture = ASSETS / "textures/block" / f"{identifier}.png"
        png = texture.read_bytes()
        require(png.startswith(b"\x89PNG\r\n\x1a\n"), f"纹理不是 PNG：{identifier}")
        require(png[12:16] == b"IHDR" and int.from_bytes(png[16:20], "big") == 16 and int.from_bytes(png[20:24], "big") == 16, f"纹理尺寸错误：{identifier}")
        require((DATA / "loot_tables/blocks" / f"{identifier}.json").is_file(), f"缺少战利品表：{identifier}")
        for language in ("en_us", "zh_cn"):
            values = json.loads((ASSETS / "lang" / f"{language}.json").read_text(encoding="utf-8"))
            require(bool(values.get(f"block.blindboxchallenge.{identifier}")), f"缺少方块双语：{language}/{identifier}")
            require(bool(values.get(f"item.blindboxchallenge.{identifier}")), f"缺少物品双语：{language}/{identifier}")


def check_resource_manifest() -> None:
    forbidden = {".jpg", ".jpeg", ".webp", ".psd", ".zip", ".mp3", ".ogg", ".wav"}
    forbidden_files = [path.relative_to(MOD).as_posix() for path in (MOD / "src/main/resources").rglob("*") if path.is_file() and path.suffix.lower() in forbidden]
    require(not forbidden_files, f"正式资源含禁止格式：{', '.join(forbidden_files)}")

    manifest = (ROOT / "docs/ASSET_MANIFEST.md").read_text(encoding="utf-8")
    rows = re.findall(r"\|`(mod/src/main/resources/[^`]+)`\|`([0-9a-f]{64})`\|", manifest)
    recorded = dict(rows)
    actual = {path.relative_to(ROOT).as_posix(): path for path in (MOD / "src/main/resources").rglob("*") if path.is_file()}
    require(len(rows) == len(recorded) == len(actual), "资源清单存在重复、遗漏或数量不一致")
    require(set(recorded) == set(actual), "资源清单路径与正式资源不一致")
    for path, resource in actual.items():
        require(recorded[path] == hashlib.sha256(canonical_resource_bytes(resource)).hexdigest(), f"资源哈希不一致：{path}")

    require("项目内原创重绘" in manifest and "项目方提供" in manifest, "资源清单缺少来源边界")
    require("原版图片仅作需求输入且不进入 Release" in manifest, "资源清单缺少原版图片排除边界")
    require("待权利人/法务确认" not in manifest and "Release 阻塞" not in manifest, "资源清单仍含已撤销的阻塞结论")

    generator = ROOT / "tools/generate_original_textures.py"
    targets = runpy.run_path(str(generator))["TARGETS"]
    require(len(targets) == 68, "原创重绘目标数量不是 68")
    subprocess.run([sys.executable, str(generator), "--check"], check=True)
    for target in targets:
        row = next((line for line in manifest.splitlines() if line.startswith(f"|`mod/src/main/resources/{target}`|")), "")
        require("项目内原创重绘" in row and "原版图片仅作需求输入且不进入 Release" in row, f"原创重绘清单行错误：{target}")


def check_original_resource_definitions() -> None:
    """校验完整原创资源链的可复现输出与稳定引用闭合，不约束实现排版或文案顺序。"""
    model_generator = ROOT / "tools/generate_original_models.py"
    metadata_generator = ROOT / "tools/generate_original_metadata.py"
    subprocess.run([sys.executable, str(model_generator), "--check"], check=True)
    subprocess.run([sys.executable, str(metadata_generator), "--check"], check=True)

    model_root = ASSETS / "models"
    for path in model_root.rglob("*.json"):
        model = json.loads(path.read_text(encoding="utf-8"))
        for texture in model.get("textures", {}).values():
            if not isinstance(texture, str) or not texture.startswith("blindboxchallenge:"):
                continue
            namespace_path = texture.split(":", 1)[1]
            require((ASSETS / "textures" / f"{namespace_path}.png").is_file(), f"模型纹理引用缺失：{path.relative_to(MOD)} → {texture}")
        parent = model.get("parent")
        if isinstance(parent, str) and parent.startswith("blindboxchallenge:"):
            namespace_path = parent.split(":", 1)[1]
            require((model_root / f"{namespace_path}.json").is_file(), f"模型父引用缺失：{path.relative_to(MOD)} → {parent}")

    for path in (ASSETS / "blockstates").glob("*.json"):
        for variant in json.loads(path.read_text(encoding="utf-8")).get("variants", {}).values():
            model = variant.get("model") if isinstance(variant, dict) else None
            if isinstance(model, str) and model.startswith("blindboxchallenge:"):
                namespace_path = model.split(":", 1)[1]
                require((model_root / f"{namespace_path}.json").is_file(), f"方块状态模型引用缺失：{path.relative_to(MOD)} → {model}")

    registered_items = set(re.findall(r'ITEMS\.register\s*\(\s*"([a-z0-9_]+)"', read("src/main/java/cn/blindboxchallenge/registry/ModItems.java")))
    for path in (DATA / "loot_tables/blocks").glob("*.json"):
        for pool in json.loads(path.read_text(encoding="utf-8")).get("pools", []):
            for entry in pool.get("entries", []):
                identifier = entry.get("name", "")
                if isinstance(identifier, str) and identifier.startswith("blindboxchallenge:"):
                    require(identifier.split(":", 1)[1] in registered_items, f"战利品引用未注册物品：{path.relative_to(MOD)} → {identifier}")

    zh = json.loads((ASSETS / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((ASSETS / "lang/en_us.json").read_text(encoding="utf-8"))
    require(set(zh) == set(en) and all(zh.values()) and all(en.values()), "双语键集合不一致或存在空文案")


def check_network_and_isolation() -> None:
    network = read("src/main/java/cn/blindboxchallenge/network/ModNetwork.java")
    directions = {
        "PLAY_TO_SERVER": ("CommitPackingPacket", "RequestDoubleJumpPacket", "CommitLetterEditPacket", "CommitDeathNotePacket", "CommitMusicBoxUrlPacket"),
        "PLAY_TO_CLIENT": ("SyncPlayerAbilityPacket", "ShowLetterPacket", "PlayMusicBoxPacket"),
    }
    for direction, packets in directions.items():
        for packet in packets:
            pattern = rf"{packet}\.class,.*?Optional\.of\(NetworkDirection\.{direction}\)"
            require(re.search(pattern, network, flags=re.DOTALL) is not None, f"网络包方向错误：{packet}")

    require("mayInteract(" in read("src/main/java/cn/blindboxchallenge/service/DoorService.java"), "任意门缺少交互权限校验")
    require("mayInteract(" in read("src/main/java/cn/blindboxchallenge/service/MusicBoxService.java"), "八音盒缺少交互权限校验")
    require("mayInteract(" in read("src/main/java/cn/blindboxchallenge/network/CommitMusicBoxUrlPacket.java"), "八音盒提交包缺少交互权限校验")
    require(not list((MOD / "src/ciTest/java").rglob("*.java")) or not any(re.search(r"^package cn\.blindboxchallenge\.client(?:\.|;)", path.read_text(encoding="utf-8"), flags=re.MULTILINE) for path in (MOD / "src/ciTest/java").rglob("*.java")), "ciTest 与生产客户端包重叠")

    server_roots = ("block", "blockentity", "item", "service", "network", "registry", "menu", "event", "data", "config")
    leaks = []
    for name in server_roots:
        directory = MOD / "src/main/java/cn/blindboxchallenge" / name
        for path in directory.rglob("*.java"):
            if "net.minecraft.client" in path.read_text(encoding="utf-8") or "javazoom." in path.read_text(encoding="utf-8"):
                leaks.append(path.relative_to(MOD).as_posix())
    require(not leaks, f"服务端包泄漏客户端类：{', '.join(leaks)}")


def check_p5_safety() -> None:
    decor_server = read("src/ciTest/java/cn/blindboxchallenge/citest/P5DecorCiScenario.java")
    decor_client = read("src/ciTest/java/cn/blindboxchallenge/citest/CiClientP5DecorObservation.java")
    cache_server = read("src/ciTest/java/cn/blindboxchallenge/citest/P5MusicCacheCiScenario.java")
    cache_client = read("src/ciTest/java/cn/blindboxchallenge/citest/client/audio/CiClientP5MusicCacheObservation.java")

    require_absent(decor_server, ("setBlock(target", ".destroyBlock(", ".removeBlock(", ".useOn(", "Block.dropResources", "setItemInHand("), "P5 装饰服务端探针")
    require_absent(decor_client, ("minecraft.gameMode", ".connection.send(", "Serverbound", ".destroyBlock(", ".removeBlock(", ".setBlock(", "Block.dropResources", "setItemInHand(", ".useOn("), "P5 装饰客户端探针")
    direct_audio = ("RemoteAudioDownload.fetch(", "ClientMusicService.play", "MusicBoxService.play", "PlayMusicBoxPacket", "getSoundManager().play", "RemoteMusicSoundInstance.prepare")
    require_absent(cache_server, direct_audio, "P5 缓存服务端探针")
    require_absent(cache_client, direct_audio, "P5 缓存客户端探针")

    pressure = MOD / "src/ciTest/resources/ci-audio/blindbox-ci-cache-pressure.ogg"
    data = pressure.read_bytes()
    require(data.startswith(b"OggS") and 13 * 1024 * 1024 < len(data) <= 16 * 1024 * 1024, "P5 压力 OGG 格式或大小错误")
    workflow_sources = [*ROOT.glob(".github/workflows/*.yml"), *((MOD / "scripts/ci").glob("**/*"))]
    text_suffixes = {".sh", ".py", ".yml", ".yaml"}
    offenders = [
        path.relative_to(ROOT).as_posix()
        for path in workflow_sources
        if path.is_file() and path.suffix in text_suffixes and "continue-on-error" in path.read_text(encoding="utf-8")
    ]
    require(not offenders, f"CI 含 continue-on-error：{', '.join(offenders)}")


def main() -> None:
    check_p5_resources()
    check_resource_manifest()
    check_original_resource_definitions()
    check_network_and_isolation()
    check_p5_safety()
    print("质量静态契约通过：资源闭合、双端隔离和反绕过安全边界均成立。")


if __name__ == "__main__":
    main()
