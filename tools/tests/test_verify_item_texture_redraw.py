import copy
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from PIL import Image


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
                ["python", str(验证器路径), "--validate-manifest", "--manifest", str(临时清单), "--texture-root", str(贴图目录)],
                cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace"
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

    def test_baseline恰好59项且包含完整元数据(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输出 = Path(临时目录) / "baseline.json"
            结果 = subprocess.run(
                ["python", str(验证器路径), "--write-baseline", str(输出)],
                cwd=仓库根目录, text=True, capture_output=True, encoding="utf-8", errors="replace"
            )
            self.assertEqual(0, 结果.returncode, 结果.stdout + 结果.stderr)
            基线 = json.loads(输出.read_text(encoding="utf-8"))
            self.assertEqual(59, len(基线))
            for 项 in 基线:
                self.assertEqual({"path", "width", "height", "mode", "sha256", "git_status"}, set(项))
                self.assertEqual(64, len(项["sha256"]))
                文件 = 仓库根目录 / 项["path"]
                self.assertEqual(hashlib.sha256(文件.read_bytes()).hexdigest(), 项["sha256"])


if __name__ == "__main__":
    unittest.main()
