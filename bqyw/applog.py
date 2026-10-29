# -*- coding: utf-8 -*-
r"""日志文件管理。

打包成 exe 之后 `__file__` 指向 PyInstaller 的临时解包目录（退出就删），
所以日志位置必须跟着 exe 走，不能跟着 `__file__` 走。

磁盘上最多留 MAX_LOGS 份，多出来的从最旧的开始删。
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

from . import __version__
from .paths import app_dir

MAX_LOGS = 5              # 日志最多保留份数
PREFIX = "run_"           # 日志文件名前缀
APP_NAME = "百趣演武自动抽牌"


# ---------------------------------------------------------------- 路径
def _writable(folder: Path) -> bool:
    try:
        folder.mkdir(parents=True, exist_ok=True)
        probe = folder / f".probe_{os.getpid()}"
        probe.write_text("", encoding="utf-8")
        probe.unlink()
        return True
    except OSError:
        return False


def log_dir() -> Path:
    r"""日志目录。

    优先放在程序旁边的 logs/；如果那里写不了（比如装进了 Program Files，
    或者 exe 在只读介质上），退到 %LOCALAPPDATA%\百趣演武自动抽牌\logs。
    """
    primary = app_dir() / "logs"
    if _writable(primary):
        return primary
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    fallback = Path(base) / APP_NAME / "logs"
    fallback.mkdir(parents=True, exist_ok=True)
    return fallback


# ---------------------------------------------------------------- 轮转
def list_logs(folder: Path) -> list[Path]:
    """目录里属于本程序的日志，按时间从旧到新。"""
    try:
        found = [p for p in folder.iterdir()
                 if p.is_file() and p.suffix.lower() == ".log"
                 and p.name.startswith(PREFIX)]
    except OSError:
        return []

    def key(p: Path):
        try:
            return (p.stat().st_mtime, p.name)
        except OSError:
            return (0.0, p.name)

    return sorted(found, key=key)


def prune_logs(folder: Path | None = None, keep: int = MAX_LOGS) -> list[str]:
    """只保留最近 keep 份日志，返回被删掉的文件名。"""
    folder = folder or log_dir()
    files = list_logs(folder)
    removed: list[str] = []
    while len(files) > keep:
        old = files.pop(0)
        try:
            old.unlink()
            removed.append(old.name)
        except OSError:
            pass                      # 别的实例还开着，下次再删
    return removed


# ---------------------------------------------------------------- 新建
def _unique(path: Path) -> Path:
    """同一秒内起两次也不互相覆盖。"""
    if not path.exists():
        return path
    n = 2
    while True:
        cand = path.with_name(f"{path.stem}_{n}{path.suffix}")
        if not cand.exists():
            return cand
        n += 1


def env_header() -> str:
    """日志开头那几行，出问题时够贴给人看。"""
    import platform

    ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    how = "exe" if getattr(sys, "frozen", False) else "python"
    try:
        osname = platform.platform()
    except Exception:                                  # noqa: BLE001
        osname = f"{sys.platform}"
    return "\n".join([
        f"# {APP_NAME} v{__version__}",
        f"# {time.strftime('%Y-%m-%d %H:%M:%S')}  {osname}  运行方式 {how}  Python {ver}",
    ])


def new_log(header: str | None = None, prefix: str = PREFIX):
    """开一份新日志（顺手把旧的多余的删掉），返回 (路径, 文件对象)。"""
    folder = log_dir()
    path = _unique(folder / (prefix + time.strftime("%Y%m%d_%H%M%S") + ".log"))
    # 行缓冲：进程崩了也能留下已经打出来的内容
    f = open(path, "w", encoding="utf-8", buffering=1, errors="replace")
    try:
        if header:
            f.write(header.rstrip("\n") + "\n")
            f.flush()
        prune_logs(folder, MAX_LOGS)
    except OSError:
        pass
    return path, f


def stamp() -> str:
    return time.strftime("[%H:%M:%S]")


# ---------------------------------------------------------------- 控制台
class Tee:
    """命令行模式：同时写控制台和日志文件。"""

    def __init__(self, *streams):
        self.streams = streams

    def write(self, s):
        for st in self.streams:
            try:
                st.write(s)
            except Exception:                          # noqa: BLE001
                pass

    def flush(self):
        for st in self.streams:
            try:
                st.flush()
            except Exception:                          # noqa: BLE001
                pass
