import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import verify_quality_contract as 被测模块


class 登录补丁客户端隔离契约测试(unittest.TestCase):
    def setUp(self):
        self.临时目录 = tempfile.TemporaryDirectory()
        self.addCleanup(self.临时目录.cleanup)
        self.模组 = Path(self.临时目录.name)
        self.配置路径 = self.模组 / "src/main/resources/blindboxchallenge.mixins.json"
        self.配置路径.parent.mkdir(parents=True)
        self.源码 = self.模组 / "src/main/java/cn/blindboxchallenge/mixin/ClientLoginConnectionMixin.java"
        self.源码.parent.mkdir(parents=True)
        self.源码.touch()
        self.配置 = {
            "package": "cn.blindboxchallenge.mixin",
            "refmap": "blindboxchallenge.refmap.json",
            "client": ["ClientLoginConnectionMixin"],
        }
        self.构建 = """apply plugin: 'org.spongepowered.mixin'
mixin {
    add sourceSets.main, 'blindboxchallenge.refmap.json'
    config 'blindboxchallenge.mixins.json'
}
dependencies {
    annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
}
"""

    def 验证(self):
        self.配置路径.write_text(json.dumps(self.配置), encoding="utf-8")
        (self.模组 / "build.gradle").write_text(self.构建, encoding="utf-8")
        with patch.object(被测模块, "MOD", self.模组):
            被测模块.check_client_login_mixin()

    def test_当前仓库与最小正确配置均通过(self):
        被测模块.check_client_login_mixin()
        self.验证()

    def test_公共与服务端列表均拒绝加载补丁(self):
        for 列表 in ("mixins", "server"):
            with self.subTest(列表=列表):
                self.配置[列表] = ["ClientLoginConnectionMixin"]
                with self.assertRaisesRegex(SystemExit, "禁止登记公共或服务端"):
                    self.验证()
                self.配置.pop(列表)

    def test_客户端列表不能缺失错名或重复(self):
        for 内容 in (None, [], ["OtherMixin"], ["ClientLoginConnectionMixin"] * 2):
            with self.subTest(内容=内容):
                self.配置["client"] = 内容
                with self.assertRaisesRegex(SystemExit, "必须且只能登记在客户端列表"):
                    self.验证()

    def test_配置映射与实现路径必须闭合(self):
        for 键, 错误 in (("package", "包路径错误"), ("refmap", "运行映射名称错误")):
            with self.subTest(键=键):
                原值 = self.配置[键]
                self.配置[键] = "other"
                with self.assertRaisesRegex(SystemExit, 错误):
                    self.验证()
                self.配置[键] = 原值
        self.源码.unlink()
        with self.assertRaisesRegex(SystemExit, "缺少正式客户端实现"):
            self.验证()

    def test_构建入口不能缺失或仅存在于注释(self):
        原构建 = self.构建
        for 入口 in ("apply plugin:", "add sourceSets.main", "config 'blindboxchallenge.mixins.json'", "annotationProcessor"):
            for 注释 in (False, True):
                with self.subTest(入口=入口, 注释=注释):
                    self.构建 = "\n".join(
                        ("// " + 行 if 注释 else "") if 入口 in 行 else 行
                        for 行 in 原构建.splitlines()
                    )
                    with self.assertRaisesRegex(SystemExit, "登录补丁"):
                        self.验证()

    def test_运行映射不能改由探针生成或使用错误名称(self):
        原构建 = self.构建
        for 原文, 新文 in (("sourceSets.main", "sourceSets.ciTest"),
                            ("blindboxchallenge.refmap.json", "other.refmap.json"),
                            ("blindboxchallenge.mixins.json", "other.mixins.json")):
            with self.subTest(新文=新文):
                self.构建 = 原构建.replace(原文, 新文)
                with self.assertRaisesRegex(SystemExit, "登录补丁缺少"):
                    self.验证()


if __name__ == "__main__":
    unittest.main()
