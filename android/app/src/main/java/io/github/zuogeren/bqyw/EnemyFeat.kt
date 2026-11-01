package io.github.zuogeren.bqyw

import kotlin.math.sqrt

/**
 * 立绘特征：灰度 → 缩到 128×128 → 减均值 → L2 归一化。
 *
 * 两个特征的点积就是 NCC（归一化互相关），和 PC 版 enemies.feat 一个口径。
 *
 * 刻意**不碰任何 Android API**（Bitmap / AssetManager），这样 JVM 单测能直接拿
 * javax.imageio 读模板来验算 —— 见 app/src/test/.../EnemiesTest.kt。
 * 真机上的裁剪、解码都在 [Enemies.Book] 里。
 */
object EnemyFeat {

    /** 归一化边长，和 PC 版 enemies.CANON 一致（模板库里的 PNG 就是这个尺寸）。 */
    const val CANON = 128

    /** 三个通道（0..255）→ 灰度。系数照抄 PC 的 cv2.cvtColor(BGR2GRAY)，两边分数才对得上。 */
    fun luma(r: Int, g: Int, b: Int): Float = 0.299f * r + 0.587f * g + 0.114f * b

    /** 一个 ARGB 像素 → 灰度。 */
    fun luma(argb: Int): Float =
        luma((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /**
     * 任意尺寸的灰度图 → [CANON]×[CANON]，按面积平均（等价 cv2.INTER_AREA）。
     *
     * 用面积平均而不是最近邻：立绘框 294×215 缩到 128×128 是**缩小**，
     * 抽样会随机丢细节，同一敌人在两帧里丢的还不是同一批像素，分数就会跳。
     */
    fun resample(gray: FloatArray, w: Int, h: Int): FloatArray {
        if (w == CANON && h == CANON) return gray
        val out = FloatArray(CANON * CANON)
        for (y in 0 until CANON) {
            val sy0 = y * h / CANON
            val sy1 = maxOf(sy0 + 1, (y + 1) * h / CANON)
            for (x in 0 until CANON) {
                val sx0 = x * w / CANON
                val sx1 = maxOf(sx0 + 1, (x + 1) * w / CANON)
                var sum = 0f
                for (sy in sy0 until sy1) {
                    val row = sy * w
                    for (sx in sx0 until sx1) sum += gray[row + sx]
                }
                out[y * CANON + x] = sum / ((sy1 - sy0) * (sx1 - sx0))
            }
        }
        return out
    }

    /**
     * 减均值 + L2 归一化。
     *
     * 整块都一样平（纯色、黑屏）时没有纹理，返 **null**：这种帧什么都认不出，
     * 硬算会得到一个和谁都像的假分数。
     */
    fun normalize(v: FloatArray): FloatArray? {
        if (v.isEmpty()) return null
        var sum = 0.0
        for (x in v) sum += x
        val mean = (sum / v.size).toFloat()
        var sq = 0.0
        for (x in v) {
            val d = (x - mean).toDouble()
            sq += d * d
        }
        val n = sqrt(sq).toFloat()
        if (n < 1e-6f) return null
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = (v[i] - mean) / n
        return out
    }

    /** 一步到位：任意尺寸灰度图 → 特征。 */
    fun of(gray: FloatArray, w: Int, h: Int): FloatArray? =
        normalize(resample(gray, w, h))

    /** 点积 = NCC。两边都归一化过，所以直接相乘求和就是相关系数。 */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
