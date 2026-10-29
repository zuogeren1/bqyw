# -*- coding: utf-8 -*-
"""识别自检：python tests/test_vision.py

依赖 refs/ 里的实机截图（不进仓库）。没有截图时只跑纯几何的检查。
标定数据都是从这些截图实测来的，改动 geometry / vision 之后跑一遍最快的回归。
"""
import os
import sys

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from bqyw import geometry as G  # noqa: E402
from bqyw import vision as V  # noqa: E402
from bqyw.rules import SUIT_SUO, SUIT_TONG, SUIT_WAN  # noqa: E402
from bqyw.vision import TemplateSet, expected_highlight, find_highlight  # noqa: E402

OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


def load(name):
    path = os.path.join(ROOT, "refs", name)
    if not os.path.exists(path):
        return None
    return cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_COLOR)


# ---------------------------------------------------------------- 坐标缩放
G.reset_resolution()
check("基准 抽牌按钮", G.BTN_DRAW, G.Box(2202, 1027, 2560, 1145))
check("基准 卡槽0", G.slot_box(0).x1, 208)
check("基准 px", G.px(4), 4)

s = G.use_resolution(1920, 1080)
check("1080p 缩放", round(s, 6), 0.75)
check("1080p 抽牌按钮", G.BTN_DRAW, G.Box(1652, 770, 1920, 859))
check("1080p 卡槽0", G.slot_box(0).x1, 156)
check("1080p 卡槽步进", G.slot_box(1).x1 - G.slot_box(0).x1, 275)
check("1080p 轮次框", G.ROUND_NUM, G.Box(1792, 912, 1833, 944))
check("1080p 长度", G.px(40), 30)
check("1080p 面积", G.area(400), 225)

# 1440p 与 900p 也应该落在同一族
check("1600x900 缩放", round(G.use_resolution(1600, 900), 6), 0.625)
check("1600x900 抽牌按钮", G.BTN_DRAW, G.Box(1376, 642, 1600, 716))

for wh in ((1920, 1200), (2560, 1080), (3440, 1440)):
    try:
        G.use_resolution(*wh)
    except G.UnsupportedResolution:
        OK.append(f"拒绝 {wh[0]}x{wh[1]}")
    else:
        raise AssertionError(f"{wh} 应该被拒绝")

G.reset_resolution()
check("复位后 抽牌按钮", G.BTN_DRAW, G.Box(2202, 1027, 2560, 1145))

# ---------------------------------------------------------------- 表格窗口
check("表格 5 点", expected_highlight(5), 4)
check("表格 13 点", expected_highlight(13), 5)
check("表格 17 点", expected_highlight(17), 7)
check("表格 19 点", expected_highlight(19), 9)
check("表格 20 点（MAX 列）", expected_highlight(20), None)
check("表格 溢出", expected_highlight(23), None)
check("表格 没牌", expected_highlight(0), None)

# ---------------------------------------------------------------- 截图回归
# (文件名, 期望手牌, 期望轮次, 期望高亮列)
CASES = [
    ("抽牌界面.png", [], 1, None),
    ("抽牌界面-选卡后-点数未溢出.png", [(5, SUIT_SUO)], 1, 4),
    ("抽牌界面-选卡后-点数溢出.png",
     [(5, SUIT_TONG), (2, SUIT_TONG), (3, SUIT_SUO), (4, SUIT_TONG), (5, SUIT_TONG)], 1, 9),
    ("抽牌界面-第二轮-5张.png",
     [(3, SUIT_TONG), (4, SUIT_SUO), (1, SUIT_WAN), (5, SUIT_SUO), (4, SUIT_TONG)], 2, 7),
    ("1080p-13.png",
     [(2, SUIT_WAN), (5, SUIT_WAN), (1, SUIT_WAN), (2, SUIT_SUO), (3, SUIT_SUO)], 1, 5),
    ("1080p-筒条万245.png",
     [(5, SUIT_TONG), (5, SUIT_TONG), (2, SUIT_TONG), (4, SUIT_SUO), (5, SUIT_WAN)], 1, None),
]

ts = TemplateSet()
ran = 0
for name, want_cards, want_round, want_hl in CASES:
    img = load(name)
    if img is None:
        continue
    ran += 1
    G.use_resolution(img.shape[1], img.shape[0])
    got = [(c.point, c.suit) for c in ts.read_hand(img)]
    check(f"{name} 手牌", got, want_cards)
    check(f"{name} 轮次", ts.read_round(img), want_round)
    check(f"{name} 高亮列", find_highlight(img), want_hl)
    check(f"{name} 像抽牌界面", V.looks_like_draw_screen(img), True)

# ---------------- 4K / 5K：参考图放大后再认
# 没有 4K 显示器，但缩放路径是同一套代码 —— 放大后仍认对，就说明 1.5× 这套换算成立。
for name, want_cards, want_round, _hl in CASES:
    img = load(name)
    if img is None or not want_cards:
        continue
    big = cv2.resize(img, None, fx=1.5, fy=1.5, interpolation=cv2.INTER_CUBIC)
    G.use_resolution(big.shape[1], big.shape[0])
    check(f"{name} ×1.5 手牌", [(c.point, c.suit) for c in ts.read_hand(big)], want_cards)
    check(f"{name} ×1.5 轮次", ts.read_round(big), want_round)

G.reset_resolution()

print(f"全部 {len(OK)} 项通过" + (f"（{ran} 张截图）" if ran else "（没找到 refs/，跳过截图回归）"))
