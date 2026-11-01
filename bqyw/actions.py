# -*- coding: utf-8 -*-
"""鼠标点击。

两个坑，都踩过：

1. 游戏是 DirectInput 风格，pyautogui 的软件事件经常不生效，所以走 pydirectinput
   （SendInput + 扫描码）。
2. **按下/抬起之间必须有足够长的间隔**。实测 20ms 太短——游戏按帧轮询输入，
   抬起事件会被吞掉，结果是左键卡在按下状态，之后所有点击都被当成同一次长按，
   表现就是"按住抽牌按钮不动了"。
"""
from __future__ import annotations

import ctypes
import time

try:
    import pydirectinput
    pydirectinput.FAILSAFE = False
    # 这个 PAUSE 是 pydirectinput 每次调用后自己加的睡眠，
    # moveTo/mouseDown/mouseUp 三次就是 60ms —— 节拍全靠我们自己控制就行
    pydirectinput.PAUSE = 0.005
    HAS_PDI = True
except Exception:                                   # pragma: no cover
    HAS_PDI = False

user32 = ctypes.windll.user32
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004

PRESS_SECONDS = 0.06        # 按下到抬起的保持时间；20ms 会被游戏吞掉，60ms 实测安全
SETTLE_SECONDS = 0.015      # 移动到位后、按下之前的稳定时间


def release_stuck() -> None:
    """先把左键强行抬起来。

    每次点击前都做一遍：万一上一轮的抬起事件丢了，这里能救回来，
    不会让整局都陷在"按住不动"的状态里。
    """
    user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    time.sleep(0.004)


def _send_input_click(x: int, y: int) -> None:
    user32.SetCursorPos(int(x), int(y))
    time.sleep(SETTLE_SECONDS)
    user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    time.sleep(PRESS_SECONDS)
    user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)


def click(x: int, y: int, delay: float = 0.01) -> None:
    x, y = int(x), int(y)
    release_stuck()
    try:
        if HAS_PDI:
            pydirectinput.moveTo(x, y)
            time.sleep(SETTLE_SECONDS)
            pydirectinput.mouseDown()
            time.sleep(PRESS_SECONDS)
            pydirectinput.mouseUp()
        else:
            _send_input_click(x, y)
    finally:
        # 无论中间出什么事，抬起来这件事必须执行
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
        time.sleep(delay)


def click_box(box, delay: float = 0.01, origin: tuple[int, int] = (0, 0)) -> None:
    """点一个 Box 的中心。origin 是游戏客户区左上角在屏幕上的位置。"""
    cx, cy = box.center
    click(cx + origin[0], cy + origin[1], delay=delay)


def activate_window(hwnd) -> None:
    """把游戏窗口提到前台，避免点到别的窗口上。

    只调 SetForegroundWindow 经常白调：当前台窗口属于别的进程时，Windows 会
    **直接拒绝**（日志里就是"没能把游戏窗口提到前台"）。标准解法是把本线程和
    前台窗口的线程输入队列临时挂在一起（AttachThreadInput），借它的权限把窗口
    拎到前面 —— 实测在"用户正盯着别的窗口"时也只有这一招管用。
    """
    if not hwnd:
        return
    u = ctypes.windll.user32
    try:
        u.ShowWindow(hwnd, 9)                          # SW_RESTORE
        fg = u.GetForegroundWindow()
        if fg == hwnd:
            time.sleep(0.2)
            return
        t_fg = u.GetWindowThreadProcessId(fg, None) if fg else 0
        t_me = ctypes.windll.kernel32.GetCurrentThreadId()
        attached = False
        if t_fg and t_fg != t_me:
            attached = bool(u.AttachThreadInput(t_me, t_fg, True))
        try:
            u.BringWindowToTop(hwnd)
            u.SetForegroundWindow(hwnd)
            u.SetFocus(hwnd)
        finally:
            if attached:
                u.AttachThreadInput(t_me, t_fg, False)
        time.sleep(0.2)
    except Exception:
        pass
