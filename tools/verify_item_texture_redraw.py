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
        状态 = 项.get("reference_status")
        if 状态 not in 合法参考状态:
            错误.append(f"第 {序号} 项 reference_status 非法：{状态}")
        elif 状态 == "free_design" and isinstance(项.get("id"), str):
            无参考编号.add(项["id"])
        禁项 = 项.get("avoid")
        if not isinstance(禁项, list):
            错误.append(f"第 {序号} 项 avoid 必须是数组")
        else:
            少禁项 = 必需禁项 - set(禁项)
            if 少禁项:
                错误.append(f"第 {序号} 项 avoid 缺少禁项：{', '.join(sorted(少禁项))}")
        配色 = 项.get("palette")
        if not isinstance(配色, list) or not 配色 or not all(isinstance(颜色, str) and 颜色.strip() for 颜色 in 配色):
            错误.append(f"第 {序号} 项 palette 必须是非空字符串数组")

    if len(路径列表) != len(set(路径列表)):
        错误.append("清单 texture 路径存在重复")
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


def 查询Git状态(相对路径: str) -> str:
    结果 = subprocess.run(
        ["git", "status", "--short", "--", 相对路径],
        cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace"
    )
    if 结果.returncode != 0:
        return "查询失败"
    return 结果.stdout.rstrip("\r\n") or "clean"


def 写入基线(输出路径: Path, 贴图目录: Path) -> list[str]:
    贴图 = 正式贴图路径(贴图目录)
    if len(贴图) != 59:
        return [f"正式贴图目录必须恰好包含 59 张 PNG，当前为 {len(贴图)} 张"]
    基线 = []
    for 路径 in 贴图.values():
        相对路径 = 路径.relative_to(仓库根目录).as_posix()
        with Image.open(路径) as 图像:
            宽, 高 = 图像.size
            模式 = 图像.mode
        基线.append({
            "path": 相对路径,
            "width": 宽,
            "height": 高,
            "mode": 模式,
            "sha256": hashlib.sha256(路径.read_bytes()).hexdigest(),
            "git_status": 查询Git状态(相对路径),
        })
    输出路径.parent.mkdir(parents=True, exist_ok=True)
    输出路径.write_text(json.dumps(基线, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return []


def main() -> int:
    解析器 = argparse.ArgumentParser(description=__doc__)
    解析器.add_argument("--validate-manifest", action="store_true", help="校验重绘清单（默认行为）")
    解析器.add_argument("--write-baseline", type=Path, help="把正式贴图基线写入指定路径")
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
