package io.github.zuogeren.bqyw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Fit] 的 JVM 单测：按高度铺满 + **三条锚边**。
 *
 * 四条底线：
 *  1. **2800×1260 上必须一个数都不变** —— 老用户全在这块屏上，改完得逐值相等；
 *  2. 2376×1080（11:5，实拍图那台）→ s=0.85714、ox=−12、oy=0，且左/中/右三条锚边
 *     分别贴屏幕左边、正中、右边（旧版三条都套居中偏移，差 12 px）；
 *  3. 实拍图上的坐标（抽牌按钮 1918…2286、徽章窗口 2106…2138）要逐值对得上；
 *  4. 1920×1080（16:9，**没有真机样本**）→ 只保证公式不崩、给出可解释的数。
 */
class FitTest {

    @After
    fun reset() {
        Geom.setMargin(0)
        Geom.setScreen(2800, 1260)
    }

    /** 1) 20:9 基准屏：三条锚边恒等，所有坐标和改之前逐值相等。 */
    @Test
    fun base2800x1260IsIdentity() {
        val fit = Fit(2800, 1260)
        assertEquals("s", 1f, fit.s, 1e-6f)
        assertEquals("ox", 0f, fit.ox, 1e-4f)
        assertEquals("oy", 0f, fit.oy, 1e-4f)

        // 改之前的实测值（实机截图），一个都不许动；三条锚边也要恒等
        val pinned = intArrayOf(
            0, 60, 356, 392, 427, 1015, 1400, 2405, 2520, 2624, 2662, 2800,
        )
        for (v in pinned) {
            assertEquals("px($v)", v, fit.px(v.toFloat()))
            assertEquals("lpx($v)", v, fit.lpx(v.toFloat()))
            assertEquals("rpx($v)", v, fit.rpx(v.toFloat()))
        }
        val pinnedY = intArrayOf(0, 300, 360, 811, 870, 885, 1015, 1075, 1140, 1260)
        for (v in pinnedY) {
            assertEquals("py($v)", v, fit.py(v.toFloat()))
        }

        // 点击坐标 / 徽章窗口也都还是标定值
        assertEquals("抽牌 x", 2520, Geom.tapDraw().first)
        assertEquals("抽牌 y", 885, Geom.tapDraw().second)
        assertEquals("放弃 x", 1955, Geom.tapForfeit().first)
        assertEquals("确认 x（居中）", 1638, Geom.tapConfirm().first)
        assertEquals(
            "徽章窗口",
            listOf(2624, 1015, 2662, 1075),
            Badge.window(2800, 1260).toList(),
        )
    }

    /** 2) 11:5 实拍图那台：按高度铺满，缩放 0.857，居中组左偏 12 px。 */
    @Test
    fun elevenByFiveFillsHeightAndCropsSides() {
        val fit = Fit(2376, 1080)
        assertEquals("s = 1080/1260", 1080f / 1260f, fit.s, 1e-6f)
        assertEquals("oy 恒 0", 0f, fit.oy, 1e-6f)
        // 居中的东西（卡牌行）按 ox = −12 画：2800·s = 2400 > 2376，两侧各"多出" 12 px
        assertEquals("ox = (2376−2400)/2", -12f, fit.ox, 0.01f)
        assertEquals("居中组右沿 = ox + 2800·s", 2388f, fit.ox + 2800f * fit.s, 0.01f)

        // 三条锚边：左贴屏左、中留 ox、右贴屏右（§12.5 实拍图 ox_eff = 0 / −12 / −24）
        assertEquals("左锚 0 → 屏幕 0", 0, fit.lpx(0f))
        assertEquals("左锚 2800 → 2400（比屏幕宽，超出部分在右侧）", 2400, fit.lpx(2800f))
        assertEquals("居中 1400 = 屏幕正中", 1188, fit.px(1400f))
        assertEquals("右锚 2800 → 屏幕右沿", 2376, fit.rpx(2800f))
        assertEquals("右锚 0 → −24（左锚的 24 是这么来的）", -24, fit.rpx(0f))
    }

