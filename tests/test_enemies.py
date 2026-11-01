# -*- coding: utf-8 -*-
"""敌人黑名单自检：python tests/test_enemies.py"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np  # noqa: E402

from bqyw import config  # noqa: E402
from bqyw import geometry as G  # noqa: E402
from bqyw import enemies as E  # noqa: E402

OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


def check_true(name, cond):
    if not cond:
        raise AssertionError(f"{name}: 不成立")
    OK.append(name)


# ---------------------------------------------------------------- 模板库
book = E.Book()
check("模板库有 25 个敌人", len(book), 25)
check("阈值 0.78", round(book.threshold, 2), 0.78)
check("1 点 4 种", len(book.by_point(1)), 4)
check("5 点 8 种", len(book.by_point(5)), 8)
check_true("每个敌人都有立绘", all(os.path.exists(book.art_path(e)) for e in book.enemies))
check_true("没有重名的敌人", len(set(book.names())) == len(book.enemies))
check_true("每个敌人都标了点数", all(1 <= e.point <= 5 for e in book.enemies))

# ---------------------------------------------------------------- 自己认自己
# 拿每张模板当输入，必须认出它自己 —— 顺带验证"点数门"没把候选筛空
for enemy in book.enemies:
    art = E._read_gray(book.art_path(enemy))
    hit, score = book.best(art, enemy.point)
    check(f"认自己 {enemy.name}", (hit.name, round(score, 3)),
          (enemy.name, 1.0))
    hit0, score0 = book.best(art)          # 不给点数也要认得
    check(f"不限点数也认自己 {enemy.name}", hit0.name, enemy.name)

# ---------------------------------------------------------------- 分得开吗
feats = book._feats.reshape(len(book), -1)
sim = feats @ feats.T
np.fill_diagonal(sim, -1.0)
check_true("不同敌人最高相似度 < 阈值 - 0.05",
           float(sim.max()) < book.threshold - 0.05)

# ---------------------------------------------------------------- 点数门
# 1 点的敌人拿 5 点的点数去查，候选里根本没有它
art1 = E._read_gray(book.art_path(book.by_point(1)[0]))
hit, score = book.best(art1, 5)
check_true("点数不对时认不出（也不会乱认）",
           hit is None or hit.point == 5)
check_true("点数不对时分数够不上阈值", score < book.threshold)

# ---------------------------------------------------------------- 裁剪框
G.reset_resolution()
b = E.art_box(0)
check("1440p 立绘框", (b.x1, b.y1, b.x2, b.y2), (214, 459, 549, 705))
b = E.art_box(4)
check("1440p 第 5 槽", (b.x1, b.x2), (214 + 367 * 4, 549 + 367 * 4))
G.use_resolution(1920, 1080)
b = E.art_box(0)
check("1080p 立绘框", (b.x1, b.y1, b.x2, b.y2), (160, 344, 412, 529))
G.reset_resolution()

# ---------------------------------------------------------------- 名单语义
off = E.Policy()
check("默认是关", (off.mode, off.names, off.active()), (E.MODE_OFF, (), False))
check("关着永不弃", off.should_drop("存禄"), False)

ban = E.Policy(E.MODE_BAN, ("存禄", "解燥"))
check("黑名单 名单内弃", ban.should_drop("存禄"), True)
check("黑名单 名单外留", ban.should_drop("断云叟"), False)

only = E.Policy(E.MODE_ONLY, ("存禄",))
check("白名单 名单内留", only.should_drop("存禄"), False)
check("白名单 名单外弃", only.should_drop("断云叟"), True)

check("白名单空 = 关", E.Policy(E.MODE_ONLY, ()).active(), False)
check("白名单空 不弃", E.Policy(E.MODE_ONLY, ()).should_drop("x"), False)
check("黑名单空 = 关", E.Policy(E.MODE_BAN, ()).active(), False)

# ---------------------------------------------------------------- 名单解析
check("逗号分隔", E.parse_names("存禄,解燥"), ("存禄", "解燥"))
check("中文逗号/顿号/空格", E.parse_names("存禄，解燥、断云叟 存禄"),
      ("存禄", "解燥", "断云叟"))
check("空串", E.parse_names(""), ())
check("None", E.parse_names(None), ())

check("认不出的名字", E.unknown_names(("存禄", "张三"), book), ["张三"])

# ---------------------------------------------------------------- 配置读写
tmp = tempfile.mkdtemp(prefix="bqyw-cfg-")
real_path = config.path
config.path = lambda: os.path.join(tmp, "config.json")
try:
    check("没有配置时是关", E.load_policy().mode, E.MODE_OFF)
    check("写进配置", E.store_policy(E.Policy(E.MODE_BAN, ("存禄", "解燥"))), True)
    back = E.load_policy()
    check("读回模式", back.mode, E.MODE_BAN)
    check("读回名单", back.names, ("存禄", "解燥"))
    # store_policy 只动 enemies 这一块，别的设置不能被它抹掉
    check("先写一份别的设置", config.save({"other": 1}), True)
    check("再存名单", E.store_policy(E.Policy(E.MODE_BAN, ("存禄",))), True)
    doc = config.load()
    check("别的设置还在", doc.get("other"), 1)
    check("名单也写进去了", (doc["enemies"]["mode"], doc["enemies"]["names"]),
          (E.MODE_BAN, ["存禄"]))
    with open(os.path.join(tmp, "config.json"), "w", encoding="utf-8") as f:
        f.write("{ 坏掉的 json")
    check("坏配置当没配置", E.load_policy().mode, E.MODE_OFF)
    check("坏配置不炸", config.load(), {})
finally:
    config.path = real_path

print(f"全部 {len(OK)} 项通过")
