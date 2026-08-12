import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image, ImageDraw, ImageFont


根目录 = Path(__file__).resolve().parents[2]
脚本路径 = 根目录 / "tools/render_item_texture_contact_sheet.py"
规格 = importlib.util.spec_from_file_location("联系表工具", 脚本路径)
模块 = importlib.util.module_from_spec(规格)
规格.loader.exec_module(模块)


class 联系表测试(unittest.TestCase):
    def setUp(self):
        self.临时目录 = tempfile.TemporaryDirectory()
        self.根 = Path(self.临时目录.name)
        self.候选 = self.根 / "candidates"
        self.候选.mkdir()
        self.清单 = self.根 / "manifest.json"
        self.输出 = self.根 / "contact.png"
        self.复核 = self.根 / "review.json"

    def tearDown(self):
        self.临时目录.cleanup()

    def 写清单(self, *编号):
        数据 = [{"id": 编号, "texture": f"assets/blindboxchallenge/textures/item/{编号}.png"} for 编号 in 编号]
        self.清单.write_text(json.dumps(数据), encoding="utf-8")

    def 写图(self, 编号, 颜色):
        路径 = self.候选 / f"{编号}.png"
        Image.new("RGBA", (16, 16), 颜色).save(路径)
        return 路径

    def test_按清单顺序生成并记录哈希(self):
        self.写清单("second", "first")
        二 = self.写图("second", (255, 0, 0, 255))
        self.写图("first", (0, 0, 255, 255))
        模块.渲染联系表(self.候选, self.清单, self.输出, self.复核, 列数=2)
        复核 = json.loads(self.复核.read_text(encoding="utf-8"))
        self.assertEqual(["second", "first"], [项["id"] for 项 in 复核])
        self.assertEqual(hashlib.sha256(二.read_bytes()).hexdigest(), 复核[0]["candidate_sha256"])
        self.assertEqual("second.png", 复核[0]["candidate_path"])
        self.assertEqual("pending", 复核[0]["status"])
        with Image.open(self.输出) as 图:
            self.assertGreaterEqual(图.width, 256)
            self.assertGreaterEqual(图.height, 160)
            self.assertEqual((255, 0, 0, 255), 图.getpixel((64, 64)))
            self.assertEqual((0, 0, 255, 255), 图.getpixel((192, 64)))

    def test_缺图显示红色并记录missing(self):
        self.写清单("missing_item")
        模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        项 = json.loads(self.复核.read_text(encoding="utf-8"))[0]
        self.assertEqual("missing", 项["status"])
        self.assertIsNone(项["candidate_sha256"])
        with Image.open(self.输出) as 图:
            self.assertGreater(图.getpixel((8, 8))[0], 150)

    def test_输出可原子替换且不留临时文件(self):
        self.写清单("one")
        self.写图("one", (1, 2, 3, 255))
        self.输出.write_bytes(b"old")
        self.复核.write_text("old", encoding="utf-8")
        模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        self.assertEqual(b"\x89PNG", self.输出.read_bytes()[:4])
        self.assertIsInstance(json.loads(self.复核.read_text(encoding="utf-8")), list)
        self.assertEqual([], list(self.根.glob("*.tmp")))

    def test_拒绝非十六像素候选(self):
        self.写清单("bad")
        Image.new("RGBA", (15, 16)).save(self.候选 / "bad.png")
        with self.assertRaisesRegex(ValueError, "16x16"):
            模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        self.assertFalse(self.输出.exists())

    def test_拒绝非法清单且不覆盖旧输出(self):
        self.清单.write_text('[{"id":"../bad","texture":"bad.png"}]', encoding="utf-8")
        self.输出.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        self.assertEqual(b"keep", self.输出.read_bytes())

    def test_完整清单的每个标签都不越过单元边界(self):
        完整清单 = 根目录 / "tools/item_texture_redraw_manifest.json"
        数据 = json.loads(完整清单.read_text(encoding="utf-8"))
        尺寸 = 模块.渲染联系表(self.候选, 完整清单, self.输出, self.复核)
        列数 = 8
        单元宽 = 尺寸[0] // 列数
        测量图 = Image.new("RGBA", (1, 1))
        画笔 = ImageDraw.Draw(测量图)
        字体 = ImageFont.load_default()
        for 项 in 数据:
            边界 = 画笔.textbbox((3, 134), 项["id"], font=字体)
            self.assertGreaterEqual(单元宽 - 3, 边界[2], 项["id"])
        self.assertGreaterEqual(尺寸[1], 8 * 160)

    def test_第二个替换失败时回滚两个旧输出且无残片(self):
        self.写清单("one")
        self.写图("one", (1, 2, 3, 255))
        self.输出.write_bytes(b"old-png")
        self.复核.write_bytes(b"old-json")
        真替换 = os.replace
        次数 = 0

        def 第二次失败(源, 目标):
            nonlocal 次数
            次数 += 1
            if 次数 == 2:
                raise OSError("注入失败")
            return 真替换(源, 目标)

        with mock.patch.object(模块.os, "replace", side_effect=第二次失败):
            with self.assertRaisesRegex(ValueError, "回滚"):
                模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        self.assertEqual(b"old-png", self.输出.read_bytes())
        self.assertEqual(b"old-json", self.复核.read_bytes())
        self.assertEqual([], [p for p in self.根.rglob(".*") if p.is_file()])

    def test_JSON父路径错误时不改动旧图且无残片(self):
        self.写清单("one")
        self.写图("one", (1, 2, 3, 255))
        self.输出.write_bytes(b"old-png")
        阻塞 = self.根 / "blocked"
        阻塞.write_bytes(b"not-a-directory")
        with self.assertRaisesRegex(ValueError, "写入联系表"):
            模块.渲染联系表(self.候选, self.清单, self.输出, 阻塞 / "review.json")
        self.assertEqual(b"old-png", self.输出.read_bytes())
        self.assertEqual(b"not-a-directory", 阻塞.read_bytes())
        self.assertEqual([], [p for p in self.根.rglob(".*") if p.is_file()])

    def test_百万字符编号在分配画布前被拒绝(self):
        self.写清单("a" * 1_000_000)
        with mock.patch.object(模块.Image, "new") as 分配:
            with self.assertRaisesRegex(ValueError, "128"):
                模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
            分配.assert_not_called()

    def test_过多项目在分配画布前被拒绝(self):
        self.写清单(*(f"item_{i}" for i in range(101)))
        with mock.patch.object(模块.Image, "new") as 分配:
            with self.assertRaisesRegex(ValueError, "100"):
                模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
            分配.assert_not_called()

    def test_总像素超限在分配画布前被拒绝(self):
        self.写清单("one")
        with mock.patch.object(模块.Image, "new") as 分配:
            with self.assertRaisesRegex(ValueError, "总像素"):
                模块.渲染联系表(self.候选, self.清单, self.输出, self.复核, 最大总像素=1)
            分配.assert_not_called()

    def test_损坏PNG的CLI错误为中文且无traceback(self):
        self.写清单("broken")
        (self.候选 / "broken.png").write_bytes(b"not-png")
        环境 = dict(os.environ)
        环境["PYTHONUTF8"] = "1"
        结果 = subprocess.run([
            sys.executable, str(脚本路径), "--input", str(self.候选), "--manifest", str(self.清单),
            "--output", str(self.输出), "--review-output", str(self.复核),
        ], text=True, encoding="utf-8", capture_output=True, env=环境)
        self.assertNotEqual(0, 结果.returncode)
        self.assertIn("候选图无法读取", 结果.stderr)
        self.assertNotIn("Traceback", 结果.stderr)

    def test_复核路径不携带临时目录绝对路径(self):
        self.写清单("portable")
        self.写图("portable", (1, 2, 3, 255))
        模块.渲染联系表(self.候选, self.清单, self.输出, self.复核)
        路径 = json.loads(self.复核.read_text(encoding="utf-8"))[0]["candidate_path"]
        self.assertEqual("portable.png", 路径)
        self.assertNotIn(str(self.根), 路径)
        self.assertNotIn("\\", 路径)

    def test_字面相同的两个输出路径在写入前被拒绝(self):
        self.写清单("one")
        self.写图("one", (1, 2, 3, 255))
        self.输出.write_bytes(b"keep")
        with self.assertRaisesRegex(ValueError, "不能相同"):
            模块.渲染联系表(self.候选, self.清单, self.输出, self.输出)
        self.assertEqual(b"keep", self.输出.read_bytes())
        self.assertEqual([], [p for p in self.根.rglob(".*") if p.is_file()])

    def test_经点点规范化后相同的输出路径在写入前被拒绝(self):
        self.写清单("one")
        self.写图("one", (1, 2, 3, 255))
        self.输出.write_bytes(b"keep")
        等价路径 = self.根 / "unused" / ".." / self.输出.name
        with self.assertRaisesRegex(ValueError, "不能相同"):
            模块.渲染联系表(self.候选, self.清单, self.输出, 等价路径)
        self.assertEqual(b"keep", self.输出.read_bytes())
        self.assertFalse((self.根 / "unused").exists())
        self.assertEqual([], [p for p in self.根.rglob(".*") if p.is_file()])


if __name__ == "__main__":
    unittest.main()
