# -*- coding: utf-8 -*-
"""日志自检：python tests/test_applog.py"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bqyw import applog  # noqa: E402

OK = []


def check(name, got, want):
    if got != want:
        raise AssertionError(f"{name}: got {got!r}, want {want!r}")
    OK.append(name)


def make(folder, *names, t0=1_000_000):
    """造一批日志，mtime 依次递增，方便验证「删最旧」。"""
    for i, n in enumerate(names):
        p = folder / n
        p.write_text("x", encoding="utf-8")
        os.utime(p, (t0 + i, t0 + i))


def names(folder):
    return sorted(p.name for p in folder.iterdir())


with tempfile.TemporaryDirectory() as td:
    d = Path(td)

    # ---------------- 数量不超过 5
    make(d, *[f"run_{i}.log" for i in range(8)])
    gone = applog.prune_logs(d, applog.MAX_LOGS)
    check("只留 5 份", len(list(d.iterdir())), 5)
    check("删的是最旧的", gone, [f"run_{i}.log" for i in range(3)])
    check("留下的是最新的", names(d), [f"run_{i}.log" for i in range(3, 8)])

    # ---------------- 刚好 5 份时不动
    for p in list(d.iterdir()):
        p.unlink()
    make(d, *[f"run_{i}.log" for i in range(5)])
    check("5 份不删", applog.prune_logs(d, applog.MAX_LOGS), [])
    check("5 份还在", len(list(d.iterdir())), 5)

    # ---------------- 别的东西不碰
    for p in list(d.iterdir()):
        p.unlink()
    make(d, *[f"run_{i}.log" for i in range(7)], "notes.txt", "run_.log.bak",
         "other.log")
    applog.prune_logs(d, 2)
    check("只删自家日志", names(d), ["notes.txt", "other.log", "run_.log.bak",
                                  "run_5.log", "run_6.log"])

    # ---------------- 同名不覆盖
    p = d / "run_20260101_000000.log"
    check("首个不撞名", applog._unique(p), p)
    p.write_text("a", encoding="utf-8")
    check("撞名加后缀", applog._unique(p).name, "run_20260101_000000_2.log")

    # ---------------- 真正开日志（把目录指到临时目录，不碰真实 logs/）
    for f in list(d.iterdir()):
        f.unlink()
    real = applog.log_dir
    applog.log_dir = lambda: d
    try:
        path, fh = applog.new_log(header="# 标题\n# 第二行")
        fh.write("hello\n")
        fh.close()
        text = path.read_text(encoding="utf-8")
        check("建在配置的目录里", path.parent, d)
        check("开头有表头", text.startswith("# 标题\n# 第二行\n"), True)
        check("行缓冲已落盘", "hello" in text, True)

        # 连开 7 份，磁盘上只剩最近 5 份
        for f in list(d.iterdir()):
            f.unlink()
        for i in range(7):
            pth, f2 = applog.new_log(header=f"# n{i}")
            f2.close()
            os.utime(pth, (1_000_000 + i, 1_000_000 + i))
        left = applog.list_logs(d)
        check("连开 7 份只剩 5", len(left), 5)
        check("留下的是最后 5 份", [p.read_text(encoding="utf-8")[:4] for p in left],
              [f"# n{i}" for i in range(2, 7)])
    finally:
        applog.log_dir = real

check("源码运行时程序目录", applog.app_dir(), Path(__file__).resolve().parent.parent)
check("MAX_LOGS", applog.MAX_LOGS, 5)
check("时间戳格式", len(applog.stamp()), len("[00:00:00]"))
header = applog.env_header().splitlines()
check("表头三行", len(header), 3)
check("表头第一行是版本", header[0].startswith("# 百趣演武自动抽牌 v"), True)
check("表头第二行有运行方式", "运行方式" in header[1], True)
check("表头第三行有屏幕与窗口比例", "屏幕 " in header[2] and "比例" not in header[2]
      and "（" in header[2] and "）" in header[2], True)

print(f"全部 {len(OK)} 项通过")
