"""百趣演武 · 自动抽牌（Arena of Games auto-draw bot）"""
import os as _os

# 必须在 import numpy 之前设：numpy 一导入，OpenBLAS 就按 CPU 核数开线程池，
# 每个线程一块 32MB 的计算缓冲。16 核 = 512MB 提交内存，而本项目根本不做矩阵
# 运算，这些内存全是白占的（实测提交 530MB → 48MB）。
for _name in ("OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "OMP_NUM_THREADS",
              "NUMEXPR_NUM_THREADS", "VECLIB_MAXIMUM_THREADS"):
    _os.environ.setdefault(_name, "1")
del _name

__version__ = "1.1.3"
