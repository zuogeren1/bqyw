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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮球：游戏里也能操作的主界面。
 *
 * 收起只有一个小球，拖动移动、点一下展开。展开后一次只显示一页：
 *  - 主面板：当前目标摘要 + 按顺序 + 开始 / 停止 / 状态
 *  - 牌型页：9 个标准牌型 + 自定义，都能多选，达成其中任意一个就停手
 *  - 自定义页：可以写好几组，每组 1~5 张，达成任意一组就停手
 *  - 点数页：改某一组里的某一格（点数 / 花色 / 「不抽」）
 *  - 敌人页：黑白名单，按战力点分组、点一下勾/取消，另有「试认」入口
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
    private var scroll: ScrollView? = null
    private var expanded = false

    /** 勾上的标准牌型。多个时按倍率从高到低追求，见 Targets.combine。 */
    private val handSel = LinkedHashSet<Hand>()

    /** 勾上「自定义」：下面那几组牌面参与判定。 */
    private var customOn = false

    /** 自定义目标，每组 1~5 张；达成任意一组就停手。 */
    private val groups = ArrayList<Group>()

    /**
     * 敌人名单：模式 + 勾上的名字。
     *
     * 和 SharedPreferences 里那份同步 —— 开跑时 CaptureService 读的就是它，
     * 所以这里改完直接生效，不用重开 App。
     */
    private var enemyMode = Enemies.MODE_OFF
    private val enemySel = LinkedHashSet<String>()

    /** 敌人目录（只读 json，不解码立绘），第一次进「敌人」页时才读一次。 */
    private var catalog: List<Enemy>? = null

    init {
        // 默认口径跟 1.1.0 保持一致：老版本一打开就是「自定义 3 2 5 任 任」，
        // 点开始就能跑。改成空的等于每次都要先勾一下，没必要。
        customOn = true
        groups.add(Group.legacy())
        // 名单重启 App 后还在：这里读回来，改完立刻写回
        val saved = EnemyStore.load(ctx)
        enemyMode = saved.mode
        enemySel.addAll(saved.names)
    }

    private var ordered = false

    private var mode = MODE_MAIN

    /** 上一次渲染的是哪一页：敌人页 25 项，点一下不能把滚动条弹回顶部。 */
    private var lastMode = -1

    private var editGroup = 0
    private var editSlot = 0
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

        // 自定义能写好几组，面板有可能比屏幕还高；套一层可滚动的，
        // 免得「开始」被挤到屏幕外面点不着（横屏只有 1260px 高）
        scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val dm = ctx.resources.displayMetrics
                val cap = (dm.heightPixels - px(96)).coerceAtLeast(px(180))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(
                body,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
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
            layoutParams = LinearLayout.LayoutParams(px(250), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(8) }
            addView(scroll)
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
        scroll = null
        status = null
    }

    // ------------------------------------------------------------ 渲染
    private fun render() {
        val b = body ?: return
        // 敌人页有 25 项：同一页里点勾选只是重画，别把滚动位置丢了
        val keep = if (mode == MODE_ENEMY && lastMode == MODE_ENEMY) scroll?.scrollY ?: 0 else 0
        lastMode = mode
        b.removeAllViews()
        // 旧的 status 已经不在面板上了，别再让轮询往它身上写
        status = null
        // 换页后面板高度会变，把滚动条退回顶部，不然会停在上一次的偏移上
        scroll?.scrollTo(0, 0)
        when (mode) {
            MODE_HAND -> renderHandList(b)
            MODE_GROUPS -> renderGroupList(b)
            MODE_SLOT -> renderSlotList(b)
            MODE_ENEMY -> renderEnemy(b)
            else -> renderMain(b)
        }
        fitOnScreen()
        // 高度要等这一帧布局完才定，滚回去得等下一轮
        if (keep > 0) scroll?.post { scroll?.scrollTo(0, keep) }
    }

    private fun renderMain(b: LinearLayout) {
        b.addView(label("目标（勾几个都行，达成任意一个就停）"))
        b.addView(button(handButtonText(), wide = true) {
            mode = MODE_HAND
            render()
        })
        if (customOn) {
            b.addView(button("编辑自定义（" + groups.size + " 组）", wide = true) {
                mode = MODE_GROUPS
                render()
            })
        }
        b.addView(button("敌人名单：" + enemyLabel(), wide = true) {
            mode = MODE_ENEMY
            render()
        })

        // 摘要和真正开跑用的是同一个 Targets.combine，选了什么、按什么顺序追一眼能看到
        b.addView(summaryView())

        val orderBox = CheckBox(ctx).apply {
            text = "按顺序（对每组自定义都生效）"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isChecked = ordered
            setOnCheckedChangeListener { _, v ->
                ordered = v
                // 勾选这一下还在分发，等它走完再重建面板，免得把正在点的控件拆掉
                handler.post { render() }
            }
        }
        b.addView(orderBox)

        val hint = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFFFC857"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, px(4))
            text = hintText()
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
        b.addView(label("牌型 / 自定义（点一下切换，可多选）"))
        // 按倍率从高到低排，和判定时的优先级一致，勾起来不容易看错
        val names = HAND_ORDER.map { it.cn } + "自定义"
        grid(b, names, 2, 92, selected = { idx ->
            if (idx == HAND_ORDER.size) customOn else handSel.contains(HAND_ORDER[idx])
        }) { idx ->
            if (idx == HAND_ORDER.size) toggleCustom() else toggleHand(HAND_ORDER[idx])
            render()
        }
        // 勾上自定义之后，就在这一页直接给个入口，省得退出去再点一次
        if (customOn) {
            b.addView(button("编辑自定义（" + groups.size + " 组）", wide = true) {
                mode = MODE_GROUPS
                render()
            })
        }
        b.addView(summaryView())
        b.addView(button("返回", wide = true) {
            mode = MODE_MAIN
            render()
        })
    }

    private fun renderGroupList(b: LinearLayout) {
        b.addView(label("自定义目标（成任意一组就停）"))
        groups.forEachIndexed { gi, g ->
            // 每组一行标题 + 5 张牌各占一行，点哪张进去改哪张
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(ctx).apply {
                text = "第 " + (gi + 1) + " 组"
                setTextColor(Color.parseColor("#AAFFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (groups.size > 1) {
                head.addView(cell("删除这一组", 78) {
                    groups.removeAt(gi)
                    // 删空了就没法编辑了，留一组空壳（没勾自定义时它也不参与判定）
                    if (groups.isEmpty()) groups.add(Group.fresh())
                    render()
                })
            }
            b.addView(head)
            for (i in 0 until Target.MAX_DRAWS) {
                val row = cell("第 " + (i + 1) + " 张    " + g.fmt(i), 222) {
                    if (g.editable(i)) {
                        editGroup = gi
                        editSlot = i
                        mode = MODE_SLOT
                        render()
                    }
                }
                // 「不抽」之后的格子置灰：它们不算数，点了也只是让人困惑
                row.alpha = if (g.editable(i)) 1f else 0.35f
                b.addView(row)
            }
        }
        b.addView(button("+ 再加一组", wide = true) {
            groups.add(Group.fresh())
            render()
        })
        b.addView(summaryView())
        b.addView(button("返回", wide = true) {
            mode = MODE_MAIN
            render()
        })
    }

    private fun renderSlotList(b: LinearLayout) {
        if (editGroup !in groups.indices) editGroup = 0
        if (editSlot !in 0 until Target.MAX_DRAWS) editSlot = 0
        val g = groups.getOrNull(editGroup)
        if (g == null) {
            mode = MODE_MAIN
            renderMain(b)
            return
        }
        b.addView(label("第 " + (editGroup + 1) + " 组 · 第 " + (editSlot + 1) + " 张"))

        // 这一组 5 张横着摆出来，点哪张改哪张，比一页一页翻快
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until Target.MAX_DRAWS) {
            val c = cell(g.fmt(i), 40, on = i == editSlot) {
                if (g.editable(i)) {
                    editSlot = i
                    render()
                }
            }
            // 「不抽」后面的格子置灰：它们不算数，点了也只是让人困惑
            c.alpha = if (g.editable(i)) 1f else 0.35f
            row.addView(c)
        }
        b.addView(row)

        // 第 1 张只能是牌；第 2 张起可以先选「不抽」，把这一组截短
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val skip = g.skip[editSlot]
        head.addView(cell("任", 54, on = !skip && g.point[editSlot] == 0) { pickSlot(0, 0) })
        if (editSlot > 0) {
            head.addView(cell("不抽", 54, on = skip) { pickSkip() })
        }
        b.addView(head)
        for (p in 1..5) {
            val prow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            prow.addView(
                cell(p.toString(), 54,
                    on = !skip && g.point[editSlot] == p && g.suit[editSlot] == 0) { pickSlot(p, 0) }
            )
            for (s in 1..3) {
                prow.addView(
                    cell(p.toString() + Target.SUIT_CN[s], 54,
                        on = !skip && g.point[editSlot] == p && g.suit[editSlot] == s) { pickSlot(p, s) }
                )
            }
            b.addView(prow)
        }
        b.addView(button("返回", wide = true) {
            mode = MODE_GROUPS
            render()
        })
    }

    /**
     * 敌人页：顶部三档（关 / 黑名单 / 白名单），下面按战力点分组列出全部敌人。
     *
     * 每一项是「缩略图 + 名字 + 勾选状态」，点一下切换。名单存 SharedPreferences，
     * 开跑时 CaptureService 读的是同一份 —— 这里改完直接生效，不用重开 App。
     */
    private fun renderEnemy(b: LinearLayout) {
        b.addView(label("敌人名单（点一下勾 / 取消；白名单只留勾上的）"))
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(cell("关", 54, on = enemyMode == Enemies.MODE_OFF) {
            setEnemyMode(Enemies.MODE_OFF)
        })
        chips.addView(cell("黑名单", 74, on = enemyMode == Enemies.MODE_BAN) {
            setEnemyMode(Enemies.MODE_BAN)
        })
        chips.addView(cell("白名单", 74, on = enemyMode == Enemies.MODE_ONLY) {
            setEnemyMode(Enemies.MODE_ONLY)
        })
        b.addView(chips)

        val all = enemies()
        if (all.isEmpty()) {
            b.addView(label("没读到模板（assets/enemies/enemies.json 缺了）"))
            b.addView(button("返回", wide = true) { backToMain() })
            return
        }
        b.addView(label("已勾 " + enemySel.size + " 个 / 共 " + all.size + " 个"))
        for (p in 1..5) {
            val list = all.filter { it.point == p }
            if (list.isEmpty()) continue
            b.addView(label(p.toString() + " 点（" + list.size + " 个）"))
            for (e in list) b.addView(enemyCell(e))
        }
        b.addView(button("试认：看这 5 张是谁", wide = true) {
            val svc = CaptureService.instance
            if (svc == null) BotLog.add("[!] 服务没在跑，先回 App 授权抓屏")
            else svc.probeEnemiesFromUi()
        })
        b.addView(button("返回", wide = true) { backToMain() })
    }

    /** 一行敌人：缩略图 + 名字 + 勾选状态，整行都能点。 */
    private fun enemyCell(e: Enemy): LinearLayout {
        val on = enemySel.contains(e.name)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(2), px(4), px(2))
            background = chip(on)
            layoutParams = LinearLayout.LayoutParams(px(222), px(34)).apply {
                topMargin = px(3)
                marginEnd = px(3)
            }
        }
        row.addView(ImageView(ctx).apply {
            setImageBitmap(Enemies.thumb(ctx.assets, e.art))
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(6) }
        })
        row.addView(TextView(ctx).apply {
            text = e.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = if (on) "✓" else "○"
            setTextColor(if (on) Color.parseColor("#FF7AC8FF") else Color.parseColor("#66FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        })
        row.setOnClickListener { toggleEnemy(e.name) }
        return row
    }

    private fun enemies(): List<Enemy> =
        catalog ?: Enemies.catalog(ctx.assets).also { catalog = it }

    private fun setEnemyMode(m: String) {
        enemyMode = m
        saveEnemy()
        render()
    }

    private fun toggleEnemy(name: String) {
        if (!enemySel.add(name)) enemySel.remove(name)
        saveEnemy()
        render()
    }

    private fun saveEnemy() = EnemyStore.save(ctx, EnemyPolicy(enemyMode, enemySel.toList()))

    private fun enemyLabel(): String = EnemyPolicy(enemyMode, enemySel.toList()).label()

    private fun backToMain() {
        mode = MODE_MAIN
        render()
    }

    // ------------------------------------------------------------ 选项
    private fun pickSlot(p: Int, s: Int) {
        val g = groups.getOrNull(editGroup) ?: return
        g.point[editSlot] = p
        g.suit[editSlot] = s
        // 选了牌就不再是「不抽」；原来的截断点顺延到下一个还标着「不抽」的格子
        g.skip[editSlot] = false
        render()
    }

    private fun pickSkip() {
        val g = groups.getOrNull(editGroup) ?: return
        // 第 1 张不许「不抽」：一组至少要有一张牌
        if (editSlot <= 0) return
        g.skip[editSlot] = true
        render()
    }

    private fun toggleHand(h: Hand) {
        if (!handSel.add(h)) handSel.remove(h)
    }

    private fun toggleCustom() {
        customOn = !customOn
        // 第一次勾上先给一组默认牌面（原来那组 3 / 2 / 5 / 任 / 任），
        // 免得进去是空的、不知道从哪下手
        if (customOn && groups.isEmpty()) groups.add(Group.legacy())
    }

    private fun handNames(): List<String> = handSel.map { it.name }

    private fun specs(): List<String> = if (customOn) groups.map { it.spec() } else emptyList()

    private fun currentTarget(): TargetBase? =
        runCatching { Targets.combine(handNames(), specs(), ordered) }.getOrNull()

    private fun handButtonText(): String {
        val n = handSel.size + (if (customOn) groups.size else 0)
        return if (n == 0) "选择牌型 / 自定义" else "已选 " + n + " 个目标（点击修改）"
    }

    private fun hintText(): String =
        if (customOn && handSel.isNotEmpty()) "牌型和自定义一起判定，成任意一个就停" else " "

    /** 底部摘要：用和开跑时同一个目标对象，优先级、牌型名都不会跟实际跑的差开。 */
    private fun summaryView(): TextView = TextView(ctx).apply {
        setTextColor(Color.parseColor("#99FFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, px(4), 0, px(2))
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        text = "当前：" + (currentTarget()?.toString() ?: "未选择")
    }

    private fun start() {
        val svc = CaptureService.instance
        if (svc == null) {
            BotLog.add("[!] 服务没在跑，先回 App 授权抓屏")
            return
        }
        if (handSel.isEmpty() && (!customOn || groups.isEmpty())) {
            BotLog.add("[!] 还没选目标：点开「选择牌型 / 自定义」勾一个")
            return
        }
        svc.startBotFromUi(specs(), handNames(), ordered)
        collapse()
    }

    // ------------------------------------------------------------ 控件
    private fun cell(text: String, w: Int, on: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = chip(on)
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
        selected: (Int) -> Boolean = { false },
        onPick: (Int) -> Unit,
    ) {
        var row: LinearLayout? = null
        items.forEachIndexed { i, text ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                parent.addView(row)
            }
            val c = button(text, wide = false, on = selected(i)) { onPick(i) }
            c.layoutParams = LinearLayout.LayoutParams(px(cellW), px(32)).apply {
                marginEnd = px(4)
                topMargin = px(3)
            }
            row?.addView(c)
        }
    }

    private fun label(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.parseColor("#AAFFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, px(2), 0, px(3))
    }

    private fun button(
        text: String,
        wide: Boolean,
        on: Boolean = false,
        onClick: () -> Unit,
    ): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(px(8), px(4), px(8), px(4))
            background = chip(on)
            layoutParams = if (wide) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(34),
                ).apply { topMargin = px(2) }
            } else {
                LinearLayout.LayoutParams(px(56), px(32))
            }
            setOnClickListener { onClick() }
        }

    /** 选中态用亮一点的底 + 描边，没选中就还是原来的半透明白。 */
    private fun chip(on: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(6).toFloat()
        if (on) {
            setColor(Color.parseColor("#6657C8FF"))
            setStroke(px(1), Color.parseColor("#FF7AC8FF"))
        } else {
            setColor(Color.parseColor("#33FFFFFF"))
        }
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

    /**
     * 一组自定义目标：5 个格子，前 len 个算数，后面的「不抽」。
     *
     * 「不抽」按格记而不是只记一个长度：改中间那格时后面要跟着置灰，
     * 改回来时还得知道原来哪几格是「不抽」（和 PC 版的下拉框一个道理）。
     */
    private class Group {
        val point = IntArray(Target.MAX_DRAWS)
        val suit = IntArray(Target.MAX_DRAWS)
        val skip = BooleanArray(Target.MAX_DRAWS)

        /** 抽到第几张：第一个「不抽」的位置，都没标就是 5。第 1 张不允许「不抽」。 */
        val len: Int
            get() = (1 until Target.MAX_DRAWS).firstOrNull { skip[it] } ?: Target.MAX_DRAWS

        /** 这一格还能不能改：截断点自己也能改（改回牌面就把截断往后挪）。 */
        fun editable(i: Int): Boolean = i <= len

        fun fmt(i: Int): String {
            // 截断点和它后面的格子都显示成「不抽」，跟 PC 版下拉框看到的是一回事
            if (i >= len) return "不抽"
            val p = point[i]
            if (p == 0) return "任"
            return if (suit[i] == 0) p.toString() else p.toString() + Target.SUIT_CN[suit[i]]
        }

        /** 交给 Targets.parse 的写法；「不抽」之后的格子不写进去。 */
        fun spec(): String = (0 until len).joinToString(" ") { fmt(it) }

        companion object {
            /** 老界面那一组默认牌面：3 / 2 / 5 / 任 / 任。 */
            fun legacy(): Group = Group().apply {
                point[0] = 3
                point[1] = 2
                point[2] = 5
            }

            /** 新加的一组：先要一张 1，后面的「不抽」。 */
            fun fresh(): Group = Group().apply {
                point[0] = 1
                for (i in 1 until Target.MAX_DRAWS) skip[i] = true
            }
        }
    }

    private companion object {
        const val MODE_MAIN = 0
        const val MODE_HAND = 1
        const val MODE_SLOT = 2
        const val MODE_GROUPS = 3
        const val MODE_ENEMY = 4

        /** 牌型页的顺序：倍率从高到低，和判定优先级、PC 版的勾选清单一致。 */
        val HAND_ORDER = listOf(
            Hand.STRAIGHT_FLUSH,
            Hand.QUADS,
            Hand.FLUSH,
            Hand.FULL_HOUSE,
            Hand.STRAIGHT,
            Hand.TRIPS,
            Hand.TWO_PAIR,
            Hand.PAIR,
            Hand.HIGH,
        )
    }
}
