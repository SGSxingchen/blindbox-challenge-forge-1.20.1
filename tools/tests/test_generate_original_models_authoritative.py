import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import generate_original_models as 被测模块


class 权威模型确定性生成测试(unittest.TestCase):
    def test_权威载荷集合恰为当前旧生成器漂移集合(self):
        漂移 = {
            relative for relative in 被测模块.TARGETS
            if (被测模块.RESOURCE_ROOT / relative).read_bytes().replace(b"\r\n", b"\n") != 被测模块.render_template(relative)
        }
        self.assertEqual(95, len(漂移))
        self.assertEqual(漂移, set(被测模块.AUTHORITATIVE_JSON_PAYLOADS))

    def test_临时重建九十五项与正式文件逐字节一致(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            被测模块.write_authoritative_json(临时根)
            for relative in 被测模块.AUTHORITATIVE_JSON_PAYLOADS:
                self.assertEqual(
                    (被测模块.RESOURCE_ROOT / relative).read_bytes().replace(b"\r\n", b"\n"),
                    (临时根 / relative).read_bytes(),
                    relative,
                )

    def test_运行时渲染权威项不读取正式资源或output(self):
        relative = next(iter(被测模块.AUTHORITATIVE_JSON_PAYLOADS))
        with patch.object(Path, "read_bytes", side_effect=AssertionError("不得读取资源文件")):
            内容 = 被测模块.render(relative)
        self.assertEqual(被测模块.decode_authoritative_json(relative), 内容)


if __name__ == "__main__":
    unittest.main()
