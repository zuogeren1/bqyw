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

    private const val BASE_W = 2800f
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
        val w = bmp.width
        val h = bmp.height
        val s = w / BASE_W
        val out = ArrayList<Card>(5)
        for (i in 0 until 5) {
            val cx = CX0 + STEP * i
            val point = glyph(bmp, w, h,
                cx - HALF, Y0, cx + HALF, Y1, s,
                40f * s, 400f * s * s, pts,
            )
            if (point == 0) {
                out.add(Card(0, 0))
                continue
            }
            val suit = glyph(bmp, w, h,
                cx + SUIT_X0, SUIT_Y0, cx + SUIT_X1, SUIT_Y1, s,
                24f * s, 150f * s * s, sts,
            )
            out.add(Card(point, suit))
        }
        return out
    }

    fun readPoints(bmp: Bitmap): IntArray =
        readCards(bmp).map { it.point }.toIntArray()

    /** 窗口（基准坐标）→ 二值化 → 最大连通块 → 归一化 → 和模板比，返回 1 起的序号或 0。 */
    private fun glyph(
        bmp: Bitmap, w: Int, h: Int,
        bx0: Float, by0: Float, bx1: Float, by1: Float, s: Float,
        minH: Float, minArea: Float,
        tpl: Array<Array<BooleanArray>>,
    ): Int {
        val x0 = max(0, (bx0 * s).toInt())
        val x1 = min(w, (bx1 * s).toInt())
        val y0 = max(0, (by0 * s).toInt())
        val y1 = min(h, (by1 * s).toInt())
        if (x1 - x0 < 6 || y1 - y0 < 6) return 0
        val cw = x1 - x0
        val ch = y1 - y0
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, x0, y0, cw, ch)
        val mask = Glyph.mask(px, cw, 0, 0, cw, ch, false, INK)
        val box = Glyph.biggest(
            mask, cw, ch,
            minH.toInt().coerceAtLeast(6),
            minArea.toInt().coerceAtLeast(30),
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
