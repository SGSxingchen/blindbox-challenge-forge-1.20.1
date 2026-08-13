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
        return {"parent": "minecraft:block/torch", "textures": {"torch": texture}}
    if identifier == "glow_stick_wall":
        texture = "blindboxchallenge:block/glow_stick"
        return {"parent": "minecraft:block/wall_torch", "textures": {"torch": texture}}
    if identifier in {"music_box", "safety_landing"}:
        return {"parent": "minecraft:block/cube_all", "textures": {"all": texture}}
    raise ValueError(f"未知方块模型：{identifier}")


def item_model(identifier: str) -> dict[str, object]:
    if identifier in {"music_box", "road_barrier_helmet"}:
        return {"parent": "builtin/entity"}
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
