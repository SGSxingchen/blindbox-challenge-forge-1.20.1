"""本地与云端隔离验证共用的 Java 17 与平台参数解析。"""

import os
from pathlib import Path
import platform
import re
import shutil
import subprocess


def resolve_java(explicit=None):
    """优先显式路径，其次 JAVA_HOME，最后 PATH；配置错误不静默回退。"""
    if explicit is not None:
        candidate = Path(explicit).expanduser()
    elif os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]).expanduser() / "bin/java"
    else:
        located = shutil.which("java")
        if located is None:
            raise RuntimeError("找不到 Java 17，请设置 JAVA_HOME 或传入 --java")
        candidate = Path(located)
    candidate = candidate.resolve()
    if not candidate.is_file() or not os.access(candidate, os.X_OK):
        raise RuntimeError(f"Java 路径不存在或不可执行：{candidate}")
    result = subprocess.run([str(candidate), "-version"], capture_output=True, text=True, timeout=10)
    version = re.search(r'(?:openjdk|java) version "(\d+)(?:\.|\")', result.stderr + result.stdout)
    if result.returncode != 0 or version is None or version.group(1) != "17":
        raise RuntimeError(f"验证要求 Java 17：{candidate}")
    return candidate


def gradle_cache():
    """遵循 Gradle 的标准缓存位置，不依赖某台开发机的临时目录。"""
    return Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).expanduser() / "caches"


def client_platform_arguments():
    """macOS 要求首线程运行图形客户端；Linux JVM 不接受该参数。"""
    return ["-XstartOnFirstThread"] if platform.system() == "Darwin" else []
