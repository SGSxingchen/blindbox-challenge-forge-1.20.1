import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools import generate_item_texture_candidates as 被测模块


示例项 = {
    "id": "blind_box",
    "texture": "assets/blindboxchallenge/textures/item/blind_box.png",
    "subject": "盲盒",
    "must_show": "盒盖接缝和抽象问号形色块",
    "palette": ["靛蓝", "紫罗兰", "金黄"],
    "avoid": ["广告背景", "包装", "品牌", "文字", "水印", "人物手持"],
}


class 候选生成测试(unittest.TestCase):
    def test_提示词包含逐项内容与全部硬约束(self):
        提示词 = 被测模块.构造提示词(示例项)
        for 片段 in (
            "Minecraft 物品栏图标", "单个完整物品", "正视或轻微3/4视角", "硬边像素画",
            "有限色板", "纯#00FF00键色背景", "主体不含键色", "无场景", "地面", "阴影",
            "文字", "品牌", "包装", "水印", "人物手持", "盲盒", "盒盖接缝", "靛蓝", "广告背景",
        ):
            self.assertIn(片段, 提示词)

    def test_每个物品形成独立命令(self):
        另一项 = dict(示例项, id="letter", texture="assets/x/letter.png", subject="信件")
        命令一 = 被测模块.构造命令(示例项, Path("sources/blind_box.png"))
        命令二 = 被测模块.构造命令(另一项, Path("sources/letter.png"))
        self.assertEqual("generate", 命令一[2])
        self.assertIn("gpt-image-2", 命令一)
        self.assertIn("#00FF00", 命令一)
        self.assertIn(Path("sources/blind_box.png"), [Path(str(x)) for x in 命令一])
        self.assertNotEqual(命令一, 命令二)
        self.assertNotIn("信件", " ".join(map(str, 命令一)))

    def test_键色去背并输出十六像素二值透明边距(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输入 = Path(临时目录) / "source.png"
            清理 = Path(临时目录) / "clean.png"
            候选 = Path(临时目录) / "candidate.png"
            图 = Image.new("RGB", (40, 20), (0, 255, 0))
            for x in range(5, 35):
                for y in range(2, 18):
                    图.putpixel((x, y), (90, 20, 180))
            图.save(输入)
            原始尺寸 = 被测模块.处理图像(输入, 清理, 候选)
            self.assertEqual((40, 20), 原始尺寸)
            with Image.open(候选) as 结果:
                self.assertEqual((16, 16), 结果.size)
                self.assertEqual("RGBA", 结果.mode)
                alpha = 结果.getchannel("A")
                self.assertLessEqual(alpha.getbbox()[2] - alpha.getbbox()[0], 14)
                self.assertLessEqual(alpha.getbbox()[3] - alpha.getbbox()[1], 14)
                self.assertGreaterEqual(alpha.getbbox()[0], 1)
                self.assertGreaterEqual(alpha.getbbox()[1], 1)
                self.assertEqual({0, 255}, set(alpha.get_flattened_data()))

    def test_resume仅在成功元数据与候选哈希匹配时跳过(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            候选 = 根 / "candidates/blind_box.png"
            元数据 = 根 / "metadata/blind_box.json"
            候选.parent.mkdir(parents=True)
            元数据.parent.mkdir(parents=True)
            候选.write_bytes(b"candidate")
            哈希 = hashlib.sha256(候选.read_bytes()).hexdigest()
            元数据.write_text(json.dumps({"生成状态": "成功", "候选SHA256": 哈希}), encoding="utf-8")
            self.assertTrue(被测模块.可恢复跳过(元数据, 候选))
            候选.write_bytes(b"changed")
            self.assertFalse(被测模块.可恢复跳过(元数据, 候选))

    def test_非法选择被拒绝(self):
        with self.assertRaisesRegex(ValueError, "未找到"):
            被测模块.选择项目([示例项], ["missing"], False)

    def test_版本化输出优先使用本次新增文件(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            目录 = Path(临时目录)
            请求 = 目录 / "blind_box.png"
            请求.write_bytes(b"old")
            生成前 = {请求}
            新文件 = 目录 / "blind_box-2.png"
            新文件.write_bytes(b"new")
            self.assertEqual(新文件, 被测模块._实际源文件(目录, 请求, 生成前))

    def test_子进程失败不生成候选并写失败元数据(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            def 失败运行器(*args, **kwargs):
                return subprocess.CompletedProcess(args[0], 7, "", "provider rejected secret text")
            状态 = 被测模块.生成单项(示例项, 根, 失败运行器)
            self.assertEqual("失败", 状态)
            self.assertFalse((根 / "candidates/blind_box.png").exists())
            数据 = json.loads((根 / "metadata/blind_box.json").read_text(encoding="utf-8"))
            self.assertEqual("失败", 数据["生成状态"])
            self.assertNotIn("provider rejected", json.dumps(数据, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
