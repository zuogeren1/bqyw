package io.github.zuogeren.bqyw

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import kotlin.math.roundToInt

/** 归一化小图匹配的公共零件：二值化窗口 → 最大连通块 → 32×40 → IoU。 */
object Glyph {

    const val W = 32
    const val H = 40

    /** 取一块窗口二值化成 mask。brighter=true 取亮像素，否则取暗像素。 */
    fun mask(
        px: IntArray, stride: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        brighter: Boolean, edge: Int,
    ): BooleanArray {
        val cw = x1 - x0
        val ch = y1 - y0
        val out = BooleanArray(cw * ch)
        for (y in 0 until ch) {
            val row = (y0 + y) * stride + x0
            for (x in 0 until cw) {
                val c = px[row + x]
                val g = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                out[y * cw + x] = if (brighter) g > edge else g < edge
            }
        }
        return out
    }

    /** 最大连通块的 bbox (l, t, r, b)；size 不够就返回 null。 */
    fun biggest(mask: BooleanArray, cw: Int, ch: Int, minH: Int, minArea: Int): IntArray? {
        val seen = BooleanArray(cw * ch)
        val stack = IntArray(cw * ch)
        var best: IntArray? = null
        var bestArea = 0
        for (start in 0 until cw * ch) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            seen[start] = true
            stack[0] = start
            top = 1
            var area = 0
            var l = cw
            var r = 0
            var t = ch
            var b = 0
            while (top > 0) {
                top--
                val idx = stack[top]
                val x = idx % cw
                val y = idx / cw
                area++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (x > 0 && mask[idx - 1] && !seen[idx - 1]) {
                    seen[idx - 1] = true; stack[top] = idx - 1; top++
                }
                if (x < cw - 1 && mask[idx + 1] && !seen[idx + 1]) {
                    seen[idx + 1] = true; stack[top] = idx + 1; top++
                }
                if (y > 0 && mask[idx - cw] && !seen[idx - cw]) {
                    seen[idx - cw] = true; stack[top] = idx - cw; top++
                }
                if (y < ch - 1 && mask[idx + cw] && !seen[idx + cw]) {
                    seen[idx + cw] = true; stack[top] = idx + cw; top++
                }
            }
            if (b - t + 1 < minH || area < minArea) continue
            if (area > bestArea) {
                bestArea = area
                best = intArrayOf(l, t, r, b)
            }
        }
        return best
    }

    /** 按高度缩到 40、水平居中塞进 32 宽。 */
    fun normalize(mask: BooleanArray, cw: Int, box: IntArray): Array<BooleanArray> {
        val l = box[0]
        val t = box[1]
        val r = box[2]
        val b = box[3]
        val bw = r - l + 1
        val bh = b - t + 1
        val nw = (bw.toFloat() * H / bh).roundToInt().coerceIn(1, W)
        val out = Array(H) { BooleanArray(W) }
        val ox = (W - nw) / 2
        for (y in 0 until H) {
            val sy = t + (y * bh / H)
            for (x in 0 until nw) {
                val sx = l + (x * bw / nw)
                out[y][ox + x] = mask[sy * cw + sx]
            }
        }
        return out
    }

    fun iou(a: Array<BooleanArray>, b: Array<BooleanArray>): Float {
        var inter = 0
        var union = 0
        for (y in 0 until H) {
            for (x in 0 until W) {
                val p = a[y][x]
                val q = b[y][x]
                if (p && q) inter++
                if (p || q) union++
            }
        }
        return if (union == 0) 0f else inter.toFloat() / union
    }

    fun loadTemplate(assets: AssetManager, path: String): Array<BooleanArray> {
        assets.open(path).use { ins ->
            val bmp = BitmapFactory.decodeStream(ins)
            val arr = Array(H) { BooleanArray(W) }
            for (y in 0 until H) {
                for (x in 0 until W) {
                    arr[y][x] = (bmp.getPixel(x, y) and 0xFF) > 127
                }
            }
            bmp.recycle()
            return arr
        }
    }
}
