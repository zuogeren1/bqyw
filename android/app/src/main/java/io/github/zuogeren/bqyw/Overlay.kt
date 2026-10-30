package io.github.zuogeren.bqyw

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮球：游戏里也能操作的主界面。
 *
 * 收起只有一个小球，拖动移动、点一下展开。面板里：
 *  - 牌型（下拉）：不限 / 散牌 / 对子 / … / 同花顺，选了就按牌型判定
 *  - 点数（下拉）：5 个格子，每个可选 任意 / 1~5 / 1~5 带花色
 *  - 开始 / 停止 / 状态
 *
 * 全部用面板内展开的选项网格，不弹系统下拉框——悬浮窗里弹窗容易抢焦点。
 */
class Overlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var body: LinearLayout? = null
    private var expanded = false

    private val point = intArrayOf(3, 2, 5, 0, 0)
    private val suit = intArrayOf(0, 0, 0, 0, 0)
    private var handIdx = 0
    private var ordered = false
    private var fast = false
    private val handOptions: List<Hand?> = listOf(null) + Hand.values().toList()

    private var mode = MODE_MAIN
    private var slotIdx = 0
    private var status: TextView? = null

    private val dp: Float get() = ctx.resources.displayMetrics.density
    private fun px(v: Int): Int = (v * dp).toInt()

    val isShowing: Boolean get() = root != null

    // ------------------------------------------------------------ 组装
    fun show() {
        if (root != null) return

        val ball = ImageView(ctx).apply {
            setImageResource(R.drawable.ball)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(px(52), px(52))
        }

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(8), px(10), px(8))
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(Color.parseColor("#F00E1412"))
                setStroke(px(1), Color.parseColor("#55FFFFFF"))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(px(250), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(8) }
            addView(body)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ball)
            addView(panel)
        }
        root = container

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = px(8)
        p.y = px(160)
        params = p
        wm.addView(container, p)
        attachDrag(ball)
        render()
        handler.post(poll)
    }

    fun hide() {
        handler.removeCallbacks(poll)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        panel = null
        body = null
        status = null
    }

    // ------------------------------------------------------------ 渲染
    private fun render() {
        val b = body ?: return
        b.removeAllViews()
        when (mode) {
            MODE_HAND -> renderHandList(b)
            MODE_SLOT -> renderSlotList(b)
            else -> renderMain(b)
        }
    }

    private fun renderMain(b: LinearLayout) {
        b.addView(label("牌型"))
        b.addView(button(handText(), wide = true) {
            mode = MODE_HAND
            render()
        })

        b.addView(label("目标点数（每格可选花色）"))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, px(2))
        }
        for (i in 0 until 5) {
            val cell = button(slotText(i), wide = false) {
                slotIdx = i
                mode = MODE_SLOT
                render()
            }
            cell.layoutParams = LinearLayout.LayoutParams(px(40), px(36)).apply { marginEnd = px(4) }
            cell.alpha = if (handOptions[handIdx] != null) 0.35f else 1f
            row.addView(cell)
        }
        b.addView(row)

        val orderBox = CheckBox(ctx).apply {
            text = "按顺序（第 1 格 = 最左那张）"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isChecked = ordered
            setOnCheckedChangeListener { _, v -> ordered = v }
        }
        b.addView(orderBox)

        val fastBox = CheckBox(ctx).apply {
            text = "快速模式（连点铺满，不提前放弃）"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isChecked = fast
            setOnCheckedChangeListener { _, v -> fast = v }
        }
        b.addView(fastBox)

        val hint = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFFFC857"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, px(4))
            text = if (handOptions[handIdx] != null) "已按牌型判定，上面这行不生效" else " "
        }
        b.addView(hint)

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("开始", wide = false) { start() }.apply {
            layoutParams = LinearLayout.LayoutParams(px(58), px(36)).apply { marginEnd = px(6) }
        })
        actions.addView(button("停止", wide = false) {
            CaptureService.instance?.stopBotFromUi()
        }.apply { layoutParams = LinearLayout.LayoutParams(px(58), px(36)) })
        b.addView(actions)

        status = TextView(ctx).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, px(6), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        b.addView(status)
    }

    private fun renderHandList(b: LinearLayout) {
        b.addView(label("选择牌型"))
        grid(b, handOptions.map { it?.cn ?: "自定义" }, 2, 92) { idx ->
            handIdx = idx
            mode = MODE_MAIN
            render()
        }
        b.addView(button("返回", wide = true) {
            mode = MODE_MAIN
            render()
        })
    }

    private fun renderSlotList(b: LinearLayout) {
        b.addView(label("第 " + (slotIdx + 1) + " 张"))
        // 第一行只有「任」，下面每行一个点数：无花色 / 萬 / 筒 / 索
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(cell("任", 54) { pickSlot(0, 0) })
        b.addView(head)
        for (p in 1..5) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(cell(p.toString(), 54) { pickSlot(p, 0) })
            for (s in 1..3) {
                row.addView(cell(p.toString() + Target.SUIT_CN[s], 54) { pickSlot(p, s) })
            }
            b.addView(row)
        }
        b.addView(button("返回", wide = true) {
            mode = MODE_MAIN
            render()
        })
    }

    private fun pickSlot(p: Int, s: Int) {
        point[slotIdx] = p
        suit[slotIdx] = s
        mode = MODE_MAIN
        render()
    }

    private fun cell(text: String, w: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = px(6).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(px(w), px(30)).apply {
                marginEnd = px(3)
                topMargin = px(3)
            }
            setOnClickListener { onClick() }
        }

    private fun grid(
        parent: LinearLayout,
        items: List<String>,
        cols: Int,
        cellW: Int = 56,
        onPick: (Int) -> Unit,
    ) {
        var row: LinearLayout? = null
        items.forEachIndexed { i, text ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(row)
            }
            val cell = button(text, wide = false) { onPick(i) }
            cell.layoutParams = LinearLayout.LayoutParams(px(cellW), px(32)).apply {
                marginEnd = px(4)
                topMargin = px(3)
            }
            row?.addView(cell)
        }
    }

    private fun label(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#AAFFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, px(2), 0, px(3))
    }

    private fun button(text: String, wide: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(px(8), px(4), px(8), px(4))
            background = GradientDrawable().apply {
                cornerRadius = px(6).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = if (wide) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(34),
                ).apply { topMargin = px(2) }
            } else {
                LinearLayout.LayoutParams(px(56), px(32))
            }
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ 选项
    private fun slotText(i: Int): String {
        val p = point[i]
        if (p == 0) return "任"
        return if (suit[i] == 0) p.toString() else p.toString() + Target.SUIT_CN[suit[i]]
    }

    private fun handText(): String = handOptions[handIdx]?.cn ?: "自定义"

    private fun slotOptionLabels(): List<String> =
        SLOT_POINTS.indices.map { idx ->
            val p = SLOT_POINTS[idx]
            val s = SLOT_SUITS[idx]
            when {
                p == 0 -> "任"
                s == 0 -> p.toString()
                else -> p.toString() + Target.SUIT_CN[s]
            }
        }

    private fun targetSpec(): String =
        (0 until 5).joinToString(" ") { i ->
            val p = point[i]
            when {
                p == 0 -> "任意"
                suit[i] == 0 -> p.toString()
                else -> p.toString() + Target.SUIT_CN[suit[i]]
            }
        }

    private fun start() {
        val svc = CaptureService.instance
        if (svc == null) {
            BotLog.add("服务没在跑，先回 App 授权抓屏")
            return
        }
        svc.startBotFromUi(targetSpec(), 0, handOptions[handIdx]?.name, ordered, fast)
        collapse()
    }

    // ------------------------------------------------------------ 拖动
    private fun attachDrag(ball: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        ball.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > px(6) || Math.abs(dy) > px(6)) moved = true
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle()
            }
            true
        }
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        expanded = true
        mode = MODE_MAIN
        render()
        panel?.visibility = View.VISIBLE
        fitOnScreen()
    }

    /**
     * 展开后把整个面板挪回屏幕内。
     *
     * 要按面板的实测高度算，不能用固定值：主面板两百多 dp，格子页还要高一截，
     * 用一个偏大的常量等于每次都把球硬拽到屏幕顶部（横屏只有 1260px 高）。
     * 高度要等这一帧布局完才拿得准，所以 post 一下。
     */
    private fun fitOnScreen() {
        val p = params ?: return
        val container = root ?: return
        val dm = ctx.resources.displayMetrics
        panel?.post {
            val panelW = panel?.width ?: 0
            val panelH = panel?.height ?: 0
            if (panelW <= 0 || panelH <= 0) return@post
            var moved = false
            // 容器是横排：球 + 间距 + 面板
            val maxX = dm.widthPixels - panelW - px(60)
            val maxY = dm.heightPixels - panelH - px(4)
            if (p.x > maxX) {
                p.x = maxOf(0, maxX)
                moved = true
            }
            if (p.y > maxY) {
                p.y = maxOf(0, maxY)
                moved = true
            }
            if (moved) runCatching { wm.updateViewLayout(container, p) }
        }
    }

    private fun collapse() {
        expanded = false
        panel?.visibility = View.GONE
        params?.let { runCatching { wm.updateViewLayout(root, it) } }
    }

    private val poll = object : Runnable {
        override fun run() {
            val svc = CaptureService.instance
            val running = svc?.isBotRunning() == true
            val last = BotLog.tail(1).firstOrNull() ?: ""
            status?.text = (if (running) "运行中  " else "空闲  ") + last
            handler.postDelayed(this, 700)
        }
    }

    private companion object {
        const val MODE_MAIN = 0
        const val MODE_HAND = 1
        const val MODE_SLOT = 2

        val SLOT_POINTS = intArrayOf(0, 1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2, 3, 4, 5)
        val SLOT_SUITS = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3)
    }
}
