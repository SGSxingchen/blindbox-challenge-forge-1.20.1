#!/usr/bin/env python3
"""生成并校验完整原创资源包中的双语文案与 Forge 元数据。

文字只来自本文件中逐项定义的中性名称、交互约束和注册兼容键；不读取历史
语言文件、照片或外部资料。它保留翻译键/格式参数，以免改动服务端消息和 GUI
协议，但不把人物、赛事、包装或第三方作品名称重新写入公开显示文本。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path

try:
    from .original_metadata_payloads import AUTHORITATIVE_METADATA_PAYLOADS, decode_authoritative_metadata
    from .transactional_resource_writer import transactional_write
except ImportError:  # 兼容直接执行脚本
    from original_metadata_payloads import AUTHORITATIVE_METADATA_PAYLOADS, decode_authoritative_metadata
    from transactional_resource_writer import transactional_write


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "mod/src/main/resources"
MANIFEST = ROOT / "docs/ASSET_MANIFEST.md"
TARGETS = (
    "assets/blindboxchallenge/lang/zh_cn.json",
    "assets/blindboxchallenge/lang/en_us.json",
    "META-INF/mods.toml",
    "pack.mcmeta",
)

ITEM_ZH = {
    "blind_box": "惊喜盒", "packing_tool": "奖池封装器", "letter": "信纸", "death_note": "命运名册",
    "clockwork_chicken": "发条小鸡", "music_box": "木制八音盒", "anywhere_door": "联结门", "safety_landing": "安全基座",
    "black_knight_telescopic_knife": "伸缩训练刀", "purple_toy_pickaxe_sword": "紫晶玩具镐剑", "adrenaline": "活力药剂",
    "rat_jerky_totem": "守护护符", "long_screwdriver": "长柄螺丝刀", "pickaxe_hoe": "组合镐锄", "lighter": "点火器",
    "bath_bucket": "耐用水桶", "glow_stick": "冷光棒", "bml_cheer_stick": "双色照明棒", "paper_cup": "纸杯",
    "truffle_ham_cracker": "咸香薄脆", "potato_snack": "脆片零食", "ration_pack": "补给包", "sun_candy": "暖光糖",
    "fairy_wand": "微光棒", "toy_car": "玩具小车", "million_pound_note": "收藏纸钞", "math_exam_paper": "练习纸",
    "wang_lixin_badge": "纪念徽章", "flowing_black_flag": "深色旗帜", "shark_dagger_pillow": "软垫短刃",
    "white_rabbit_candy": "牛奶糖", "deep_sea_fish": "海味小食", "ham_sausage": "肉味肠", "quail_egg": "小型禽蛋",
    "green_soy_milk": "绿豆饮", "beef_bites": "肉粒小食", "oil_chestnut": "烘烤栗子", "wind_blown_cake": "轻酥饼",
    "sweet_sour_turkey_noodles": "酸甜面", "sesame_rice_noodles": "芝麻米线", "potato_chips": "薄切脆片",
    "black_truffle_ham_cracker": "香草薄脆", "magic_crispy_noodles": "香脆面", "kazoo": "小笛", "nail_art": "指甲饰品",
    "pink_butterfly_wings": "彩色飞翼", "toy_knife": "练习小刀", "chainsaw_sword": "齿刃剑", "eggy_eye_mask": "蛋壳眼罩",
    "wenxu_standee": "纸质立牌", "cat_doll": "布偶", "face_mask": "面罩", "vodka": "烈性饮品", "headphones": "头戴耳机",
    "safety_exit_sign_shield": "安全标记盾牌", "decision_coin": "选择硬币", "birthday_candle": "许愿蜡烛", "rainbow_hoop": "彩色弹环",
    "yijin_manual": "健体手册", "road_barrier_helmet": "路障头盔", "efficient_pig_breeding": "牧场手册",
    "stone_pillow": "石纹坐垫", "diamond_pillow": "晶面坐垫", "returning_scissors": "返航剪刀",
    "abstract_white_figurine": "抽象白色小摆件", "floor_art_panel": "风格化地面画板", "neutral_trophy": "中性纪念奖杯",
}
ITEM_EN = {
    "blind_box": "Surprise Box", "packing_tool": "Pool Packer", "letter": "Letter Sheet", "death_note": "Fate Ledger",
    "clockwork_chicken": "Clockwork Chick", "music_box": "Wooden Music Box", "anywhere_door": "Linked Door", "safety_landing": "Safety Base",
    "black_knight_telescopic_knife": "Telescopic Practice Knife", "purple_toy_pickaxe_sword": "Amethyst Toy Pickaxe Sword", "adrenaline": "Vitality Tonic",
    "rat_jerky_totem": "Guardian Charm", "long_screwdriver": "Long Screwdriver", "pickaxe_hoe": "Combined Pickaxe Hoe", "lighter": "Fire Starter",
    "bath_bucket": "Durable Water Bucket", "glow_stick": "Glow Stick", "bml_cheer_stick": "Dual Light Stick", "paper_cup": "Paper Cup",
    "truffle_ham_cracker": "Savory Cracker", "potato_snack": "Crisp Snack", "ration_pack": "Ration Pack", "sun_candy": "Warm Candy",
    "fairy_wand": "Glow Wand", "toy_car": "Toy Car", "million_pound_note": "Collector Note", "math_exam_paper": "Practice Sheet",
    "wang_lixin_badge": "Keepsake Badge", "flowing_black_flag": "Dark Banner", "shark_dagger_pillow": "Cushioned Dagger",
    "white_rabbit_candy": "Milk Candy", "deep_sea_fish": "Sea Snack", "ham_sausage": "Savory Sausage", "quail_egg": "Small Bird Egg",
    "green_soy_milk": "Mung Bean Drink", "beef_bites": "Meat Bites", "oil_chestnut": "Roasted Chestnut", "wind_blown_cake": "Crisp Cake",
    "sweet_sour_turkey_noodles": "Sweet-Sour Noodles", "sesame_rice_noodles": "Sesame Rice Noodles", "potato_chips": "Thin Crisps",
    "black_truffle_ham_cracker": "Herb Cracker", "magic_crispy_noodles": "Crispy Noodles", "kazoo": "Pocket Kazoo", "nail_art": "Nail Ornament",
    "pink_butterfly_wings": "Colorful Wings", "toy_knife": "Practice Knife", "chainsaw_sword": "Toothed Sword", "eggy_eye_mask": "Shell Eye Mask",
    "wenxu_standee": "Paper Standee", "cat_doll": "Plush Doll", "face_mask": "Face Mask", "vodka": "Strong Drink", "headphones": "Headphones",
    "safety_exit_sign_shield": "Safety Mark Shield", "decision_coin": "Choice Coin", "birthday_candle": "Wish Candle", "rainbow_hoop": "Color Spring Ring",
    "yijin_manual": "Fitness Manual", "road_barrier_helmet": "Barrier Helmet", "efficient_pig_breeding": "Ranch Manual",
    "stone_pillow": "Stone Cushion", "diamond_pillow": "Crystal Cushion", "returning_scissors": "Returning Scissors",
    "abstract_white_figurine": "Abstract White Figurine", "floor_art_panel": "Stylized Floor Panel", "neutral_trophy": "Neutral Keepsake Trophy",
}
BLOCK_IDS = ("music_box", "anywhere_door", "safety_landing", "glow_stick", "bml_cheer_stick", "stone_pillow", "diamond_pillow", "abstract_white_figurine", "floor_art_panel", "neutral_trophy")
WALL_NAMES = {"zh": {"glow_stick_wall": "墙面冷光棒", "bml_cheer_stick_wall": "墙面双色照明棒"}, "en": {"glow_stick_wall": "Wall Glow Stick", "bml_cheer_stick_wall": "Wall Dual Light Stick"}}

SYSTEM = {
    "zh": {
        "menu.blindboxchallenge.packing": "奖池封装", "menu.blindboxchallenge.letter_edit": "编辑信纸", "menu.blindboxchallenge.death_note": "命运名册", "menu.blindboxchallenge.music_box": "八音盒在线音频",
        "screen.blindboxchallenge.selection": "槽位:数量", "screen.blindboxchallenge.pack": "封装并生成惊喜盒", "screen.blindboxchallenge.help": "输入背包槽位:数量，例如 0:5、12:1", "screen.blindboxchallenge.invalid_selection": "请输入有效且不重复的槽位:数量。",
        "screen.blindboxchallenge.letter_read": "信纸", "screen.blindboxchallenge.letter_line": "第 %s 行", "screen.blindboxchallenge.letter_hint": "正文由服务器保存；最多 %s 个码点、%s 行。", "screen.blindboxchallenge.letter_too_long": "输入超过客户端安全上限。",
        "screen.blindboxchallenge.save": "保存", "screen.blindboxchallenge.music_box_url": "HTTPS 音频地址", "screen.blindboxchallenge.music_box_hint": "只接受公开 HTTPS 的 OGG 或 MP3；仅当前在线玩家接收一次播放。", "screen.blindboxchallenge.music_box_invalid_url": "请输入以 https:// 开头的地址", "screen.blindboxchallenge.close": "关闭",
        "screen.blindboxchallenge.death_note_target": "在线玩家名", "screen.blindboxchallenge.death_note_hint": "服务器将在延迟后处理已确认的在线目标。", "screen.blindboxchallenge.death_note_invalid_target": "请输入 3 到 16 位的有效玩家名。", "screen.blindboxchallenge.confirm": "确认",
        "message.blindboxchallenge.death_note_invalid_target": "名册只接受有效的玩家名。", "message.blindboxchallenge.death_note_target_offline": "目标当前不在线，未建立记录。", "message.blindboxchallenge.death_note_scheduled": "已记录 %s，服务器将按延迟执行。", "message.blindboxchallenge.death_note_target_left": "目标在记录到期前离线，未执行伤害。", "message.blindboxchallenge.death_note_executed": "已对 %s 执行记录效果。",
        "message.blindboxchallenge.letter_invalid_data": "信纸数据不安全，已拒绝读取。", "message.blindboxchallenge.door_selected": "已选择第一扇联结门；请潜行右键另一扇未配对的门。", "message.blindboxchallenge.door_same_rejected": "联结门不能与自身配对，已取消选择。", "message.blindboxchallenge.door_first_invalid": "第一扇门无效、未加载或已配对，已取消选择。", "message.blindboxchallenge.door_already_linked": "这扇联结门已配对。", "message.blindboxchallenge.door_safety_required": "两扇门各需要且只能需要一个相邻安全基座。", "message.blindboxchallenge.door_linked": "联结门已双向配对。", "message.blindboxchallenge.music_box_download_failed": "八音盒音频下载或解码失败",
        "key.blindboxchallenge.double_jump": "二段跳", "key.categories.blindboxchallenge": "盲盒挑战生存",
    },
    "en": {
        "menu.blindboxchallenge.packing": "Pool Packing", "menu.blindboxchallenge.letter_edit": "Edit Letter Sheet", "menu.blindboxchallenge.death_note": "Fate Ledger", "menu.blindboxchallenge.music_box": "Music Box Online Audio",
        "screen.blindboxchallenge.selection": "slot:count", "screen.blindboxchallenge.pack": "Pack and Create Surprise Box", "screen.blindboxchallenge.help": "Enter inventory slot:count, for example 0:5, 12:1", "screen.blindboxchallenge.invalid_selection": "Enter valid, non-duplicate slot:count pairs.",
        "screen.blindboxchallenge.letter_read": "Letter Sheet", "screen.blindboxchallenge.letter_line": "Line %s", "screen.blindboxchallenge.letter_hint": "The server saves the body; limit: %s code points and %s lines.", "screen.blindboxchallenge.letter_too_long": "The input exceeds the client safety limit.",
        "screen.blindboxchallenge.save": "Save", "screen.blindboxchallenge.music_box_url": "HTTPS audio URL", "screen.blindboxchallenge.music_box_hint": "Only public HTTPS OGG or MP3 is allowed; players online now receive one playback.", "screen.blindboxchallenge.music_box_invalid_url": "Enter an address beginning with https://", "screen.blindboxchallenge.close": "Close",
        "screen.blindboxchallenge.death_note_target": "Online player name", "screen.blindboxchallenge.death_note_hint": "The server processes the confirmed online target after its delay.", "screen.blindboxchallenge.death_note_invalid_target": "Enter a valid 3 to 16 character player name.", "screen.blindboxchallenge.confirm": "Confirm",
        "message.blindboxchallenge.death_note_invalid_target": "The ledger only accepts a valid player name.", "message.blindboxchallenge.death_note_target_offline": "The target is offline; no entry was created.", "message.blindboxchallenge.death_note_scheduled": "%s was recorded; the server will act after its delay.", "message.blindboxchallenge.death_note_target_left": "The target left before the record became due; no damage was dealt.", "message.blindboxchallenge.death_note_executed": "The record effect was applied to %s.",
        "message.blindboxchallenge.letter_invalid_data": "The letter data is unsafe and was not opened.", "message.blindboxchallenge.door_selected": "First linked door selected; sneak-use another unlinked door.", "message.blindboxchallenge.door_same_rejected": "A linked door cannot pair with itself; selection cancelled.", "message.blindboxchallenge.door_first_invalid": "The first door is invalid, unloaded, or linked; selection cancelled.", "message.blindboxchallenge.door_already_linked": "This linked door is already paired.", "message.blindboxchallenge.door_safety_required": "Each door needs exactly one adjacent Safety Base.", "message.blindboxchallenge.door_linked": "The linked doors are paired in both directions.", "message.blindboxchallenge.music_box_download_failed": "Music box audio download or decoding failed",
        "key.blindboxchallenge.double_jump": "Double Jump", "key.categories.blindboxchallenge": "Blind Box Challenge",
    },
}


def language(locale: str) -> bytes:
    names = ITEM_ZH if locale == "zh" else ITEM_EN
    values: dict[str, str] = {f"item.blindboxchallenge.{identifier}": name for identifier, name in names.items()}
    for identifier in BLOCK_IDS:
        values[f"block.blindboxchallenge.{identifier}"] = names[identifier]
    for identifier, name in WALL_NAMES[locale].items():
        values[f"block.blindboxchallenge.{identifier}"] = name
    entities = {
        "clockwork_chicken": names["clockwork_chicken"], "returning_scissors": names["returning_scissors"],
        "thrown_pillow": "投掷坐垫" if locale == "zh" else "Thrown Cushion", "pillow_seat": "坐垫座位" if locale == "zh" else "Cushion Seat",
    }
    values.update({f"entity.blindboxchallenge.{identifier}": name for identifier, name in entities.items()})
    values.update(SYSTEM[locale])
    if len(values) != 119:
        raise ValueError(f"{locale} 翻译键数量异常：{len(values)}")
    return (json.dumps(dict(sorted(values.items())), ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def mods_toml() -> bytes:
    return """modLoader="javafml"
