#!/usr/bin/env python3
"""生成并校验 P5 发行整改中的确定性原创模型、方块状态和战利品 JSON。

本工具不读取旧 JSON 来作为输入。资源路径、注册 ID、原版模型父类和两个已
存在的动态谓词是兼容约束；其余 JSON 由下面明确的中性模板建立。这样既保留
存档/联机路径，也不会把历史模型的文字、图案或结构当作可再发行来源。
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "mod/src/main/resources"
MANIFEST = ROOT / "docs/ASSET_MANIFEST.md"
MODEL_ROOT = RESOURCE_ROOT / "assets/blindboxchallenge/models"
PROTECTED_MODELS = {
    "assets/blindboxchallenge/models/block/abstract_white_figurine.json",
    "assets/blindboxchallenge/models/block/floor_art_panel.json",
    "assets/blindboxchallenge/models/block/neutral_trophy.json",
    "assets/blindboxchallenge/models/item/abstract_white_figurine.json",
    "assets/blindboxchallenge/models/item/floor_art_panel.json",
    "assets/blindboxchallenge/models/item/neutral_trophy.json",
}
HISTORIC_BLOCKSTATES = (
    "assets/blindboxchallenge/blockstates/anywhere_door.json",
    "assets/blindboxchallenge/blockstates/bml_cheer_stick.json",
    "assets/blindboxchallenge/blockstates/bml_cheer_stick_wall.json",
    "assets/blindboxchallenge/blockstates/diamond_pillow.json",
    "assets/blindboxchallenge/blockstates/glow_stick.json",
    "assets/blindboxchallenge/blockstates/glow_stick_wall.json",
    "assets/blindboxchallenge/blockstates/music_box.json",
    "assets/blindboxchallenge/blockstates/safety_landing.json",
    "assets/blindboxchallenge/blockstates/stone_pillow.json",
)
HISTORIC_LOOT = (
    "data/blindboxchallenge/loot_tables/blocks/anywhere_door.json",
    "data/blindboxchallenge/loot_tables/blocks/bml_cheer_stick.json",
    "data/blindboxchallenge/loot_tables/blocks/bml_cheer_stick_wall.json",
    "data/blindboxchallenge/loot_tables/blocks/diamond_pillow.json",
    "data/blindboxchallenge/loot_tables/blocks/glow_stick.json",
    "data/blindboxchallenge/loot_tables/blocks/glow_stick_wall.json",
    "data/blindboxchallenge/loot_tables/blocks/music_box.json",
    "data/blindboxchallenge/loot_tables/blocks/safety_landing.json",
    "data/blindboxchallenge/loot_tables/blocks/stone_pillow.json",
)
BLOCK_ITEM_MODELS = {
    "anywhere_door",
    "bml_cheer_stick",
    "diamond_pillow",
    "glow_stick",
    "music_box",
    "safety_landing",
    "stone_pillow",
}
TARGETS = tuple(
    sorted(
        path.relative_to(RESOURCE_ROOT).as_posix()
        for path in MODEL_ROOT.rglob("*.json")
        if path.relative_to(RESOURCE_ROOT).as_posix() not in PROTECTED_MODELS
    )
) + HISTORIC_BLOCKSTATES + HISTORIC_LOOT


def face(texture: str) -> dict[str, str]:
    return {"texture": texture}


def cuboid(from_: list[int], to: list[int], texture: str) -> dict[str, object]:
    return {
        "from": from_,
        "to": to,
        "faces": {side: face(texture) for side in ("down", "up", "north", "south", "west", "east")},
    }


def block_model(identifier: str) -> dict[str, object]:
    texture = f"blindboxchallenge:block/{identifier}"
    if identifier in {"bml_cheer_stick_off", "bml_cheer_stick_on", "glow_stick"}:
        return {"parent": "minecraft:block/torch", "textures": {"torch": texture}}
    if identifier in {"bml_cheer_stick_wall_off", "bml_cheer_stick_wall_on"}:
        texture = f"blindboxchallenge:block/bml_cheer_stick_{identifier.rsplit('_', 1)[1]}"
        return {"parent": "minecraft:block/wall_torch", "textures": {"torch": texture}}
    if identifier == "glow_stick_wall":
        texture = "blindboxchallenge:block/glow_stick"
        return {"parent": "minecraft:block/wall_torch", "textures": {"torch": texture}}
    if identifier in {"music_box", "safety_landing"}:
        return {"parent": "minecraft:block/cube_all", "textures": {"all": texture}}
    if identifier == "anywhere_door":
        return {
            "ambientocclusion": False,
            "textures": {"all": texture},
            "elements": [cuboid([1, 0, 6], [15, 16, 10], "#all")],
        }
    if identifier in {"diamond_pillow", "stone_pillow"}:
        return {
            "ambientocclusion": False,
            "textures": {"fabric": texture},
            "elements": [cuboid([1, 0, 1], [15, 7, 15], "#fabric"), cuboid([2, 7, 2], [14, 8, 14], "#fabric")],
        }
    raise ValueError(f"未知方块模型：{identifier}")


def item_model(identifier: str) -> dict[str, object]:
    if identifier in BLOCK_ITEM_MODELS:
        parent = "bml_cheer_stick_off" if identifier == "bml_cheer_stick" else identifier
        return {"parent": f"blindboxchallenge:block/{parent}"}
    texture = identifier
    if identifier == "purple_toy_pickaxe_sword":
        texture = "purple_toy_pickaxe_sword_pickaxe"
    result: dict[str, object] = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"blindboxchallenge:item/{texture}"},
    }
    if identifier == "black_knight_telescopic_knife":
        result["overrides"] = [{"predicate": {"blindboxchallenge:extended": 1.0}, "model": "blindboxchallenge:item/black_knight_telescopic_knife_extended"}]
    if identifier == "purple_toy_pickaxe_sword":
        result["overrides"] = [{"predicate": {"blindboxchallenge:sword_form": 1.0}, "model": "blindboxchallenge:item/purple_toy_pickaxe_sword_sword"}]
    return result


def blockstate(identifier: str) -> dict[str, object]:
    model = lambda name: f"blindboxchallenge:block/{name}"
    if identifier == "bml_cheer_stick":
        return {"variants": {"lit=false": {"model": model("bml_cheer_stick_off")}, "lit=true": {"model": model("bml_cheer_stick_on")}}}
    if identifier == "bml_cheer_stick_wall":
        variants = {}
        rotations = {"east": 270, "north": 0, "south": 180, "west": 90}
        for facing, y in rotations.items():
            for lit, suffix in (("false", "off"), ("true", "on")):
                variants[f"facing={facing},lit={lit}"] = {"model": model(f"bml_cheer_stick_wall_{suffix}"), "y": y, "uvlock": True}
        return {"variants": variants}
    if identifier == "glow_stick_wall":
        rotations = {"east": 270, "north": 0, "south": 180, "west": 90}
        return {"variants": {f"facing={facing}": {"model": model("glow_stick_wall"), "y": y, "uvlock": True} for facing, y in rotations.items()}}
    return {"variants": {"": {"model": model(identifier)}}}


def loot(identifier: str) -> dict[str, object]:
    # 两个墙面技术方块没有独立 BlockItem；破坏后按原有玩法回收对应的站立物，不能生成
    # 未注册的 *_wall 物品 ID。
    drop_identifier = {
        "bml_cheer_stick_wall": "bml_cheer_stick",
        "glow_stick_wall": "glow_stick",
    }.get(identifier, identifier)
    return {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": f"blindboxchallenge:{drop_identifier}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    }


def render(relative: str) -> bytes:
    path = Path(relative)
    if "/models/block/" in f"/{relative}":
        value = block_model(path.stem)
    elif "/models/item/" in f"/{relative}":
        value = item_model(path.stem)
    elif "/blockstates/" in f"/{relative}":
        value = blockstate(path.stem)
    elif "/loot_tables/blocks/" in f"/{relative}":
        value = loot(path.stem)
    else:
        raise ValueError(f"未知原创模型目标：{relative}")
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def update_manifest() -> None:
    target_paths = {f"mod/src/main/resources/{relative}": relative for relative in TARGETS}
    updated = set()
    rows = []
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        if line.startswith("|`"):
            path = line.split("`", 2)[1]
            relative = target_paths.get(path)
            if relative is not None:
                checksum = hashlib.sha256((RESOURCE_ROOT / relative).read_bytes()).hexdigest()
                rows.append(
                    f"|`{path}`|`{checksum}`|项目内原创定义产物|"
                    "由明确模板、资源路径与兼容约束生成；不含原版图片|"
                    "项目方提供素材与需求背景，许可本项目使用、修改与发行；不外推第三方再授权|2026-08-07|"
                )
                updated.add(path)
                continue
        rows.append(line)
    missing = set(target_paths) - updated
    if missing:
        raise SystemExit("资源清单缺少目标行：" + ", ".join(sorted(missing)))
    MANIFEST.write_text("\n".join(rows) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只比较目标 JSON 是否和生成结果完全一致")
    parser.add_argument("--update-manifest", action="store_true", help="重算并更新本工具拥有的资源清单行")
    args = parser.parse_args()
    if args.check and args.update_manifest:
        raise SystemExit("--check 不修改文件，不能和 --update-manifest 同时使用")
    drift = []
    for relative in TARGETS:
        target = RESOURCE_ROOT / relative
        expected = render(relative)
        if args.check:
            if not target.is_file() or target.read_bytes() != expected:
                drift.append(relative)
            continue
        target.write_bytes(expected)
        print(target.relative_to(ROOT))
    if drift:
        raise SystemExit("原创模型与生成器不一致：" + ", ".join(drift))
    if args.update_manifest:
        update_manifest()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
