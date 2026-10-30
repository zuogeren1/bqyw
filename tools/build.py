# -*- coding: utf-8 -*-
"""一键打包：python tools/build.py

产物：dist/百趣演武自动抽牌.exe（单文件，双击即用，清单里要求管理员）。
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = os.path.join(ROOT, "pack", "bqyw.spec")
NAME = "百趣演武自动抽牌"

# GBK 控制台打不出 "✓" 会抛 UnicodeEncodeError：包打好了，脚本却以 1 退出，
# 让人以为打包失败。换成替换字符，别让一句提示把成败带偏
try:
    sys.stdout.reconfigure(errors="replace")
except Exception:                                   # noqa: BLE001
    pass


def main() -> int:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("没装 PyInstaller：python -m pip install pyinstaller")
        return 1

    for junk in ("build", "dist"):
        path = os.path.join(ROOT, junk)
        if os.path.isdir(path):
            shutil.rmtree(path, ignore_errors=True)

    cmd = [sys.executable, "-m", "PyInstaller", SPEC, "--noconfirm",
           "--distpath", os.path.join(ROOT, "dist"),
           "--workpath", os.path.join(ROOT, "build")]
    print(" ".join(cmd))
    rc = subprocess.call(cmd, cwd=ROOT)
    if rc != 0:
        print(f"\n打包失败（PyInstaller 返回 {rc}）")
        return rc

    exe = os.path.join(ROOT, "dist", NAME + ".exe")
    if not os.path.exists(exe):
        print(f"\n没找到产物：{exe}")
        return 1

    with open(exe, "rb") as fh:
        blob = fh.read()
    size = len(blob) / 1024 / 1024
    print(f"\n好了：{exe}")
    print(f"大小：{size:.1f} MB")
    print(f"SHA256：{hashlib.sha256(blob).hexdigest()}")

    # 清单里必须写着"需要管理员"，否则双击会以普通权限跑起来，点击全被 UIPI 丢掉
    if b"requireAdministrator" in blob:
        print("清单：requireAdministrator ✓（双击会弹 UAC）")
    else:
        print("清单：!! 没找到 requireAdministrator，检查 pack/bqyw.spec 的 uac_admin")
        return 1

    print("提示：首次双击 Windows 可能弹「已保护你的电脑」，点『更多信息』→『仍要运行』。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
