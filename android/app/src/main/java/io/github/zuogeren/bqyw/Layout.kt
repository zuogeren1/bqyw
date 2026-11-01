package io.github.zuogeren.bqyw

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 开局布局自检：**只用画面像素**反推 (s, ox, 边距 m)，和公式算出来的对一遍。
 *
 * 为什么要做：`Fit` 现在只有"按高度铺满"这一条规则，是拿两台机器（2800×1260 与
 * 2376×1080）反推出来的；再遇到别的比例/别的边距值，公式未必对。与其盲点，
 * 不如开局从画面里直接量一遍 —— 量出来的东西和公式对不上就停手，别硬点。
 *
 * 怎么量（不需要任何预设比例）：
 *
 *  1. **卡牌行**：竖向扫一遍，只认"一行里能数出 ≥3 条等宽亮条"的行（就是卡牌下缘
 *     那条白色亮带）。同一序号亮条的左沿取中位数，再对序号做最小二乘 → 步进 pitch。
 *     `s = pitch / 363`，`ox = 卡1左沿 − s·356`（356/363 是 2800×1260 实拍图标定值）。
 *  2. **抽牌按钮**：沿按钮中线扫一行，找最左边那段够长的青色（边距 0 时左沿固定在
 *     基准 2405）→ `m = 2405 − (左沿 − ox) / s`。
 *
 * 实测（.notes/margin-android.md §11.5）：
 *
 * | 帧 | s | ox | m | 反推边距 |
 * |---|---|---|---|---|
 * | 2800×1260 边距 0   | 1.00000 | +1.0  | 1.0   | 0.9 → 0 ✓ |
 * | 2800×1260 边距 130 | 1.00000 | +1.0  | 152.0 | 130.0 ✓ |
 * | 2376×1080 未知     | 0.85675 | −11.0 | 153.5 | 131.3 |
 *
 * 这个文件**不碰任何 Android API**（只吃每行的 IntArray），所以能在 JVM 单测里跑。
 */
object Layout {

    /** 基准坐标里的死数：卡 1 左沿、卡牌步进（2800×1260 实拍图标定）。 */
    const val CARD1_X = 356f
    const val CARD_PITCH = 363f

    /** 边距 0 时抽牌按钮的青色左沿 / 竖直中线（基准坐标）。 */
    const val DRAW_EDGE = 2405f
    const val DRAW_Y = 885f

    /* ---- 和公式对账的容差 ------------------------------------------------
     *
     * 缩放：1.5%（2376 宽上是 0.013 绝对量，比实测噪声 0.0004 大一个量级，
     *       又小于"老 Fit 用错公式"造成的 1% 偏差之外的可疑区间）；
     * ox：8 px（实测 −11.0 vs 理论 −12.0，差在 1 px 量级）；
     * 边距：10 px（青色边缘的色值判据有 1~3 px 系统误差，实测值比公式小 1.5 px）。
     */
    const val MAX_S_REL = 0.015f
    const val MAX_OX_DIFF = 8f
    const val MAX_M_DIFF = 10f

    /** 一行像素：`row(y)` 返回第 y 行的 ARGB，长度必须 = 宽度。 */
    fun interface Rows {
        fun row(y: Int): IntArray
    }

    /**
     * 量出来的布局参数。
     *
     * @param s  实测缩放
     * @param ox 实测水平偏移（屏幕像素）
     * @param m  实测边距（**基准坐标**像素）；抽牌按钮不是亮的青色时是 NaN
     * @param cards 这次数到了几张卡（≥3 才敢量）
     */
    data class Measured(val s: Float, val ox: Float, val m: Float, val cards: Int) {
        val hasMargin: Boolean get() = !m.isNaN()
    }

    /** 对账结果。`ok=true` 时 [detail] 是"一致"一类的短句。 */
    data class Verdict(val ok: Boolean, val detail: String)

    /** 从一帧里量布局；量不出来（牌桌空 / 按钮灰 / 画面是白的）返回 null。 */
    fun measure(w: Int, h: Int, rows: Rows): Measured? {
        if (w < 200 || h < 200) return null

        val minW = (0.09f * w).toInt()
        val maxW = (0.14f * w).toInt()
        val yFrom = (0.22f * h).toInt()
        val yTo = (0.80f * h).toInt()
        // 卡牌白带在 1260 高上大约 100 行，隔行扫足够，省一半 getPixels
        val stride = if (h > 720) 2 else 1

        val perK = HashMap<Int, ArrayList<Int>>()
        var used = 0
        var y = yFrom
        while (y < yTo) {
            val lefts = brightRuns(rows.row(y), minW, maxW)
            if (lefts.size >= 3) {
                used++
                for (k in lefts.indices) {
                    perK.getOrPut(k) { ArrayList<Int>(8) }.add(lefts[k])
                }
            }
            y += stride
        }
        if (used < 3) return null

        // 每个序号取中位数（圆角那几行会偏，中位数把它们挤掉）
        val need = maxOf(3, used / 3)
        val xs = ArrayList<Float>(8)
        var k = 0
        while (true) {
            val v = perK[k] ?: break
            if (v.size < need) break
            xs.add(median(v))
            k++
        }
        if (xs.size < 3) return null

        // 对序号做最小二乘，斜率就是步进
        val n = xs.size
        var kBar = 0f
        var xBar = 0f
        for (i in 0 until n) {
            kBar += i
            xBar += xs[i]
        }
        kBar /= n
        xBar /= n
        var num = 0f
        var den = 0f
        for (i in 0 until n) {
            val dk = i - kBar
            num += dk * (xs[i] - xBar)
            den += dk * dk
        }
        if (den < 1e-6f) return null
        val pitch = num / den
        if (pitch < 40f) return null

        val s = pitch / CARD_PITCH
        val ox = xs[0] - s * CARD1_X

        return Measured(s, ox, margin(rows, w, h, s, ox), xs.size)
    }

