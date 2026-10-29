# -*- coding: utf-8 -*-
"""从 refs/ 的游戏截图里切出模板。

想补模板：往 SAMPLES 里加一行 (图片文件名, 槽位, 点数, 花色) 再跑一次即可。
花色用中文「筒/索/萬」写，落盘时会转成 tong/suo/wan。
"""
import os
import sys

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from bqyw import geometry as G  # noqa: E402
from bqyw.rules import SUIT_UNKNOWN  # noqa: E402
from bqyw.vision import SUIT_ASCII, locate_parts  # noqa: E402

REF = os.path.join(ROOT, "refs")
OUT = os.path.join(ROOT, "templates")
os.makedirs(OUT, exist_ok=True)

# (截图, 槽位, 点数, 花色)
SAMPLES = [
    ("抽牌界面-选卡后-点数未溢出.png", 0, 5, "索"),
    ("抽牌界面-选卡后-点数溢出.png", 0, 5, "筒"),
    ("抽牌界面-选卡后-点数溢出.png", 1, 2, "筒"),
    ("抽牌界面-选卡后-点数溢出.png", 2, 3, "索"),
    ("抽牌界面-选卡后-点数溢出.png", 3, 4, "筒"),
    ("抽牌界面-选卡后-点数溢出.png", 4, 5, "筒"),
    ("抽牌界面-第二轮-5张.png", 2, 1, "萬"),
]

# 当前轮次的数字：文件名 -> 轮次
ROUND_SAMPLES = {
    "抽牌界面.png": 1,
    "抽牌界面-第二轮-5张.png": 2,
}


def read_gray(name):
    p = os.path.join(REF, name)
    return cv2.imdecode(np.fromfile(p, np.uint8), cv2.IMREAD_GRAYSCALE)


def main():
    gallery = []
    for fn, slot, point, suit in SAMPLES:
        gray = read_gray(fn)
        parts = locate_parts(gray, slot)
        if parts is None:
            print(f"!! {fn} slot{slot} 没找到卡头")
            continue
        dink, gink = parts
        cv2.imencode(".png", dink)[1].tofile(os.path.join(OUT, f"digit_{point}.png"))
        tag = f"p{point}_{suit}"
        if suit and suit != SUIT_UNKNOWN and gink is not None:
            cv2.imencode(".png", gink)[1].tofile(
                os.path.join(OUT, f"suit_{SUIT_ASCII[suit]}.png"))
        else:
            tag += "(花色未定)"
        print(f"slot{slot} -> point={point} suit={suit} digit={dink.shape} "
              f"glyph={None if gink is None else gink.shape}")
        gallery.append((tag, dink, gink))

    for fn, rnd in ROUND_SAMPLES.items():
        gray = read_gray(fn)
        b = G.ROUND_NUM
        crop = gray[b.y1:b.y2, b.x1:b.x2]
        if crop.size == 0:
            print(f"!! {fn} 轮次数字裁不到")
            continue
        cv2.imencode(".png", crop)[1].tofile(os.path.join(OUT, f"round_{rnd}.png"))
        print(f"round {rnd} <- {fn} {crop.shape}")

    rows = []
    for label, d, g in gallery:
        d2 = cv2.resize(d, (56, 56), interpolation=cv2.INTER_NEAREST)
        g2 = (cv2.resize(g, (56, 56), interpolation=cv2.INTER_NEAREST) if g is not None
              else np.full((56, 56), 255, np.uint8))
        cell = np.hstack([d2, np.full((56, 8), 60, np.uint8), g2])
        cell = cv2.copyMakeBorder(cell, 6, 6, 6, 6, cv2.BORDER_CONSTANT, value=40)
        cell3 = cv2.cvtColor(cell, cv2.COLOR_GRAY2BGR)
        lab = np.full((22, cell3.shape[1], 3), 30, np.uint8)
        cv2.putText(lab, label.encode("ascii", "replace").decode(),
                    (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
        rows.append(np.vstack([lab, cell3]))
    if rows:
        wmax = max(r.shape[1] for r in rows)
        rows = [np.hstack([r, np.full((r.shape[0], wmax - r.shape[1], 3), 30, np.uint8)])
                for r in rows]
        os.makedirs(os.path.join(ROOT, ".work"), exist_ok=True)
        cv2.imencode(".png", np.vstack(rows))[1].tofile(
            os.path.join(ROOT, ".work", "tpl_gallery.png"))
    print("模板已写入", OUT)


if __name__ == "__main__":
    main()
