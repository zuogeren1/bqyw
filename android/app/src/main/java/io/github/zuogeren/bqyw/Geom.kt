package io.github.zuogeren.bqyw

/**
 * 屏幕坐标：全部按 2800×1260（20:9）标定，运行时经 [Fit] 换算到实际画面。
 *
 * 点击坐标必须走这里，不能自己按宽度乘比例——别的屏幕比例下画面是居中的。
 */
object Geom {

    var screenW = 2800
        private set
    var screenH = 1260
        private set

    /** 画面尺寸变了就重新算一次缩放。 */
    private var fit = Fit(screenW, screenH)

    fun setScreen(w: Int, h: Int) {
        screenW = w
        screenH = h
        fit = Fit(w, h)
    }

    fun sx(v: Float): Int = fit.px(v)
    fun sy(v: Float): Int = fit.py(v)

    val DRAW = floatArrayOf(2520f, 885f)
    val FORFEIT = floatArrayOf(1955f, 1140f)
    val CONFIRM = floatArrayOf(1638f, 870f)

    fun tapDraw(): Pair<Int, Int> = Pair(sx(DRAW[0]), sy(DRAW[1]))
    fun tapForfeit(): Pair<Int, Int> = Pair(sx(FORFEIT[0]), sy(FORFEIT[1]))
    fun tapConfirm(): Pair<Int, Int> = Pair(sx(CONFIRM[0]), sy(CONFIRM[1]))
}
