import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import generate_original_metadata as 被测模块


class 权威元数据确定性生成测试(unittest.TestCase):
    def test_四项权威载荷均与规范模板一致(self):
        漂移 = {
            relative for relative in 被测模块.TARGETS
            if (被测模块.RESOURCE_ROOT / relative).read_bytes() != 被测模块.render_template(relative)
        }
        self.assertEqual(set(), 漂移)
        self.assertEqual(set(被测模块.TARGETS), set(被测模块.AUTHORITATIVE_METADATA_PAYLOADS))

    def test_临时重建四项与正式文件逐字节一致(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            被测模块.write_authoritative_metadata(临时根)
            for relative in 被测模块.AUTHORITATIVE_METADATA_PAYLOADS:
                self.assertEqual((被测模块.RESOURCE_ROOT / relative).read_bytes(), (临时根 / relative).read_bytes())

    def test_运行时渲染不读取正式资源或output(self):
        relative = next(iter(被测模块.AUTHORITATIVE_METADATA_PAYLOADS))
        with patch.object(Path, "read_bytes", side_effect=AssertionError("不得读取资源文件")):
            内容 = 被测模块.render(relative)
        self.assertEqual(被测模块.decode_authoritative_metadata(relative), 内容)


if __name__ == "__main__":
    unittest.main()
