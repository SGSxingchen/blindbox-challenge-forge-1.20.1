import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image


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


if __name__ == "__main__":
    unittest.main()
