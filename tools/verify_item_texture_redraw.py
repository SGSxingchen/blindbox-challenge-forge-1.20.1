#!/usr/bin/env python3
"""校验物品贴图重绘清单，并记录正式贴图基线。"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from PIL import Image


仓库根目录 = Path(__file__).resolve().parents[1]
默认贴图目录 = 仓库根目录 / "mod/src/main/resources/assets/blindboxchallenge/textures/item"
默认清单路径 = Path(__file__).with_name("item_texture_redraw_manifest.json")
必需字段 = {"id", "texture", "reference_status", "subject", "must_show", "avoid", "palette"}
合法参考状态 = {"catalog_reference", "free_design"}
无参考设计编号 = {"blind_box", "packing_tool", "letter", "potato_chips", "black_truffle_ham_cracker"}
必需禁项 = {"广告背景", "包装", "品牌", "文字", "水印", "人物手持"}
固定逐项规格 = {
    'assets/blindboxchallenge/textures/item/adrenaline.png': ('adrenaline', 'catalog_reference', 'd1a57bdebc552c43bedccc194b505dd913463d45801b0a4a3e6bfc7705f44206'),
    'assets/blindboxchallenge/textures/item/bath_bucket.png': ('bath_bucket', 'catalog_reference', 'dd7b7c45180d3a53320e669343ceda6090853be0d734672c3149f4301e93387d'),
    'assets/blindboxchallenge/textures/item/beef_bites.png': ('beef_bites', 'catalog_reference', '9d2f0928f30f70a6406b436153630db841f4c31bc2e1062cf60abc4ed5c2aff6'),
    'assets/blindboxchallenge/textures/item/birthday_candle.png': ('birthday_candle', 'catalog_reference', '5ac504077cf4dca22c96f196c47a1e0a1471ada01c2018b78a9359a6ee4faac6'),
    'assets/blindboxchallenge/textures/item/black_knight_telescopic_knife.png': ('black_knight_telescopic_knife', 'catalog_reference', 'eac73bdf7dcef486d7124501dfbbeebd0884e45123a436fb52793cd9ddb6342f'),
    'assets/blindboxchallenge/textures/item/black_knight_telescopic_knife_extended.png': ('black_knight_telescopic_knife_extended', 'catalog_reference', 'c9db490b2c79ea7a0a28ddf5d6ea629c82ad88b50e27a21a1e5a566e718d62e0'),
    'assets/blindboxchallenge/textures/item/black_truffle_ham_cracker.png': ('black_truffle_ham_cracker', 'free_design', '5c0d61b9649ed2251097aae40e26048d7e8d56ccdc8bf6be584064988f7faf59'),
    'assets/blindboxchallenge/textures/item/blind_box.png': ('blind_box', 'free_design', '99bcc44e567f031b1aa8af8a276feb1e9c399477afe347d0cbca24c497c7235a'),
    'assets/blindboxchallenge/textures/item/cat_doll.png': ('cat_doll', 'catalog_reference', '8a6a04b04790e2e63e92fa8a61222c8289074365add4421717b343c086d7b3c9'),
    'assets/blindboxchallenge/textures/item/chainsaw_sword.png': ('chainsaw_sword', 'catalog_reference', '7f2277a21654e9c1cc251475d776de1fa91372b77cf36a27dc074d63fa8eaecc'),
    'assets/blindboxchallenge/textures/item/clockwork_chicken.png': ('clockwork_chicken', 'catalog_reference', '02335313590cfd78dc95f054ad69182a5f76836679a1dae7cfa04fc9a7d5ac58'),
    'assets/blindboxchallenge/textures/item/death_note.png': ('death_note', 'catalog_reference', 'b8d8ca0899f3cc91c4f7a8ad5c654806627cee4b184eb4a1046c546145ec243e'),
    'assets/blindboxchallenge/textures/item/decision_coin.png': ('decision_coin', 'catalog_reference', 'f1a1dc9fb9534fc29687057aa8d9e7d08554d16ff18b5babc0c8d723be08ad35'),
    'assets/blindboxchallenge/textures/item/deep_sea_fish.png': ('deep_sea_fish', 'catalog_reference', 'ea875a36698a27f82f0a2bfbace294fe79d5478d719d987805ff9ddd190d8490'),
    'assets/blindboxchallenge/textures/item/efficient_pig_breeding.png': ('efficient_pig_breeding', 'catalog_reference', 'bca218800b1248abff18c600a1f01e54f93dc0de1657d0d8a755031af4f3900f'),
    'assets/blindboxchallenge/textures/item/eggy_eye_mask.png': ('eggy_eye_mask', 'catalog_reference', '3fd3d4c420c381e6b5413588669cb026825e40594a055a039ad5c3f8ee1db06a'),
    'assets/blindboxchallenge/textures/item/face_mask.png': ('face_mask', 'catalog_reference', 'f691798c346f9a68dfcee25cf0bca6c62406f872efa1c965a91298d115fe79ac'),
    'assets/blindboxchallenge/textures/item/fairy_wand.png': ('fairy_wand', 'catalog_reference', '110fc957812910fa5216eecc212e58218fb6f980c3420cafef7aab17f8ccfe80'),
    'assets/blindboxchallenge/textures/item/flowing_black_flag.png': ('flowing_black_flag', 'catalog_reference', '4d838b42ace4b911aae74be0f317b5daf4aa89cfdca11e72f6e7bd4e7ba611ea'),
    'assets/blindboxchallenge/textures/item/green_soy_milk.png': ('green_soy_milk', 'catalog_reference', '8ce5be26da47412042ff3b3188d619a1b16f8c5dfbc78591a6b5a6a98c7ea95e'),
    'assets/blindboxchallenge/textures/item/ham_sausage.png': ('ham_sausage', 'catalog_reference', '573d9d01785f4f6d8dd576661049eb6e8a9f241f3aed768b5ea9e8f4f2cfe0bd'),
    'assets/blindboxchallenge/textures/item/headphones.png': ('headphones', 'catalog_reference', 'a61cd54506f062f2e0092b3ab0b66f7c0a9bc8169783e4b011d4443891bb7726'),
    'assets/blindboxchallenge/textures/item/kazoo.png': ('kazoo', 'catalog_reference', '6307646c36d700bd94cf8b5ce35c6159a9bde8fc2fd1e322b5781bd2eed6d713'),
    'assets/blindboxchallenge/textures/item/letter.png': ('letter', 'free_design', 'ffb14a8b8dc3e01735cc6e783864305ca5e0bf20ece83be67f4dc3372ec52f31'),
    'assets/blindboxchallenge/textures/item/lighter.png': ('lighter', 'catalog_reference', 'e866cc177f7b8f46211beccc22974f0c36684f8dc480c1119e363b6c5569fb7d'),
    'assets/blindboxchallenge/textures/item/long_screwdriver.png': ('long_screwdriver', 'catalog_reference', '151293dc622c8b463fccf5c3b550c6607d2afb79374701aee31bb9645431c70c'),
    'assets/blindboxchallenge/textures/item/magic_crispy_noodles.png': ('magic_crispy_noodles', 'catalog_reference', '6f609d631fa4b07bc2387f3627ca4dc5e47fb6f67bdd947b46013102d559c42b'),
    'assets/blindboxchallenge/textures/item/math_exam_paper.png': ('math_exam_paper', 'catalog_reference', '1874a8eaeb68e30d2713493150cdf64faac798da96e79b35ffa571a681e3ea34'),
    'assets/blindboxchallenge/textures/item/million_pound_note.png': ('million_pound_note', 'catalog_reference', '4ab80d8dc019a0eb452b57675f7a9ebe905f32f5e6525178d509523da16480a4'),
    'assets/blindboxchallenge/textures/item/nail_art.png': ('nail_art', 'catalog_reference', '9002d364442a94f62269abc62f03c4d4a75c5c52b247a37c9d2a673ac3fd8162'),
    'assets/blindboxchallenge/textures/item/oil_chestnut.png': ('oil_chestnut', 'catalog_reference', '8e251d1649fd75c1a7441701840f41a1cbab8ddb2508ac26bb4e6dabb91760b7'),
    'assets/blindboxchallenge/textures/item/packing_tool.png': ('packing_tool', 'free_design', '98af3361052c5ccf72d52c0ac07467d1071b0195ee45f2b59b21c1cfa7e8e698'),
    'assets/blindboxchallenge/textures/item/paper_cup.png': ('paper_cup', 'catalog_reference', '08e142ef4326ea7abd38d20aa69ea0f28a02b093c03543703c7711c03cecd017'),
    'assets/blindboxchallenge/textures/item/pickaxe_hoe.png': ('pickaxe_hoe', 'catalog_reference', '7a8f4df0c6d4d17347aaa6a4b3a278cfd3bb42fa8eeddbfd7256260918bb08d3'),
    'assets/blindboxchallenge/textures/item/pink_butterfly_wings.png': ('pink_butterfly_wings', 'catalog_reference', 'c40083f6e6da4147bee026ca318f6ab6e65781e82572175c4f1cfd2b44a90c5b'),
    'assets/blindboxchallenge/textures/item/potato_chips.png': ('potato_chips', 'free_design', '0ba8110de224192afdb23fa6d95395d2a07ddbb437035a98090f98f55cbb99ba'),
    'assets/blindboxchallenge/textures/item/potato_snack.png': ('potato_snack', 'catalog_reference', '8f730fde2291b5180b61790f5c01eee368eef85b5a49da16fd11a12fe946a147'),
    'assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_pickaxe.png': ('purple_toy_pickaxe_sword_pickaxe', 'catalog_reference', '5dd58c61f9d0b85417aeac590b5b6064c2f8f0cdc979bcdb17685ba488a9e645'),
    'assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_sword.png': ('purple_toy_pickaxe_sword_sword', 'catalog_reference', '06d535bb7ceb94f04d0efc218cdfd53585d546b9b0831cc61d09c916c96b5ff5'),
    'assets/blindboxchallenge/textures/item/quail_egg.png': ('quail_egg', 'catalog_reference', '58eb88d5cb1a59d8759b0e7f991fa0b4b4bbca3616ba76bb32a79c00f643846c'),
    'assets/blindboxchallenge/textures/item/rainbow_hoop.png': ('rainbow_hoop', 'catalog_reference', '81c383b0203160fd4933ad0b71f0dd31728a301866f426cda35939092763fe17'),
    'assets/blindboxchallenge/textures/item/rat_jerky_totem.png': ('rat_jerky_totem', 'catalog_reference', 'ae016a2d8ed8380a7cdb77ecc652de5c45b7b732b2461dced1a4568c2ed21fcb'),
    'assets/blindboxchallenge/textures/item/ration_pack.png': ('ration_pack', 'catalog_reference', 'ac78b71658f3720be935dcc0dd7bbe8a9256e381014f50019d7af193d4176f7a'),
    'assets/blindboxchallenge/textures/item/returning_scissors.png': ('returning_scissors', 'catalog_reference', 'eb6769e7d7f576eccdc4981624608ed8814101d45153ed2068163f8fe2f4c1d0'),
    'assets/blindboxchallenge/textures/item/road_barrier_helmet.png': ('road_barrier_helmet', 'catalog_reference', '1b0c4184ada0007e26535caf1b50906cf1e6cf022cf245ae8844f0dfaf65dc4b'),
    'assets/blindboxchallenge/textures/item/safety_exit_sign_shield.png': ('safety_exit_sign_shield', 'catalog_reference', 'f486cccd0ca46fd10f627a9bbde03b93abce5cfafb828c3773c574f7c3e5caef'),
    'assets/blindboxchallenge/textures/item/sesame_rice_noodles.png': ('sesame_rice_noodles', 'catalog_reference', '7987912658247177364968cb178bd0222a86cef4a7b871b474ba5d569883d174'),
    'assets/blindboxchallenge/textures/item/shark_dagger_pillow.png': ('shark_dagger_pillow', 'catalog_reference', '3511c99ec5576b812782120c2badf0cac74b15a11223539ca22b0646cc82b2a9'),
    'assets/blindboxchallenge/textures/item/sun_candy.png': ('sun_candy', 'catalog_reference', '7afd1c660049f03d1c6537d545235420b5c24e2b518e3465ed7a091e24b4ea83'),
    'assets/blindboxchallenge/textures/item/sweet_sour_turkey_noodles.png': ('sweet_sour_turkey_noodles', 'catalog_reference', 'd3b47ed207aa9c63cdfe6fd6fed206059fb8cb98417e1b63bd96fc02ce52d003'),
    'assets/blindboxchallenge/textures/item/toy_car.png': ('toy_car', 'catalog_reference', 'd68fbd4e81b9a88c84562da55f43a60966cc402a907e7568bdd3d539d6c8fa49'),
    'assets/blindboxchallenge/textures/item/toy_knife.png': ('toy_knife', 'catalog_reference', '732da1a3eac2e60651c79244fe3df3bcb4530343fcb41241b2360e37fdb5cb6f'),
    'assets/blindboxchallenge/textures/item/truffle_ham_cracker.png': ('truffle_ham_cracker', 'catalog_reference', '699ad7caa488a2f094da4ec36f17cba60104f6c538cf7a6c99cddd465004b2c2'),
    'assets/blindboxchallenge/textures/item/vodka.png': ('vodka', 'catalog_reference', '51a1ecda5550a45a3c99f98db8befefd2819639e8e9182954cdef53855a99a47'),
    'assets/blindboxchallenge/textures/item/wang_lixin_badge.png': ('wang_lixin_badge', 'catalog_reference', 'cbaf91a46f2f92adcf1dfbfc31eba52e485827d4b4b3e23ee3e254cfe4a1dfdc'),
    'assets/blindboxchallenge/textures/item/wenxu_standee.png': ('wenxu_standee', 'catalog_reference', 'abccac4a15597204b85ff4b894675d7350d4c1fbf17d87edda6488bdd5d360cb'),
    'assets/blindboxchallenge/textures/item/white_rabbit_candy.png': ('white_rabbit_candy', 'catalog_reference', 'b7332e5ce9581606bb5d2680117512fa9f0e2a5f941150f6da7327773c60451f'),
    'assets/blindboxchallenge/textures/item/wind_blown_cake.png': ('wind_blown_cake', 'catalog_reference', 'cbdf4833453c3d5527ed3219eeeff114dc1710411c412f689e64501e4a34105a'),
    'assets/blindboxchallenge/textures/item/yijin_manual.png': ('yijin_manual', 'catalog_reference', '443f04095b2f2a77c253f8a5e7d1842b06523baa8f650b128ae012534f808d93'),
}

# 这些类别契约是逐项列举的明确规则，不尝试依赖通用中文语义推断。
食品本体关键词 = {
    "beef_bites": ("牛肉粒", "烤牛肉粒"), "black_truffle_ham_cracker": ("苏打饼干", "火腿片", "黑松露碎"),
    "deep_sea_fish": ("深海鱼", "鱼身"), "green_soy_milk": ("豆浆", "液面"),
    "ham_sausage": ("火腿肠", "圆柱肉体"), "magic_crispy_noodles": ("面条", "面饼碎块"),
    "oil_chestnut": ("板栗", "果肉"), "potato_chips": ("薯片", "薄片"),
    "potato_snack": ("土豆", "土豆条"), "quail_egg": ("鹌鹑蛋", "蛋壳"),
    "ration_pack": ("军粮", "谷物块"), "sesame_rice_noodles": ("米线", "米线"),
    "sun_candy": ("硬糖", "糖心"), "sweet_sour_turkey_noodles": ("拌面", "面条"),
    "truffle_ham_cracker": ("苏打饼干", "火腿薄片"), "white_rabbit_candy": ("奶糖", "糖块"),
    "wind_blown_cake": ("圆饼", "薄脆圆饼"),
}
书纸币徽章旗关键词 = {
    "death_note": ("笔记本", "封面", "不可读", "抽象符号"),
    "efficient_pig_breeding": ("技术书", "书脊", "书页", "不可读"),
    "yijin_manual": ("线装古书", "封皮", "书脊", "不可读"),
    "math_exam_paper": ("试卷", "纸张轮廓", "不可读"),
    "million_pound_note": ("纸币", "纸张边框", "不可读", "不含肖像"),
    "wang_lixin_badge": ("徽章", "金属边", "不可读", "不含人物肖像"),
    "flowing_black_flag": ("布旗", "旗杆", "不可读"),
}
原创防复刻关键词 = {
    "chainsaw_sword": ("原创", "不采用任何现有IP"), "eggy_eye_mask": ("原创", "不复刻角色脸"),
    "rat_jerky_totem": ("原创", "不复刻任何角色"), "shark_dagger_pillow": ("原创", "不复刻角色"),
    "toy_car": ("原创", "不使用品牌造型"), "wenxu_standee": ("原创", "不复刻人物或角色"),
}
状态变体编号 = {
    "assets/blindboxchallenge/textures/item/black_knight_telescopic_knife.png": "black_knight_telescopic_knife",
    "assets/blindboxchallenge/textures/item/black_knight_telescopic_knife_extended.png": "black_knight_telescopic_knife_extended",
    "assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_pickaxe.png": "purple_toy_pickaxe_sword_pickaxe",
    "assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_sword.png": "purple_toy_pickaxe_sword_sword",
}


def 正式贴图路径(贴图目录: Path) -> dict[str, Path]:
    return {
        f"assets/blindboxchallenge/textures/item/{路径.name}": 路径
        for 路径 in sorted(贴图目录.glob("*.png"))
    }


def 读取清单(清单路径: Path) -> list[dict]:
    try:
        数据 = json.loads(清单路径.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as 异常:
        raise ValueError(f"无法读取清单：{异常}") from 异常
    if not isinstance(数据, list):
        raise ValueError("清单顶层必须是数组")
    return 数据


def 语义摘要(项: dict) -> str:
    """生成受固定规格约束的四字段摘要；期望摘要不从待校验清单读取。"""
    内容 = json.dumps(
        [项.get("id"), 项.get("reference_status"), 项.get("subject"), 项.get("must_show")],
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(内容.encode("utf-8")).hexdigest()


def 校验类别关键词(项: dict, 序号: int) -> list[str]:
    编号 = 项.get("id")
    语义 = f"{项.get('subject', '')} {项.get('must_show', '')}"
    错误: list[str] = []
    for 类别, 契约 in (
        ("食品本体", 食品本体关键词),
        ("书/纸币/徽章/旗外形材质与抽象符号", 书纸币徽章旗关键词),
        ("人物/IP原创防复刻", 原创防复刻关键词),
    ):
        if 编号 in 契约:
            缺失 = [关键词 for 关键词 in 契约[编号] if 关键词 not in 语义]
            if 缺失:
                错误.append(f"第 {序号} 项不符合{类别}契约，缺少：{', '.join(缺失)}")
    if 编号 in 食品本体关键词 and ("只画包装" in 语义 or "不画" in 语义 and "本体" in 语义):
        错误.append(f"第 {序号} 项食品规格禁止只画包装或排除食物本体")
    return 错误


def 校验状态变体(项: dict, 序号: int) -> list[str]:
    路径 = 项.get("texture")
    if 路径 not in 状态变体编号:
        return []
    期望编号 = 状态变体编号[路径]
    if 项.get("id") != 期望编号:
        return [f"第 {序号} 项 001/002 状态变体 id 必须为 {期望编号}"]
    return []


def 校验清单(清单路径: Path, 贴图目录: Path) -> list[str]:
    错误: list[str] = []
    try:
        清单 = 读取清单(清单路径)
    except ValueError as 异常:
        return [str(异常)]

    if len(清单) != 59:
        错误.append(f"清单必须恰好包含 59 项，当前为 {len(清单)} 项")

    路径列表: list[str] = []
    无参考编号: set[str] = set()
    for 序号, 项 in enumerate(清单, start=1):
        if not isinstance(项, dict):
            错误.append(f"第 {序号} 项必须是对象")
            continue
        缺失 = 必需字段 - 项.keys()
        if 缺失:
            错误.append(f"第 {序号} 项缺少字段：{', '.join(sorted(缺失))}")
        for 字段 in ("id", "texture", "subject", "must_show"):
            if 字段 in 项 and (not isinstance(项[字段], str) or not 项[字段].strip()):
                错误.append(f"第 {序号} 项的 {字段} 必须是非空字符串")
        if isinstance(项.get("texture"), str):
            路径列表.append(项["texture"])
            固定规格 = 固定逐项规格.get(项["texture"])
            if 固定规格 is None:
                错误.append(f"第 {序号} 项 texture 不在固定逐项规格中：{项['texture']}")
            else:
                期望编号, 期望状态, 期望摘要 = 固定规格
                if 项.get("id") != 期望编号:
                    错误.append(f"第 {序号} 项 id 与固定规格不符：应为 {期望编号}")
                if 项.get("reference_status") != 期望状态:
                    错误.append(f"第 {序号} 项 reference_status 与固定规格不符：应为 {期望状态}")
                if 语义摘要(项) != 期望摘要:
                    错误.append(f"第 {序号} 项 subject/must_show 与固定逐项语义规格不符")
        错误.extend(校验类别关键词(项, 序号))
        错误.extend(校验状态变体(项, 序号))
        状态 = 项.get("reference_status")
        if 状态 not in 合法参考状态:
            错误.append(f"第 {序号} 项 reference_status 非法：{状态}")
        elif 状态 == "free_design" and isinstance(项.get("id"), str):
            无参考编号.add(项["id"])
        禁项 = 项.get("avoid")
        if not isinstance(禁项, list):
            错误.append(f"第 {序号} 项 avoid 必须是数组")
        else:
            合法禁项: set[str] = set()
            for 禁项序号, 内容 in enumerate(禁项, start=1):
                if not isinstance(内容, str) or not 内容.strip():
                    错误.append(f"第 {序号} 项 avoid 第 {禁项序号} 个元素必须是非空字符串")
                else:
                    合法禁项.add(内容)
            少禁项 = 必需禁项 - 合法禁项
            if 少禁项:
                错误.append(f"第 {序号} 项 avoid 缺少禁项：{', '.join(sorted(少禁项))}")
        配色 = 项.get("palette")
        if not isinstance(配色, list) or not 配色 or not all(isinstance(颜色, str) and 颜色.strip() for 颜色 in 配色):
            错误.append(f"第 {序号} 项 palette 必须是非空字符串数组")

    if len(路径列表) != len(set(路径列表)):
        错误.append("清单 texture 路径存在重复")
    if set(固定逐项规格) != set(正式贴图路径(贴图目录)):
        错误.append("验证器固定逐项规格未与 59 张正式贴图完全对应")
    for 路径, 编号 in 状态变体编号.items():
        固定规格 = 固定逐项规格.get(路径)
        if 固定规格 is None or 固定规格[0] != 编号:
            错误.append(f"001/002 状态变体固定契约损坏：{路径}")
    if 无参考编号 != 无参考设计编号:
        错误.append(
            "free_design 集合不正确：应为 " + ", ".join(sorted(无参考设计编号))
            + "；实际为 " + ", ".join(sorted(无参考编号))
        )

    正式集合 = set(正式贴图路径(贴图目录))
    if len(正式集合) != 59:
        错误.append(f"正式贴图目录必须恰好包含 59 张 PNG，当前为 {len(正式集合)} 张")
    清单集合 = set(路径列表)
    if 清单集合 != 正式集合:
        缺少 = sorted(正式集合 - 清单集合)
        多余 = sorted(清单集合 - 正式集合)
        if 缺少:
            错误.append("清单缺少正式贴图：" + ", ".join(缺少))
        if 多余:
            错误.append("清单包含非正式贴图：" + ", ".join(多余))
    return 错误


def 查询Git状态(相对路径: str) -> tuple[str | None, str | None]:
    try:
        结果 = subprocess.run(
            ["git", "status", "--short", "--", 相对路径],
            cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace"
        )
    except (FileNotFoundError, OSError) as 异常:
        return None, f"无法执行 Git：{异常}"
    if 结果.returncode != 0:
        详情 = (结果.stderr or "无错误详情").strip()
        return None, f"Git 状态查询失败（退出码 {结果.returncode}）：{详情}"
    return 结果.stdout.rstrip("\r\n") or "clean", None


def 写入基线(输出路径: Path, 贴图目录: Path) -> list[str]:
    贴图 = 正式贴图路径(贴图目录)
    if len(贴图) != 59:
        return [f"正式贴图目录必须恰好包含 59 张 PNG，当前为 {len(贴图)} 张"]
    基线 = []
    for 路径 in 贴图.values():
        相对路径 = 路径.relative_to(仓库根目录).as_posix()
        Git状态, Git错误 = 查询Git状态(相对路径)
        if Git错误:
            return [f"{相对路径}：{Git错误}"]
        with Image.open(路径) as 图像:
            宽, 高 = 图像.size
            模式 = 图像.mode
        基线.append({
            "path": 相对路径,
            "width": 宽,
            "height": 高,
            "mode": 模式,
            "sha256": hashlib.sha256(路径.read_bytes()).hexdigest(),
            "git_status": Git状态,
        })
    输出路径.parent.mkdir(parents=True, exist_ok=True)
    输出路径.write_text(json.dumps(基线, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return []


def main() -> int:
    解析器 = argparse.ArgumentParser(description=__doc__)
    操作组 = 解析器.add_mutually_exclusive_group()
    操作组.add_argument("--validate-manifest", action="store_true", help="校验重绘清单（默认行为）")
    操作组.add_argument("--write-baseline", type=Path, help="把正式贴图基线写入指定路径")
    解析器.add_argument("--manifest", type=Path, default=默认清单路径, help=argparse.SUPPRESS)
    解析器.add_argument("--texture-root", type=Path, default=默认贴图目录, help=argparse.SUPPRESS)
    参数 = 解析器.parse_args()

    if 参数.write_baseline:
        错误 = 写入基线(参数.write_baseline, 参数.texture_root)
        成功消息 = f"物品贴图基线已写入：{参数.write_baseline}"
    else:
        错误 = 校验清单(参数.manifest, 参数.texture_root)
        成功消息 = "物品贴图重绘清单校验通过：59 项"
    if 错误:
        for 内容 in 错误:
            print(f"错误：{内容}", file=sys.stderr)
        return 1
    print(成功消息)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
