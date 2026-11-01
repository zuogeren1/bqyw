package io.github.zuogeren.bqyw

/**
 * 屏幕坐标：全部按 2800×1260（20:9）标定，运行时经 [Fit] 换算到实际画面。
 *
 * 点击坐标必须走这里，不能自己按宽度乘比例——别的屏幕比例下画面是居中的。
 *
 * 游戏内「边距 / 安全区」不为 0 时，界面**不是整体平移**：它按控件自己的锚边反向挪，
 * 贴左的 +m、贴右的 −m、居中的不动，纵向一点都不动（实测 11 个特征 dy 全是 0）。
 * 所以补偿是**每个控件各带一个锚边标记**（见 [lx] / [rx] / [cx]），
 * 不能写成 `if (x > 2000) x -= m` 这种按坐标分区的写法——「今日好运」面板横跨
 * x=47…888 是左锚，卡牌行横跨 x=356…2146 却是居中，两者的范围是重叠的。
 * 实测表见 .notes/margin-android.md。
 *
 * m 一律在**基准坐标**里加减，再交给 [Fit] 缩放 —— 这样换分辨率会自动跟着走。
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

    // ------------------------------------------------------------ 边距补偿
    //
    // 实测（refs/安卓端-自定义边距-0.jpg 对 -130.jpg，2800×1260 同机同帧条件）：
    //   边距 130 → 界面按锚边内缩 151.96 px，k = 151.96 / 130 = 1.1689。
    //   最可能的精确形式是 1260/1080 = 7/6（1080p 设计稿在这块 1260 高的屏上放大 7/6），
    //   算出来 151.67，和实测只差 0.3 px —— 补偿量本身能容 ±10 px，两种写法等价。
    //
    // k 只在**基准坐标**里用：基准 2800×1260 已经带着这个 7/6 的放大，
    // 所以别的分辨率 / 屏幕比例不用再乘一次。只有一个实测样本，k 是不是常数还没验（§10）。

    /** 边距值 → 基准坐标像素的系数（实测值，见上）。 */
    const val MARGIN_K = 1.169f

    /** 边距上限：300 → 351 px，2800 宽还剩 2098 px；再大就得怀疑界面会不会整体重排了。 */
    const val MARGIN_MAX = 300

    /** 边距=0 时抽牌按钮的青色左沿（基准坐标）：「校准」拿它当标尺量真实内缩量。 */
    const val DRAW_EDGE = 2405f

    /** 游戏内「边距 / 安全区」设置值，0 = 不补偿（老用户默认值）。 */
    @Volatile
    var margin = 0
        private set

    /** 用户改边距：越界钳到 0~[MARGIN_MAX]。改完立刻生效，不用重开。 */
    fun setMargin(v: Int) {
        margin = v.coerceIn(0, MARGIN_MAX)
    }

    /** 当前边距折算出的**基准**像素数（交给 [Fit] 才是屏幕像素）。 */
    val marginPx: Float get() = margin * MARGIN_K

    // 三个锚边。都不碰纵向 —— 边距只管左右两条边，[sy] 一行都不用改。

    /** 贴左边画的控件：顶部标题、敌人信息、今日好运面板… */
    fun lx(v: Float): Int = fit.px(v + marginPx)

    /** 贴右边画的控件：抽牌、撤离、铭牌库剩余、当前轮次条… */
    fun rx(v: Float): Int = fit.px(v - marginPx)

    /** 居中画的东西：5 张卡、战力点/奖励点表、放弃确认弹窗。实测逐像素不动。 */
    fun cx(v: Float): Int = fit.px(v)

    fun sy(v: Float): Int = fit.py(v)

    /** 老写法，按居中处理。新代码请显式写 [lx] / [rx] / [cx]。 */
    fun sx(v: Float): Int = cx(v)

    val DRAW = floatArrayOf(2520f, 885f)
    val FORFEIT = floatArrayOf(1955f, 1140f)
    val CONFIRM = floatArrayOf(1638f, 870f)

    /** 抽牌：右锚（边距 130 时实测 x = 2520 − 152 = 2368）。 */
    fun tapDraw(): Pair<Int, Int> = Pair(rx(DRAW[0]), sy(DRAW[1]))

    /** 放弃挑战：右锚。不补偿必点空 —— 实测 1955 落在按钮外，1803 才在里面。 */
    fun tapForfeit(): Pair<Int, Int> = Pair(rx(FORFEIT[0]), sy(FORFEIT[1]))

    /**
     * 放弃确认弹窗的黄色确认键：居中。
     *
     * 理论上是居中的（取消 + 确认以屏幕中心对称），**但弹窗会不会跟着边距整体挪没在真机上验过**
     * （两张边距实测图里都没有弹窗）。所以 [Bot.isModal] 改成横扫找黄条，不靠这一个点：
     * 弹窗动不动都不影响判定，而这里的坐标表在真机验过之前按「居中」算。
     */
    fun tapConfirm(): Pair<Int, Int> = Pair(cx(CONFIRM[0]), sy(CONFIRM[1]))
}
