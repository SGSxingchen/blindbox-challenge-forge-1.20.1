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

from PIL import Image, ImageDraw, ImageFont, UnidentifiedImageError


仓库根目录 = Path(__file__).resolve().parents[1]
默认输入 = 仓库根目录 / "output/imagegen/item-redraw/candidates"
默认清单 = 仓库根目录 / "tools/item_texture_redraw_manifest.json"
默认输出 = 仓库根目录 / "output/imagegen/item-redraw/contact-sheet.png"
默认复核输出 = 仓库根目录 / "output/imagegen/item-redraw/visual-review.json"
安全编号 = re.compile(r"^[a-z0-9_]+$")
最小单元宽, 单元高, 图标尺寸 = 128, 160, 128
标签水平留白 = 3
默认最大项目数 = 100
最大编号长度 = 128
最大单元宽 = 4096
最大单元高 = 4096
默认最大总像素 = 100_000_000


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
        if len(编号) > 最大编号长度:
            raise ValueError(f"清单第 {序号} 项 id 长度不能超过 {最大编号长度}")
        if not 安全编号.fullmatch(编号) or 贴图.is_absolute() or ".." in 贴图.parts or 贴图.suffix != ".png" or not 安全编号.fullmatch(贴图.stem):
            raise ValueError(f"清单第 {序号} 项路径或编号非法")
        结果.append({"id": 编号, "texture": 项["texture"], "stem": 贴图.stem})
    return 结果


def 暂存图(图: Image.Image, 目标: Path) -> Path:
    目标.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=f".{目标.name}.", suffix=".tmp", dir=目标.parent, delete=False) as 临时:
        临时路径 = Path(临时.name)
    try:
        图.save(临时路径, format="PNG")
        with 临时路径.open("r+b") as 文件:
            os.fsync(文件.fileno())
        return 临时路径
    except Exception:
        临时路径.unlink(missing_ok=True)
        raise


def 暂存JSON(数据: object, 目标: Path) -> Path:
    目标.parent.mkdir(parents=True, exist_ok=True)
    临时路径 = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix=f".{目标.name}.", suffix=".tmp", dir=目标.parent, delete=False) as 临时:
            临时路径 = Path(临时.name)
            json.dump(数据, 临时, ensure_ascii=False, indent=2)
            临时.write("\n")
            临时.flush()
            os.fsync(临时.fileno())
        return 临时路径
    except Exception:
        if 临时路径 is not None:
            临时路径.unlink(missing_ok=True)
        raise


def 备份旧文件(目标: Path) -> Path | None:
    if not 目标.exists():
        return None
    with tempfile.NamedTemporaryFile(prefix=f".{目标.name}.", suffix=".bak", dir=目标.parent, delete=False) as 备份:
        备份.write(目标.read_bytes())
        备份.flush()
        os.fsync(备份.fileno())
        return Path(备份.name)


def 成对提交(图: Image.Image, 复核: object, 输出: Path, 复核输出: Path) -> None:
    输出, 复核输出 = Path(输出), Path(复核输出)
    临时图 = 临时JSON = 备份图 = 备份JSON = None
    图原本存在, JSON原本存在 = 输出.exists(), 复核输出.exists()
    try:
        临时图 = 暂存图(图, 输出)
        临时JSON = 暂存JSON(复核, 复核输出)
        备份图 = 备份旧文件(输出)
        备份JSON = 备份旧文件(复核输出)
        os.replace(临时图, 输出); 临时图 = None
        os.replace(临时JSON, 复核输出); 临时JSON = None
    except OSError as 异常:
        回滚错误 = []
        for 目标, 备份, 原本存在 in ((输出, 备份图, 图原本存在), (复核输出, 备份JSON, JSON原本存在)):
            try:
                if 原本存在 and 备份 is not None:
                    os.replace(备份, 目标)
                elif not 原本存在 and 目标.exists():
                    目标.unlink(missing_ok=True)
            except OSError as 回滚异常:
                回滚错误.append(str(回滚异常))
        附加 = f"；回滚亦失败：{' | '.join(回滚错误)}" if 回滚错误 else "，已回滚两个输出"
        raise ValueError(f"写入联系表失败：{异常}{附加}") from 异常
    finally:
        for 路径 in (临时图, 临时JSON, 备份图, 备份JSON):
            if 路径 is not None:
                路径.unlink(missing_ok=True)


