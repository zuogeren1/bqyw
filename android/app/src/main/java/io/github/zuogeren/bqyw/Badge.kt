package io.github.zuogeren.bqyw

import android.content.res.AssetManager
import kotlin.math.min

/**
 * 读右下角的「当前轮次 N/2」。只判断是不是第 1 轮 —— 第 2 轮时放弃会退出整场演算。
 *
 * 只带一个「1」的模板：读不出数字时返回 0（画面还没稳），由调用方决定等还是停。
 */
object Badge {

    private const val X0 = 2624f
    private const val X1 = 2662f
    private const val Y0 = 1015f
    private const val Y1 = 1075f

    private var template: Array<BooleanArray>? = null

    /** 上一次读数的调试信息，排查用。 */
    @Volatile
    var lastInfo: String = ""

    fun load(assets: AssetManager) {
        if (template != null) return
        template = Glyph.loadTemplate(assets, "templates/round-1.png")
    }

    /**
     * 读取窗口，实际像素：x0,y0,x1,y1。
     *
     * 轮次条贴右边画，所以是**右锚**：边距一大它就整体左移，x 各减 [Geom.marginPx]，
     * 纵向一点不动（实测 dy = 0.00）。不补偿时右边距下这块窗口里根本没字，
     * 读数会从 1 变成 0（实测，见 .notes/margin-android.md §6.2）。
     */
    fun window(w: Int, h: Int): IntArray {
        val fit = Fit(w, h)
        val m = Geom.marginPx
        return intArrayOf(
            fit.px(X0 - m),
            fit.py(Y0),
            min(w, fit.px(X1 - m)),
            min(h, fit.py(Y1)),
        )
    }

    /** 1 = 第 1 轮；2 = 不是第 1 轮；0 = 读不出来（画面还没稳）。 */
    fun read(bmp: android.graphics.Bitmap): Int {
        val tpl = template ?: return 1
        val s = Fit(bmp.width, bmp.height).s
        val win = window(bmp.width, bmp.height)
        val x0 = win[0]
        val y0 = win[1]
        val x1 = win[2]
        val y1 = win[3]
        if (x1 - x0 < 6 || y1 - y0 < 6) {
            lastInfo = "窗口太小 " + x0 + "," + y0 + "-" + x1 + "," + y1
            return 0
        }
        val cw = x1 - x0
        val ch = y1 - y0
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, x0, y0, cw, ch)
        val mask = Glyph.mask(px, cw, 0, 0, cw, ch, true, 190)
        val box = Glyph.biggest(
            mask, cw, ch,
            (28 * s).toInt().coerceAtLeast(8),
            (150 * s * s).toInt().coerceAtLeast(30),
        )
        if (box == null) {
            lastInfo = "窗口 " + x0 + "," + y0 + " " + cw + "x" + ch + " 里没找到字"
            return 0
        }
        val bw = box[2] - box[0] + 1
        val bh = box[3] - box[1] + 1
        if (bw * 10 >= cw * 9 && bh * 10 >= ch * 9) {
            // 整块都是白的：过场动画或弹窗盖住了，不是数字
            lastInfo = "整块发白"
            return 0
        }
        val score = Glyph.iou(Glyph.normalize(mask, cw, box), tpl)
        lastInfo = "iou=" + String.format("%.2f", score) +
            " bbox=" + (box[0] + x0) + "," + (box[1] + y0) + " " +
            (box[2] - box[0] + 1) + "x" + (box[3] - box[1] + 1)
        return if (score >= 0.5f) 1 else 2
    }
}
