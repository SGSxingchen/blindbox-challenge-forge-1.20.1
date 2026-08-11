#!/usr/bin/env python3
"""按清单顺序生成物品贴图联系表与待复核清单。"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import tempfile
from pathlib import Path, PurePosixPath

from PIL import Image, ImageDraw, ImageFont


仓库根目录 = Path(__file__).resolve().parents[1]
默认输入 = 仓库根目录 / "output/imagegen/item-redraw/candidates"
默认清单 = 仓库根目录 / "tools/item_texture_redraw_manifest.json"
默认输出 = 仓库根目录 / "output/imagegen/item-redraw/contact-sheet.png"
默认复核输出 = 仓库根目录 / "output/imagegen/item-redraw/visual-review.json"
安全编号 = re.compile(r"^[a-z0-9_]+$")
单元宽, 单元高, 图标尺寸 = 128, 160, 128


def 文件哈希(路径: Path) -> str:
    return hashlib.sha256(路径.read_bytes()).hexdigest()


def 读取清单(路径: Path) -> list[dict]:
    try:
        数据 = json.loads(路径.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as 异常:
        raise ValueError(f"无法读取清单：{异常}") from 异常
    if not isinstance(数据, list) or not 数据:
        raise ValueError("清单必须是非空数组")
    结果 = []
    for 序号, 项 in enumerate(数据, 1):
        if not isinstance(项, dict) or not isinstance(项.get("id"), str) or not isinstance(项.get("texture"), str):
            raise ValueError(f"清单第 {序号} 项缺少 id 或 texture")
        编号, 贴图 = 项["id"], PurePosixPath(项["texture"])
        if not 安全编号.fullmatch(编号) or 贴图.is_absolute() or ".." in 贴图.parts or 贴图.suffix != ".png" or not 安全编号.fullmatch(贴图.stem):
            raise ValueError(f"清单第 {序号} 项路径或编号非法")
        结果.append({"id": 编号, "texture": 项["texture"], "stem": 贴图.stem})
    return 结果


def 原子写图(图: Image.Image, 目标: Path) -> None:
    目标.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=f".{目标.name}.", suffix=".tmp", dir=目标.parent, delete=False) as 临时:
        临时路径 = Path(临时.name)
    try:
        图.save(临时路径, format="PNG")
        os.replace(临时路径, 目标)
    finally:
        临时路径.unlink(missing_ok=True)


def 原子写JSON(数据: object, 目标: Path) -> None:
    目标.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix=f".{目标.name}.", suffix=".tmp", dir=目标.parent, delete=False) as 临时:
        json.dump(数据, 临时, ensure_ascii=False, indent=2)
        临时.write("\n")
        临时路径 = Path(临时.name)
    try:
        os.replace(临时路径, 目标)
    finally:
        临时路径.unlink(missing_ok=True)


def 渲染联系表(输入: Path, 清单路径: Path, 输出: Path, 复核输出: Path, 列数: int | None = None) -> tuple[int, int]:
    项目 = 读取清单(Path(清单路径))
    输入 = Path(输入)
    if not 输入.is_dir():
        raise ValueError(f"候选输入目录不存在：{输入}")
    列数 = 列数 or min(8, max(1, math.ceil(math.sqrt(len(项目)))))
    if 列数 < 1:
        raise ValueError("列数必须大于零")
    行数 = math.ceil(len(项目) / 列数)
    画布 = Image.new("RGBA", (列数 * 单元宽, 行数 * 单元高), (32, 32, 32, 255))
    画笔, 字体, 复核 = ImageDraw.Draw(画布), ImageFont.load_default(), []
    for 序号, 项 in enumerate(项目):
        x, y = (序号 % 列数) * 单元宽, (序号 // 列数) * 单元高
        候选路径 = (输入 / f"{项['stem']}.png").resolve()
        状态, 哈希, 备注 = "missing", None, "候选图缺失"
        if 候选路径.is_file():
            with Image.open(候选路径) as 图:
                if 图.size != (16, 16):
                    raise ValueError(f"{项['id']} 候选图必须是 16x16，实际为 {图.width}x{图.height}")
                图标 = 图.convert("RGBA").resize((图标尺寸, 图标尺寸), Image.Resampling.NEAREST)
            画布.alpha_composite(图标, (x, y))
            状态, 哈希, 备注 = "pending", 文件哈希(候选路径), ""
        else:
            画笔.rectangle((x, y, x + 图标尺寸 - 1, y + 图标尺寸 - 1), fill=(180, 0, 0, 255))
            画笔.text((x + 38, y + 58), "MISSING", fill=(255, 255, 255, 255), font=字体)
        画笔.text((x + 3, y + 134), 项["id"], fill=(255, 255, 255, 255), font=字体)
        复核.append({"id": 项["id"], "texture": 项["texture"], "candidate_path": str(候选路径), "candidate_sha256": 哈希, "status": 状态, "notes": 备注})
    原子写图(画布, Path(输出))
    原子写JSON(复核, Path(复核输出))
    return 画布.size


def main() -> int:
    解析器 = argparse.ArgumentParser(description=__doc__)
    解析器.add_argument("--input", type=Path, default=默认输入)
    解析器.add_argument("--manifest", type=Path, default=默认清单)
    解析器.add_argument("--output", type=Path, default=默认输出)
    解析器.add_argument("--review-output", type=Path, default=默认复核输出)
    参数 = 解析器.parse_args()
    try:
        尺寸 = 渲染联系表(参数.input, 参数.manifest, 参数.output, 参数.review_output)
    except ValueError as 异常:
        解析器.error(str(异常))
    print(f"已生成联系表：{参数.output} ({尺寸[0]}x{尺寸[1]})")
    print(f"已生成复核清单：{参数.review_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
