#!/usr/bin/env python3
"""生成并校验完整原创资源包中的双语文案与 Forge 元数据。

文字来自本文件中依据物品台账明确的原名称、交互约束和注册兼容键；生成时
不读取历史语言文件、照片或外部资料。它保留翻译键和格式参数，确保物品、
方块、实体、菜单与服务端消息使用一致的名称，且不改动注册标识和交互协议。
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
    "blind_box": "盲盒", "packing_tool": "奖池打包道具", "letter": "信件", "death_note": "死亡笔记",
    "clockwork_chicken": "发条小黄鸡", "music_box": "木质手工八音盒", "anywhere_door": "任意门", "safety_landing": "安全落点",
    "black_knight_telescopic_knife": "黑武士伸缩刀", "purple_toy_pickaxe_sword": "紫色玩具钻石镐 / 剑", "adrenaline": "盐酸肾上腺素",
    "rat_jerky_totem": "奶龙老鼠干", "long_screwdriver": "一米长螺丝刀", "pickaxe_hoe": "镐锄一体", "lighter": "打火机",
    "bath_bucket": "70cm 泡澡桶", "glow_stick": "荧光棒", "bml_cheer_stick": "BML 应援棒", "paper_cup": "纸杯",
    "truffle_ham_cracker": "黑松露火腿苏打饼干", "potato_snack": "呀土豆零食", "ration_pack": "军粮包", "sun_candy": "太阳糖",
    "fairy_wand": "仙女棒", "toy_car": "风火轮玩具车", "million_pound_note": "百万英镑", "math_exam_paper": "小升初模拟卷数学品",
    "wang_lixin_badge": "王立新徽章", "flowing_black_flag": "流动黑旗", "shark_dagger_pillow": "鲨匕抱枕",
    "white_rabbit_candy": "大白兔奶糖", "deep_sea_fish": "深海小鱼", "ham_sausage": "火腿肠", "quail_egg": "鹌鹑蛋",
    "green_soy_milk": "哈基米南北绿豆浆", "beef_bites": "牛肉粒一包", "oil_chestnut": "油板栗", "wind_blown_cake": "风吹饼",
    "sweet_sour_turkey_noodles": "糖醋火鸡面", "sesame_rice_noodles": "麻酱米线", "potato_chips": "呀土豆",
    "black_truffle_ham_cracker": "黑松露火腿苏打饼干", "magic_crispy_noodles": "魔法士干脆面", "kazoo": "卡祖笛", "nail_art": "美甲",
    "pink_butterfly_wings": "粉色蝴蝶翅膀", "toy_knife": "玩具小刀", "chainsaw_sword": "战锤40k 链锯剑 泰拉之牙", "eggy_eye_mask": "蛋仔眼罩",
    "wenxu_standee": "文绪立牌", "cat_doll": "猫玩偶", "face_mask": "口罩", "vodka": "伏特加", "headphones": "头戴式耳机",
    "safety_exit_sign_shield": "安全出口牌子盾牌", "decision_coin": "决策币", "birthday_candle": "生日蜡烛", "rainbow_hoop": "彩虹圈",
    "yijin_manual": "易筋经", "road_barrier_helmet": "路障头饰", "efficient_pig_breeding": "高效养猪技术",
    "stone_pillow": "石墩子抱枕", "diamond_pillow": "钻石抱枕", "returning_scissors": "刺客伍六七剪刀",
    "abstract_white_figurine": "小白人", "floor_art_panel": "蒙娜奶龙画像", "neutral_trophy": "鹿管大赛特等奖奖杯",
}
ITEM_EN = {
    "blind_box": "Blind Box", "packing_tool": "Prize Pool Packer", "letter": "Letter", "death_note": "Death Note",
    "clockwork_chicken": "Clockwork Chick", "music_box": "Handcrafted Wooden Music Box", "anywhere_door": "Anywhere Door", "safety_landing": "Safety Landing",
    "black_knight_telescopic_knife": "Black Knight Telescopic Knife", "purple_toy_pickaxe_sword": "Purple Toy Diamond Pickaxe / Sword", "adrenaline": "Adrenaline",
    "rat_jerky_totem": "Rat Jerky Totem", "long_screwdriver": "One-Metre Screwdriver", "pickaxe_hoe": "Pickaxe Hoe", "lighter": "Lighter",
    "bath_bucket": "70 cm Bath Bucket", "glow_stick": "Glow Stick", "bml_cheer_stick": "BML Cheer Stick", "paper_cup": "Paper Cup",
    "truffle_ham_cracker": "Truffle Ham Cracker", "potato_snack": "Potato Snack", "ration_pack": "Ration Pack", "sun_candy": "Sun Candy",
    "fairy_wand": "Fairy Wand", "toy_car": "Toy Car", "million_pound_note": "Million Pound Note", "math_exam_paper": "Math Practice Paper",
    "wang_lixin_badge": "Wang Lixin Badge", "flowing_black_flag": "Flowing Black Flag", "shark_dagger_pillow": "Shark Dagger Pillow",
    "white_rabbit_candy": "Milk Candy", "deep_sea_fish": "Deep-Sea Fish Snack", "ham_sausage": "Ham Sausage", "quail_egg": "Quail Egg",
    "green_soy_milk": "Green Soy Milk", "beef_bites": "Beef Bites", "oil_chestnut": "Oil Chestnut", "wind_blown_cake": "Wind-blown Cake",
    "sweet_sour_turkey_noodles": "Sweet-Sour Turkey Noodles", "sesame_rice_noodles": "Sesame Rice Noodles", "potato_chips": "Potato Chips",
    "black_truffle_ham_cracker": "Black Truffle Ham Cracker", "magic_crispy_noodles": "Magic Crispy Noodles", "kazoo": "Kazoo", "nail_art": "Nail Art",
    "pink_butterfly_wings": "Pink Butterfly Wings", "toy_knife": "Toy Knife", "chainsaw_sword": "Warhammer 40k Chainsaw Sword - Teeth of Terra", "eggy_eye_mask": "Eggy Eye Mask",
    "wenxu_standee": "Wenxu Standee", "cat_doll": "Cat Doll", "face_mask": "Face Mask", "vodka": "Vodka", "headphones": "Headphones",
    "safety_exit_sign_shield": "Safety Exit Sign Shield", "decision_coin": "Decision Coin", "birthday_candle": "Birthday Candle", "rainbow_hoop": "Rainbow Hoop",
    "yijin_manual": "Muscle and Tendon Manual", "road_barrier_helmet": "Road Barrier Helmet", "efficient_pig_breeding": "Efficient Pig Breeding",
    "stone_pillow": "Stone Stool Pillow", "diamond_pillow": "Diamond Pillow", "returning_scissors": "Scissor Seven Scissors",
    "abstract_white_figurine": "Little White Figure", "floor_art_panel": "Mona Nailong Portrait", "neutral_trophy": "Lu Guan Competition Grand Prize Trophy",
}
BLOCK_IDS = ("music_box", "anywhere_door", "safety_landing", "glow_stick", "bml_cheer_stick", "stone_pillow", "diamond_pillow", "abstract_white_figurine", "floor_art_panel", "neutral_trophy")
WALL_NAMES = {"zh": {"glow_stick_wall": "墙面荧光棒", "bml_cheer_stick_wall": "墙面 BML 应援棒"}, "en": {"glow_stick_wall": "Wall Glow Stick", "bml_cheer_stick_wall": "Wall BML Cheer Stick"}}

SYSTEM = {
    "zh": {
        "itemGroup.blindboxchallenge": "盲盒挑战",
        "menu.blindboxchallenge.packing": "奖池打包", "menu.blindboxchallenge.letter_edit": "编辑信件", "menu.blindboxchallenge.death_note": "死亡笔记", "menu.blindboxchallenge.music_box": "八音盒在线音频",
        "screen.blindboxchallenge.selection": "槽位:数量", "screen.blindboxchallenge.pack": "打包并生成盲盒", "screen.blindboxchallenge.packing_hint": "左键选整组或取消，右键减少一个", "screen.blindboxchallenge.help": "输入背包槽位:数量，例如 0:5, 12:1", "screen.blindboxchallenge.invalid_selection": "物品已变化或选择无效，请重新选择要打包的物品。",
        "screen.blindboxchallenge.letter_read": "信件", "screen.blindboxchallenge.letter_line": "第 %s 行", "screen.blindboxchallenge.letter_hint": "正文由服务器保存；最多 %s 个码点、%s 行。", "screen.blindboxchallenge.letter_too_long": "输入超过客户端安全上限。",
        "screen.blindboxchallenge.save": "保存", "screen.blindboxchallenge.music_box_url": "HTTPS 音频地址", "screen.blindboxchallenge.music_box_hint": "仅接受公开 HTTPS 的 OGG 或 MP3；播放仅发送给当前在线玩家。", "screen.blindboxchallenge.music_box_invalid_url": "请输入公开域名的 HTTPS 地址；不接受本机、IP 地址、账号信息或非标准端口。", "screen.blindboxchallenge.close": "关闭",
        "screen.blindboxchallenge.death_note_target": "在线玩家名", "screen.blindboxchallenge.death_note_hint": "服务端将在延迟后处理已确认的在线目标。", "screen.blindboxchallenge.death_note_invalid_target": "请输入 3 到 16 位的有效玩家名。", "screen.blindboxchallenge.confirm": "确认",
        "message.blindboxchallenge.death_note_invalid_target": "死亡笔记只接受有效的玩家名。", "message.blindboxchallenge.death_note_target_offline": "目标当前不在线，未建立死亡笔记。", "message.blindboxchallenge.death_note_scheduled": "已记录 %s，服务端将按延迟执行。", "message.blindboxchallenge.death_note_target_left": "目标在死亡笔记到期前离线，未执行伤害。", "message.blindboxchallenge.death_note_executed": "死亡笔记已对 %s 执行。",
        "message.blindboxchallenge.letter_invalid_data": "信件数据不安全，已拒绝读取。", "message.blindboxchallenge.door_selected": "已选择第一扇任意门；请潜行右键另一扇未配对的门。", "message.blindboxchallenge.door_same_rejected": "不能将任意门与自身配对，已取消选择。", "message.blindboxchallenge.door_first_invalid": "第一扇门已无效、未加载或已配对，已取消选择。", "message.blindboxchallenge.door_already_linked": "这扇任意门已配对。", "message.blindboxchallenge.door_safety_required": "两扇门各需要且只能需要一个相邻的安全落点。", "message.blindboxchallenge.door_linked": "任意门已双向配对。", "message.blindboxchallenge.music_box_download_failed": "八音盒音频下载或解码失败",
        "tooltip.blindboxchallenge.fluid_empty": "容器为空", "tooltip.blindboxchallenge.fluid_water": "已装水", "tooltip.blindboxchallenge.fluid_lava": "已装岩浆",
        "key.blindboxchallenge.double_jump": "二段跳", "key.categories.blindboxchallenge": "盲盒挑战生存",
    },
    "en": {
        "itemGroup.blindboxchallenge": "Blind Box Challenge",
        "menu.blindboxchallenge.packing": "Prize Pool Packer", "menu.blindboxchallenge.letter_edit": "Edit Letter", "menu.blindboxchallenge.death_note": "Death Note", "menu.blindboxchallenge.music_box": "Music Box Online Audio",
        "screen.blindboxchallenge.selection": "slot:count", "screen.blindboxchallenge.pack": "Pack and Create Blind Box", "screen.blindboxchallenge.packing_hint": "Left-click to select a stack or cancel; right-click to reduce by one", "screen.blindboxchallenge.help": "Enter inventory slot:count, e.g. 0:5, 12:1", "screen.blindboxchallenge.invalid_selection": "The items changed or the selection is invalid. Please select the items again.",
        "screen.blindboxchallenge.letter_read": "Letter", "screen.blindboxchallenge.letter_line": "Line %s", "screen.blindboxchallenge.letter_hint": "The server saves the body; limit: %s code points and %s lines.", "screen.blindboxchallenge.letter_too_long": "The input exceeds the client safety limit.",
        "screen.blindboxchallenge.save": "Save", "screen.blindboxchallenge.music_box_url": "HTTPS audio URL", "screen.blindboxchallenge.music_box_hint": "Only public HTTPS OGG or MP3 is allowed; playback reaches players currently online.", "screen.blindboxchallenge.music_box_invalid_url": "Enter a public HTTPS domain URL without credentials, an IP address, or a non-standard port.", "screen.blindboxchallenge.close": "Close",
        "screen.blindboxchallenge.death_note_target": "Online player name", "screen.blindboxchallenge.death_note_hint": "The server acts on the confirmed online target after its delay.", "screen.blindboxchallenge.death_note_invalid_target": "Enter a valid 3 to 16 character player name.", "screen.blindboxchallenge.confirm": "Confirm",
        "message.blindboxchallenge.death_note_invalid_target": "The Death Note only accepts a valid player name.", "message.blindboxchallenge.death_note_target_offline": "The target is offline; no Death Note entry was created.", "message.blindboxchallenge.death_note_scheduled": "%s was recorded; the server will act after its delay.", "message.blindboxchallenge.death_note_target_left": "The target left before the Death Note became due; no damage was dealt.", "message.blindboxchallenge.death_note_executed": "The Death Note was executed for %s.",
        "message.blindboxchallenge.letter_invalid_data": "The letter data is unsafe and was not opened.", "message.blindboxchallenge.door_selected": "First door selected; sneak-use another unlinked door.", "message.blindboxchallenge.door_same_rejected": "A door cannot link to itself; selection cancelled.", "message.blindboxchallenge.door_first_invalid": "The first door is invalid, unloaded, or linked; selection cancelled.", "message.blindboxchallenge.door_already_linked": "This Anywhere Door is already linked.", "message.blindboxchallenge.door_safety_required": "Each door needs exactly one adjacent Safety Landing block.", "message.blindboxchallenge.door_linked": "The Anywhere Doors are linked in both directions.", "message.blindboxchallenge.music_box_download_failed": "Music box audio download or decoding failed",
        "tooltip.blindboxchallenge.fluid_empty": "Empty container", "tooltip.blindboxchallenge.fluid_water": "Contains water", "tooltip.blindboxchallenge.fluid_lava": "Contains lava",
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
        "thrown_pillow": "投掷抱枕" if locale == "zh" else "Thrown Pillow", "pillow_seat": "抱枕座位" if locale == "zh" else "Pillow Seat",
    }
    values.update({f"entity.blindboxchallenge.{identifier}": name for identifier, name in entities.items()})
    values.update(SYSTEM[locale])
    if len(values) != 124:
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

[[dependencies.${mod_id}]]
modId="geckolib"
mandatory=true
versionRange="[4.8.4,5)"
ordering="AFTER"
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
    if relative == "META-INF/mods.toml":
        return mods_toml()
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
            if not {"forge", "minecraft", "geckolib"}.issubset(actual_dependencies):
                raise ValueError("mods.toml 缺少 Forge、Minecraft 或 GeckoLib 依赖")
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
                rows.append(f"|`{path}`|`{checksum}`|项目内原创定义产物|依据原物品台账定义的双语与 Forge 元数据；不含原版图片|项目方提供素材与需求背景，许可本项目使用、修改与发行；不外推第三方再授权|2026-10-03|")
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
        drift = [relative for relative in TARGETS if not (resource_root / relative).is_file() or (resource_root / relative).read_bytes().replace(b"\r\n", b"\n") != render(relative)]
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
