import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageDraw

from tools import verify_quality_contract as 被测模块


class 穿戴与荧光棒UV契约测试(unittest.TestCase):
    def setUp(self):
        self.临时目录 = tempfile.TemporaryDirectory()
        self.资源 = Path(self.临时目录.name)
        self.眼罩 = self.资源 / "textures/models/armor/eggy_eye_mask_layer_1.png"
        self.口罩 = self.资源 / "textures/models/armor/face_mask_layer_1.png"
        self.翅膀 = self.资源 / "textures/entity/pink_butterfly_wings.png"
        self.荧光棒 = self.资源 / "textures/block/glow_stick.png"
        for 路径 in (self.眼罩, self.口罩):
            self.写图(路径, (64, 32), ((8, 10, 15, 12),))
        self.写图(self.翅膀, (64, 32), ((24, 3, 33, 20), (36, 3, 45, 20)))
        self.写图(self.荧光棒, (16, 16), ((7, 6, 8, 15),))
        self.模型 = {}
        for 编号, 父模型 in (("glow_stick", "minecraft:block/torch"),
                            ("glow_stick_wall", "minecraft:block/wall_torch")):
            路径 = self.资源 / f"models/block/{编号}.json"
            路径.parent.mkdir(parents=True, exist_ok=True)
            路径.write_text(json.dumps({
                "parent": 父模型,
                "textures": {"torch": "blindboxchallenge:block/glow_stick"},
                "render_type": "minecraft:cutout",
            }), encoding="utf-8")
            self.模型[编号] = 路径

    def tearDown(self):
        self.临时目录.cleanup()

    def 写图(self, 路径, 尺寸, 矩形):
        图 = Image.new("RGBA", 尺寸)
        画笔 = ImageDraw.Draw(图)
        for 区域 in 矩形:
            画笔.rectangle(区域, fill=(200, 100, 150, 255))
        路径.parent.mkdir(parents=True, exist_ok=True)
        图.save(路径)

    def 改像素(self, 路径, 坐标, 颜色):
        with Image.open(路径) as 原图:
            图 = 原图.copy()
        图.putpixel(坐标, 颜色)
        图.save(路径)

    def 验证(self):
        with patch.object(被测模块, "ASSETS", self.资源):
            被测模块.check_wearable_and_glow_textures()

    def test_正确UV留白与两种cutout模型通过(self):
        self.验证()

    def test_装备拒绝错误尺寸(self):
        self.写图(self.眼罩, (32, 32), ((8, 10, 15, 12),))
        with self.assertRaisesRegex(SystemExit, "64×32 RGBA PNG"):
            self.验证()

    def test_装备拒绝缺失或损坏PNG(self):
        self.眼罩.write_bytes(b"not-png")
        with self.assertRaisesRegex(SystemExit, "无法读取贴图"):
            self.验证()

    def test_头饰拒绝头顶或身体UV污染(self):
        for 坐标 in ((8, 0), (40, 20)):
            with self.subTest(坐标=坐标):
                self.改像素(self.眼罩, 坐标, (1, 2, 3, 255))
                with self.assertRaisesRegex(SystemExit, "UV 边界或污染透明区"):
                    self.验证()
                self.改像素(self.眼罩, 坐标, (0, 0, 0, 0))

    def test_头饰拒绝无图形或整面覆盖(self):
        for 区域 in ((), ((8, 8, 15, 15),)):
            with self.subTest(区域=区域):
                self.写图(self.口罩, (64, 32), 区域)
                with self.assertRaisesRegex(SystemExit, "主要面须有图形和透明留白"):
                    self.验证()

    def test_翅膀拒绝UV外像素(self):
        self.改像素(self.翅膀, (23, 0), (1, 2, 3, 255))
        with self.assertRaisesRegex(SystemExit, "UV 边界或污染透明区"):
            self.验证()

    def test_翅膀背面不能缺失(self):
        self.写图(self.翅膀, (64, 32), ((24, 3, 33, 20),))
        with self.assertRaisesRegex(SystemExit, "主要面须有图形和透明留白"):
            self.验证()

    def test_穿戴与荧光棒都拒绝半透明像素(self):
        for 路径, 坐标 in ((self.眼罩, (8, 10)), (self.口罩, (8, 10)),
                           (self.翅膀, (24, 3)), (self.荧光棒, (7, 6))):
            with self.subTest(路径=路径.name):
                self.改像素(路径, 坐标, (1, 2, 3, 128))
                with self.assertRaisesRegex(SystemExit, "透明度须为二值"):
                    self.验证()
                self.改像素(路径, 坐标, (200, 100, 150, 255))

    def test_荧光棒拒绝背景或细杆断口(self):
        for 坐标, 颜色 in (((0, 0), (1, 2, 3, 255)), ((7, 7), (0, 0, 0, 0))):
            with self.subTest(坐标=坐标):
                self.写图(self.荧光棒, (16, 16), ((7, 6, 8, 15),))
                self.改像素(self.荧光棒, 坐标, 颜色)
                with self.assertRaisesRegex(SystemExit, "完整两像素细杆与端盖"):
                    self.验证()

    def test_两种荧光棒模型都必须cutout(self):
        for 路径 in self.模型.values():
            with self.subTest(模型=路径.name):
                内容 = 路径.read_text(encoding="utf-8")
                模型 = json.loads(内容)
                模型.pop("render_type")
                路径.write_text(json.dumps(模型), encoding="utf-8")
                with self.assertRaisesRegex(SystemExit, "使用 cutout"):
                    self.验证()
                路径.write_text(内容, encoding="utf-8")

    def test_荧光棒模型不能更换UV父模型或贴图引用(self):
        路径 = self.模型["glow_stick_wall"]
        原模型 = json.loads(路径.read_text(encoding="utf-8"))
        for 键, 值 in (("parent", "minecraft:block/cube_all"), ("textures", {"torch": "blindboxchallenge:block/other"})):
            with self.subTest(键=键):
                路径.write_text(json.dumps(dict(原模型, **{键: 值})), encoding="utf-8")
                with self.assertRaisesRegex(SystemExit, "保持原版火把 UV"):
                    self.验证()


if __name__ == "__main__":
    unittest.main()