    /** 抽牌按钮的青色左沿 → 边距（基准坐标）。找不到就 NaN。 */
    private fun margin(rows: Rows, w: Int, h: Int, s: Float, ox: Float): Float {
        val y = (s * DRAW_Y).roundToInt().coerceIn(0, h - 1)
        val row = rows.row(y)
        // 边距 300 时左沿 = 2405 − 351 = 2054，两头都留够
        val xa = (ox + s * 1900f).roundToInt().coerceIn(0, w - 1)
        val xb = (ox + s * 2600f).roundToInt().coerceIn(xa, w - 1)
        val minRun = (10f * s).roundToInt().coerceAtLeast(4)
        var run = 0
        var i = xa
        while (i <= xb) {
            val c = row[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 按钮是亮的青蓝色（实测 RGB ≈ 101,193,216 / 118,230,254），背景是灰白
            if (b - r > 55 && g - r > 45 && b > 140) {
                run++
                if (run >= minRun) {
                    val left = (i - run + 1).toFloat()
                    return DRAW_EDGE - (left - ox) / s
                }
            } else {
                run = 0
            }
            i++
        }
        return Float.NaN
    }

    /**
     * 一行里"够宽的一段亮像素"的左沿。
     *
     * 亮 = 三通道均值 ≥ 200（卡牌下缘的白带）；宽度落在 [minW, maxW] 才算一张卡；
     * 中间允许 12 px 的暗缝（卡面图标会把亮带切开）。贴边的不要（那是背景亮带）。
     */
    private fun brightRuns(row: IntArray, minW: Int, maxW: Int): IntArray {
        val out = ArrayList<Int>(8)
        val n = row.size
        var i = 0
        while (i < n) {
            if (!bright(row[i])) {
                i++
                continue
            }
            val start = i
            var last = i
            var j = i + 1
            while (j < n) {
                if (bright(row[j])) {
                    last = j
                    j++
                } else if (j - last <= 12) {
                    j++
                } else {
                    break
                }
            }
            val len = last - start + 1
            if (len in minW..maxW && start > 2 && last < n - 3) out.add(start)
            i = last + 1
        }
        return out.toIntArray()
    }

    private fun bright(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r + g + b) / 3 >= 200
    }

    private fun median(v: ArrayList<Int>): Float {
        v.sort()
        val n = v.size
        return if (n % 2 == 1) v[n / 2].toFloat() else (v[n / 2 - 1] + v[n / 2]) / 2f
    }

    /**
     * 和公式对账。
     *
     * @param got     实测值
     * @param fitS    公式的 s（`Fit.s`）
     * @param fitOx   公式的 ox（`Fit.ox`）
     * @param formulaM 公式的边距（基准坐标，`Geom.marginPx`）
     */
    fun compare(got: Measured, fitS: Float, fitOx: Float, formulaM: Float): Verdict {
        val bad = ArrayList<String>(3)

        val ds = got.s - fitS
        if (abs(ds) > MAX_S_REL * fitS) {
            bad.add("缩放差 " + pct(ds / fitS))
        }
        val dox = got.ox - fitOx
        if (abs(dox) > MAX_OX_DIFF) {
            bad.add("水平偏移差 " + one(dox) + " px")
        }
        if (got.hasMargin) {
            val dm = got.m - formulaM
            if (abs(dm) > MAX_M_DIFF) {
                bad.add("边距差 " + one(dm) + " px（游戏里填的和这里输入的可能不一样）")
            }
        }
        return if (bad.isEmpty()) Verdict(true, "一致") else Verdict(false, bad.joinToString("，"))
    }

    private fun one(v: Float): String = String.format(java.util.Locale.US, "%+.1f", v)

    private fun pct(v: Float): String = String.format(java.util.Locale.US, "%+.1f%%", v * 100f)

    /** 日志里用的 "s=0.857 ox=-12.0 m=152.0"。ox 带符号（比公式偏左还是偏右一看就知道），m 不带。 */
    fun brief(s: Float, ox: Float, m: Float): String {
        val mb = if (m.isNaN()) "n/a" else plain(m)
        return "s=" + three(s) + " ox=" + one(ox) + " m=" + mb
    }

    private fun plain(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun three(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
