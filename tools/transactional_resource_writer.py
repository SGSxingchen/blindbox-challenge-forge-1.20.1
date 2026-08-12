"""为确定性资源生成器提供小型、可回滚的事务写入。"""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable


class ResourceTransactionError(RuntimeError):
    pass


def validate_targets(targets: Iterable[str]) -> tuple[str, ...]:
    result = tuple(targets)
    if len(result) != len(set(result)):
        raise ValueError("资源目标存在重复")
    for relative in result:
        posix = PurePosixPath(relative)
        if not relative or posix.is_absolute() or ".." in posix.parts or "\\" in relative or Path(relative).is_absolute():
            raise ValueError(f"不安全的资源目标路径：{relative}")
    return result


def atomic_bytes_write(path: Path, data: bytes, replacer: Callable = os.replace) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        replacer(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def transactional_write(
    resource_root: Path,
    targets: Iterable[str],
    renderer: Callable[[str], bytes],
    validator: Callable[[str, bytes], None],
    *,
    replacer: Callable = os.replace,
    restorer: Callable[[Path, bytes], None] | None = None,
) -> None:
    targets = validate_targets(targets)
    resource_root = resource_root.resolve()
    resource_root.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".original-resource-staging-", dir=resource_root.parent))
    restore = restorer or atomic_bytes_write
    try:
        expected: dict[str, bytes] = {}
        for relative in targets:
            data = renderer(relative)
            if not isinstance(data, bytes):
                raise TypeError(f"生成结果不是字节：{relative}")
            validator(relative, data)
            expected[relative] = data
            staged = staging / relative
            staged.parent.mkdir(parents=True, exist_ok=True)
            staged.write_bytes(data)

        staged_set = {path.relative_to(staging).as_posix() for path in staging.rglob("*") if path.is_file()}
        if staged_set != set(targets):
            raise RuntimeError("staging 资源集合不完整")
        for relative, data in expected.items():
            staged_data = (staging / relative).read_bytes()
            if hashlib.sha256(staged_data).digest() != hashlib.sha256(data).digest() or staged_data != data:
                raise RuntimeError(f"staging 字节校验失败：{relative}")

        backups: dict[Path, bytes | None] = {}
        for relative in targets:
            target = (resource_root / relative).resolve()
            if resource_root not in target.parents:
                raise ValueError(f"不安全的资源目标路径：{relative}")
            backups[target] = target.read_bytes() if target.is_file() else None

        try:
            for relative in targets:
                atomic_bytes_write(resource_root / relative, expected[relative], replacer)
        except Exception as forward_error:
            rollback_errors = []
            for target, old_data in backups.items():
                try:
                    if old_data is None:
                        target.unlink(missing_ok=True)
                    else:
                        restore(target, old_data)
                except Exception as rollback_error:
                    rollback_errors.append(f"{target.name}: {rollback_error}")
            message = f"资源事务前向失败：{forward_error}"
            if rollback_errors:
                message += "；回滚失败：" + "；".join(rollback_errors)
            raise ResourceTransactionError(message) from forward_error
    finally:
        shutil.rmtree(staging, ignore_errors=True)
