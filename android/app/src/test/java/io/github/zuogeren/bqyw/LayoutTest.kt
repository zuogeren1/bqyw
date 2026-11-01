package io.github.zuogeren.bqyw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * [Layout] 开局自检的 JVM 单测。
 *
 * 两条路：
 *  - **合成图**（必跑）：按实拍图量到的坐标画一张 2376×1080 的图（卡牌白带 + 青色抽牌按钮），
 *    要求反推出来的 (s, ox, m) 和实拍图一致。这钉的是"算法"，不依赖任何图片文件。
 *  - **真图**（本地有 refs/ 才跑，没有就 skip）：直接读 11:5 那张 jpg 走一遍。
 *    refs/ 不进仓库，所以用 [Assume] 跳过，别让 CI 挂。
 */
class LayoutTest {

    /** 2376×1080 实拍图上量到的坐标（.notes/margin-android.md §11.1 / §11.5）。 */
    private class Fake(
        val w: Int = 2376,
        val h: Int = 1080,
        val cardLefts: IntArray = intArrayOf(294, 605, 916, 1227, 1538),
        val cardTop: Int = 520,
        val cardBottom: Int = 640,
        val button: Boolean = true,
    ) : Layout.Rows {
        override fun row(y: Int): IntArray {
            val out = IntArray(w) { BG }
            if (y in cardTop until cardBottom) {
                for (l in cardLefts) {
                    for (x in l until minOf(w, l + 289)) out[x] = CARD
                }
            }
            if (button && y in 695 until 816) {
                for (x in 1918 until 2286) out[x] = CYAN
            }
            return out
        }
    }

    /** 实拍图那台的三个数。 */
    @Test
    fun measuresElevenByFiveFrame() {
        val got = Layout.measure(2376, 1080, Fake())
        assertNotNull("合成图应该能量出来", got)
        assertEquals("数到 5 张卡", 5, got!!.cards)
        assertEquals("s = 步进 311 / 363", 311f / 363f, got.s, 0.002f)
        assertEquals("ox = 294 − s·356", -11f, got.ox, 1.5f)
        assertTrue("量到了边距", got.hasMargin)
        assertEquals("m = 2405 − (1918 − ox)/s", 153.5f, got.m, 2f)
    }

    /** 牌桌空（没有卡牌白带）→ 量不出来，调用方按公式继续。 */
    @Test
    fun emptyTableMeasuresNothing() {
        val fake = object : Layout.Rows {
            override fun row(y: Int) = IntArray(2376) { BG }
        }
        assertNull(Layout.measure(2376, 1080, fake))
    }

    /** 抽牌按钮是灰的（手里 5 张牌）→ 能定 s/ox，但 m 量不到（NaN），不许当成 0。 */
    @Test
    fun greyButtonLeavesMarginUnknown() {
        val got = Layout.measure(2376, 1080, Fake(button = false))
        assertNotNull(got)
        assertTrue("m 应该是 NaN", got!!.m.isNaN())
        assertTrue("s 仍然量得到", Math.abs(got.s - 311f / 363f) < 0.002f)
    }

    /** 日志行的写法（回报里贴的就是这一行，钉住格式，别哪天悄悄改了）。 */
    @Test
    fun logLineFormat() {
        assertEquals(
            "s=0.857 ox=-12.0 m=153.1",
            Layout.brief(0.85714f, -12f, 153.1f),
        )
        assertEquals("m=量不到时写 n/a", "s=1.000 ox=+1.0 m=n/a", Layout.brief(1f, 1f, Float.NaN))
    }

