# -*- coding: utf-8 -*-
"""全局急停热键。

用 WH_KEYBOARD_LL 低级键盘钩子，而不是 RegisterHotKey：
后者会把按键**系统级独占**，游戏本身再也收不到 F11（不少游戏 F11 是全屏切换）。
钩子只观察、不吞按键，按下去照常传给游戏，我们只管收到通知。

用法：
    HotkeyListener("f11", on_fire).start()
    ...
    listener.stop()

支持写法：f1~f24、单个字母/数字、以及 ctrl+/alt+/shift+ 组合，
例如 "f11"、"ctrl+f8"、"alt+x"。解析失败会抛 ValueError。
"""
from __future__ import annotations

import ctypes
import threading
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

WH_KEYBOARD_LL = 13
WM_KEYDOWN = 0x0100
WM_SYSKEYDOWN = 0x0104
WM_QUIT = 0x0012
VK_CONTROL, VK_SHIFT, VK_MENU = 0x11, 0x10, 0x12

_NAMED = {
    "esc": 0x1B, "escape": 0x1B, "space": 0x20, "enter": 0x0D, "return": 0x0D,
    "tab": 0x09, "backspace": 0x08, "insert": 0x2D, "delete": 0x2E, "del": 0x2E,
    "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "printscreen": 0x2C, "pause": 0x13,
}


class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", wintypes.DWORD),
                ("scanCode", wintypes.DWORD),
                ("flags", wintypes.DWORD),
                ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.POINTER(wintypes.ULONG))]


HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int,
                              wintypes.WPARAM, wintypes.LPARAM)

user32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOKPROC,
                                     wintypes.HINSTANCE, wintypes.DWORD]
user32.SetWindowsHookExW.restype = ctypes.c_void_p
user32.CallNextHookEx.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                  wintypes.WPARAM, wintypes.LPARAM]
user32.CallNextHookEx.restype = ctypes.c_ssize_t
user32.UnhookWindowsHookEx.argtypes = [ctypes.c_void_p]
user32.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND,
                               wintypes.UINT, wintypes.UINT]
user32.GetMessageW.restype = ctypes.c_int
user32.TranslateMessage.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.DispatchMessageW.argtypes = [ctypes.POINTER(wintypes.MSG)]
user32.PostThreadMessageW.argtypes = [wintypes.DWORD, wintypes.UINT,
                                      wintypes.WPARAM, wintypes.LPARAM]
user32.PeekMessageW.argtypes = [ctypes.POINTER(wintypes.MSG), wintypes.HWND,
                                wintypes.UINT, wintypes.UINT, wintypes.UINT]
kernel32.GetCurrentThreadId.restype = wintypes.DWORD
MAPVK_VSC_TO_VK = 1


def parse(spec: str) -> tuple[int, set[str]]:
    """'ctrl+f11' -> (VK_F11, {'ctrl'})。"""
    if not spec:
        raise ValueError("热键不能为空")
    parts = [p.strip().lower() for p in spec.replace(" ", "").split("+") if p.strip()]
    if not parts:
        raise ValueError(f"看不懂的热键：{spec!r}")
    key = parts[-1]
    mods = set(parts[:-1])
    unknown = mods - {"ctrl", "alt", "shift"}
    if unknown:
        raise ValueError(f"不支持的修饰键：{','.join(sorted(unknown))}")

    if key in _NAMED:
        return _NAMED[key], mods
    if len(key) >= 2 and key[0] == "f" and key[1:].isdigit():
        n = int(key[1:])
        if 1 <= n <= 24:
            return 0x70 + n - 1, mods
    if len(key) == 1 and key.isalpha():
        return ord(key.upper()), mods
    if len(key) == 1 and key.isdigit():
        return ord(key), mods
    raise ValueError(f"不支持的按键：{key!r}")


class HotkeyListener(threading.Thread):
    """在独立线程里跑消息循环，监听一个全局热键（不吞按键）。"""

    def __init__(self, spec: str, callback, logger=None):
        super().__init__(daemon=True)
        self.vk, self.mods = parse(spec)
        self.spec = spec
        self.callback = callback
        self.logger = logger
        self.ready = threading.Event()
        self.failed: str | None = None
        self._tid = 0
        self._hook = None
        self._proc = None
        self._held = False

    def _log(self, msg: str):
        if self.logger:
            self.logger(msg)

    def _on_key(self, code, wparam, lparam):
        try:
            if code >= 0 and wparam in (WM_KEYDOWN, WM_SYSKEYDOWN):
                kb = ctypes.cast(lparam, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
                vk = int(kb.vkCode)
                if not vk and kb.scanCode:
                    # 有些工具/游戏用扫描码注入，vkCode 是 0，得反查一下
                    vk = int(user32.MapVirtualKeyW(int(kb.scanCode), MAPVK_VSC_TO_VK))
                if vk == self.vk:
                    down = {
                        "ctrl": bool(user32.GetAsyncKeyState(VK_CONTROL) & 0x8000),
                        "shift": bool(user32.GetAsyncKeyState(VK_SHIFT) & 0x8000),
                        "alt": bool(user32.GetAsyncKeyState(VK_MENU) & 0x8000),
                    }
                    if all(down.get(m, False) for m in self.mods) and not self._held:
                        self._held = True
                        try:
                            self.callback()
                        except Exception:
                            pass
            elif code >= 0 and wparam not in (WM_KEYDOWN, WM_SYSKEYDOWN):
                self._held = False
        except Exception:
            pass
        return user32.CallNextHookEx(None, code, wparam, lparam)

    def run(self):
        self._tid = kernel32.GetCurrentThreadId()
        self._proc = HOOKPROC(self._on_key)
        # 低级键盘钩子要求调用线程**已经有消息队列**，先 Peek 一下把队列建出来
        msg = wintypes.MSG()
        user32.PeekMessageW(ctypes.byref(msg), None, 0, 0, 0)
        self._hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc, None, 0)
        if not self._hook:
            self.failed = f"热键钩子注册失败（错误码 {ctypes.get_last_error()}）"
            self.ready.set()
            return
        self.ready.set()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        user32.UnhookWindowsHookEx(self._hook)
        self._hook = None

    def stop(self, wait: float = 1.0):
        if self._tid:
            user32.PostThreadMessageW(self._tid, WM_QUIT, 0, 0)
        if self.is_alive():
            self.join(timeout=wait)
