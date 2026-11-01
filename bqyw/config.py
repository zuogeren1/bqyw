# -*- coding: utf-8 -*-
"""config.json：跟 exe 放在一起的配置文件。

只有一件事要注意：**打包后 app_dir() 是 exe 所在目录**（不是临时解包目录），
所以配置写在 exe 旁边，重开、升级都不会丢。源码运行时就是仓库根目录。

读写一律容错：文件坏了、目录没权限，都当成"没有配置"，绝不因此让程序起不来。
"""
from __future__ import annotations

import json
import os

from . import paths

NAME = "config.json"


def path() -> str:
    return os.path.join(str(paths.app_dir()), NAME)


def load() -> dict:
    """读整份配置；读不出来就返回空字典。"""
    p = path()
    try:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save(cfg: dict) -> bool:
    """整份写回（先写临时文件再替换，断电也不会留半截）。"""
    p = path()
    tmp = p + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=1)
        os.replace(tmp, p)
        return True
    except OSError:
        try:
            os.remove(tmp)
        except OSError:
            pass
        return False
