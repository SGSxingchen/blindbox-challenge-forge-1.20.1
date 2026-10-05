#!/usr/bin/env python3
"""生成并校验 P5 发行整改中的确定性原创模型、方块状态和战利品 JSON。

本工具不读取旧 JSON 来作为输入。资源路径、注册 ID、原版模型父类和既有
动态谓词是兼容约束；其余 JSON 由下面明确的中性模板建立。这样既保留
存档/联机路径，也不会把历史模型的文字、图案或结构当作可再发行来源。
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

try:
    from .original_model_json_payloads import AUTHORITATIVE_JSON_PAYLOADS, decode_authoritative_json
    from .transactional_resource_writer import transactional_write
except ImportError:  # 兼容直接执行脚本
    from original_model_json_payloads import AUTHORITATIVE_JSON_PAYLOADS, decode_authoritative_json
    from transactional_resource_writer import transactional_write


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "mod/src/main/resources"
MANIFEST = ROOT / "docs/ASSET_MANIFEST.md"
MODEL_ROOT = RESOURCE_ROOT / "assets/blindboxchallenge/models"
PROTECTED_MODELS = {
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
BLIND_BOX_STAGES = tuple(
    f"assets/blindboxchallenge/models/item/blind_box_open_{stage}.json" for stage in range(1, 5)
)
TARGETS = tuple(
    sorted({
        path.relative_to(RESOURCE_ROOT).as_posix()
        for path in MODEL_ROOT.rglob("*.json")
        if path.relative_to(RESOURCE_ROOT).as_posix() not in PROTECTED_MODELS
    } | set(BLIND_BOX_STAGES))
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
    imported = {
        "abstract_white_figurine", "floor_art_panel", "neutral_trophy",
        "anywhere_door", "diamond_pillow", "stone_pillow",
        "bml_cheer_stick_off", "bml_cheer_stick_on",
        "bml_cheer_stick_wall_off", "bml_cheer_stick_wall_on",
    }
    if identifier in imported:
        relative = f"assets/blindboxchallenge/models/block/{identifier}.json"
        return json.loads(decode_authoritative_json(relative).decode("utf-8"))
    texture = f"blindboxchallenge:block/{identifier}"
    if identifier == "glow_stick":
        return {"parent": "minecraft:block/torch", "textures": {"torch": texture}, "render_type": "minecraft:cutout"}
    if identifier == "glow_stick_wall":
        texture = "blindboxchallenge:block/glow_stick"
        return {"parent": "minecraft:block/wall_torch", "textures": {"torch": texture}, "render_type": "minecraft:cutout"}
    if identifier in {"music_box", "safety_landing"}:
        return {"parent": "minecraft:block/cube_all", "textures": {"all": texture}}
    raise ValueError(f"未知方块模型：{identifier}")


def blind_box_model(stage: int) -> dict[str, object]:
    """五档铰链盒盖；只引用原版材质，不增加图片或客户端渲染器。"""
    def piece(start, end, texture):
        element = cuboid(start, end, texture)
        for value in element["faces"].values():
            value["uv"] = [0, 0, 16, 16]
        return element

    # 底和四壁分开，开盖后能看到内腔；金色侧带与正面问号延续盲盒图标。
    elements = [piece([3, 2, 3], [13, 3, 13], "#inside")]
    for start, end in (([3, 3, 3], [13, 11, 4]), ([3, 3, 12], [13, 11, 13]),
                       ([3, 3, 4], [4, 11, 12]), ([12, 3, 4], [13, 11, 12])):
        elements.append(piece(start, end, "#body"))
    for start, end in (([2.9, 2, 7], [3.1, 11, 9]), ([12.9, 2, 7], [13.1, 11, 9]),
                       ([3, 2, 2.9], [13, 3, 3.1]), ([3, 2, 12.9], [13, 3, 13.1])):
        elements.append(piece(start, end, "#ribbon"))
    for x, y in ((6, 9), (7, 9), (8, 9), (9, 8), (8, 7), (7, 6), (7, 4)):
        elements.append(piece([x, y, 2.85], [x + 1, y + 1, 3], "#ribbon"))

    lid = [piece([2.5, 11, 2.5], [13.5, 13, 13.5], "#body"),
           piece([7, 13, 2.5], [9, 13.15, 13.5], "#ribbon"),
           piece([2.5, 13, 7], [13.5, 13.15, 9], "#ribbon")]
    for element in lid:
        # 原版元素只接受 0、±22.5、±45 度。后两档先将坐标转到 90 度，
        # 再以同一个铰链回转 22.5 度，避免使用非法的 67.5/90 度元素旋转。
        angle = stage * 22.5
        if stage >= 3:
            start, end = element["from"], element["to"]
            element["from"] = [start[0], 24 - end[2], start[1] + 2]
            element["to"] = [end[0], 24 - start[2], end[1] + 2]
            angle -= 90
        if angle:
            element["rotation"] = {"origin": [8, 11, 13], "axis": "x", "angle": angle}
    result = {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#body", "body": "minecraft:block/purple_concrete",
                     "ribbon": "minecraft:block/yellow_concrete", "inside": "minecraft:block/black_concrete"},
        "display": {
            "gui": {"rotation": [25, 135, 0], "translation": [0, -1, 0], "scale": [0.65, 0.65, 0.65]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.5, 0.5, 0.5]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "scale": [0.5, 0.5, 0.5]},
        },
        "elements": elements + lid,
    }
    if stage == 0:
        result["overrides"] = [
            {"predicate": {"blindboxchallenge:opening": threshold},
             "model": f"blindboxchallenge:item/blind_box_open_{index}"}
            for index, threshold in enumerate((0.15, 0.35, 0.6, 0.85), 1)
        ]
    return result


def item_model(identifier: str) -> dict[str, object]:
    if identifier == "blind_box" or identifier.startswith("blind_box_open_"):
        return blind_box_model(0 if identifier == "blind_box" else int(identifier.rsplit("_", 1)[1]))
    if identifier in {"music_box", "road_barrier_helmet"}:
        return {"parent": "builtin/entity"}
    if identifier in BLOCK_ITEM_MODELS:
        parent = "bml_cheer_stick_off" if identifier == "bml_cheer_stick" else identifier
        result = {"parent": f"blindboxchallenge:block/{parent}"}
        if identifier in {"stone_pillow", "diamond_pillow"}:
            result["display"] = {
                "firstperson_righthand": {"scale": [0.3, 0.3, 0.3]},
                "firstperson_lefthand": {"scale": [0.3, 0.3, 0.3]},
                "gui": {"rotation": [25, 135, 0], "scale": [0.5, 0.5, 0.5]},
            }
        return result
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


def render_template(relative: str) -> bytes:
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


def render(relative: str) -> bytes:
    if relative in AUTHORITATIVE_JSON_PAYLOADS:
        return decode_authoritative_json(relative)
    return render_template(relative)


def write_authoritative_json(resource_root: Path) -> None:
    transactional_write(
        resource_root,
        AUTHORITATIVE_JSON_PAYLOADS,
        decode_authoritative_json,
        validate_generated_json,
    )


def validate_generated_json(relative: str, data: bytes) -> None:
    try:
        json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"生成 JSON 无效：{relative}：{error}") from error


def write_all(resource_root: Path, *, renderer=render, replacer=None, restorer=None) -> None:
    options = {}
    if replacer is not None:
        options["replacer"] = replacer
    if restorer is not None:
        options["restorer"] = restorer
    transactional_write(resource_root, TARGETS, renderer, validate_generated_json, **options)


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


def main(argv=None, *, resource_root: Path = RESOURCE_ROOT) -> int:
    parser = argparse.ArgumentParser()
    operations = parser.add_mutually_exclusive_group()
    operations.add_argument("--check", action="store_true", help="只比较目标 JSON 是否和生成结果完全一致")
    operations.add_argument("--write-all", action="store_true", help="以事务方式重建全部目标 JSON")
    parser.add_argument("--update-manifest", action="store_true", help="重算并更新本工具拥有的资源清单行")
    args = parser.parse_args(argv)
    if args.update_manifest and not args.write_all:
        parser.error("--update-manifest 只能与 --write-all 一起使用")
    if not args.check and not args.write_all:
        parser.print_help()
        return 0
    if args.check:
        drift = [relative for relative in TARGETS if not (resource_root / relative).is_file() or (resource_root / relative).read_bytes().replace(b"\r\n", b"\n") != render(relative)]
        if drift:
            raise SystemExit("原创模型与生成器不一致：" + ", ".join(drift))
        return 0
    write_all(resource_root)
    for relative in TARGETS:
        print(resource_root / relative)
    if args.update_manifest:
        update_manifest()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
