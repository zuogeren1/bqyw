# -*- coding: utf-8 -*-
"""从仓库根的 assets/icon.png 生成安卓图标：python android/tools/make_icons.py"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(os.path.dirname(ROOT), "assets", "icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

src = Image.open(SRC).convert("RGBA")

# 1) 桌面图标：五档密度
for folder, size in {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}.items():
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    src.resize((size, size), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    print("wrote", folder, size)

# 2) 悬浮球：圆形裁切 + 白圈
BALL = 144
ball = src.resize((BALL, BALL), Image.LANCZOS)
mask = Image.new("L", (BALL, BALL), 0)
ImageDraw.Draw(mask).ellipse((2, 2, BALL - 3, BALL - 3), fill=255)
ball.putalpha(mask)
ring = Image.new("RGBA", (BALL, BALL), (0, 0, 0, 0))
ImageDraw.Draw(ring).ellipse((1, 1, BALL - 2, BALL - 2), outline=(255, 255, 255, 230), width=5)
ball = Image.alpha_composite(ball, ring)
out = os.path.join(RES, "drawable-nodpi")
os.makedirs(out, exist_ok=True)
ball.save(os.path.join(out, "ball.png"))
print("wrote drawable-nodpi/ball.png", ball.size)
