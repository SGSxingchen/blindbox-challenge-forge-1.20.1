#!/usr/bin/env python3
"""逐项生成并整理物品贴图候选；不会修改正式资源。"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from PIL import Image


仓库根目录 = Path(__file__).resolve().parents[1]
默认清单 = 仓库根目录 / "tools/item_texture_redraw_manifest.json"
默认输出根 = 仓库根目录 / "output/imagegen/item-redraw"
生图脚本 = Path(r"C:\Users\85330\.codex\skills\chordvers-imagegen\scripts\chordvers_imagegen.py")
键色 = (0, 255, 0)


def 构造提示词(项: dict) -> str:
    禁止内容 = "、".join(项["avoid"])
    配色 = "、".join(项["palette"])
    return (
        f"制作一个 Minecraft 物品栏图标：{项['subject']}。只画单个完整物品，"
        f"正视或轻微3/4视角；必须清楚表现：{项['must_show']}。"
        f"采用硬边像素画、无抗锯齿、有限色板，配色仅围绕：{配色}。"
        "画布使用纯#00FF00键色背景，主体不含键色。"
        "无场景、无地面、无阴影、无文字、无品牌、无包装、无水印、无人物手持。"
        f"逐项额外禁用：{禁止内容}。不要合并或补充其他物品。"
    )


def 构造命令(项: dict, 请求输出: Path) -> list[str]:
    return [
        sys.executable, str(生图脚本), "generate", "--model", "gpt-image-2",
        "--prompt", 构造提示词(项), "--chroma-key", "#00FF00",
        "--output", str(请求输出),
    ]


def 选择项目(清单: list[dict], 选择: list[str], 全部: bool) -> list[dict]:
    if 全部:
        return list(清单)
    索引: dict[str, dict] = {}
    for 项 in 清单:
        索引[项["id"]] = 项
        索引[Path(项["texture"]).stem] = 项
    未找到 = [值 for 值 in 选择 if 值 not in 索引]
    if 未找到:
        raise ValueError("未找到选择项：" + "、".join(未找到))
    结果: list[dict] = []
    已有: set[str] = set()
    for 值 in 选择:
        项 = 索引[值]
        if 项["id"] not in 已有:
            结果.append(项)
            已有.add(项["id"])
    return 结果


def 文件哈希(路径: Path) -> str:
    return hashlib.sha256(路径.read_bytes()).hexdigest()


def 可恢复跳过(元数据路径: Path, 候选路径: Path) -> bool:
    try:
        数据 = json.loads(元数据路径.read_text(encoding="utf-8"))
        return 数据.get("生成状态") == "成功" and 文件哈希(候选路径) == 数据.get("候选SHA256")
    except (OSError, ValueError, json.JSONDecodeError):
        return False


def _二值去背(图像: Image.Image) -> Image.Image:
    rgba = 图像.convert("RGBA")
    输出 = Image.new("RGBA", rgba.size)
    像素 = []
    for 红, 绿, 蓝, alpha in rgba.get_flattened_data():
        是键色 = 绿 >= 190 and 红 <= 80 and 蓝 <= 80 and 绿 - max(红, 蓝) >= 100
        像素.append((红, 绿, 蓝, 0 if 是键色 or alpha < 128 else 255))
    输出.putdata(像素)
    return 输出


def 处理图像(源路径: Path, 清理路径: Path, 候选路径: Path) -> tuple[int, int]:
    with Image.open(源路径) as 已打开:
        已打开.load()
        原始尺寸 = 已打开.size
        透明图 = _二值去背(已打开)
    边界 = 透明图.getchannel("A").getbbox()
    if 边界 is None:
        raise ValueError("去除键色后没有可见主体")
    裁切 = 透明图.crop(边界)
    清理路径.parent.mkdir(parents=True, exist_ok=True)
    裁切.save(清理路径)
    比例 = min(14 / 裁切.width, 14 / 裁切.height)
    新尺寸 = (max(1, round(裁切.width * 比例)), max(1, round(裁切.height * 比例)))
    缩放 = 裁切.resize(新尺寸, Image.Resampling.NEAREST)
    画布 = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    画布.alpha_composite(缩放, ((16 - 新尺寸[0]) // 2, (16 - 新尺寸[1]) // 2))
    候选路径.parent.mkdir(parents=True, exist_ok=True)
    画布.save(候选路径)
    return 原始尺寸


def _实际源文件(目录: Path, 请求输出: Path, 生成前: set[Path]) -> Path:
    生成后 = set(目录.glob(f"{请求输出.stem}*.png"))
    新文件 = [路径 for 路径 in 生成后 - 生成前 if 路径.is_file()]
    if 新文件:
        return max(新文件, key=lambda 路径: 路径.stat().st_mtime_ns)
    raise FileNotFoundError("生图命令成功，但未找到本次实际输出 PNG")


def _写元数据(路径: Path, 数据: dict) -> None:
    路径.parent.mkdir(parents=True, exist_ok=True)
    路径.write_text(json.dumps(数据, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def 生成单项(项: dict, 输出根: Path, 运行器: Callable = subprocess.run, 演练: bool = False) -> str:
    stem = Path(项["texture"]).stem
    请求源 = 输出根 / "sources" / f"{项['id']}.png"
    清理 = 输出根 / "clean" / f"{stem}.png"
    候选 = 输出根 / "candidates" / f"{stem}.png"
    元数据 = 输出根 / "metadata" / f"{stem}.json"
    命令 = 构造命令(项, 请求源)
    基础 = {
        "资源id": 项["id"], "texture": 项["texture"], "最终提示词": 构造提示词(项),
        "命令": 命令, "时间": datetime.now(timezone.utc).isoformat(),
    }
    if 演练:
        print(json.dumps(基础, ensure_ascii=False))
        return "演练"
    请求源.parent.mkdir(parents=True, exist_ok=True)
    生成前 = set(请求源.parent.glob(f"{请求源.stem}*.png"))
    try:
        结果 = 运行器(命令, text=True, capture_output=True, encoding="utf-8", errors="replace")
        if 结果.returncode != 0:
            raise RuntimeError(f"生图子进程失败（退出码 {结果.returncode}）")
        实际源 = _实际源文件(请求源.parent, 请求源, 生成前)
        尺寸 = 处理图像(实际源, 清理, 候选)
        _写元数据(元数据, 基础 | {
            "源文件": str(实际源), "源文件实际尺寸": list(尺寸), "clean文件": str(清理),
            "候选文件": str(候选), "候选SHA256": 文件哈希(候选), "生成状态": "成功",
        })
        return "成功"
    except Exception as 异常:
        候选.unlink(missing_ok=True)
        清理.unlink(missing_ok=True)
        原因 = str(异常) if isinstance(异常, (FileNotFoundError, ValueError, RuntimeError)) else type(异常).__name__
        _写元数据(元数据, 基础 | {"生成状态": "失败", "失败原因": 原因})
        return "失败"


def main() -> int:
    解析器 = argparse.ArgumentParser(description=__doc__)
    模式 = 解析器.add_mutually_exclusive_group(required=True)
    模式.add_argument("--only", action="append", default=[], metavar="ID或贴图名")
    模式.add_argument("--all", action="store_true")
    解析器.add_argument("--dry-run", action="store_true")
    解析器.add_argument("--resume", action="store_true")
    解析器.add_argument("--output-root", type=Path, default=默认输出根)
    参数 = 解析器.parse_args()
    try:
        清单 = json.loads(默认清单.read_text(encoding="utf-8"))
        项目 = 选择项目(清单, 参数.only, 参数.all)
    except (OSError, json.JSONDecodeError, ValueError) as 异常:
        解析器.error(str(异常))
    失败 = 0
    for 项 in 项目:
        stem = Path(项["texture"]).stem
        if 参数.resume and 可恢复跳过(参数.output_root / "metadata" / f"{stem}.json", 参数.output_root / "candidates" / f"{stem}.png"):
            print(f"跳过（已验证哈希）：{项['id']}")
            continue
        状态 = 生成单项(项, 参数.output_root, 演练=参数.dry_run)
        print(f"{项['id']}：{状态}")
        失败 += 状态 == "失败"
    return 1 if 失败 else 0


if __name__ == "__main__":
    raise SystemExit(main())
