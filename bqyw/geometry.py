# -*- coding: utf-8 -*-
"""界面坐标。

基准是 **2560×1440** —— 所有常量都按这个分辨率实测写死。
跑到别的分辨率时先调 `use_resolution(w, h)`，本模块里的常量会被就地换成
缩放后的值，之后所有 `G.BTN_DRAW` / `slot_box(i)` 拿到的都已经是当前分辨率的坐标。

只认 16:9。别的比例（16:10、21:9）游戏会重新排布 UI，不能靠乘系数得到，
硬算出来的坐标会点歪，所以直接报错让人去标定。

用法：
    G.use_resolution(1920, 1080)      # 之后 G.BTN_DRAW 就是 1080p 的坐标
    G.px(4)                           # 基准 4px 在当前分辨率是多少

实测：1920×1080 就是 2560×1440 的 0.75 倍，卡槽、按钮、表格全部严丝合缝。
"""
from __future__ import annotations

from dataclasses import dataclass

BASE_W, BASE_H = 2560, 1440
ASPECT_TOL = 0.02        # 宽高比允许的偏差（16:9 ≈ 1.7778）
SCALE = 1.0              # 当前生效的缩放，由 use_resolution() 改


class UnsupportedResolution(ValueError):
    """这个分辨率没标定过，不能瞎点。"""


@dataclass(frozen=True)
class Box:
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def w(self) -> int:
        return self.x2 - self.x1

    @property
    def h(self) -> int:
        return self.y2 - self.y1

    @property
    def center(self) -> tuple[int, int]:
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    def scaled(self, fx: float, fy: float) -> "Box":
        return Box(round(self.x1 * fx), round(self.y1 * fy),
                   round(self.x2 * fx), round(self.y2 * fy))

    def crop(self, img):
        return img[self.y1:self.y2, self.x1:self.x2]


# ---------------------------------------------------------------- 卡槽
# 5 个卡槽，左边缘 208 起、步进 367；实体区 y 321..772
SLOT_COUNT = 5
SLOT_X0 = 208
SLOT_PITCH = 367
SLOT_Y1, SLOT_Y2 = 321, 772

# 卡头内部（避开描边）：数字区 / 花色图标区
# 数字右侧 x+74 附近有一根竖线分隔符，所以数字区在 +78 处收边，图标区从 +84 起
DIGIT_DX1, DIGIT_DX2 = 16, 78
GLYPH_DX1, GLYPH_DX2 = 84, 146
HEAD_Y1, HEAD_Y2 = 334, 404


def slot_box(i: int) -> Box:
    x = SLOT_X0 + SLOT_PITCH * i
    return Box(x, SLOT_Y1, x + 347, SLOT_Y2)


def digit_box(i: int) -> Box:
    x = SLOT_X0 + SLOT_PITCH * i
    return Box(x + DIGIT_DX1, HEAD_Y1, x + DIGIT_DX2, HEAD_Y2)


def glyph_box(i: int) -> Box:
    x = SLOT_X0 + SLOT_PITCH * i
    return Box(x + GLYPH_DX1, HEAD_Y1, x + GLYPH_DX2, HEAD_Y2)


def head_box(i: int) -> Box:
    """数字 + 分隔 + 花色图标 整条。"""
    x = SLOT_X0 + SLOT_PITCH * i
    return Box(x + DIGIT_DX1, HEAD_Y1, x + GLYPH_DX2, HEAD_Y2)


# ---------------------------------------------------------------- 战力点表
# 10 列 + 右侧 MAX 20 列
TABLE_Y1, TABLE_Y2 = 1010, 1160
COL_X0 = 227
COL_PITCH = 176
COL_N = 10
COL_LAST = Box(COL_X0 + COL_PITCH * COL_N, TABLE_Y1, COL_X0 + COL_PITCH * COL_N + 180, TABLE_Y2)

def col_box(i: int) -> Box:
    """第 i 列（0 基）的整格。"""
    x = COL_X0 + COL_PITCH * i
    return Box(x, TABLE_Y1, x + COL_PITCH, TABLE_Y2)


# ---------------------------------------------------------------- 按钮
BTN_DRAW = Box(2202, 1027, 2560, 1145)        # 抽牌
BTN_FORFEIT = Box(1675, 1270, 1920, 1365)     # 撤离 / 放弃挑战
BTN_KAIMEN = Box(1920, 1270, 2240, 1365)      # 开门见喜（灰）
BTN_START = Box(2240, 1270, 2500, 1365)       # 开始挑战
ROUND_LABEL = Box(2240, 1230, 2560, 1262)     # 当前轮次 1/2 整条
ROUND_NUM = Box(2390, 1216, 2444, 1258)       # 只有那个数字（1 或 2）

# 放弃确认弹窗
MODAL_CANCEL = Box(840, 888, 1270, 970)
MODAL_CONFIRM = Box(1304, 888, 1711, 970)

# 右侧铭牌库剩余面板
DECK_PANEL = Box(2224, 205, 2545, 715)
DECK_ROW_Y = (285, 390, 495, 590, 680)        # 1..5 战力点所在行中心


# ---------------------------------------------------------------- 缩放
_SCALED = (
    "SLOT_X0", "SLOT_PITCH", "SLOT_Y1", "SLOT_Y2",
    "DIGIT_DX1", "DIGIT_DX2", "GLYPH_DX1", "GLYPH_DX2", "HEAD_Y1", "HEAD_Y2",
    "TABLE_Y1", "TABLE_Y2", "COL_X0", "COL_PITCH", "COL_LAST",
    "BTN_DRAW", "BTN_FORFEIT", "BTN_KAIMEN", "BTN_START",
    "ROUND_LABEL", "ROUND_NUM", "MODAL_CANCEL", "MODAL_CONFIRM",
    "DECK_PANEL", "DECK_ROW_Y",
)

# 基准值快照：切几次分辨率都不怕，永远从这一份算
_BASE = {name: globals()[name] for name in _SCALED}


def use_resolution(width: int, height: int) -> float:
    """按实际画面尺寸重算全部坐标，返回缩放倍数。不支持的比例抛异常。"""
    if width <= 0 or height <= 0:
        raise UnsupportedResolution(f"分辨率不合法：{width}×{height}")
    if abs(width / height - BASE_W / BASE_H) > ASPECT_TOL:
        raise UnsupportedResolution(
            f"只支持 16:9，现在是 {width}×{height}（{width / height:.3f}:1）。"
            "比例不同时游戏会重新排布界面，坐标不能靠缩放换算，得重新标定。")

    fx, fy = width / BASE_W, height / BASE_H
    for name, base in _BASE.items():
        if isinstance(base, Box):
            globals()[name] = base.scaled(fx, fy)
        elif isinstance(base, tuple):
            globals()[name] = tuple(round(v * fy) for v in base)
        else:
            globals()[name] = round(base * fy)
    globals()["SCALE"] = fy
    return fy


def reset_resolution() -> None:
    """回到基准 2560×1440（写测试时用）。"""
    use_resolution(BASE_W, BASE_H)


def px(v: float) -> int:
    """基准分辨率下的长度 → 当前分辨率的长度。"""
    return int(round(v * SCALE)) if v else 0


def area(v: float) -> int:
    """基准分辨率下的像素面积 → 当前分辨率。面积按平方缩放。"""
    return max(1, int(round(v * SCALE * SCALE)))

