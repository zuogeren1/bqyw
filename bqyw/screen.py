# -*- coding: utf-8 -*-
"""截屏。

按标题找游戏窗口，只抓它的客户区；抓不到就退回整个主屏。
注意：mss 抓的是**屏幕像素**，所以游戏窗口必须在前台可见，
被别的窗口挡住就会抓到挡它的那个窗口。Bot 启动时会先把游戏提到前台。
"""
from __future__ import annotations

import ctypes
import time

import mss
import numpy as np

from . import geometry as G

try:
    import win32gui
    HAS_WIN32 = True
except Exception:                                   # pragma: no cover
    HAS_WIN32 = False

DEFAULT_TITLES = ("Endfield", "终末地")

# 标题里带这些词的一律当浏览器/编辑器，绝不当游戏
_BLACKLIST = ("chrome", "edge", "firefox", "brave", "opera", "vivaldi",
              "deepseek", "visual studio", "code", "notepad", "记事本",
              "文件资源管理器", "explorer", "program manager", "任务管理器")


def _enable_dpi_awareness():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)   # PER_MONITOR_AWARE
        return
    except Exception:
        pass
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


_enable_dpi_awareness()


def window_client_box(hwnd):
    left, top, right, bottom = win32gui.GetClientRect(hwnd)
    px, py = win32gui.ClientToScreen(hwnd, (0, 0))
    return {"left": px, "top": py, "width": right - left, "height": bottom - top}


def list_windows(titles=DEFAULT_TITLES):
    """按「标题越像游戏越靠前」排序返回候选窗口。"""
    if not HAS_WIN32:
        return []
    out = []
    q = [t.lower() for t in titles]

    def cb(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd):
            return
        title = win32gui.GetWindowText(hwnd)
        if not title:
            return
        low = title.lower()
        if any(b in low for b in _BLACKLIST):
            return
        box = window_client_box(hwnd)
        if box["width"] < 800 or box["height"] < 600:
            return
        rank = 99
        for i, t in enumerate(q):
            if low == t:
                rank = min(rank, i)
            elif low.startswith(t):
                rank = min(rank, i + 10)
            elif t in low:
                rank = min(rank, i + 20)
        if rank == 99:
            return
        out.append((rank, -box["width"] * box["height"], hwnd, title, box))

    win32gui.EnumWindows(cb, None)
    out.sort()
    return [(hwnd, title, box) for _r, _s, hwnd, title, box in out]


def find_window(titles=DEFAULT_TITLES):
    hits = list_windows(titles)
    return hits[0] if hits else None


def is_foreground(hwnd) -> bool:
    return bool(hwnd) and win32gui.GetForegroundWindow() == hwnd


def aspect(w: int, h: int) -> str:
    """2560×1440 → 16:9。约不整也照实写（比如 9:20），不要四舍五入成"差不多"。"""
    from math import gcd

    if w <= 0 or h <= 0:
        return "?"
    g = gcd(int(w), int(h)) or 1
    return f"{int(w) // g}:{int(h) // g}"


def display_info() -> str:
    """一行环境：屏幕 + 找到的游戏窗口，都带宽高比。

    写进日志头，换机器 / 换分辨率之后一眼就能对上号。
    """
    try:
        u = ctypes.windll.user32
        sw, sh = int(u.GetSystemMetrics(0)), int(u.GetSystemMetrics(1))
    except Exception:                                  # noqa: BLE001
        return "屏幕 读不出"
    bits = [f"屏幕 {sw}×{sh}（{aspect(sw, sh)}）"]
    try:
        hit = find_window()
    except Exception:                                  # noqa: BLE001
        hit = None
    if hit:
        _hwnd, _title, box = hit
        w, h = int(box["width"]), int(box["height"])
        bits.append(f"窗口 {w}×{h}（{aspect(w, h)}）")
    else:
        bits.append("窗口 未找到游戏窗口")
    return "  ".join(bits)


