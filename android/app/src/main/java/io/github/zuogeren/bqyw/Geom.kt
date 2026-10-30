package io.github.zuogeren.bqyw

/** 屏幕坐标：全部按 2800×1260 标定，运行时按实际宽度等比缩放。 */
object Geom {

    const val BASE_W = 2800f

    var screenW = 2800
    var screenH = 1260

    fun sx(v: Float): Int = (v * screenW / BASE_W).toInt()
    fun sy(v: Float): Int = (v * screenW / BASE_W).toInt()

    val DRAW = floatArrayOf(2520f, 885f)
    val FORFEIT = floatArrayOf(1955f, 1140f)
    val CONFIRM = floatArrayOf(1638f, 870f)

    fun tapDraw(): Pair<Int, Int> = Pair(sx(DRAW[0]), sy(DRAW[1]))
    fun tapForfeit(): Pair<Int, Int> = Pair(sx(FORFEIT[0]), sy(FORFEIT[1]))
    fun tapConfirm(): Pair<Int, Int> = Pair(sx(CONFIRM[0]), sy(CONFIRM[1]))
}
