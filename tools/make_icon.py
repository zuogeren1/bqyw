# -*- coding: utf-8 -*-
"""生成程序图标：python tools/make_icon.py

画一张「五点」牌（点数最大的那张），1024 超采样后缩成多尺寸 .ico。
产物 assets/icon.ico 是打包进 exe 的，改完要重新 build。
"""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "assets", "icon.ico")
S = 1024                                   # 画布边长
SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

BG_TOP = (31, 122, 99)
BG_BOT = (10, 58, 48)
RED = (198, 47, 42)
PAPER = (248, 246, 240)


def gradient(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)],
               fill=tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOT)))
    return img


def rounded(size: int, radius: int, box=None) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(box or (0, 0, size - 1, size - 1),
                                        radius=radius, fill=255)
    return m


def ring(draw, cx, cy, r_out, r_in, fill, paper):
    draw.ellipse([cx - r_out, cy - r_out, cx + r_out, cy + r_out], fill=fill)
    draw.ellipse([cx - r_in, cy - r_in, cx + r_in, cy + r_in], fill=paper)


def build() -> Image.Image:
    base = gradient(S).convert("RGBA")

    # 圆角底板
    base.putalpha(rounded(S, 170))

    # 卡片：先画正的，再整体转一点
    card = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    w, h = 540, 740
    box = [S // 2 - w // 2, S // 2 - h // 2, S // 2 + w // 2, S // 2 + h // 2]
    d = ImageDraw.Draw(card)
    d.rounded_rectangle(box, radius=64, fill=PAPER + (255,))

    # 五点（像「5 筒」）：中间一个 + 四角各一个
    cx, cy = S // 2, S // 2
    for dx, dy in ((0, 0), (-138, -205), (138, -205), (-138, 205), (138, 205)):
        ring(d, cx + dx, cy + dy, 58, 34, RED, PAPER)

    # 投影
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 110), (0, 0), card.split()[3])
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    base.alpha_composite(shadow, (0, 18))

    card = card.rotate(-11, resample=Image.BICUBIC, center=(cx, cy))
    base.alpha_composite(card)
    return base


def main():
    img = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.resize((256, 256), Image.LANCZOS).save(OUT, format="ICO", sizes=SIZES)
    png = os.path.join(os.path.dirname(OUT), "icon.png")
    img.resize((256, 256), Image.LANCZOS).save(png)
    print(f"写出 {OUT}  ({os.path.getsize(OUT)} 字节，{len(SIZES)} 个尺寸)")
    print(f"预览 {png}")


if __name__ == "__main__":
    sys.exit(main())
