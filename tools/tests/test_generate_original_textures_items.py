import hashlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from tools import generate_original_textures as 被测模块
from tools.render_item_texture_contact_sheet import 特殊贴图尺寸


正式目录 = 被测模块.RESOURCE_ROOT / "assets/blindboxchallenge/textures/item"


class 物品贴图确定性生成测试(unittest.TestCase):
    def test_八张权威方块载荷集合精确且临时重建逐字节一致(self):
        正式集合 = {
            path.relative_to(被测模块.RESOURCE_ROOT).as_posix()
            for path in (被测模块.RESOURCE_ROOT / "assets/blindboxchallenge/textures/block").glob("*.png")
            if path.relative_to(被测模块.RESOURCE_ROOT).as_posix() in 被测模块.TARGETS
        }
        self.assertEqual(8, len(正式集合))
        self.assertTrue(正式集合.issubset(set(被测模块.BLOCK_PNG_PAYLOADS)))
        for relative in sorted(正式集合):
            self.assertEqual(
                (被测模块.RESOURCE_ROOT / relative).read_bytes(),
                被测模块.render_block_payload(relative),
                relative,
            )

    def test_非漂移盔甲仍使用原有算法(self):
        relative = "assets/blindboxchallenge/textures/models/armor/road_barrier_layer_1.png"
        self.assertNotIn(relative, 被测模块.BLOCK_PNG_PAYLOADS)
        self.assertEqual(被测模块.png(被测模块.armor(relative)), 被测模块.render(relative))

    def test_三张穿戴贴图优先使用内嵌完整PNG且不读取文件(self):
        装备目标 = {
            "assets/blindboxchallenge/textures/entity/pink_butterfly_wings.png",
            "assets/blindboxchallenge/textures/models/armor/eggy_eye_mask_layer_1.png",
            "assets/blindboxchallenge/textures/models/armor/face_mask_layer_1.png",
        }
        self.assertEqual(71, len(被测模块.TARGETS))
        self.assertEqual(装备目标, set(被测模块.EQUIPMENT_PNG_PAYLOADS))
        self.assertTrue(装备目标.issubset(被测模块.TARGETS))
        self.assertTrue(装备目标.isdisjoint(被测模块.ITEM_TARGETS))
        for 相对 in sorted(装备目标):
            with patch.object(Path, "read_bytes", side_effect=AssertionError("不得读取正式贴图")), \
                    patch.object(被测模块, "armor", side_effect=AssertionError("不得回退盔甲算法")), \
                    patch.object(被测模块, "render_item", side_effect=AssertionError("不得分派到物品图标")):
                内容 = 被测模块.render(相对)
                self.assertEqual(被测模块.decode_equipment_png(相对), 内容)
            self.assertEqual((被测模块.RESOURCE_ROOT / 相对).read_bytes(), 内容, 相对)
            with Image.open(io.BytesIO(内容)) as 图:
                self.assertEqual((64, 32), 图.size, 相对)
                self.assertEqual("RGBA", 图.mode, 相对)

    def test_物品目标与正式五十九项精确一致(self):
        正式集合 = {
            path.relative_to(被测模块.RESOURCE_ROOT).as_posix()
            for path in 正式目录.glob("*.png")
        }
        self.assertEqual(59, len(正式集合))
        self.assertEqual(正式集合, set(被测模块.ITEM_TARGETS))

    def test_生成到临时目录与正式文件逐字节一致(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            被测模块.write_items(临时根)
            for relative in 被测模块.ITEM_TARGETS:
                正式字节 = (被测模块.RESOURCE_ROOT / relative).read_bytes()
                生成字节 = (临时根 / relative).read_bytes()
                self.assertEqual(hashlib.sha256(正式字节).hexdigest(), hashlib.sha256(生成字节).hexdigest(), relative)
                self.assertEqual(正式字节, 生成字节, relative)

    def test_check_items当前仓库通过且不受方块漂移影响(self):
        with patch.object(被测模块, "render", side_effect=AssertionError("不得调用旧的方块或盔甲渲染")):
            self.assertEqual([], 被测模块.check_items(被测模块.RESOURCE_ROOT))
        结果 = subprocess.run(
            [sys.executable, str(被测模块.__file__), "--check-items"],
            cwd=被测模块.ROOT, text=True, capture_output=True, encoding="utf-8",
            env=dict(os.environ, PYTHONUTF8="1"),
        )
        self.assertEqual(0, 结果.returncode, 结果.stderr)

    def test_check_items能检测临时目录中的单张篡改(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            被测模块.write_items(临时根)
            被改路径 = 临时根 / 被测模块.ITEM_TARGETS[0]
            被改路径.write_bytes(被改路径.read_bytes() + b"tampered")
            self.assertEqual([被测模块.ITEM_TARGETS[0]], 被测模块.check_items(临时根))

    def test_更新清单保留用户参考与高清人物照片来源(self):
        项目 = tuple(f"assets/blindboxchallenge/textures/item/{编号}.png" for 编号 in ("wenxu_standee", "rat_jerky_totem", "wang_lixin_badge", "yijin_manual"))
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            清单 = 临时根 / "manifest.md"
            for 相对 in 项目:
                路径 = 临时根 / 相对
                路径.parent.mkdir(parents=True, exist_ok=True)
                路径.write_bytes(b"new-texture")
            清单.write_text("\n".join(f"|`mod/src/main/resources/{相对}`|`{'0' * 64}`|旧来源|" for 相对 in 项目) + "\n保留其他审计说明\n", encoding="utf-8")
            with patch.object(被测模块, "TARGETS", 项目), patch.object(被测模块, "RESOURCE_ROOT", 临时根), patch.object(被测模块, "MANIFEST", 清单):
                被测模块.update_manifest()
            行 = 清单.read_text(encoding="utf-8").splitlines()
            for 来源行 in 行[:3]:
                self.assertIn("用户外观参考重绘", 来源行)
                self.assertIn("参考图不直接进入发行包", 来源行)
                self.assertNotIn("不读取、采样或混合原图", 来源行)
            self.assertIn("用户提供人物照片", 行[2])
            self.assertIn("不宣称纯文字原创", 行[2])
            self.assertIn("项目内原创重绘", 行[3])
            self.assertEqual("保留其他审计说明", 行[4])

    def test_每项载荷解码符合逐项尺寸且生成器不读取正式图或output(self):
        self.assertEqual(set(被测模块.ITEM_TARGETS), set(被测模块.ITEM_PIXEL_PAYLOADS) | set(被测模块.ITEM_PNG_PAYLOADS))
        for relative in 被测模块.ITEM_TARGETS:
            with patch.object(Path, "read_bytes", side_effect=AssertionError("载荷解码不得读取文件")):
                内容 = 被测模块.render_item(relative)
            with tempfile.TemporaryDirectory() as 临时目录:
                路径 = Path(临时目录) / "item.png"
                路径.write_bytes(内容)
                with Image.open(路径) as 图像:
                    期望尺寸 = 特殊贴图尺寸.get(relative, (16, 16))
                    self.assertEqual(期望尺寸, 图像.size, relative)
                    self.assertEqual("RGBA", 图像.mode, relative)


if __name__ == "__main__":
    unittest.main()
