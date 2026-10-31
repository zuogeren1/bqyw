# -*- coding: utf-8 -*-
"""截屏 → 读数：卡头模板匹配 + 战力点表高亮定位。

为什么不用固定坐标裁剪：实测 5 个卡槽的像素位置有几像素漂移
（第 5 槽的数字比第 1 槽右移 4px），固定框会把不同卡的边角切进来。
所以先按连通域**定位**数字与花色图标，再按各自外接框归一化比对。

模板都在 templates/ 下，由 tools/extract_templates.py 从截图切出：
    digit_1.png .. digit_5.png
    suit_tong.png / suit_suo.png / suit_wan.png
缺模板时不瞎猜：返回 (None, score) 或 (SUIT_UNKNOWN, score)，由上层决定标注还是跳过。
"""
from __future__ import annotations

import glob
import os

import cv2
import numpy as np

from . import geometry as G, paths
from .rules import SUIT_SUO, SUIT_TONG, SUIT_UNKNOWN, SUIT_WAN, Card

CANON = (56, 56)          # 归一化尺寸 (w, h)
INK_THRESHOLD = 110
MIN_COMPONENT = 120
MIN_DIGIT_AREA = 400
SUIT_ASCII = {SUIT_TONG: "tong", SUIT_SUO: "suo", SUIT_WAN: "wan"}
ASCII_SUIT = {v: k for k, v in SUIT_ASCII.items()}


def _read_gray(path: str) -> np.ndarray:
    return cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_GRAYSCALE)


def locate_parts(gray: np.ndarray, slot: int, ox: int = 0, oy: int = 0):
    """返回 (数字墨迹, 花色墨迹)；卡槽没翻开返回 None。

    墨迹靠连通域找，尺寸阈值都随分辨率缩放 —— 归一化到 56×56 之后，
    同一套模板在 1080p / 1440p 下比对结果是一样的（实测相似度 0.93~0.98）。

    ox/oy 是这张图的左上角在原画面里的位置：只抓了卡头条带时，
    坐标要减掉这个偏移才能落在条带内的正确位置。
    """
    x = G.SLOT_X0 + G.SLOT_PITCH * slot - ox
    y1, y2 = G.HEAD_Y1 - G.px(4) - oy, G.HEAD_Y2 + G.px(4) - oy
    reg = gray[y1:y2, x + G.px(4): x + G.px(152)]
    if reg.size == 0:
        return None
    m = (reg < INK_THRESHOLD).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    n, _lab, st, _cen = cv2.connectedComponentsWithStats(m, 8)
    comps = [(st[i][0], st[i][1], st[i][2], st[i][3], st[i][4])
             for i in range(1, n) if st[i][4] >= G.area(MIN_COMPONENT)]
    if not comps:
        return None
    comps.sort(key=lambda c: c[0])

    digit = next((c for c in comps
                  if c[4] >= G.area(MIN_DIGIT_AREA) and c[3] >= G.px(40)), None)
    if digit is None:
        return None
    # 花色图标：数字右侧、同一行附近的较大连通域
    glyph = None
    for c in comps:
        if c[0] <= digit[0] + digit[2] - 6:
            continue
        if abs(c[1] - digit[1]) > G.px(30):
            continue
        glyph = c
        break

    def cut(c):
        if c is None:
            return None
        cx, cy, cw, ch, _ = c
        return reg[max(0, cy - 1): cy + ch + 1, max(0, cx - 1): cx + cw + 1]

    return cut(digit), (cut(glyph) if glyph else None)


def _norm(img: np.ndarray | None) -> np.ndarray | None:
    if img is None or img.size == 0:
        return None
    return cv2.resize(img, CANON, interpolation=cv2.INTER_AREA)


def similarity(a: np.ndarray, b: np.ndarray) -> float:
    """墨迹 IoU 与灰度差混合，0..1。"""
    ma = (a < INK_THRESHOLD).astype(np.uint8)
    mb = (b < INK_THRESHOLD).astype(np.uint8)
    union = np.logical_or(ma, mb).sum()
    iou = np.logical_and(ma, mb).sum() / union if union else 0.0
    diff = 1.0 - np.abs(a.astype(np.float32) - b.astype(np.float32)).mean() / 255.0
    return 0.65 * iou + 0.35 * diff


