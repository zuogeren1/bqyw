package io.github.zuogeren.bqyw

import android.content.res.AssetManager
import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 卡面识别：5 个卡槽左上角的「点数」+ 右边的「花色」。
 *
 * 几何来自 2800×1260（20:9）的实机截图，按画面宽度等比缩放。
 * 只从 Bitmap 里抠这两块窗口，不整帧复制。
 */
object CardReader {

    private const val CX0 = 392f
    private const val STEP = 362.75f
    private const val HALF = 35f
    private const val Y0 = 300f
    private const val Y1 = 360f

    private const val SUIT_X0 = 38f
    private const val SUIT_X1 = 100f
    private const val SUIT_Y0 = 305f
    private const val SUIT_Y1 = 370f

    private const val INK = 90
    private val SUIT_NAMES = arrayOf("wan", "tong", "suo")

    private var pointTpl: Array<Array<BooleanArray>>? = null
    private var suitTpl: Array<Array<BooleanArray>>? = null

    fun load(assets: AssetManager) {
        if (pointTpl == null) {
            pointTpl = Array(5) { Glyph.loadTemplate(assets, "templates/digit-" + (it + 1) + ".png") }
        }
        if (suitTpl == null) {
            suitTpl = Array(3) { Glyph.loadTemplate(assets, "templates/suit-" + SUIT_NAMES[it] + ".png") }
        }
    }

    /** 读 5 张牌：点数 1..5、花色 1=萬 2=筒 3=索；0 表示没翻开/认不出。 */
    fun readCards(bmp: Bitmap): List<Card> {
        val pts = pointTpl ?: return emptyList()
        val sts = suitTpl ?: return emptyList()
        val fit = Fit(bmp.width, bmp.height)
        val out = ArrayList<Card>(5)
        for (i in 0 until 5) {
            val cx = CX0 + STEP * i
            val point = glyph(bmp, fit,
                cx - HALF, Y0, cx + HALF, Y1,
                40f, 400f, pts,
            )
            if (point == 0) {
                out.add(Card(0, 0))
                continue
            }
            val suit = glyph(bmp, fit,
                cx + SUIT_X0, SUIT_Y0, cx + SUIT_X1, SUIT_Y1,
                24f, 150f, sts,
            )
            out.add(Card(point, suit))
        }
        return out
    }

    fun readPoints(bmp: Bitmap): IntArray =
        readCards(bmp).map { it.point }.toIntArray()

    /**
     * 窗口（基准坐标）→ 二值化 → 最大连通块 → 归一化 → 和模板比，返回 1 起的序号或 0。
     *
     * minH / minArea 也是基准像素下的阈值，按实际缩放换算。
     */
    private fun glyph(
        bmp: Bitmap, fit: Fit,
        bx0: Float, by0: Float, bx1: Float, by1: Float,
        minH: Float, minArea: Float,
        tpl: Array<Array<BooleanArray>>,
    ): Int {
        val s = fit.s
        val w = bmp.width
        val h = bmp.height
        val x0 = max(0, fit.px(bx0))
        val x1 = min(w, fit.px(bx1))
        val y0 = max(0, fit.py(by0))
        val y1 = min(h, fit.py(by1))
        if (x1 - x0 < 6 || y1 - y0 < 6) return 0
        val cw = x1 - x0
        val ch = y1 - y0
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, x0, y0, cw, ch)
        val mask = Glyph.mask(px, cw, 0, 0, cw, ch, false, INK)
        val box = Glyph.biggest(
            mask, cw, ch,
            (minH * s).toInt().coerceAtLeast(6),
            (minArea * s * s).toInt().coerceAtLeast(30),
        ) ?: return 0
        val norm = Glyph.normalize(mask, cw, box)
        var best = 0
        var score = 0f
        for (i in tpl.indices) {
            val v = Glyph.iou(norm, tpl[i])
            if (v > score) {
                score = v
                best = i + 1
            }
        }
        return if (score >= 0.45f) best else 0
    }
}
