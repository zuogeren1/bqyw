# -*- coding: utf-8 -*-
"""百趣演武 · 自动抽牌

用法：
    python main.py stats                     # 各牌型达成概率 / 期望重开次数
    python main.py inspect <截图.png>        # 离线读一张截图，不碰游戏
    python main.py run --target 四条         # 实机跑循环
    python main.py run --target 四条 --dry   # 只读不动手（第一次务必先跑这个）
"""
from __future__ import annotations

import argparse
import re
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bqyw import __version__            # noqa: E402  （先落下线程数设置，再导入 numpy）

import cv2                              # noqa: E402
import numpy as np                      # noqa: E402

# 控制台可能是 GBK，别让个别字符把整个程序打断
try:
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:
    pass

from bqyw.deck import completion_probability, expected_attempts, full_deck  # noqa: E402
from bqyw.rules import (HAND_NAME_CN, Hand, all_hands, battle_total,  # noqa: E402
                        best_hand, score)
from bqyw.targets import MultiTarget, make_target  # noqa: E402
from bqyw import geometry  # noqa: E402
from bqyw.vision import TemplateSet, expected_highlight, find_highlight  # noqa: E402

NAME_TO_HAND = {v: k for k, v in HAND_NAME_CN.items()}
NAME_TO_HAND.update({"散牌": Hand.HIGH})


def read_image(path: str) -> np.ndarray:
    if not os.path.exists(path):
        raise SystemExit(f"找不到文件：{path}")
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit(f"读不出来：{path}")
    return img


def cmd_stats(_args):
    print(f"百趣演武自动抽牌 v{__version__}")
    ts = TemplateSet()
    print(f"模板目录：{ts.dir}")
    print(f"  数字模板 {sorted(ts.digits)}   花色模板 {list(ts.suits)}")
    miss = ts.missing
    if miss["digit"] or miss["suit"]:
        print(f"  [!] 模板缺口：数字 {miss['digit']}，花色 {miss['suit']}")
    print()
    print(f"{'牌型':<6}{'达成概率':>10}{'期望重开':>12}")
    for h in sorted(Hand, key=lambda x: x.value):
        p = completion_probability((), h, 5, full_deck())
        print(f"{HAND_NAME_CN[h]:<6}{p*100:>9.3f}%{expected_attempts(h):>11.1f} 次")


def cmd_inspect(args):
    img = read_image(args.image)
    # 坐标由图片本身的分辨率决定，所以任意分辨率的截图都能离线看
    try:
        s = geometry.use_resolution(img.shape[1], img.shape[0])
    except geometry.UnsupportedResolution as exc:
        raise SystemExit(str(exc))
    ts = TemplateSet()
    cards = ts.read_hand(img)
    print(f"图片 {img.shape[1]}×{img.shape[0]}（坐标缩放 {s:.3f}）")
    print(f"当前轮次：{ts.read_round(img)}")
    print(f"识别到 {len(cards)} 张：{[ (c.point, c.suit) for c in cards ]}")
    if cards:
        total = sum(c.point for c in cards)
        print(f"  战力点合计 {total}（表格高亮列 {find_highlight(img)}，"
              f"按合计应为 {expected_highlight(total)}）")
        print(f"  达成牌型：{[HAND_NAME_CN[h] for h in sorted(all_hands(cards), key=lambda x: x.value)]}")
        best = best_hand(cards)
        lucky = NAME_TO_HAND.get(args.lucky) if args.lucky else None
        print(f"  生效牌型：{HAND_NAME_CN[best]}  预估奖励点 {score(cards, lucky, args.s1)}")
        if args.s1:
            print(f"  两轮合计：{battle_total(args.s1, cards, lucky)}（S1 = {args.s1}）")
        left = 5 - len(cards)
        print(f"  还能抽 {left} 张；已不可能达成的牌型：")
        dead = [HAND_NAME_CN[h] for h in Hand if completion_probability(cards, h, left) == 0]
        print(f"    {dead if dead else '无'}")