class TemplateSet:
    def __init__(self, template_dir: str | None = None):
        if template_dir is None:
            template_dir = str(paths.resource_dir("templates", "*.png"))
        self.dir = template_dir
        self.digits: dict[int, np.ndarray] = {}
        self.suits: dict[str, np.ndarray] = {}
        self.rounds: dict[int, np.ndarray] = {}
        self._load()

    def _load(self):
        for p in glob.glob(os.path.join(self.dir, "digit_*.png")):
            n = os.path.basename(p)[6:-4]
            if n.isdigit():
                t = _norm(_read_gray(p))
                if t is not None:
                    self.digits[int(n)] = t
        for p in glob.glob(os.path.join(self.dir, "suit_*.png")):
            name = os.path.basename(p)[5:-4]
            if name in ASCII_SUIT:
                t = _norm(_read_gray(p))
                if t is not None:
                    self.suits[ASCII_SUIT[name]] = t
        for p in glob.glob(os.path.join(self.dir, "round_*.png")):
            n = os.path.basename(p)[6:-4]
            if n.isdigit():
                raw = _read_gray(p)
                if raw is not None:
                    self.rounds[int(n)] = raw

    @property
    def missing(self) -> dict[str, list]:
        return {
            "digit": [d for d in range(1, 6) if d not in self.digits],
            "suit": [s for s in SUIT_ASCII if s not in self.suits],
        }

    def classify_digit(self, ink: np.ndarray | None, min_score: float = 0.80):
        n = _norm(ink)
        if n is None or not self.digits:
            return None, 0.0
        ranked = sorted(((similarity(n, t), d) for d, t in self.digits.items()), reverse=True)
        score, best = ranked[0]
        return (best if score >= min_score else None), score

    def classify_suit(self, ink: np.ndarray | None, min_score: float = 0.80):
        n = _norm(ink)
        if n is None or not self.suits:
            return SUIT_UNKNOWN, 0.0
        ranked = sorted(((similarity(n, t), s) for s, t in self.suits.items()), reverse=True)
        score, best = ranked[0]
        return (best if score >= min_score else SUIT_UNKNOWN), score

    def read_card(self, frame: np.ndarray, slot: int) -> tuple[Card | None, float]:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
        return self.read_card_gray(gray, slot)

    def read_card_gray(self, gray: np.ndarray, slot: int,
                      ox: int = 0, oy: int = 0) -> tuple[Card | None, float]:
        """已经是灰度图时走这里 —— 一整手牌只转一次色。"""
        parts = locate_parts(gray, slot, ox, oy)
        if parts is None:
            return None, 0.0
        dink, gink = parts
        point, pscore = self.classify_digit(dink)
        if point is None:
            return None, pscore
        suit, _ = self.classify_suit(gink)
        return Card(point, suit), pscore

    def read_round_patch(self, patch: np.ndarray, min_score: float = 0.55) -> int | None:
        """已经裁到「轮次数字」那个小框时的版本（复核轮次用，8ms 抓一次就够）。"""
        if not self.rounds or patch is None or patch.size == 0:
            return None
        crop = patch
        if crop.ndim == 3:
            crop = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        best, best_score = None, -1.0
        size = (crop.shape[1], crop.shape[0])
        for rnd, tpl in self.rounds.items():
            if tpl.shape != crop.shape:
                # 模板是按 2560 裁的；换个分辨率就把模板缩到当前框的大小
                tpl = cv2.resize(tpl, size, interpolation=cv2.INTER_AREA)
            score = float(cv2.matchTemplate(crop, tpl, cv2.TM_CCOEFF_NORMED)[0, 0])
            if score > best_score:
                best, best_score = rnd, score
        return best if best_score >= min_score else None

    def read_round(self, frame: np.ndarray, min_score: float = 0.55) -> int | None:
        """读「当前轮次」里的那个数字：1 或 2。第二轮不能放弃重开，必须靠它判断。"""
        if not self.rounds:
            return None
        b = G.ROUND_NUM
        # 先裁再转灰度：这个框只有 54×42，没必要为它把整帧 2560×1440 转一遍
        return self.read_round_patch(frame[b.y1:b.y2, b.x1:b.x2], min_score)

    def read_hand(self, frame: np.ndarray, ox: int = 0, oy: int = 0) -> list[Card]:
        """读手牌。frame 可以是整屏，也可以只是 card_band() 那条；后者要带 ox/oy。

        整屏转灰度一次就够。以前是每个卡槽各转一次，5 张牌就把 2560×1440
        反复算 5 遍（每次还要临时分配 3.7MB），纯属白烧 CPU 和堆。
        """
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
        out: list[Card] = []
        for i in range(G.SLOT_COUNT):
            card, _ = self.read_card_gray(gray, i, ox, oy)
            if card is None:
                break
            out.append(card)
        return out


DRAW_BTN_REGION = (2320, 1040, 2520, 1130)   # 「抽牌」按钮的文字区（基准分辨率）


def draw_btn_region() -> tuple[int, int, int, int]:
    return tuple(G.px(v) for v in DRAW_BTN_REGION)


def draw_btn_ready(patch: np.ndarray, min_sat: float = 60.0) -> bool:
    """已经裁好的「抽牌」按钮区域是否可点。

    实测饱和度：可用 ≈ 117，冷却/禁用 ≈ 12，区分度很好。
    用它当"可以点了"的信号，比等整张牌读完更快 —— 按钮比牌面先就绪。
    """
    if patch is None or patch.size == 0:
        return False
    hsv = cv2.cvtColor(patch, cv2.COLOR_BGR2HSV)
    return float(hsv[:, :, 1].mean()) > min_sat


