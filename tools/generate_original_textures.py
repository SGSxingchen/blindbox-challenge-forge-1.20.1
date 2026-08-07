#!/usr/bin/env python3
"""生成并校验 P5 发行整改中的确定性原创像素贴图。

本工具只处理 TARGETS 中不得直接发行的原版 PNG。它不读取、不采样或
拼接原贴图：每个像素仅由文件标识、明确的类别轮廓和固定调色板计算得出。
因此同一源码可重复得到同一 RGBA PNG，也可用 --check 防止手工漂移。
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "mod/src/main/resources"
MANIFEST = ROOT / "docs/ASSET_MANIFEST.md"
TARGETS = (
    "assets/blindboxchallenge/textures/block/anywhere_door.png",
    "assets/blindboxchallenge/textures/block/bml_cheer_stick_off.png",
    "assets/blindboxchallenge/textures/block/bml_cheer_stick_on.png",
    "assets/blindboxchallenge/textures/block/diamond_pillow.png",
    "assets/blindboxchallenge/textures/block/glow_stick.png",
    "assets/blindboxchallenge/textures/block/music_box.png",
    "assets/blindboxchallenge/textures/block/safety_landing.png",
    "assets/blindboxchallenge/textures/block/stone_pillow.png",
    "assets/blindboxchallenge/textures/item/adrenaline.png",
    "assets/blindboxchallenge/textures/item/bath_bucket.png",
    "assets/blindboxchallenge/textures/item/beef_bites.png",
    "assets/blindboxchallenge/textures/item/birthday_candle.png",
    "assets/blindboxchallenge/textures/item/black_knight_telescopic_knife.png",
    "assets/blindboxchallenge/textures/item/black_knight_telescopic_knife_extended.png",
    "assets/blindboxchallenge/textures/item/black_truffle_ham_cracker.png",
    "assets/blindboxchallenge/textures/item/blind_box.png",
    "assets/blindboxchallenge/textures/item/cat_doll.png",
    "assets/blindboxchallenge/textures/item/chainsaw_sword.png",
    "assets/blindboxchallenge/textures/item/clockwork_chicken.png",
    "assets/blindboxchallenge/textures/item/death_note.png",
    "assets/blindboxchallenge/textures/item/decision_coin.png",
    "assets/blindboxchallenge/textures/item/deep_sea_fish.png",
    "assets/blindboxchallenge/textures/item/efficient_pig_breeding.png",
    "assets/blindboxchallenge/textures/item/eggy_eye_mask.png",
    "assets/blindboxchallenge/textures/item/face_mask.png",
    "assets/blindboxchallenge/textures/item/fairy_wand.png",
    "assets/blindboxchallenge/textures/item/flowing_black_flag.png",
    "assets/blindboxchallenge/textures/item/green_soy_milk.png",
    "assets/blindboxchallenge/textures/item/ham_sausage.png",
    "assets/blindboxchallenge/textures/item/headphones.png",
    "assets/blindboxchallenge/textures/item/kazoo.png",
    "assets/blindboxchallenge/textures/item/letter.png",
    "assets/blindboxchallenge/textures/item/lighter.png",
    "assets/blindboxchallenge/textures/item/long_screwdriver.png",
    "assets/blindboxchallenge/textures/item/magic_crispy_noodles.png",
    "assets/blindboxchallenge/textures/item/math_exam_paper.png",
    "assets/blindboxchallenge/textures/item/million_pound_note.png",
    "assets/blindboxchallenge/textures/item/nail_art.png",
    "assets/blindboxchallenge/textures/item/oil_chestnut.png",
    "assets/blindboxchallenge/textures/item/packing_tool.png",
    "assets/blindboxchallenge/textures/item/paper_cup.png",
    "assets/blindboxchallenge/textures/item/pickaxe_hoe.png",
    "assets/blindboxchallenge/textures/item/pink_butterfly_wings.png",
    "assets/blindboxchallenge/textures/item/potato_chips.png",
    "assets/blindboxchallenge/textures/item/potato_snack.png",
    "assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_pickaxe.png",
    "assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_sword.png",
    "assets/blindboxchallenge/textures/item/quail_egg.png",
    "assets/blindboxchallenge/textures/item/rainbow_hoop.png",
    "assets/blindboxchallenge/textures/item/rat_jerky_totem.png",
    "assets/blindboxchallenge/textures/item/ration_pack.png",
    "assets/blindboxchallenge/textures/item/returning_scissors.png",
    "assets/blindboxchallenge/textures/item/road_barrier_helmet.png",
    "assets/blindboxchallenge/textures/item/safety_exit_sign_shield.png",
    "assets/blindboxchallenge/textures/item/sesame_rice_noodles.png",
    "assets/blindboxchallenge/textures/item/shark_dagger_pillow.png",
    "assets/blindboxchallenge/textures/item/sun_candy.png",
    "assets/blindboxchallenge/textures/item/sweet_sour_turkey_noodles.png",
    "assets/blindboxchallenge/textures/item/toy_car.png",
    "assets/blindboxchallenge/textures/item/toy_knife.png",
    "assets/blindboxchallenge/textures/item/truffle_ham_cracker.png",
    "assets/blindboxchallenge/textures/item/vodka.png",
    "assets/blindboxchallenge/textures/item/wang_lixin_badge.png",
    "assets/blindboxchallenge/textures/item/wenxu_standee.png",
    "assets/blindboxchallenge/textures/item/white_rabbit_candy.png",
    "assets/blindboxchallenge/textures/item/wind_blown_cake.png",
    "assets/blindboxchallenge/textures/item/yijin_manual.png",
    "assets/blindboxchallenge/textures/models/armor/road_barrier_layer_1.png",
)


def digest(name: str) -> bytes:
    return hashlib.sha256(("blindbox-original-pixel-v1:" + name).encode("utf-8")).digest()


def colors(name: str) -> tuple[tuple[int, int, int, int], ...]:
    seed = digest(name)
    base = (52 + seed[0] % 110, 52 + seed[1] % 110, 52 + seed[2] % 110, 255)
    light = tuple(min(255, value + 70) for value in base[:3]) + (255,)
    dark = tuple(max(0, value - 38) for value in base[:3]) + (255,)
    accent = (210 + seed[3] % 40, 140 + seed[4] % 90, 45 + seed[5] % 120, 255)
    outline = (22, 27, 39, 255)
    return (base, light, dark, accent, outline)


def blank(width: int = 16, height: int = 16) -> list[list[tuple[int, int, int, int]]]:
    return [[(0, 0, 0, 0) for _ in range(width)] for _ in range(height)]


def paint(canvas: list[list[tuple[int, int, int, int]]], x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if 0 <= y < len(canvas) and 0 <= x < len(canvas[0]):
        canvas[y][x] = color


def rectangle(canvas: list[list[tuple[int, int, int, int]]], left: int, top: int, right: int, bottom: int, color: tuple[int, int, int, int]) -> None:
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            paint(canvas, x, y, color)


def outlined_box(canvas: list[list[tuple[int, int, int, int]]], left: int, top: int, right: int, bottom: int, palette: tuple[tuple[int, int, int, int], ...]) -> None:
    base, light, dark, _, outline = palette
    rectangle(canvas, left, top, right, bottom, outline)
    rectangle(canvas, left + 1, top + 1, right - 1, bottom - 1, base)
    rectangle(canvas, left + 1, top + 1, right - 1, top + 1, light)
    rectangle(canvas, left + 1, bottom - 1, right - 1, bottom - 1, dark)


def diagonal_tool(canvas: list[list[tuple[int, int, int, int]]], palette: tuple[tuple[int, int, int, int], ...], blade: bool = False) -> None:
    base, light, dark, accent, outline = palette
    for index in range(9):
        x, y = 3 + index, 12 - index
        paint(canvas, x, y, outline)
        paint(canvas, x + 1, y, outline)
        paint(canvas, x, y - 1, base if index < 6 else accent)
    paint(canvas, 2, 13, dark)
    paint(canvas, 3, 14, outline)
    paint(canvas, 4, 13, light)
    if blade:
        for x, y in ((11, 3), (12, 2), (13, 2), (13, 1), (14, 1)):
            paint(canvas, x, y, light)


def food(canvas: list[list[tuple[int, int, int, int]]], palette: tuple[tuple[int, int, int, int], ...], seed: bytes) -> None:
    base, light, dark, accent, outline = palette
    outlined_box(canvas, 3, 5, 12, 11, palette)
    rectangle(canvas, 4, 6, 11, 7, light)
    for index in range(4):
        paint(canvas, 5 + index * 2, 9, accent if seed[index] & 1 else dark)
    paint(canvas, 8, 4, outline)
    paint(canvas, 8, 3, accent)


def paper(canvas: list[list[tuple[int, int, int, int]]], palette: tuple[tuple[int, int, int, int], ...], seed: bytes) -> None:
    _, light, dark, accent, outline = palette
    rectangle(canvas, 4, 2, 11, 13, outline)
    rectangle(canvas, 5, 3, 10, 12, light)
    for row in (5, 7, 9):
        rectangle(canvas, 6, row, 9, row, dark)
    paint(canvas, 9, 11, accent)
    if seed[0] & 1:
        paint(canvas, 10, 3, accent)


def wearable(canvas: list[list[tuple[int, int, int, int]]], palette: tuple[tuple[int, int, int, int], ...]) -> None:
    base, light, dark, accent, outline = palette
    rectangle(canvas, 3, 5, 12, 10, outline)
    rectangle(canvas, 4, 6, 11, 9, base)
    rectangle(canvas, 5, 5, 10, 5, light)
    rectangle(canvas, 5, 10, 10, 10, dark)
    paint(canvas, 6, 7, accent)
    paint(canvas, 9, 7, accent)


def small_object(name: str) -> list[list[tuple[int, int, int, int]]]:
    palette = colors(name)
    seed = digest(name)
    canvas = blank()
    identifier = Path(name).stem
    if any(token in identifier for token in ("sword", "knife", "scissors", "screwdriver", "wand", "hoe")):
        diagonal_tool(canvas, palette, blade="sword" in identifier or "knife" in identifier)
    elif any(token in identifier for token in ("book", "manual", "note", "letter", "paper", "exam")):
        paper(canvas, palette, seed)
    elif any(token in identifier for token in ("mask", "helmet", "wings", "nail")):
        wearable(canvas, palette)
    elif any(token in identifier for token in ("candy", "noodles", "cracker", "bites", "milk", "sausage", "egg", "snack", "chips", "chestnut", "fish", "vodka", "ration")):
        food(canvas, palette, seed)
    elif "coin" in identifier or "hoop" in identifier:
        base, light, dark, accent, outline = palette
        rectangle(canvas, 5, 3, 10, 12, outline)
        rectangle(canvas, 6, 4, 9, 11, base)
        rectangle(canvas, 7, 5, 8, 10, light)
        paint(canvas, 7, 8, accent)
        paint(canvas, 8, 8, dark)
    else:
        base, light, dark, accent, outline = palette
        outlined_box(canvas, 3, 4, 12, 12, palette)
        rectangle(canvas, 5, 6, 10, 7, light)
        paint(canvas, 7, 9, accent)
        paint(canvas, 8, 9, dark)
        paint(canvas, 4, 11, base)
    return canvas


def block(name: str) -> list[list[tuple[int, int, int, int]]]:
    palette = colors(name)
    base, light, dark, accent, outline = palette
    seed = digest(name)
    canvas = blank()
    rectangle(canvas, 0, 0, 15, 15, outline)
    rectangle(canvas, 1, 1, 14, 14, base)
    for y in range(2, 14, 4):
        for x in range(2, 14, 4):
            rectangle(canvas, x, y, x + 2, y + 2, light if (x + y + seed[0]) % 3 else dark)
    identifier = Path(name).stem
    if "door" in identifier:
        rectangle(canvas, 3, 1, 12, 14, outline)
        rectangle(canvas, 4, 2, 11, 13, base)
        rectangle(canvas, 5, 3, 10, 9, light)
        paint(canvas, 10, 11, accent)
    elif "stick" in identifier:
        rectangle(canvas, 7, 1, 8, 14, dark)
        rectangle(canvas, 7, 3, 8, 11, accent if "on" in identifier else light)
    elif "pillow" in identifier:
        outlined_box(canvas, 2, 5, 13, 11, palette)
        rectangle(canvas, 4, 6, 11, 6, light)
    elif "music" in identifier:
        outlined_box(canvas, 2, 3, 13, 13, palette)
        rectangle(canvas, 5, 6, 10, 10, dark)
        paint(canvas, 7, 7, accent)
        paint(canvas, 8, 9, accent)
    elif "landing" in identifier:
        for index in range(3):
            rectangle(canvas, 3 + index * 3, 4 + index * 2, 5 + index * 3, 5 + index * 2, accent)
    return canvas


def armor(name: str) -> list[list[tuple[int, int, int, int]]]:
    palette = colors(name)
    base, light, dark, accent, outline = palette
    canvas = blank(64, 32)
    for y in range(32):
        for x in range(64):
            if (x // 4 + y // 4) % 2 == 0:
                paint(canvas, x, y, base)
    for y in range(0, 32, 8):
        rectangle(canvas, 0, y, 63, y + 1, accent)
    rectangle(canvas, 0, 0, 63, 0, outline)
    rectangle(canvas, 0, 31, 63, 31, outline)
    for x in range(0, 64, 16):
        rectangle(canvas, x, 0, x + 1, 31, dark)
        rectangle(canvas, x + 2, 2, x + 3, 29, light)
    return canvas


def png(canvas: list[list[tuple[int, int, int, int]]]) -> bytes:
    height, width = len(canvas), len(canvas[0])
    raw = b"".join(b"\x00" + bytes(channel for pixel in row for channel in pixel) for row in canvas)

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def render(relative: str) -> bytes:
    if "/textures/models/armor/" in relative:
        return png(armor(relative))
    if "/textures/block/" in relative:
        return png(block(relative))
    return png(small_object(relative))


def update_manifest() -> None:
    """仅更新本工具拥有的行，保留其他资源的审计状态。"""
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
                    f"|`{path}`|`{checksum}`|项目内原创重绘|"
                    "原版图片仅作需求输入且不进入 Release；不读取、采样或混合原图|"
                    "项目方提供需求背景；发行使用项目内原创重绘 PNG|2026-08-07|"
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
    parser.add_argument("--check", action="store_true", help="只比较目标贴图是否和生成结果完全一致")
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
        raise SystemExit("原创贴图与生成器不一致：" + ", ".join(drift))
    if args.update_manifest:
        update_manifest()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
