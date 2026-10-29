# -*- coding: utf-8 -*-
"""以管理员身份启动主程序（给 .bat 用的入口）。

游戏多半是提权进程，Windows 的 UIPI 会把低权限进程的点击**静默丢掉**，
所以真正跑循环时必须是管理员。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from bqyw.privilege import is_admin, relaunch_as_admin  # noqa: E402


def main():
    argv = sys.argv[1:]
    if not argv:
        argv = ["run", "--target", "四条"]
    elif argv[0] not in ("run", "inspect", "stats"):
        argv = ["run", "--target", argv[0]] + argv[1:]

    if is_admin():
        import main as app
        app.build_parser().parse_args(argv)
        return

    print("当前没有管理员权限，正在请求提权（请在弹出的 UAC 里点『是』）…")
    if relaunch_as_admin(argv):
        print("已在新窗口启动。")
    else:
        print("提权失败或被取消。")
        print("也可以自己开一个「管理员终端」，再运行：")
        print("  python main.py " + " ".join(argv))


if __name__ == "__main__":
    main()