def is_draw_enabled(frame: np.ndarray, min_sat: float = 60.0) -> bool:
    """整帧里的「抽牌」按钮是否可点。"""
    if frame is None or frame.size == 0:
        return False
    x1, y1, x2, y2 = draw_btn_region()
    return draw_btn_ready(frame[y1:y2, x1:x2], min_sat)


CARD_BAND = (150, 320, 2100, 420)      # 5 个卡头的横向条带（基准分辨率）


def card_band() -> tuple[int, int, int, int]:
    return tuple(G.px(v) for v in CARD_BAND)


def modal_confirm_visible(patch: np.ndarray) -> bool:
    """放弃确认弹窗：只看那颗黄色「确认」按钮。"""
    if patch is None or patch.size == 0:
        return False
    hsv = cv2.cvtColor(patch, cv2.COLOR_BGR2HSV)
    yellow = cv2.inRange(hsv, np.array((20, 120, 150)), np.array((35, 255, 255)))
    return float(yellow.mean()) > 40


def count_cards_band(band: np.ndarray) -> int:
    """在卡头条带上数翻开了几张。只要几毫秒，用于等清空/等铺满。"""
    if band is None or band.size == 0:
        return 0
    gray = cv2.cvtColor(band, cv2.COLOR_BGR2GRAY) if band.ndim == 3 else band
    bx, by = card_band()[:2]
    n = 0
    for i in range(G.SLOT_COUNT):
        x = G.SLOT_X0 + G.SLOT_PITCH * i + G.px(16) - bx
        y = G.HEAD_Y1 - by + G.px(4)
        patch = gray[y:y + G.px(70), x:x + G.px(62)]
        if patch.size and int((patch < INK_THRESHOLD).sum()) > G.area(250):
            n += 1
        else:
            break
    return n


def _gray_of(patch: np.ndarray) -> np.ndarray:
    return cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY) if patch.ndim == 3 else patch


def looks_like_draw_screen(frame: np.ndarray) -> bool:
    """判断这一帧到底是不是百趣演武的抽牌界面。

    用两个几乎不会变的结构做锚点：
      * 左下「战力点 / 奖励点」深色标签块（x 0..220, y 1010..1160）
      * 右上「铭牌库剩余」浅色面板（x 2230..2545, y 210..300）

    这个检查专门用来挡"抓到了被遮挡的窗口 / 冻结的旧画面"——
    那种情况下数字全错，与其瞎点不如直接停。
    """
    if frame is None or frame.size == 0:
        return False
    try:
        # 两块各转各的：整帧转灰度是 3.7MB，这两块加起来不到 5 万像素
        label = _gray_of(frame[G.px(1015):G.px(1150), G.px(10):G.px(215)])
        panel = _gray_of(frame[G.px(215):G.px(300), G.px(2235):G.px(2540)])
        return float(label.mean()) < 120.0 and float(panel.mean()) > 195.0
    except Exception:
        return False


# ---------------------------------------------------------------- 战力点表

def find_highlight(frame: np.ndarray) -> int | None:
    """返回高亮列下标 0..9；还没抽牌时返回 None。"""
    band = frame[G.TABLE_Y1:G.TABLE_Y2, :]
    if band.shape[0] == 0:
        return None
    hsv = cv2.cvtColor(band, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, np.array((95, 130, 120)), np.array((115, 255, 255)))
    mask[:, : G.COL_X0] = 0
    mask[:, G.COL_X0 + G.COL_PITCH * G.COL_N:] = 0
    counts = [int(mask[:, G.COL_X0 + G.COL_PITCH * i: G.COL_X0 + G.COL_PITCH * (i + 1)].sum())
              for i in range(G.COL_N)]
    if not counts or max(counts) < G.area(500):
        return None
    return int(np.argmax(counts))


def table_window_start(sum_bp: int) -> int:
    """战力点表格的窗口起点。

    表格不是固定在 1..10：合计算高时整条会横向滚动，让高亮列大致居中。
    实测四组（手牌合计 → 高亮列下标）：5→4、13→5、17→7、19→9，
    都符合 起点 = clamp(合计-5, 1, 10)、下标 = 合计 - 起点。
    """
    return max(1, min(int(sum_bp) - 5, 10))


def expected_highlight(sum_bp: int) -> int | None:
    """按手牌合计算出「表格该高亮第几列」。

    没牌、或者合计顶到 20 以上（那时高亮落在 MAX 列，不在那 10 格里）
    都返回 None —— 不知道就别拿它当判据。
    """
    if sum_bp <= 0:
        return None
    i = int(sum_bp) - table_window_start(sum_bp)
    return i if 0 <= i < G.COL_N else None
