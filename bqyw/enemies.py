# -*- coding: utf-8 -*-
"""敌人黑名单：靠立绘认牌，命中就放弃重开。

**为什么认立绘、不认名字**

* 牌面就是敌人。立绘在「数据溢出」提级后**不变**，而 Lv 会变（710~740 那行必须避开）；
* 名字条每张都带 `×N` 后缀，文字还是居中排的，同一敌人两次抽到只有 NCC 0.29~0.56，
  当模板必漏；
* 立绘同一敌人 0.84~0.93，不同敌人最高 0.69 —— 阈值 0.78 两边都留着余量，
  而且**宁可漏弃、不可误弃**：认不准就当没这个敌人，绝不拿错的手牌去放弃。

裁剪框（基准 2560×1440）：卡槽 x0 = 208 + 367i，立绘 `x0+6 … x0+341`、`y 459 … 705`。
这条框是量出来的，只框立绘：往上一格是战力点数字、往下一格是 Lv.90 和名字，
框进去之后同一敌人在别的点数/等级下就认不出来了。

模板由 `tools/build_enemies.py` 从 100 轮扫描整理：`templates/enemies.json` + `templates/enemies/<slug>.png`。
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field

import cv2
import numpy as np

from . import config, geometry as G, paths

# ---------------------------------------------------------------- 常量

CANON = 128                     # 立绘归一化边长
MIN_NCC = 0.78                  # 认定阈值
CONFIRM_NCC = 0.88              # 一眼定案线：低于它的先记下，下一帧还是同一个才弃
WEAK_NCC = 0.60                 # 灰区下界：这一带多半是翻牌动画没走完，值得再抓一帧

ART_DX = (6, 341)               # 立绘横向（相对卡槽左边缘）
ART_DY = (459, 705)             # 立绘纵向 —— 避开上面的战力点、下面的 Lv 与名字

MODE_OFF = "off"
MODE_BAN = "ban"
MODE_ONLY = "only"

MODE_CN = {MODE_OFF: "关", MODE_BAN: "黑名单", MODE_ONLY: "白名单"}
CN_MODE = {v: k for k, v in MODE_CN.items()}

MAX_DROPS = 20                  # 连弃这么多次还没出目标，大概是名单配错了，停手提醒


# ---------------------------------------------------------------- 立绘

def art_box(slot: int):
    """第 slot 张牌的立绘裁剪框（当前分辨率）。"""
    x = G.SLOT_X0 + G.SLOT_PITCH * slot
    return G.Box(x + G.px(ART_DX[0]), G.px(ART_DY[0]),
                 x + G.px(ART_DX[1]), G.px(ART_DY[1]))


def feat(img) -> np.ndarray | None:
    """裁剪块 → 去均值、归一化的 128×128 特征（两个特征的点积就是 NCC）。"""
    if img is None or getattr(img, "size", 0) == 0:
        return None
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    g = cv2.resize(g, (CANON, CANON), interpolation=cv2.INTER_AREA).astype(np.float32)
    g -= g.mean()
    n = float(np.linalg.norm(g)) + 1e-9
    return g / n


def _read_gray(path: str):
    try:
        data = np.fromfile(path, np.uint8)
    except OSError:
        return None
    if data.size == 0:
        return None
    return cv2.imdecode(data, cv2.IMREAD_GRAYSCALE)


# ---------------------------------------------------------------- 名单

@dataclass(frozen=True)
class Enemy:
    slug: str
    name: str
    point: int
    count: int = 0
    res: dict = field(default_factory=dict)

    def __str__(self) -> str:
        return f"{self.name}（{self.point} 点）"


@dataclass(frozen=True)
class Hit:
    enemy: Enemy
    score: float

    @property
    def name(self) -> str:
        return self.enemy.name

    @property
    def point(self) -> int:
        return self.enemy.point

    def text(self, digits: int = 2) -> str:
        return f"{self.enemy.name}（{self.score:.{digits}f}）"


class Book:
    """全部敌人模板 + 比对。名单里没有的不认。"""

    def __init__(self, directory: str | None = None):
        self.dir = directory or str(paths.resource_dir("templates", "*.png"))
        self.enemies: list[Enemy] = []
        self.threshold = MIN_NCC
        self._feats: np.ndarray | None = None
        self._by_point: dict[int, list[int]] = {}
        self._load()

    def _load(self) -> None:
        path = os.path.join(self.dir, "enemies.json")
        try:
            with open(path, encoding="utf-8") as f:
                doc = json.load(f)
        except (OSError, ValueError):
            return
        try:
            self.threshold = float(doc.get("threshold") or MIN_NCC)
        except (TypeError, ValueError):
            self.threshold = MIN_NCC
        feats = []
        for item in doc.get("enemies") or []:
            art = os.path.join(self.dir, "enemies", str(item.get("art") or ""))
            img = _read_gray(art)
            f = feat(img)
            if f is None:
                continue
            self._by_point.setdefault(int(item.get("point") or 0), []).append(len(self.enemies))
            self.enemies.append(Enemy(
                slug=str(item.get("slug") or ""),
                name=str(item.get("name") or ""),
                point=int(item.get("point") or 0),
                count=int(item.get("count") or 0),
                res=dict(item.get("res") or {}),
            ))
            feats.append(f)
        if feats:
            self._feats = np.stack(feats)

    def __len__(self) -> int:
        return len(self.enemies)

    def names(self) -> list[str]:
        return [e.name for e in self.enemies]

    def by_point(self, point: int) -> list[Enemy]:
        return [self.enemies[i] for i in self._by_point.get(point, [])]

    def art_path(self, enemy: Enemy) -> str:
        return os.path.join(self.dir, "enemies", enemy.slug + ".png")

    def best(self, patch, point: int = 0):
        """最像的那个敌人。point 非 0 时只在该点数的候选里找（先把范围缩到 4~8 个）。

        返回 `(Hit | None, score)`：`score` 是最高分，未必过关；
        调用方自己按 `threshold` 判 —— 日志里要写"像谁、几分"，方便排查。
        """
        f = feat(patch)
        if f is None or self._feats is None:
            return None, 0.0
        idx = self._by_point.get(point) if point else None
        if idx is None:
            idx = list(range(len(self.enemies)))
        if not idx:
            return None, 0.0
        scores = (self._feats[idx] * f).sum(axis=(1, 2))
        k = int(np.argmax(scores))
        return Hit(self.enemies[idx[k]], float(scores[k])), float(scores[k])


# ---------------------------------------------------------------- 策略

@dataclass(frozen=True)
class Policy:
    """弃牌策略：关 / 名单内弃 / 名单外弃。"""

    mode: str = MODE_OFF
    names: tuple[str, ...] = ()

    def active(self) -> bool:
        # 白名单一条都没勾等于关 —— 不然会把每一手牌都弃掉
        return bool(self.names) and self.mode in (MODE_BAN, MODE_ONLY)

    def should_drop(self, name: str) -> bool:
        if not self.active():
            return False
        inside = name in self.names
        return inside if self.mode == MODE_BAN else not inside

    def label(self) -> str:
        if not self.names:
            return MODE_CN[MODE_OFF] if self.mode == MODE_OFF else MODE_CN[self.mode] + "（空）"
        if self.mode == MODE_OFF:
            return MODE_CN[MODE_OFF]
        return f"{MODE_CN[self.mode]} {len(self.names)} 个"

    def __str__(self) -> str:
        if not self.active():
            return MODE_CN[MODE_OFF]
        return f"{MODE_CN[self.mode]}：{'、'.join(self.names)}"


OFF = Policy()


def parse_names(text: str | None) -> tuple[str, ...]:
    """`--ban 解燥,断云叟` / 空格 / 顿号都能分隔。"""
    if not text:
        return ()
    seen = []
    for part in re.split(r"[,，、/\s]+", str(text)):
        part = part.strip()
        if part and part not in seen:
            seen.append(part)
    return tuple(seen)


def unknown_names(names, book: Book | None = None) -> list[str]:
    """名单里写了、但模板库里没有的名字 —— 拼错了等于名单没生效。"""
    pool = set((book or Book()).names())
    return [n for n in names if n not in pool]


def load_policy(cfg: dict | None = None) -> Policy:
    cfg = config.load() if cfg is None else cfg
    raw = cfg.get("enemies")
    if not isinstance(raw, dict):
        return OFF
    mode = str(raw.get("mode") or MODE_OFF)
    if mode not in MODE_CN:
        mode = MODE_OFF
    names = tuple(str(n) for n in (raw.get("names") or []) if str(n).strip())
    return Policy(mode=mode, names=names)


def store_policy(policy: Policy) -> bool:
    cfg = config.load()
    cfg["enemies"] = {"mode": policy.mode, "names": list(policy.names)}
    return config.save(cfg)
