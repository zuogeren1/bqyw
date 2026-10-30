package io.github.zuogeren.bqyw

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 基准坐标（2800×1260）→ 实际画面坐标。
 *
 * 游戏是把整个界面按 20:9 排好，再等比缩放塞进窗口居中显示的：
 * 窗口比 20:9 更方（16:9、4:3）就上下留黑边，更宽（21:9）就左右留黑边。
 * 所以缩放取两个方向里的小者，多出来的部分左右/上下平分。
 *
 * 20:9 的机器上 s = w / 2800、偏移正好是 0，
 * 和以前「只按宽度缩放」的写法完全等价，换到别的比例才不会错位。
 */
class Fit(val w: Int, val h: Int) {

    /** 基准像素 → 实际像素的缩放。 */
    val s: Float = min(w / BASE_W, h / BASE_H)

    /** 左右黑边的宽度（窗口比 20:9 更宽时才有）。 */
    val ox: Float = (w - BASE_W * s) / 2f

    /** 上下黑边的高度（窗口比 20:9 更方时才有）。 */
    val oy: Float = (h - BASE_H * s) / 2f

    fun px(v: Float): Int = (ox + v * s).roundToInt()

    fun py(v: Float): Int = (oy + v * s).roundToInt()

    companion object {
        /** 界面标定用的基准分辨率：2800×1260，20:9。 */
        const val BASE_W = 2800f
        const val BASE_H = 1260f
    }
}
