package io.github.zuogeren.bqyw

import android.content.res.AssetManager
import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 卡面点数识别：只看 5 个卡槽左上角那个大数字，花色先不管。
 *
 * 几何来自 2800×1260（20:9）的实机截图，按画面宽度等比缩放，
 * 所以半分辨率抓帧（1400×630）也能直接用。
 */
object CardReader {

    private const val BASE_W = 2800f
    private const val CX0 = 392f
    private const val STEP = 362.75f
    private const val Y0 = 300f
    private const val Y1 = 360f
    private const val HALF = 35f

    const val INK = 90

    private var templates: Array<Array<BooleanArray>>? = null

    fun load(assets: AssetManager) {
        if (templates != null) return
        templates = Array(5) { i -> Glyph.loadTemplate(assets, "templates/digit-" + (i + 1) + ".png") }
    }

    /** 返回 5 个卡槽的点数；0 表示这张牌没翻开（或认不出来）。
     *  只拷 5 个卡头窗口，不整帧复制。 */
    fun readPoints(bmp: Bitmap): IntArray {
        val tpl = templates ?: return IntArray(5)
        val w = bmp.width
        val h = bmp.height
        val s = w / BASE_W
        val out = IntArray(5)
        for (i in 0 until 5) {
            val cx = (CX0 + STEP * i) * s
            val x0 = max(0, (cx - HALF * s).toInt())
            val x1 = min(w, (cx + HALF * s).toInt())
            val y0 = max(0, (Y0 * s).toInt())
            val y1 = min(h, (Y1 * s).toInt())
            if (x1 - x0 < 8 || y1 - y0 < 8) continue
            val cw = x1 - x0
            val ch = y1 - y0
            val px = IntArray(cw * ch)
            bmp.getPixels(px, 0, cw, x0, y0, cw, ch)
            val mask = Glyph.mask(px, cw, 0, 0, cw, ch, false, INK)
            val box = Glyph.biggest(
                mask, cw, ch,
                (40 * s).toInt().coerceAtLeast(6),
                (400 * s * s).toInt().coerceAtLeast(30),
            ) ?: continue
            val norm = Glyph.normalize(mask, cw, box)
            var best = 0
            var bestScore = 0f
            for (d in 0 until 5) {
                val score = Glyph.iou(norm, tpl[d])
                if (score > bestScore) {
                    bestScore = score
                    best = d + 1
                }
            }
            if (bestScore >= 0.45f) out[i] = best
        }
        return out
    }
}