    /** 和公式对账：一致 / 各自超差的三种情况。 */
    @Test
    fun compareReportsMismatch() {
        val fit = Fit(2376, 1080)
        val ok = Layout.Measured(fit.s, fit.ox - 1f, 153.5f, 5)
        val v = Layout.compare(ok, fit.s, fit.ox, 151.7f)
        assertTrue("实测和公式只差 1 px，应该判一致：" + v.detail, v.ok)

        val badScale = Layout.Measured(fit.s * 1.02f, fit.ox, 151.7f, 5)
        assertTrue("缩放差 2% 要报出来", !Layout.compare(badScale, fit.s, fit.ox, 151.7f).ok)

        val badOx = Layout.Measured(fit.s, fit.ox + 12f, 151.7f, 5)
        assertTrue("ox 差 12 px 要报出来", !Layout.compare(badOx, fit.s, fit.ox, 151.7f).ok)

        val badM = Layout.Measured(fit.s, fit.ox, 200f, 5)
        assertTrue("边距差 48 px 要报出来", !Layout.compare(badM, fit.s, fit.ox, 151.7f).ok)

        // m 没量到（NaN）时只看 s / ox，不许因为 NaN 误报
        val noM = Layout.Measured(fit.s, fit.ox, Float.NaN, 5)
        assertTrue("m 是 NaN 时不该误报", Layout.compare(noM, fit.s, fit.ox, 151.7f).ok)
    }

    /**
     * 真图回归：refs/ 里有 11:5 那张就跑，没有就 skip（refs/ 不进仓库）。
     *
     * 这里用反射读 jpg：单测编译时用的是 android.jar，上面没有 `javax.imageio`，
     * 直接 import 编不过；反射在本机 JVM 上跑得通，类不在就当成"没素材"跳过。
     * 纯 Kotlin 的端到端复核还是 `.notes/margin_measure.py --c`（Python 那套）。
     */
    @Test
    fun realElevenByFiveScreenshot() {
        val name = "安卓端-屏幕比例11比5-自定义边距-未知-不为零.jpg"
        val f = listOf(
            File("../../refs/$name"),
            File("../refs/$name"),
            File("refs/$name"),
        ).firstOrNull { it.isFile }
        Assume.assumeTrue("没有 refs/$name 就跳过（参考图不进仓库）", f != null)

        val img = readImage(f!!)
        Assume.assumeTrue("这台机器上没有 javax.imageio，跳过", img != null)
        img!!
        val w = img.w
        val h = img.h
        assertEquals(2376, w)
        assertEquals(1080, h)
        val got = Layout.measure(w, h) { y -> img.row(y) }
        assertNotNull("真图也应该能量出来", got)
        got!!
        // 第 5 张卡和右边那块亮面板连成一片，宽度超上限被滤掉，所以只数得到 4 张 —— 够用了
        assertTrue("至少数到 4 张卡，实际 " + got.cards, got.cards >= 4)
        assertEquals("s", 0.8568f, got.s, 0.01f)
        assertEquals("ox", -11f, got.ox, 4f)
        assertTrue("量到了边距", got.hasMargin)
        assertEquals("m（基准像素）", 153.5f, got.m, 8f)
        // 反推游戏里填的数：153.5 / 1.169 ≈ 131，和另一台的 130 对得上
        assertEquals("游戏边距值", 131f, got.m / Geom.MARGIN_K, 8f)
        println(
            String.format(
                java.util.Locale.US,
                "11:5 实拍图实测：s=%.5f ox=%.1f m=%.1f px（基准）→ 游戏边距 %.1f，数到 %d 张卡",
                got.s, got.ox, got.m, got.m / Geom.MARGIN_K, got.cards,
            ),
        )
    }

    /** 反射包一层 BufferedImage，避开 android.jar 上没有 javax.imageio 的问题。 */
    private class Image(val w: Int, val h: Int, private val img: Any) {
        private val m = img.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        fun row(y: Int): IntArray {
            val buf = IntArray(w)
            m.invoke(img, 0, y, w, 1, buf, 0, w)
            return buf
        }
    }

    private fun readImage(f: File): Image? = try {
        val imageIO = Class.forName("javax.imageio.ImageIO")
        val img = imageIO.getMethod("read", File::class.java).invoke(null, f) ?: return null
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        Image(w, h, img)
    } catch (e: Throwable) {
        null
    }

    private companion object {
        const val BG = 0xFFA0A0A0.toInt()
        const val CARD = 0xFFF0F0F0.toInt()
        const val CYAN = 0xFF65C1D8.toInt()
    }
}