def frame_delta(a: np.ndarray, b: np.ndarray, step: int = 4) -> float:
    """两帧的平均差值（0~255）。

    以前是整帧转 int16 再相减：2560×1440 每比一次要临时分配约 90MB，
    而放弃之后的淡出期要连比十几次 —— 堆就是被这种瞬时大块撑大的。
    隔 step 行/列抽稀后只比 1/16 的点，分配量同比例下降，
    对「画面稳没稳」这个判断没有影响。
    """
    sa = a[::step, ::step]
    sb = b[::step, ::step]
    return float(np.abs(sa.astype(np.int16) - sb.astype(np.int16)).mean())


class Capture:
    """抓屏。

    默认走**屏幕区域抓取**（mss），因为它反映的是"你眼睛看到的画面"。
    不要默认用 BitBlt：对已被遮挡的窗口，BitBlt 会返回最后一次绘制的
    **冻结画面**且不报错——这比抓错更危险，会让人误以为读到了实时状态。
    """

    def __init__(self, prefer_window: bool = True, titles=DEFAULT_TITLES,
                 window_title: str | None = None):
        self.sct = mss.mss()
        self.hwnd = None
        self.matched_title = "<全屏>"
        if prefer_window:
            hit = None
            if window_title:
                for hwnd, title, box in list_windows(titles):
                    if window_title.lower() in title.lower():
                        hit = (hwnd, title, box)
                        break
            if hit is None:
                hit = find_window(titles)
            if hit:
                self.hwnd, self.matched_title, _ = hit
        self._box = None

    @property
    def title(self) -> str:
        return self.matched_title

    def foreground(self) -> bool:
        return is_foreground(self.hwnd) if self.hwnd else True

    def minimized(self) -> bool:
        return bool(self.hwnd) and bool(win32gui.IsIconic(self.hwnd))

    def status(self) -> str:
        if not self.hwnd:
            return "整屏抓取"
        bits = []
        if self.minimized():
            bits.append("已最小化")
        if not self.foreground():
            bits.append("不在前台（可能被遮挡）")
        return "、".join(bits) if bits else "前台可见"

    def _current_box(self):
        if self.hwnd and HAS_WIN32:
            return window_client_box(self.hwnd)
        return self.sct.monitors[1]

    def frame_size(self) -> tuple[int, int]:
        box = self._current_box()
        return int(box["width"]), int(box["height"])

    def origin(self) -> tuple[int, int]:
        """客户区左上角在屏幕上的位置。

        抓屏用窗口内相对坐标，点击用屏幕绝对坐标，中间差的就是这个偏移。
        """
        box = self._current_box()
        return int(box["left"]), int(box["top"])

    def layout(self) -> float:
        """按实际画面尺寸重算界面坐标，返回缩放倍数。

        不支持的宽高比会抛 geometry.UnsupportedResolution —— 宁可停下报错，
        也不要拿错的坐标去点游戏。
        """
        w, h = self.frame_size()
        return G.use_resolution(w, h)

    def grab(self) -> np.ndarray:
        self._box = self._current_box()
        shot = self.sct.grab(self._box)
        return np.asarray(shot, dtype=np.uint8)[:, :, :3]

    def grab_region(self, x1: int, y1: int, x2: int, y2: int) -> np.ndarray:
        """只抓窗口内的一小块。判定弹窗/数牌只要几毫秒，不用整屏。"""
        base = self._current_box()
        box = {"left": base["left"] + int(x1), "top": base["top"] + int(y1),
               "width": int(x2 - x1), "height": int(y2 - y1)}
        shot = self.sct.grab(box)
        return np.asarray(shot, dtype=np.uint8)[:, :, :3]

    def wait_stable(self, timeout: float = 3.0, tol: float = 1.2,
                    need: int = 2, interval: float = 0.06) -> np.ndarray:
        """等画面不再变化（抽牌/翻牌动画结束）。

        要连续 need 次比较都稳定才返回 —— 只比一次容易在动画的短暂停顿处误判。
        """
        deadline = time.time() + timeout
        prev = self.grab()
        streak = 0
        while time.time() < deadline:
            time.sleep(interval)
            cur = self.grab()
            same = cur.shape == prev.shape and frame_delta(cur, prev) < tol
            streak = streak + 1 if same else 0
            prev = cur
            if streak >= need:
                return cur
        return prev