    /** 2b) 拿实拍图上量到的坐标对一遍（11:5、边距 120 那张）。 */
    @Test
    fun elevenByFiveMatchesRealScreenshot() {
        Geom.setScreen(2376, 1080)
        Geom.setMargin(120) // §12.1：这张实拍图是边距 120（不是以前推的 131）
        val fit = Fit(2376, 1080)

        // 抽牌按钮（右锚）：实拍图实测青色左沿 1918、右沿 2286
        val left = Geom.rx(2405f)
        val right = Geom.rx(2834f)
        assertTrue("按钮左沿实测 1918，算出 " + left, Math.abs(left - 1918) <= 2)
        assertTrue("按钮右沿实测 2286，算出 " + right, Math.abs(right - 2286) <= 2)

        // 轮次徽章窗口（右锚）：实拍图数字落在 2106,870 → 2138,921
        val win = Badge.window(2376, 1080)
        assertTrue("徽章窗口左实测 2106，算出 " + win[0], Math.abs(win[0] - 2106) <= 2)
        assertEquals("徽章窗口上", 870, win[1])
        assertTrue("徽章窗口右实测 2138，算出 " + win[2], Math.abs(win[2] - 2138) <= 2)
        assertEquals("徽章窗口下", 921, win[3])
        // 窗口 32 px 宽、数字约 17 px：偏 12 px 就只剩半个字了 —— 这就是要用右锚的原因
        assertTrue("窗口要盖住数字", win[2] - win[0] >= 30)

        // 点击点必须落在按钮里（1918…2286）
        val d = Geom.tapDraw()
        assertTrue("抽牌点落在按钮里: " + d.first, d.first in 1918..2286)
        assertEquals("纵向不动", fit.py(885f), d.second)
    }

    /** 3) 16:9：没有真机样本，只钉住公式行为，免得以后改坏了没人发现。 */
    @Test
    fun sixteenByNineIsFormulaOnly() {
        val fit = Fit(1920, 1080)
        assertEquals("s 还是按高度", 1080f / 1260f, fit.s, 1e-6f)
        // 2400 宽的画布塞进 1920：居中组两侧各偏 240 px
        assertEquals("ox = (1920−2400)/2", -240f, fit.ox, 0.01f)
        assertEquals("oy 恒 0", 0f, fit.oy, 1e-6f)

        // 锚边照样贴屏幕：左 0、中 960、右 1920
        assertEquals("左锚 0 → 0", 0, fit.lpx(0f))
        assertEquals("居中 1400 → 960", 960, fit.px(1400f))
        assertEquals("右锚 2800 → 1920", 1920, fit.rpx(2800f))

        // 注意：16:9 上"居中"这一组整体比左锚靠左 240 px，卡牌行和边距表会错开；
        // 这不代表模型对 —— 只代表没有样本。真遇到这台机器靠开局自检（Layout）停手兜底。
        assertTrue("居中组会跑到屏幕外，所以必须靠自检兜底", fit.px(60f) < 0)
    }

    /** 4) 边距叠加：m 在基准坐标里减，再交给 Fit 缩放。 */
    @Test
    fun marginComposesOnElevenByFive() {
        Geom.setScreen(2376, 1080)
        Geom.setMargin(130)
        val fit = Fit(2376, 1080)
        assertEquals(
            "抽牌 x = rpx(2520 − m)",
            fit.rpx(2520f - Geom.marginPx),
            Geom.tapDraw().first,
        )
        // 边距 0 时同一个点在哪：两者差 ≈ s·152 ≈ 130 px
        Geom.setMargin(0)
        val d0 = Geom.tapDraw().first
        Geom.setMargin(130)
        val d130 = Geom.tapDraw().first
        assertEquals("边距 130 在 11:5 上折合 130 屏幕像素", 130.0, (d0 - d130).toDouble(), 2.0)
    }
}
