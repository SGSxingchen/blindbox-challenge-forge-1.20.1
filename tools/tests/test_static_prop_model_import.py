import json
import unittest
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2] / "mod/src/main/resources/assets/blindboxchallenge"


class 静态人工模型导入测试(unittest.TestCase):
    def test_人工模型元素数与英文纹理引用(self):
        minimum = {"anywhere_door": 14, "neutral_trophy": 9, "floor_art_panel": 3,
                   "abstract_white_figurine": 12, "stone_pillow": 62, "diamond_pillow": 8}
        for name, count in minimum.items():
            model = json.loads((ROOT / f"models/block/{name}.json").read_text("utf-8"))
            self.assertEqual(count, len(model["elements"]), name)
            for texture in model.get("textures", {}).values():
                self.assertNotRegex(texture, r"[\u4e00-\u9fff]|^block/texture$|^alex$")

    def test_钻石抱枕具有可见厚度(self):
        model = json.loads((ROOT / "models/block/diamond_pillow.json").read_text("utf-8"))
        self.assertGreaterEqual(max(e["to"][1] for e in model["elements"]) - min(e["from"][1] for e in model["elements"]), 3)
        self.assertGreaterEqual(min(e["from"][1] for e in model["elements"]), 0)

    def test_奖杯每个元素三轴均有实体厚度(self):
        model = json.loads((ROOT / "models/block/neutral_trophy.json").read_text("utf-8"))
        for index, element in enumerate(model["elements"]):
            for axis in range(3):
                self.assertGreater(element["to"][axis], element["from"][axis], (index, axis))

    def test_应援棒四模型保留两段人工几何(self):
        for suffix in ("off", "on", "wall_off", "wall_on"):
            model = json.loads((ROOT / f"models/block/bml_cheer_stick_{suffix}.json").read_text("utf-8"))
            self.assertEqual(2, len(model["elements"]), suffix)
        state = json.loads((ROOT / "blockstates/bml_cheer_stick_wall.json").read_text("utf-8"))
        self.assertEqual(8, len(state["variants"]))

    def test_墙面应援棒已烘焙为贴墙姿态(self):
        for suffix in ("wall_off", "wall_on"):
            model = json.loads((ROOT / f"models/block/bml_cheer_stick_{suffix}.json").read_text("utf-8"))
            # 墙面版本沿 Z 向墙外伸出，竖向高度明显小于站立版本。
            y_span = max(e["to"][1] for e in model["elements"]) - min(e["from"][1] for e in model["elements"])
            z_span = max(e["to"][2] for e in model["elements"]) - min(e["from"][2] for e in model["elements"])
            self.assertGreater(z_span, y_span, suffix)
            min_z = min(e["from"][2] for e in model["elements"])
            self.assertGreaterEqual(min_z, 0, suffix)
            self.assertAlmostEqual(0, min_z, delta=0.001, msg=suffix)
            self.assertGreater(max(e["to"][2] for e in model["elements"]), 0, suffix)

    def test_配套纹理均为合理的二次幂尺寸(self):
        for name in ("anywhere_door", "neutral_trophy", "floor_art_panel", "abstract_white_figurine", "stone_pillow", "diamond_pillow", "bml_cheer_stick_off", "bml_cheer_stick_on"):
            with Image.open(ROOT / f"textures/block/{name}.png") as image:
                self.assertIn(image.width, (16, 32, 64, 128, 256))
                self.assertIn(image.height, (16, 32, 64, 128, 256))


if __name__ == "__main__":
    unittest.main()
