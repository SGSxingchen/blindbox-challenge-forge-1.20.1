import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from PIL import Image
from tools import verify_item_texture_redraw as 验证器


仓库根目录 = Path(__file__).resolve().parents[2]
验证器路径 = 仓库根目录 / "tools" / "verify_item_texture_redraw.py"
清单路径 = 仓库根目录 / "tools" / "item_texture_redraw_manifest.json"
正式贴图目录 = 仓库根目录 / "mod" / "src" / "main" / "resources" / "assets" / "blindboxchallenge" / "textures" / "item"


class 物品贴图重绘验证测试(unittest.TestCase):
    def setUp(self):
        if self._testMethodName != "test_验证器与清单已经建立" and (not 验证器路径.is_file() or not 清单路径.is_file()):
            self.skipTest("等待验证器与清单实现")

    def 运行验证(self, 清单, 贴图目录=正式贴图目录):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时清单 = Path(临时目录) / "manifest.json"
            临时清单.write_text(json.dumps(清单, ensure_ascii=False), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(验证器路径), "--validate-manifest", "--manifest", str(临时清单), "--texture-root", str(贴图目录)],
                cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            )

    @classmethod
    def setUpClass(cls):
        cls.有效清单 = json.loads(清单路径.read_text(encoding="utf-8")) if 清单路径.is_file() else []

    def test_验证器与清单已经建立(self):
        self.assertTrue(验证器路径.is_file(), "验证器尚未实现")
        self.assertTrue(清单路径.is_file(), "重绘清单尚未建立")

    def test_有效清单通过(self):
        结果 = self.运行验证(self.有效清单)
        self.assertEqual(0, 结果.returncode, 结果.stdout + 结果.stderr)

    def test_缺少字段失败(self):
        清单 = copy.deepcopy(self.有效清单)
        del 清单[0]["subject"]
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_重复路径失败(self):
        清单 = copy.deepcopy(self.有效清单)
        清单[1]["texture"] = 清单[0]["texture"]
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_非59项失败(self):
        self.assertNotEqual(0, self.运行验证(self.有效清单[:-1]).returncode)

    def test_贴图集合不一致失败(self):
        清单 = copy.deepcopy(self.有效清单)
        清单[0]["texture"] = "assets/blindboxchallenge/textures/item/not_present.png"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_非法参考状态失败(self):
        清单 = copy.deepcopy(self.有效清单)
        清单[0]["reference_status"] = "unknown"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_无参考设计集合不正确失败(self):
        清单 = copy.deepcopy(self.有效清单)
        目标 = next(项 for 项 in 清单 if 项["id"] == "blind_box")
        目标["reference_status"] = "catalog_reference"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_禁项缺失失败(self):
        清单 = copy.deepcopy(self.有效清单)
        清单[0]["avoid"].remove("广告背景")
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_avoid包含对象时输出结构化错误且无traceback(self):
        清单 = copy.deepcopy(self.有效清单)
        清单[0]["avoid"] = [{"非法": "对象"}]
        结果 = self.运行验证(清单)
        self.assertNotEqual(0, 结果.returncode)
        self.assertIn("avoid 第 1 个元素必须是非空字符串", 结果.stderr)
        self.assertNotIn("Traceback", 结果.stderr)

    def test_空泛语义替换全部逐项规格失败(self):
        清单 = copy.deepcopy(self.有效清单)
        for 项 in 清单:
            项["subject"] = "物品"
            项["must_show"] = "一个物品"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_食品只画包装不画本体失败(self):
        清单 = copy.deepcopy(self.有效清单)
        牛肉粒 = next(项 for 项 in 清单 if 项["id"] == "beef_bites")
        牛肉粒["subject"] = "品牌包装"
        牛肉粒["must_show"] = "只画品牌包装，不画牛肉本体"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_食品专项规则直接拒绝只画包装(self):
        错误 = 验证器.校验类别关键词({
            "id": "beef_bites", "subject": "品牌包装", "must_show": "只画包装，不画食物本体"
        }, 7)
        self.assertIn("第 7 项食品规格禁止只画包装或排除食物本体", 错误)
        self.assertTrue(any("食品本体契约" in 内容 for 内容 in 错误))

    def test_书籍复刻人物和可读标题失败(self):
        清单 = copy.deepcopy(self.有效清单)
        笔记 = next(项 for 项 in 清单 if 项["id"] == "death_note")
        笔记["subject"] = "完整复刻动漫人物"
        笔记["must_show"] = "完整复刻动漫人物和可读标题"
        self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_书籍专项规则直接要求外形材质和不可读符号(self):
        错误 = 验证器.校验类别关键词({
            "id": "death_note", "subject": "完整复刻动漫人物", "must_show": "可读标题"
        }, 8)
        self.assertTrue(any("第 8 项不符合书/纸币/徽章/旗外形材质与抽象符号契约" in 内容 for 内容 in 错误))

    def test_状态变体专项规则直接拒绝替换编号(self):
        错误 = 验证器.校验状态变体({
            "texture": "assets/blindboxchallenge/textures/item/black_knight_telescopic_knife.png",
            "id": "replaced_state",
        }, 9)
        self.assertEqual(["第 9 项 001/002 状态变体 id 必须为 black_knight_telescopic_knife"], 错误)

    def test_001与002四个状态编号被替换均失败(self):
        状态编号 = {
            "black_knight_telescopic_knife",
            "black_knight_telescopic_knife_extended",
            "purple_toy_pickaxe_sword_pickaxe",
            "purple_toy_pickaxe_sword_sword",
        }
        for 编号 in 状态编号:
            with self.subTest(编号=编号):
                清单 = copy.deepcopy(self.有效清单)
                next(项 for 项 in 清单 if 项["id"] == 编号)["id"] = "replaced_state"
                self.assertNotEqual(0, self.运行验证(清单).returncode)

    def test_baseline恰好59项且包含完整元数据(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输出 = Path(临时目录) / "baseline.json"
            结果 = subprocess.run(
                [sys.executable, str(验证器路径), "--write-baseline", str(输出)],
                cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            )
            self.assertEqual(0, 结果.returncode, 结果.stdout + 结果.stderr)
            基线 = json.loads(输出.read_text(encoding="utf-8"))
            self.assertEqual(59, len(基线))
            for 项 in 基线:
                self.assertEqual({"path", "width", "height", "mode", "sha256", "git_status"}, set(项))
                self.assertEqual(64, len(项["sha256"]))
                文件 = 仓库根目录 / 项["path"]
                self.assertEqual(hashlib.sha256(文件.read_bytes()).hexdigest(), 项["sha256"])

    def test_Git状态命令失败时baseline失败且不写文件(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输出 = Path(临时目录) / "baseline.json"
            with mock.patch.object(验证器.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout="", stderr="失败")):
                错误 = 验证器.写入基线(输出, 正式贴图目录)
            self.assertTrue(any("Git 状态查询失败" in 内容 for 内容 in 错误))
            self.assertFalse(输出.exists())

    def test_Git不存在时baseline失败且无异常(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输出 = Path(临时目录) / "baseline.json"
            with mock.patch.object(验证器.subprocess, "run", side_effect=FileNotFoundError("git 不存在")):
                错误 = 验证器.写入基线(输出, 正式贴图目录)
            self.assertTrue(any("无法执行 Git" in 内容 for 内容 in 错误))
            self.assertFalse(输出.exists())

    def test_校验与写基线参数互斥且不生成文件(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输出 = Path(临时目录) / "baseline.json"
            结果 = subprocess.run(
                [sys.executable, str(验证器路径), "--validate-manifest", "--write-baseline", str(输出)],
                cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            )
            self.assertEqual(2, 结果.returncode)
            self.assertFalse(输出.exists())


if __name__ == "__main__":
    unittest.main()