def cmd_run(args):
    from bqyw.bot import Abort, Bot
    from bqyw.privilege import is_admin, relaunch_as_admin
    from bqyw.screen import Capture

    try:
        target = make_target(args.target, args.custom, args.ordered)
        extra = getattr(args, "fallback", None)
        if extra:
            more = [make_target(x.strip()) for x in re.split(r"[,，/、\s]+", extra) if x.strip()]
            pool = [target] + more
            target = MultiTarget(pool) if len(pool) > 1 else target
    except ValueError as exc:
        raise SystemExit(f"目标无效：{exc}")
    lucky = NAME_TO_HAND.get(args.lucky) if args.lucky else None

    if args.auto_elevate and not args.dry and not is_admin():
        print("当前不是管理员，正在请求提权（会弹 UAC，请点『是』）…")
        if relaunch_as_admin(sys.argv[1:]):
            print("已在新窗口以管理员身份启动。")
        else:
            print("提权被取消或失败。")
        return

    from bqyw import applog

    log_path, log_file = applog.new_log(
        header=applog.env_header() + f"\n# 命令 {' '.join(sys.argv)}")
    sys.stdout = applog.Tee(sys.__stdout__, log_file)
    print(f"日志：{log_path}（保留最近 {applog.MAX_LOGS} 份）")

    cap = Capture(prefer_window=not args.fullscreen)

    if args.once:
        from bqyw.bot import dry_analyse
        try:
            s = cap.layout()
        except geometry.UnsupportedResolution as exc:
            raise SystemExit(str(exc))
        frame = cap.grab()
        r = dry_analyse(frame, target, lucky)
        from bqyw.vision import looks_like_draw_screen
        w, h = cap.frame_size()
        print(f"抓取源：{cap.title}  状态：{cap.status()}  画面 {w}×{h}（缩放 {s:.3f}）")
        print(f"像抽牌界面：{looks_like_draw_screen(frame)}")
        print(f"轮次 {r['round']}，{r['count']} 张 {[(c.point, c.suit) for c in r['cards']]}")
        print(f"战力点 {r['sum']}（表格高亮列 {r['highlight']}，应为 {r['table_bp']}）"
              f"  生效牌型 {HAND_NAME_CN[r['best']]}  预估奖励点 {r['score']}")
        left = 5 - r["count"]
        print(f"还能抽 {left} 张；目标 {target} 还可达成：{bool(r['reach_p'])}")
        if r["count"] == 0:
            print("建议：抽牌")
        elif target in r["hands"]:
            print("建议：停手，已命中")
        elif r["reach_p"] == 0:
            print("建议：放弃重开")
        else:
            print("建议：继续抽")
        sys.stdout = sys.__stdout__
        log_file.close()
        return

    bot = Bot(cap, target, lucky=lucky, auto_start=args.start,
              dry_run=args.dry, max_attempts=args.max_attempts,
              max_bp=args.max_bp, fast_mode=args.fast)
    bot.force = args.force
    bot.keep_console = args.keep_console
    bot.hotkey = args.hotkey or ""
    try:
        bot.run()
    except Abort as exc:
        print(f"\n[!] {exc}")
    except KeyboardInterrupt:
        print("\n已中断")
    finally:
        print(f"\n抽牌 {bot.stats.draws} 次，最好牌型 {HAND_NAME_CN[bot.stats.best_seen]}，"
              f"最高奖励点 {bot.stats.best_score}")
        print(bot.pace())
        sys.stdout = sys.__stdout__
        log_file.close()


def build_parser():
    p = argparse.ArgumentParser(description="百趣演武自动抽牌")
    p.add_argument("--version", action="version",
                   version=f"百趣演武自动抽牌 {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("stats", help="打印牌型概率").set_defaults(func=cmd_stats)

    pi = sub.add_parser("inspect", help="离线读一张截图")
    pi.add_argument("image")
    pi.add_argument("--s1", type=int, default=0, help="第一轮已获得的奖励点（读第二轮截图时填）")
    pi.add_argument("--lucky", default=None, help="今日好运组合")
    pi.set_defaults(func=cmd_inspect)

    pr = sub.add_parser("run", help="实机循环")
    pr.add_argument("--target", default="四条",
                    help=f"目标牌型 {list(NAME_TO_HAND)}，也可以是「自定义」")
    pr.add_argument("--custom", default=None,
                    help='自定义 1~5 张，如 "3筒 3筒 3索 5萬 5"；'
                         '没写花色=任意，点数写「任意」=不挑点数（可带花色，如 任意筒）')
    pr.add_argument("--ordered", action="store_true", help="自定义目标按抽取顺序严格匹配")
    pr.add_argument("--fallback", default=None,
                    help="再加几个能接受的目标，逗号分隔（如 --target 同花顺 --fallback 顺子,同花）")
    pr.add_argument("--lucky", default=None, help="今日好运组合（同花顺/四条/同花/葫芦/顺子/三条/两对/对子）")
    pr.add_argument("--dry", action="store_true", help="只读不动手")
    pr.add_argument("--start", action="store_true", help="命中后自动点开始挑战")
    pr.add_argument("--fullscreen", action="store_true", help="抓整个主屏而不是游戏窗口")
    pr.add_argument("--max-attempts", type=int, default=0, help="重开上限，0 为不限")
    pr.add_argument("--auto-elevate", action="store_true", help="非管理员时自动请求提权重开")
    pr.add_argument("--force", action="store_true", help="跳过提权检查，硬着头皮点")
    pr.add_argument("--once", action="store_true", help="只读分析当前一帧就退出（不改状态、不点击）")
    pr.add_argument("--keep-console", action="store_true", help="不最小化控制台（默认会最小化，免得挡住游戏）")
    pr.add_argument("--max-bp", type=int, default=0, dest="max_bp",
                    help="战力点上限，超过就放弃重开；0=不限")
    pr.add_argument("--fast", action="store_true",
                    help="快速模式：连抽 5 张再判定（省扫描，但不做提前剪枝、要打 5 个怪）")
    pr.add_argument("--hotkey", default="f11",
                    help="全局急停热键，默认 f11；空字符串关闭")
    pr.set_defaults(func=cmd_run)
    return p


if __name__ == "__main__":
    args = build_parser().parse_args()
    args.func(args)