def 渲染联系表(输入: Path, 清单路径: Path, 输出: Path, 复核输出: Path, 列数: int | None = None, 最大项目数: int = 默认最大项目数, 最大总像素: int = 默认最大总像素) -> tuple[int, int]:
    if Path(输出).resolve(strict=False) == Path(复核输出).resolve(strict=False):
        raise ValueError("联系表 PNG 与复核 JSON 的输出路径不能相同")
    项目 = 读取清单(Path(清单路径))
    if len(项目) > 最大项目数:
        raise ValueError(f"清单项目数不能超过 {最大项目数}")
    输入 = Path(输入)
    if not 输入.is_dir():
        raise ValueError(f"候选输入目录不存在：{输入}")
    列数 = 列数 or min(8, max(1, math.ceil(math.sqrt(len(项目)))))
    if 列数 < 1 or 列数 > 最大项目数:
        raise ValueError(f"列数必须在 1 至 {最大项目数} 之间")
    字体 = ImageFont.load_default()
    最长标签宽 = max(字体.getbbox(项["id"])[2] for 项 in 项目)
    单元宽 = max(最小单元宽, 最长标签宽 + 2 * 标签水平留白)
    if 单元宽 > 最大单元宽 or 单元高 > 最大单元高:
        raise ValueError("联系表单元尺寸超过安全上限")
    行数 = math.ceil(len(项目) / 列数)
    画布尺寸 = (列数 * 单元宽, 行数 * 单元高)
    if 画布尺寸[0] * 画布尺寸[1] > 最大总像素:
        raise ValueError(f"联系表总像素超过安全上限 {最大总像素}")
    画布 = Image.new("RGBA", 画布尺寸, (32, 32, 32, 255))
    画笔, 复核 = ImageDraw.Draw(画布), []
    for 序号, 项 in enumerate(项目):
        x, y = (序号 % 列数) * 单元宽, (序号 // 列数) * 单元高
        候选路径 = (输入 / f"{项['stem']}.png").resolve()
        状态, 哈希, 备注 = "missing", None, "候选图缺失"
        if 候选路径.is_file():
            try:
                with Image.open(候选路径) as 图:
                    if 图.size != (16, 16):
                        raise ValueError(f"{项['id']} 候选图必须是 16x16，实际为 {图.width}x{图.height}")
                    图标 = 图.convert("RGBA").resize((图标尺寸, 图标尺寸), Image.Resampling.NEAREST)
            except (UnidentifiedImageError, OSError) as 异常:
                raise ValueError(f"{项['id']} 候选图无法读取：{异常}") from 异常
            画布.alpha_composite(图标, (x, y))
            状态, 哈希, 备注 = "pending", 文件哈希(候选路径), ""
        else:
            画笔.rectangle((x, y, x + 图标尺寸 - 1, y + 图标尺寸 - 1), fill=(180, 0, 0, 255))
            画笔.text((x + 38, y + 58), "MISSING", fill=(255, 255, 255, 255), font=字体)
        画笔.text((x + 标签水平留白, y + 134), 项["id"], fill=(255, 255, 255, 255), font=字体)
        复核.append({"id": 项["id"], "texture": 项["texture"], "candidate_path": f"{项['stem']}.png", "candidate_sha256": 哈希, "status": 状态, "notes": 备注})
    成对提交(画布, 复核, Path(输出), Path(复核输出))
    return 画布.size


def main() -> int:
    解析器 = argparse.ArgumentParser(description=__doc__)
    解析器.add_argument("--input", type=Path, default=默认输入)
    解析器.add_argument("--manifest", type=Path, default=默认清单)
    解析器.add_argument("--output", type=Path, default=默认输出)
    解析器.add_argument("--review-output", type=Path, default=默认复核输出)
    解析器.add_argument("--max-items", type=int, default=默认最大项目数, help="允许的最大项目数（默认 100）")
    解析器.add_argument("--max-total-pixels", type=int, default=默认最大总像素, help="允许的画布总像素上限")
    参数 = 解析器.parse_args()
    try:
        尺寸 = 渲染联系表(参数.input, 参数.manifest, 参数.output, 参数.review_output, 最大项目数=参数.max_items, 最大总像素=参数.max_total_pixels)
    except ValueError as 异常:
        解析器.error(str(异常))
    print(f"已生成联系表：{参数.output} ({尺寸[0]}x{尺寸[1]})")
    print(f"已生成复核清单：{参数.review_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
