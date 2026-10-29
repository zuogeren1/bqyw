# -*- coding: utf-8 -*-
r"""程序路径。

源码运行时一切以仓库根目录为准。打包成 exe 之后要区分两个位置：

- `app_dir()`    exe 所在目录 —— 可写，日志 / 配置放这里
- `bundle_dir()` PyInstaller 的临时解包目录 —— 只读，随包资源（模板）在这里

单文件 exe 每次启动都会把资源解压到临时目录、退出即删，
所以千万别往 `bundle_dir()` 里写东西。
"""
from __future__ import annotations

import sys
from pathlib import Path


def is_frozen() -> bool:
    """是不是打包后的 exe。"""
    return bool(getattr(sys, "frozen", False))


def app_dir() -> Path:
    """程序所在目录：打包后是 exe 目录，源码运行时是仓库根目录。"""
    if is_frozen():
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def bundle_dir() -> Path:
    """随包资源目录：打包后在临时解包目录，源码运行时就是仓库根目录。"""
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass)
    return app_dir()


def resource_dir(name: str, pattern: str = "*") -> Path:
    """找随包资源目录（比如 templates）。

    先看 exe 旁边有没有同名目录——有就用它，方便出问题时就地替换；
    没有就退回打包进去的那份。`pattern` 用来确认目录里真的有东西，
    避免一个空文件夹把好的那份顶掉。
    """
    name = name.strip("/\\")
    candidates = [app_dir() / name]
    if bundle_dir() != app_dir():
        candidates.append(bundle_dir() / name)
    for cand in candidates:
        try:
            if cand.is_dir() and any(cand.glob(pattern)):
                return cand
        except OSError:
            continue
    return candidates[0]
