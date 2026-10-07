"""验证脚本跨平台启动参数，不启动 Minecraft 或访问网络。"""

import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("validation_runtime", ROOT / "mod/scripts/local/validation_runtime.py")
被测模块 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(被测模块)


class 验证运行环境测试(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.java = self.root / "jdk/bin/java"
        self.java.parent.mkdir(parents=True)
        self.java.write_text("#!/bin/sh\n", encoding="utf-8")
        self.java.chmod(0o755)

    def result(self, version="17.0.20.1", code=0):
        return subprocess.CompletedProcess([], code, "", f'openjdk version "{version}" 2026-07-21\n')

    def test_显式路径优先环境变量且检查主版本(self):
        with patch.dict(os.environ, {"JAVA_HOME": "/missing"}), patch.object(被测模块.subprocess, "run", return_value=self.result()) as run:
            self.assertEqual(self.java, 被测模块.resolve_java(self.java))
            run.assert_called_once_with([str(self.java), "-version"], capture_output=True, text=True, timeout=10)

    def test_JAVA_HOME优先PATH(self):
        with patch.dict(os.environ, {"JAVA_HOME": str(self.java.parent.parent)}), patch.object(被测模块.shutil, "which", side_effect=AssertionError("不应查PATH")), patch.object(被测模块.subprocess, "run", return_value=self.result()):
            self.assertEqual(self.java, 被测模块.resolve_java())

    def test_无JAVA_HOME时从PATH解析(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(被测模块.shutil, "which", return_value=str(self.java)), patch.object(被测模块.subprocess, "run", return_value=self.result()):
            self.assertEqual(self.java, 被测模块.resolve_java())

    def test_错误JAVA_HOME不静默回退(self):
        with patch.dict(os.environ, {"JAVA_HOME": str(self.root / "missing")}), patch.object(被测模块.shutil, "which", side_effect=AssertionError("不得回退")):
            with self.assertRaisesRegex(RuntimeError, "不存在或不可执行"):
                被测模块.resolve_java()

    def test_未安装Java给出可操作错误(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(被测模块.shutil, "which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "JAVA_HOME"):
                被测模块.resolve_java()

    def test_拒绝其他版本与失败输出(self):
        for result in [self.result("21.0.1"), self.result("1.8.0"), self.result(code=1), subprocess.CompletedProcess([], 0, "", "invalid")]:
            with self.subTest(result=result), patch.object(被测模块.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(RuntimeError, "要求 Java 17"):
                    被测模块.resolve_java(self.java)

    def test_拒绝不可执行文件(self):
        self.java.chmod(0o644)
        with self.assertRaisesRegex(RuntimeError, "不可执行"):
            被测模块.resolve_java(self.java)

    def test_Java版本检查超时不会继续(self):
        with patch.object(被测模块.subprocess, "run", side_effect=subprocess.TimeoutExpired("java", 10)):
            with self.assertRaises(subprocess.TimeoutExpired):
                被测模块.resolve_java(self.java)

    def test_Gradle缓存遵循环境与默认目录(self):
        with patch.dict(os.environ, {"GRADLE_USER_HOME": str(self.root)}):
            self.assertEqual(self.root / "caches", 被测模块.gradle_cache())
        with patch.dict(os.environ, {}, clear=True), patch.object(Path, "home", return_value=self.root):
            self.assertEqual(self.root / ".gradle/caches", 被测模块.gradle_cache())

    def test_仅macOS传入首线程参数(self):
        for system, expected in [("Linux", []), ("Darwin", ["-XstartOnFirstThread"]), ("Windows", [])]:
            with self.subTest(system=system), patch.object(被测模块.platform, "system", return_value=system):
                self.assertEqual(expected, 被测模块.client_platform_arguments())


if __name__ == "__main__":
    unittest.main()
