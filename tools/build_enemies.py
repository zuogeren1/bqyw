# -*- coding: utf-8 -*-
"""把侦察数据整理成运行时模板。

输入是 100 轮扫描的产物（`.notes/p2`，不进仓库）：

    final.json            25 个敌人的名字 / 战力点 / 抗性 / 出现次数
    <eid>/art.png         128×128 立绘（由抽牌页卡面裁出来再缩到 128）

输出（进仓库、随 exe 走）：

    templates/enemies.json       名单 + 每个敌人的点数、抗性、出现次数
    templates/enemies/<eid>.png  128×128 立绘模板

用法：
    python tools/build_enemies.py                 # 用默认路径
    python tools/build_enemies.py --src .notes/p2 --dst templates
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.path.join(ROOT, ".notes", "p2"))
    ap.add_argument("--dst", default=os.path.join(ROOT, "templates"))
    args = ap.parse_args()

    src_json = os.path.join(args.src, "final.json")
    if not os.path.exists(src_json):
        print(f"找不到 {src_json}（先跑侦察扫描）")
        return 2
    with open(src_json, encoding="utf-8") as f:
        raw = json.load(f)

    out_dir = os.path.join(args.dst, "enemies")
    os.makedirs(out_dir, exist_ok=True)

    enemies = []
    for e in raw["enemies"]:
        eid = e["rep"]
        art = os.path.join(args.src, e["art"])
        if not os.path.exists(art):
            print(f"[!] {eid} 缺立绘：{art}")
            return 3
        shutil.copyfile(art, os.path.join(out_dir, eid + ".png"))
        enemies.append({
            "slug": eid,
            "name": e["name"],
            "point": int(e["point"]),
            "art": eid + ".png",
            "count": int(e["count"]),
            "res": e.get("res") or {},
        })

    # 点数、名字排一下，界面上按点数分组浏览
    enemies.sort(key=lambda x: (x["point"], x["name"]))

    doc = {
        "version": 1,
        "canon": 128,
        "threshold": 0.78,
        "source": "100 轮扫描（.notes/p2），25 个敌人，2026-10-31",
        "enemies": enemies,
    }
    path = os.path.join(args.dst, "enemies.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)

    total = sum(e["count"] for e in enemies)
    print(f"写出 {len(enemies)} 个敌人 → {out_dir}")
    print(f"名单 → {path}")
    for p in range(1, 6):
        names = [e["name"] for e in enemies if e["point"] == p]
        print(f"  {p} 点 {len(names)} 种：{'、'.join(names)}")
    print(f"累计出现 {total} 张（扫描 495 张）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
