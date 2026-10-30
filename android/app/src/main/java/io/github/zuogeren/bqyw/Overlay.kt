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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮球：游戏里也能操作的主界面。
 *
 * 收起时只有一个小球，拖动可以换位置，点一下展开面板：
 * 五个点数格（点一下 任意→1→2→3→4→5 循环）、开始、停止、状态。
 * 故意不放输入框——悬浮窗里弹输入法很别扭，够用的组合用格子就够了。
 */
class Overlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private lateinit var chips: Array<TextView>
    private var status: TextView? = null
    private var expanded = false

    private val points = intArrayOf(5, 5, 5, 5, 0)

    private val dp: Float get() = ctx.resources.displayMetrics.density

    private fun px(v: Int): Int = (v * dp).toInt()

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val ball = TextView(ctx).apply {
            text = "抽"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC19C37D"))
                setStroke(px(2), Color.parseColor("#EEFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(px(52), px(52))
        }

        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(8), px(10), px(8))
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(Color.parseColor("#F00E1412"))
                setStroke(px(1), Color.parseColor("#55FFFFFF"))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(8) }
        }

        panel!!.addView(TextView(ctx).apply {
            text = "目标点数（点格子切换）"
            setTextColor(Color.parseColor("#AAFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(4), 0, px(4))
        }
        chips = Array(5) { i ->
            TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = px(6).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(px(34), px(34)).apply {
                    marginEnd = px(4)
                }
                setOnClickListener {
                    points[i] = (points[i] + 1) % 6
                    renderChips()
                }
            }.also { row.addView(it) }
        }
        panel!!.addView(row)

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(TextView(ctx).apply {
            text = "开始"
            setTextColor(Color.parseColor("#FF19C37D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(px(12), px(6), px(12), px(6))
            setOnClickListener { start() }
        })
        buttons.addView(TextView(ctx).apply {
            text = "停止"
            setTextColor(Color.parseColor("#FFE05B5B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(px(12), px(6), px(12), px(6))
            setOnClickListener { CaptureService.instance?.stopBotFromUi() }
        })
        panel!!.addView(buttons)

        status = TextView(ctx).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, px(4), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(px(236), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        panel!!.addView(status)

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
        renderChips()
        handler.post(poll)
    }

    fun hide() {
        handler.removeCallbacks(poll)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        panel = null
        status = null
    }

    private fun renderChips() {
        chips.forEachIndexed { i, v ->
            v.text = if (points[i] == 0) "任" else points[i].toString()
            (v.background as? GradientDrawable)?.setColor(
                if (points[i] == 0) Color.parseColor("#22FFFFFF") else Color.parseColor("#55FFFFFF")
            )
        }
    }

    private fun targetSpec(): String =
        points.joinToString(" ") { if (it == 0) "任意" else it.toString() }

    private fun start() {
        val svc = CaptureService.instance
        if (svc == null) {
            BotLog.add("服务没在跑，先回 App 授权抓屏")
            return
        }
        svc.startBotFromUi(targetSpec(), 0)
        collapse()
    }

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
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                }
            }
            true
        }
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        expanded = true
        panel?.visibility = View.VISIBLE
        val p = params ?: return
        // 展开后往左收，别挡住右边的按钮
        val maxX = ctx.resources.displayMetrics.widthPixels
        if (p.x > maxX / 3) p.x = maxX / 3
        runCatching { wm.updateViewLayout(root, p) }
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
}
