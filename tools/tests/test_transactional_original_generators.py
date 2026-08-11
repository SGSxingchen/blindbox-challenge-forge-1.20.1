import os
import tempfile
import unittest
from pathlib import Path

from tools import generate_original_metadata as 元数据
from tools import generate_original_models as 模型


class 安全命令行测试(unittest.TestCase):
    def test_无参数只显示帮助且不写(self):
        for 模块 in (模型, 元数据):
            with self.subTest(模块=模块.__name__), tempfile.TemporaryDirectory() as 临时目录:
                根 = Path(临时目录)
                self.assertEqual(0, 模块.main([], resource_root=根))
                self.assertEqual([], list(根.rglob("*")))

    def test_check只读(self):
        for 模块 in (模型, 元数据):
            with self.subTest(模块=模块.__name__), tempfile.TemporaryDirectory() as 临时目录:
                根 = Path(临时目录)
                for relative in 模块.TARGETS:
                    path = 根 / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(模块.render(relative))
                前 = {p.relative_to(根): p.read_bytes() for p in 根.rglob("*") if p.is_file()}
                self.assertEqual(0, 模块.main(["--check"], resource_root=根))
                后 = {p.relative_to(根): p.read_bytes() for p in 根.rglob("*") if p.is_file()}
                self.assertEqual(前, 后)

    def test_write_all成功且清理staging(self):
        for 模块 in (模型, 元数据):
            with self.subTest(模块=模块.__name__), tempfile.TemporaryDirectory() as 临时目录:
                根 = Path(临时目录) / "resources"
                self.assertEqual(0, 模块.main(["--write-all"], resource_root=根))
                self.assertEqual({Path(x) for x in 模块.TARGETS}, {p.relative_to(根) for p in 根.rglob("*") if p.is_file()})
                self.assertFalse(any(p.name.startswith(".original-resource-staging-") for p in 根.parent.iterdir()))


class 事务失败测试(unittest.TestCase):
    def _准备旧文件(self, 模块, 根):
        old = {}
        for index, relative in enumerate(模块.TARGETS):
            path = 根 / relative; path.parent.mkdir(parents=True, exist_ok=True)
            data = f"old-{index}".encode(); path.write_bytes(data); old[path] = data
        return old

    def test_生成阶段失败时正式文件零改动(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录) / "resources"; old = self._准备旧文件(模型, 根); calls = [0]
            def render(relative):
                calls[0] += 1
                if calls[0] == 10: raise RuntimeError("生成失败")
                return 模型.render(relative)
            with self.assertRaisesRegex(RuntimeError, "生成失败"):
                模型.write_all(根, renderer=render)
            for path, data in old.items(): self.assertEqual(data, path.read_bytes())

    def test_非法TOML在安装前被拒绝(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录) / "resources"

            def render(relative):
                if relative.endswith(".toml"):
                    return b"[[broken"
                return 元数据.render(relative)

            with self.assertRaisesRegex(ValueError, "生成元数据无效.*mods.toml"):
                元数据.write_all(根, renderer=render)
            self.assertFalse(根.exists())
            self.assertFalse(any(p.name.startswith(".original-resource-staging-") for p in 根.parent.iterdir()))

    def test_第N次安装失败时全量回滚且清理staging(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录) / "resources"; old = self._准备旧文件(模型, 根); calls = [0]
            def replace(src, dst):
                calls[0] += 1
                if calls[0] == 12: raise OSError("前向失败")
                os.replace(src, dst)
            with self.assertRaisesRegex(Exception, "前向失败"):
                模型.write_all(根, replacer=replace)
            for path, data in old.items(): self.assertEqual(data, path.read_bytes())
            self.assertFalse(any(p.name.startswith(".original-resource-staging-") for p in 根.parent.iterdir()))

    def test_回滚二次失败聚合原始与全部恢复错误(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录) / "resources"; self._准备旧文件(元数据, 根); calls = [0]
            def replace(src, dst):
                calls[0] += 1
                if calls[0] == 3: raise OSError("前向失败")
                os.replace(src, dst)
            def restore(path, data):
                if path.name in {"zh_cn.json", "mods.toml"}: raise OSError("恢复失败-" + path.name)
                from tools.transactional_resource_writer import atomic_bytes_write
                atomic_bytes_write(path, data)
            with self.assertRaises(Exception) as caught:
                元数据.write_all(根, replacer=replace, restorer=restore)
            message = str(caught.exception)
            self.assertIn("前向失败", message); self.assertIn("恢复失败-zh_cn.json", message); self.assertIn("恢复失败-mods.toml", message)

    def test_目标路径穿越在创建staging前拒绝(self):
        original = 模型.TARGETS
        try:
            模型.TARGETS = ("../escape.json",)
            with tempfile.TemporaryDirectory() as 临时目录, self.assertRaisesRegex(ValueError, "不安全"):
                模型.write_all(Path(临时目录) / "resources")
        finally:
            模型.TARGETS = original


if __name__ == "__main__":
    unittest.main()