loaderVersion="${loader_version_range}"
license="All Rights Reserved"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''

[[dependencies.${mod_id}]]
modId="forge"
mandatory=true
versionRange="${forge_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
mandatory=true
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"
""".encode("utf-8")


def render_template(relative: str) -> bytes:
    if relative.endswith("zh_cn.json"):
        return language("zh")
    if relative.endswith("en_us.json"):
        return language("en")
    if relative == "META-INF/mods.toml":
        return mods_toml()
    if relative == "pack.mcmeta":
        return (json.dumps({"pack": {"pack_format": 15, "description": "盲盒挑战生存资源"}}, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    raise ValueError(relative)


def render(relative: str) -> bytes:
    if relative in AUTHORITATIVE_METADATA_PAYLOADS:
        return decode_authoritative_metadata(relative)
    return render_template(relative)


def write_authoritative_metadata(resource_root: Path) -> None:
    transactional_write(
        resource_root,
        AUTHORITATIVE_METADATA_PAYLOADS,
        decode_authoritative_metadata,
        validate_generated_metadata,
    )


def validate_generated_metadata(relative: str, data: bytes) -> None:
    try:
        text = data.decode("utf-8")
        if relative.endswith("/lang/zh_cn.json") or relative.endswith("/lang/en_us.json"):
            value = json.loads(text)
            if not isinstance(value, dict):
                raise ValueError("语言文件根必须是对象")
            if not value or not all(isinstance(key, str) and key and isinstance(item, str) and item for key, item in value.items()):
                raise ValueError("语言文件的键值必须全是非空字符串")
            expected = json.loads(decode_authoritative_metadata(relative))
            if set(value) != set(expected):
                raise ValueError("语言键与权威期望不一致")
        elif relative == "pack.mcmeta":
            value = json.loads(text)
            pack = value.get("pack") if isinstance(value, dict) else None
            if not isinstance(pack, dict):
                raise ValueError("pack.mcmeta 缺少 pack 对象")
            if type(pack.get("pack_format")) is not int or pack["pack_format"] <= 0 or not isinstance(pack.get("description"), str) or not pack["description"]:
                raise ValueError("pack.mcmeta 的 pack_format 或 description 类型错误")
        elif relative.endswith(".json"):
            json.loads(text)
        elif relative == "META-INF/mods.toml":
            # Forge 允许 Gradle 在 TOML 表名中展开 ${mod_id}；标准 TOML 解析器
            # 不认识该模板键，因此仅为语法校验替换这一个已知占位位置。
            value = tomllib.loads(text.replace("dependencies.${mod_id}", "dependencies.__forge_mod_id__"))
            for key in ("modLoader", "loaderVersion", "license"):
                if not isinstance(value.get(key), str) or not value[key]:
                    raise ValueError(f"mods.toml 的 {key} 必须是非空字符串")
            mods = value.get("mods")
            if not isinstance(mods, list) or not mods:
                raise ValueError("mods.toml 缺少非空 mods 列表")
            for mod in mods:
                if not isinstance(mod, dict) or not all(isinstance(mod.get(key), str) and mod[key] for key in ("modId", "version", "displayName")):
                    raise ValueError("mods.toml 的模组必要字段类型错误")
            if not any(mod["modId"] in {"${mod_id}", "blindboxchallenge"} for mod in mods):
                raise ValueError("mods.toml 缺少 blindboxchallenge 模组定义")
            dependency_groups = value.get("dependencies")
            if not isinstance(dependency_groups, dict):
                raise ValueError("mods.toml 的 dependencies 必须是对象")
            dependencies = dependency_groups.get("__forge_mod_id__")
            if not isinstance(dependencies, list):
                raise ValueError("mods.toml 缺少模组依赖列表")
            actual_dependencies = set()
            for dependency in dependencies:
                if not isinstance(dependency, dict) or not isinstance(dependency.get("modId"), str) or not dependency["modId"]:
                    raise ValueError("mods.toml 的依赖必要字段类型错误")
                if type(dependency.get("mandatory")) is not bool or not all(isinstance(dependency.get(key), str) and dependency[key] for key in ("versionRange", "ordering", "side")):
                    raise ValueError("mods.toml 的依赖属性类型错误或为空")
                actual_dependencies.add(dependency["modId"])
            if not {"forge", "minecraft"}.issubset(actual_dependencies):
                raise ValueError("mods.toml 缺少 Forge 或 Minecraft 依赖")
    except (UnicodeDecodeError, json.JSONDecodeError, tomllib.TOMLDecodeError, ValueError) as error:
        raise ValueError(f"生成元数据无效：{relative}：{error}") from None


def write_all(resource_root: Path, *, renderer=render, replacer=None, restorer=None) -> None:
    options = {}
    if replacer is not None:
        options["replacer"] = replacer
    if restorer is not None:
        options["restorer"] = restorer
    transactional_write(resource_root, TARGETS, renderer, validate_generated_metadata, **options)


def update_manifest() -> None:
    target_paths = {f"mod/src/main/resources/{relative}": relative for relative in TARGETS}
    updated, rows = set(), []
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        if line.startswith("|`"):
            path = line.split("`", 2)[1]
            relative = target_paths.get(path)
            if relative is not None:
                checksum = hashlib.sha256((RESOURCE_ROOT / relative).read_bytes()).hexdigest()
                rows.append(f"|`{path}`|`{checksum}`|项目内原创定义产物|本地定义的中性双语与 Forge 元数据；不含原版图片|项目方提供素材与需求背景，许可本项目使用、修改与发行；不外推第三方再授权|2026-08-07|")
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
    operations.add_argument("--check", action="store_true")
    operations.add_argument("--write-all", action="store_true")
    parser.add_argument("--update-manifest", action="store_true")
    args = parser.parse_args(argv)
    if args.update_manifest and not args.write_all:
        parser.error("--update-manifest 只能与 --write-all 一起使用")
    if not args.check and not args.write_all:
        parser.print_help()
        return 0
    if args.check:
        drift = [relative for relative in TARGETS if not (resource_root / relative).is_file() or (resource_root / relative).read_bytes() != render(relative)]
        if drift:
            raise SystemExit("原创元数据与生成器不一致：" + ", ".join(drift))
        return 0
    write_all(resource_root)
    for relative in TARGETS:
        print(resource_root / relative)
    if args.update_manifest:
        update_manifest()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
