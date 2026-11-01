package io.github.zuogeren.bqyw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Fit] 的 JVM 单测：按高度铺满 + 水平居中（.notes/margin-android.md §11）。
 *
 * 三条底线：
 *  1. **2800×1260 上必须一个数都不变** —— 老用户全在这块屏上，改完得逐值相等；
 *  2. 2376×1080（11:5，实拍图那台）→ s=0.85714、ox=−12、oy=0；
 *  3. 1920×1080（16:9，**没有真机样本**）→ 只保证公式不崩、给出可解释的数。
 */
class FitTest {

    @After
    fun reset() {
        Geom.setMargin(0)
        Geom.setScreen(2800, 1260)
    }

    /** 1) 20:9 基准屏：s=1、ox=0、oy=0，所有坐标和改之前逐值相等。 */
    @Test
    fun base2800x1260IsIdentity() {
        val fit = Fit(2800, 1260)
        assertEquals("s", 1f, fit.s, 1e-6f)
        assertEquals("ox", 0f, fit.ox, 1e-4f)
        assertEquals("oy", 0f, fit.oy, 1e-4f)

        // 改之前的实测值（refs/安卓端-自定义边距-0.jpg），一个都不许动
        val pinned = intArrayOf(
            0, 60, 356, 392, 427, 1015, 1400, 2405, 2520, 2624, 2662, 2800,
        )
        for (v in pinned) {
            assertEquals("px($v)", v, fit.px(v.toFloat()))
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

    /** 2) 11:5 实拍图那台：按高度铺满，两侧各裁 12 px。 */
    @Test
    fun elevenByFiveFillsHeightAndCropsSides() {
        val fit = Fit(2376, 1080)
        assertEquals("s = 1080/1260", 1080f / 1260f, fit.s, 1e-6f)
        assertEquals("oy 恒 0", 0f, fit.oy, 1e-6f)
        // 2800*0.85714 = 2400 > 2376，所以 ox 是负的：两侧各裁 12 px
        assertEquals("ox = (2376−2400)/2", -12f, fit.ox, 0.01f)
        // 画布 2400 宽、屏幕 2376 宽：右边比屏幕多出 12 px（左边也裁 12），
        // 所以 ox + 2800·s 是画布右沿 = 2388，不是屏幕宽 2376。
        assertEquals("画布右沿 = ox + 2800·s", 2388f, fit.ox + 2800f * fit.s, 0.01f)
        assertEquals("屏幕高 = 1260·s（正好铺满）", 1080f, 1260f * fit.s, 0.01f)
    }

    /** 2b) 拿实拍图上量到的坐标对一遍（11:5 那张，实测边距 ≈131 → m ≈ 153）。 */
    @Test
    fun elevenByFiveMatchesRealScreenshot() {
        Geom.setScreen(2376, 1080)
        Geom.setMargin(131) // 反推值：实测 m_base 153.5 / 1.169 ≈ 131.3
        val fit = Fit(2376, 1080)
        val m = Geom.marginPx

        // 抽牌按钮（右锚）：实拍图实测左沿 1918、右沿 2286
        val left = fit.px(2405f - m)
        val right = fit.px(2834f - m)
        assertEquals("按钮左沿和实拍图对得上", 1918, left)
        assertTrue("按钮右沿和实拍图对得上（实测 2286）", Math.abs(right - 2286) <= 3)

        // 轮次徽章窗口（右锚）：实拍图实测 2106,870 → 2138,921
        val win = Badge.window(2376, 1080)
        assertEquals("徽章窗口左", 2106, win[0])
        assertEquals("徽章窗口上", 870, win[1])
        assertEquals("徽章窗口右", 2138, win[2])
        assertEquals("徽章窗口下", 921, win[3])

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
        // 2400 宽的画布塞进 1920：两侧各裁 240 px
        assertEquals("ox = (1920−2400)/2", -240f, fit.ox, 0.01f)
        assertEquals("oy 恒 0", 0f, fit.oy, 1e-6f)
        // 说明一下：16:9 上游戏大概会重新排布，这里只保证公式不崩；
        // 真遇到这台机器靠开局自检（Layout）停手，不靠这张表硬点。
        assertTrue("左锚会跑到屏幕外（所以必须靠自检兜底）", fit.px(60f) < 0)
    }

    /** 4) 边距叠加：m 在基准坐标里减，再交给 Fit 缩放。 */
    @Test
    fun marginComposesOnElevenByFive() {
        Geom.setScreen(2376, 1080)
        Geom.setMargin(130)
        val fit = Fit(2376, 1080)
        val expect = Math.round(fit.ox + fit.s * (2520f - Geom.marginPx))
        assertEquals(
            "抽牌 x = ox + s·(2520 − m)",
            expect,
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
