# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置。

不要直接 pyinstaller 跑这个 spec，用 python tools/build.py ——
它负责输出目录、清理和结果汇报。
"""
import os

ROOT = os.path.abspath(os.path.join(SPECPATH, os.pardir))
NAME = "百趣演武自动抽牌"

a = Analysis(
    [os.path.join(ROOT, "gui.py")],
    pathex=[ROOT],
    binaries=[],
    # 模板随包走；运行时由 bqyw.paths.resource_dir() 从解包目录找
    datas=[(os.path.join(ROOT, "templates"), "templates")],
    hiddenimports=[
        "win32gui", "win32process", "win32api", "pywintypes",
        "pydirectinput", "mss",
        "bqyw.actions", "bqyw.applog", "bqyw.bot", "bqyw.deck",
        "bqyw.geometry", "bqyw.hotkey", "bqyw.paths", "bqyw.privilege",
        "bqyw.rules", "bqyw.screen", "bqyw.targets", "bqyw.vision",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["pytest", "IPython", "matplotlib", "scipy", "pandas", "PIL"],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name=NAME,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,                 # 杀软对 UPX 很敏感，不压
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,             # 图形界面，不要黑框
    # 清单里写死「必须以管理员运行」：双击就弹 UAC，不再需要右键提权。
    # 游戏是提权进程，不提权的话点击会被 UIPI 静默丢弃（连光标都移不过去）。
    uac_admin=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=os.path.join(ROOT, "assets", "icon.ico"),
    # exe 属性里显示的版本号；改版本时和 bqyw/__init__.py 一起改
    version=os.path.join(ROOT, "pack", "version.txt"),
)
