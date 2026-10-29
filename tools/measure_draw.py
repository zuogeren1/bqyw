# -*- coding: utf-8 -*-
"""采样监控：记录卡槽翻牌的精确时间线，用来推算抽牌的最小间隔。

只读屏幕，不点击。用法：
    python tools/measure_draw.py --seconds 90
"""
from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mss
import numpy as np

from bqyw import geometry as G
from bqyw.screen import window_client_box, find_window

BAND_X1, BAND_X2 = 150, 2100
BAND_Y1, BAND_Y2 = 320, 420
INK = 110
DARK_MIN = 250
SAMPLE_S = 0.012


def slot_ranges():
    out = []
    for i in range(G.SLOT_COUNT):
        x = 208 + G.SLOT_PITCH * i - BAND_X1 + 16
        out.append((x, x + 62))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=90.0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    hit = find_window()
    if not hit:
        print("没找到游戏窗口"); return
    hwnd, title, _ = hit
    box = window_client_box(hwnd)
    print(f"窗口 {title} {box['width']}x{box['height']}", flush=True)

    grab_box = {"left": box["left"] + BAND_X1, "top": box["top"] + BAND_Y1,
                "width": BAND_X2 - BAND_X1, "height": BAND_Y2 - BAND_Y1}
    ranges = slot_ranges()

    lines = []
    t0 = time.time()
    prev_mask = (False,) * G.SLOT_COUNT
    prev_frame = None
    last_change_t = t0
    events = []

    with mss.MSS() as sct:
        while time.time() - t0 < args.seconds:
            now = time.time()
            raw = sct.grab(grab_box)
            frame = np.asarray(raw, dtype=np.uint8)[:, :, :3]
            gray = frame.mean(axis=2)

            mask = []
            for (x1, x2) in ranges:
                patch = gray[14:84, x1:x2]
                mask.append(int((patch < INK).sum()) > DARK_MIN)
            mask = tuple(mask)

            motion = 0.0
            if prev_frame is not None and prev_frame.shape == frame.shape:
                motion = float(np.abs(frame.astype(np.int16)
                                      - prev_frame.astype(np.int16)).mean())
                if motion > 1.0:
                    last_change_t = now
            prev_frame = frame

            if mask != prev_mask:
                n = sum(mask)
                dt = now - (events[-1][0] if events else t0)
                line = (f"{now - t0:7.3f}s  {n}张  "
                        f"{''.join('#' if m else '.' for m in mask)}  "
                        f"+{dt:6.3f}s")
                print(line, flush=True)
                lines.append(line)
                events.append((now, n))
                prev_mask = mask

            time.sleep(SAMPLE_S)

    print("\n==== 汇总 ====", flush=True)
    reveals = [events[i][0] for i in range(1, len(events))
               if events[i][1] > events[i - 1][1]]
    if len(reveals) >= 2:
        gaps = np.diff(reveals)
        print(f"翻牌事件 {len(reveals)} 次，间隔："
              f"最小 {gaps.min():.3f}s  中位 {np.median(gaps):.3f}s  "
              f"平均 {gaps.mean():.3f}s  最大 {gaps.max():.3f}s", flush=True)
    else:
        print(f"翻牌事件 {len(reveals)} 次，样本不足", flush=True)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))


if __name__ == "__main__":
    main()
