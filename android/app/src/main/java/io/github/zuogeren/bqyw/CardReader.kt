package io.github.zuogeren.bqyw

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 卡面点数识别：只看 5 个卡槽左上角那个大数字，花色先不管。
 *
 * 几何来自 2800×1260 的实机截图（vivo V2453A），全部按画面宽度等比缩放，
 * 所以半分辨率抓帧（1400×630）也能直接用。
 *
 * 流程：卡槽窗口 → 深色像素 → 最大连通块 → 按高度归一化成 32×40 → 和 5 个模板比 IoU。
 */
object CardReader {

    private const val BASE_W = 2800f
    private const val CX0 = 392f
    private const val STEP = 362.75f
    private const val Y0 = 300f
    private const val Y1 = 360f
    private const val HALF = 35f

    private const val TW = 32
    private const val TH = 40

    /** 灰阶低于它算“墨迹”。卡面是白底，数字是深灰。 */
    private const val INK = 90

    private var templates: Array<Array<BooleanArray>>? = null

    fun load(assets: AssetManager) {
        if (templates != null) return
        templates = Array(5) { idx ->
            assets.open("templates/digit-${idx + 1}.png").use { ins ->
                val bmp = BitmapFactory.decodeStream(ins)
                val arr = Array(TH) { BooleanArray(TW) }
                for (y in 0 until TH) {
                    for (x in 0 until TW) {
                        arr[y][x] = (bmp.getPixel(x, y) and 0xFF) > 127
                    }
                }
                bmp.recycle()
                arr
            }
        }
    }

    fun readPoints(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return readPoints(px, w, h)
    }

    /** 返回 5 个卡槽的点数；0 表示这张牌没翻开，或者认不出来。 */
    fun readPoints(px: IntArray, w: Int, h: Int): IntArray {
        val tpl = templates ?: return IntArray(5)
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
            val mask = BooleanArray(cw * ch)
            for (y in 0 until ch) {
                val row = (y0 + y) * w + x0
                for (x in 0 until cw) {
                    val c = px[row + x]
                    val g = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                    mask[y * cw + x] = g < INK
                }
            }
            val box = biggestGlyph(mask, cw, ch, s) ?: continue
            val norm = normalize(mask, cw, box)
            var best = 0
            var bestScore = 0f
            for (d in 0 until 5) {
                val score = iou(norm, tpl[d])
                if (score > bestScore) {
                    bestScore = score
                    best = d + 1
                }
            }
            if (bestScore >= 0.45f) out[i] = best
        }
        return out
    }

    private fun maskAt(mask: BooleanArray, cw: Int, ch: Int, x: Int, y: Int): Boolean =
        x in 0 until cw && y in 0 until ch && mask[y * cw + x]

    /** 最大的“像数字”的连通块：够高、够大，返回 (l, t, r, b)。 */
    private fun biggestGlyph(mask: BooleanArray, cw: Int, ch: Int, s: Float): IntArray? {
        val minH = (40 * s).toInt().coerceAtLeast(6)
        val minArea = (400 * s * s).toInt().coerceAtLeast(30)
        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        var best: IntArray? = null
        var bestArea = 0
        for (start in 0 until cw * ch) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var area = 0
            var l = cw
            var r = 0
            var t = ch
            var b = 0
            while (top > 0) {
                val idx = stack[--top]
                val x = idx % cw
                val y = idx / cw
                area++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (maskAt(mask, cw, ch, x - 1, y) && !seen[idx - 1]) {
                    seen[idx - 1] = true
                    stack[top] = idx - 1; top++
                }
                if (maskAt(mask, cw, ch, x + 1, y) && !seen[idx + 1]) {
                    seen[idx + 1] = true
                    stack[top] = idx + 1; top++
                }
                if (maskAt(mask, cw, ch, x, y - 1) && !seen[idx - cw]) {
                    seen[idx - cw] = true
                    stack[top] = idx - cw; top++
                }
                if (maskAt(mask, cw, ch, x, y + 1) && !seen[idx + cw]) {
                    seen[idx + cw] = true
                    stack[top] = idx + cw; top++
                }
            }
            val hgt = b - t + 1
            if (hgt < minH || area < minArea) continue
            if (area > bestArea) {
                bestArea = area
                best = intArrayOf(l, t, r, b)
            }
        }
        return best
    }

    /** 把连通块按高度缩到 40、水平居中塞进 32 宽，得到可比较的小图。 */
    private fun normalize(mask: BooleanArray, cw: Int, box: IntArray): Array<BooleanArray> {
        val (l, t, r, b) = box
        val bw = r - l + 1
        val bh = b - t + 1
        val nw = (bw.toFloat() * TH / bh).roundToInt().coerceIn(1, TW)
        val out = Array(TH) { BooleanArray(TW) }
        val x0 = (TW - nw) / 2
        for (y in 0 until TH) {
            val sy = t + (y * bh / TH)
            for (x in 0 until nw) {
                val sx = l + (x * bw / nw)
                out[y][x0 + x] = mask[sy * cw + sx]
            }
        }
        return out
    }

    private fun iou(a: Array<BooleanArray>, b: Array<BooleanArray>): Float {
        var inter = 0
        var union = 0
        for (y in 0 until TH) {
            for (x in 0 until TW) {
                val p = a[y][x]
                val q = b[y][x]
                if (p && q) inter++
                if (p || q) union++
            }
        }
        return if (union == 0) 0f else inter.toFloat() / union
    }
}