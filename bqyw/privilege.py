# -*- coding: utf-8 -*-
"""权限检查。

游戏进程通常是提权启动的，Windows 的 UIPI 会**静默丢弃**低完整性进程发给它的
SendInput / PostMessage —— 表现就是"点了没反应，也不报错"。所以在动手之前先自检。
"""
from __future__ import annotations

import ctypes
import os
import subprocess
import sys
from ctypes import wintypes

try:
    import win32process
    HAS_WIN32 = True
except Exception:                                   # pragma: no cover
    HAS_WIN32 = False

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
TOKEN_QUERY = 0x0008
TokenElevation = 20


class TOKEN_ELEVATION(ctypes.Structure):
    _fields_ = [("TokenIsElevated", wintypes.DWORD)]


def is_admin() -> bool:
    """当前进程是否已提权。"""
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def pid_of_window(hwnd) -> int | None:
    if not HAS_WIN32 or not hwnd:
        return None
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        return pid
    except Exception:
        return None


def is_process_elevated(pid: int | None) -> bool | None:
    """目标进程是否提权。返回 None 表示查不出来。"""
    if not pid:
        return None
    k32 = ctypes.windll.kernel32
    adv = ctypes.windll.advapi32
    h = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        # 打不开通常意味着对方权限更高（或已退出）
        return None
    try:
        tok = wintypes.HANDLE()
        if not adv.OpenProcessToken(h, TOKEN_QUERY, ctypes.byref(tok)):
            return None
        try:
            elev = TOKEN_ELEVATION()
            ret = wintypes.DWORD()
            ok = adv.GetTokenInformation(tok, TokenElevation, ctypes.byref(elev),
                                         ctypes.sizeof(elev), ctypes.byref(ret))
            return bool(elev.TokenIsElevated) if ok else None
        finally:
            k32.CloseHandle(tok)
    finally:
        k32.CloseHandle(h)


def relaunch_as_admin(argv: list[str] | None = None, script: str | None = None) -> bool:
    """用 UAC 把程序以管理员身份重开一份，成功返回 True。

    打包成 exe 之后不能再走「python xxx.py」：`sys.executable` 已经是 exe 本身，
    直接重开它，也不用再传脚本名。
    """
    if argv is None:
        argv = sys.argv[1:]
    if getattr(sys, "frozen", False):
        workdir = os.path.dirname(sys.executable)
        target = sys.executable
        params = subprocess.list2cmdline(list(argv))
    else:
        if script is None:
            script = os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "main.py")
        workdir = os.path.dirname(script)
        target = sys.executable
        params = subprocess.list2cmdline([script] + list(argv))
    try:
        rc = ctypes.windll.shell32.ShellExecuteW(
            None, "runas", target, params, workdir, 1)
        return int(rc) > 32
    except Exception:
        return False


def check_target(hwnd) -> dict:
    """给一块诊断信息，运行前打印用。"""
    pid = pid_of_window(hwnd)
    return {
        "self_admin": is_admin(),
        "target_pid": pid,
        "target_elevated": is_process_elevated(pid),
    }
