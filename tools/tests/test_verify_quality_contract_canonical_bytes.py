import unittest
from pathlib import Path

from tools.verify_quality_contract import canonical_resource_bytes


class 资源清单跨平台字节测试(unittest.TestCase):
    def test_文本统一换行而PNG保持原字节(self):
        class 假路径:
            def __init__(self, suffix, data): self.suffix, self.data = suffix, data
            def read_bytes(self): return self.data

        self.assertEqual(b"a\nb\n", canonical_resource_bytes(假路径(".json", b"a\r\nb\r\n")))
        self.assertEqual(b"\x89PNG\r\n", canonical_resource_bytes(假路径(".png", b"\x89PNG\r\n")))


if __name__ == "__main__":
    unittest.main()
