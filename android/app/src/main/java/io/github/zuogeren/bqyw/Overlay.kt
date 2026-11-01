package io.github.zuogeren.bqyw

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 悬浮球：游戏里也能操作的主界面。
 *
 * 收起只有一个小球，拖动移动、点一下展开。展开后是 250dp 宽的面板，
 * 顶部常驻四个页签，一屏一页：
 *  - 主：当前目标 + 当前边距（大数字）· ±10 / 输入框 / 校准 / 试认 · 开始 / 停止 · 状态
 *  - 牌型页：9 个标准牌型 + 自定义，都能多选，达成其中任意一个就停手
 *  - 自定义页：可以写好几组，每组 1~5 张，达成任意一组就停手
 *  - 点数页：改某一组里的某一格（点数 / 花色 / 「不抽」）
 *  - 敌人页：黑白名单，按战力点分组、点一下勾/取消
 *
 * 全部用面板内展开的选项网格，不弹系统下拉框——悬浮窗里弹窗容易抢焦点。
 * 唯一的例外是边距那个数值输入框：它要打字，所以在聚焦的那一刻才临时把悬浮窗的
 * FLAG_NOT_FOCUSABLE 摘掉（见 setWindowFocusable），失焦立刻装回去。
 *
 * 视觉全部走 [Ui]：深色底、绿色强调、等宽数字、1px 细线、直角；
 * 每个能点的控件都带按下反馈（Ui.pressable 的三态底）。
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

    /**
     * 边距：游戏内「边距 / 安全区」设置的数字（0~300），0 = 不补偿。
     *
     * 和 SharedPreferences 里那份同步 —— 开跑时 CaptureService 读的就是它；这里改完
     * 直接写进 [Geom]，下一次点击就用新坐标，不用重开 App。
     */
    private var borderMargin = 0

    /** 边距输入框 / 它上面那个大数字 / 下面那行折算说明；不在主面板上时是 null。 */
    private var marginField: EditText? = null
    private var marginBig: TextView? = null
    private var marginTip: TextView? = null

    /** 程序自己在改输入框内容（±10、失焦规整）时，别当成用户在打字再写一遍。 */
    private var syncingField = false

    /** 页签：文字、下划线，按下时整格变一点绿。 */
    private val tabTexts = ArrayList<TextView>()
    private val tabLines = ArrayList<View>()

    /** 面板顶部那颗状态点 + 文字（空闲 / 运行中）。 */
    private var headDot: View? = null
    private var headText: TextView? = null

    /** 主面板底部那行「上次干了什么」。 */
    private var status: TextView? = null

    /** 固定在面板底部的「开始 / 停止」：只在主页面出现，滚动时不会被推走。 */
    private var footer: LinearLayout? = null

    init {
        // 默认口径跟 1.1.0 保持一致：老版本一打开就是「自定义 3 2 5 任 任」，
        // 点开始就能跑。改成空的等于每次都要先勾一下，没必要。
        customOn = true
        groups.add(Group.legacy())
        // 名单重启 App 后还在：这里读回来，改完立刻写回
        val saved = EnemyStore.load(ctx)
        enemyMode = saved.mode
        enemySel.addAll(saved.names)
        // 边距同理：打开面板就生效（不用等开跑），默认 0 = 不补偿
        borderMargin = MarginStore.load(ctx)
        Geom.setMargin(borderMargin)
    }

    private var ordered = false

    private var mode = MODE_MAIN

    /** 上一次渲染的是哪一页：敌人页 25 项，点一下不能把滚动条弹回顶部。 */
    private var lastMode = -1

    private var editGroup = 0
    private var editSlot = 0

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

        // 10dp 内边距：面板 250dp 宽，内容可用 230dp，格子的宽度都按这个算
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(8), px(10), px(10))
        }

        // 自定义能写好几组，面板有可能比屏幕还高；套一层可滚动的，
        // 免得「开始」被挤到屏幕外面点不着（横屏只有 1260px 高）。
        //
        // 高度上限要扣掉面板里不滚的那几块（页头 + 页签 + 底部开始/停止），
        // 不然面板会顶出屏幕，底下的「开始」正好被切掉
        scroll = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val dm = ctx.resources.displayMetrics
                val footH = if (footer?.visibility == View.VISIBLE) {
                    footer?.measuredHeight?.takeIf { it > 0 } ?: px(70)
                } else {
                    0
                }
                val cap = (dm.heightPixels - px(96) - px(66) - footH).coerceAtLeast(px(120))
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
            background = Ui.block(0xF20E1412.toInt(), 0x57FFFFFF, px(1))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(px(250), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = px(8) }
            addView(headRow())
            addView(Ui.rule(ctx, Ui.LINE2))
            addView(tabBar())
            addView(Ui.rule(ctx, Ui.LINE2))
            addView(scroll)
            footer = footerView()
            addView(footer)
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
        footer = null
        headDot = null
        headText = null
        tabTexts.clear()
        tabLines.clear()
    }

    // ------------------------------------------------------------ 面板骨架

    /** 顶部一行：左边「抽牌助手」，右边状态点 + 空闲 / 运行中。 */
    private fun headRow(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(10), px(8), px(10), px(7))
        addView(
            Ui.text(ctx, "抽牌助手", 11f, Ui.DIM2).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val dot = Ui.dot(ctx, Ui.DIM, 6)
        headDot = dot
        addView(dot)
        val t = Ui.text(ctx, "空闲", 10.5f, Ui.DIM).apply { setPadding(px(5), 0, 0, 0) }
        headText = t
        addView(t)
    }

    /** 四个页签：选中一页是绿字 + 2px 下划线，按下整格泛绿。 */
    private fun tabBar(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(4), 0, px(4), 0)
        for ((i, name) in TABS.withIndex()) {
            val tv = Ui.text(ctx, name, 12f, Ui.DIM).apply { gravity = Gravity.CENTER }
            val line = View(ctx).apply {
                setBackgroundColor(Ui.GREEN)
                visibility = View.INVISIBLE
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background = Ui.pressable(Ui.CLEAR, 0x1A2FBF71)
                setOnClickListener { goTab(i) }
                addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(2)))
            }
            addView(box, LinearLayout.LayoutParams(0, px(34), 1f))
            tabTexts.add(tv)
            tabLines.add(line)
        }
    }

    /** 点页签：直接跳到那一页（点数页属于自定义页）。 */
    private fun goTab(i: Int) {
        // 点数页属于「自定义」：点这个页签一律回到组列表
        mode = when (i) {
            1 -> MODE_HAND
            2 -> MODE_GROUPS
            3 -> MODE_ENEMY
            else -> MODE_MAIN
        }
        render()
    }

    private fun paintTabs() {
        val active = when (mode) {
            MODE_HAND -> 1
            MODE_GROUPS, MODE_SLOT -> 2
            MODE_ENEMY -> 3
            else -> 0
        }
        tabTexts.forEachIndexed { i, tv -> tv.setTextColor(if (i == active) Ui.GREEN else Ui.DIM) }
        tabLines.forEachIndexed { i, v ->
            v.visibility = if (i == active) View.VISIBLE else View.INVISIBLE
        }
    }

    // ------------------------------------------------------------ 渲染
    private fun render() {
        val b = body ?: return
        // 正在打字时换页：先把输入焦点、输入法、窗口的「可聚焦」都收回来 ——
        // 输入框马上要被拆掉，不主动收的话悬浮窗会一直占着键盘焦点，游戏里按键就没反应了
        marginField?.clearFocus()
        hideIme()
        setWindowFocusable(false)
        // 敌人页有 25 项：同一页里点勾选只是重画，别把滚动位置丢了
        val keep = if (mode == MODE_ENEMY && lastMode == MODE_ENEMY) scroll?.scrollY ?: 0 else 0
        lastMode = mode
        b.removeAllViews()
        // 旧的 status / 边距控件已经不在面板上了，别再往它们身上写
        marginField = null
        marginBig = null
        marginTip = null
        // 换页后面板高度会变，把滚动条退回顶部，不然会停在上一次的偏移上
        scroll?.scrollTo(0, 0)
        paintTabs()
        when (mode) {
            MODE_HAND -> renderHandList(b)
            MODE_GROUPS -> renderGroupList(b)
            MODE_SLOT -> renderSlotList(b)
            MODE_ENEMY -> renderEnemy(b)
            else -> renderMain(b)
        }
        // 开始 / 停止 只在主页面露出来（别的页面上也用得上，但先把列表留满）
        footer?.visibility = if (mode == MODE_MAIN) View.VISIBLE else View.GONE
        // 主界面也要看到同一份目标（开始按钮跑的就是它），见 Selection
        Selection.publish(handNames(), specs(), ordered)
        fitOnScreen()
        // 高度要等这一帧布局完才定，滚回去得等下一轮
        if (keep > 0) scroll?.post { scroll?.scrollTo(0, keep) }
    }

    private fun renderMain(b: LinearLayout) {
        b.addView(Ui.label(ctx, "当前目标"))
        b.addView(
            Ui.text(ctx, currentTarget()?.toString() ?: "未选择（去「牌型」勾一个）", 13f, Ui.INK).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, px(2))
            },
        )

        b.addView(
            Ui.rule(ctx),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)).apply {
                topMargin = px(7)
                bottomMargin = px(1)
            },
        )

        // 边距：游戏内那个设置不为 0 时，界面不是整体平移，而是按控件锚边反向挪
        // （贴左 +m、贴右 −m、居中不动），所以得把游戏里的数字抄进来，见 Geom。
        b.addView(Ui.label(ctx, "边距（游戏内设置值，0 = 不补偿）"))
        b.addView(marginValue())
        b.addView(marginRow())
        marginTip = marginTipView()
        b.addView(marginTip)

        b.addView(
            wideButton("试认 · 看这 5 张是谁") {
                val svc = CaptureService.instance
                if (svc == null) BotLog.add("[!] 服务没在跑，先回 App 授权抓屏")
                else svc.probeEnemiesFromUi()
            },
        )

        b.addView(
            CheckBox(ctx).apply {
                text = "按顺序（对每组自定义都生效）"
                setTextColor(Ui.DIM2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                buttonTintList = ColorStateList.valueOf(Ui.GREEN)
                isChecked = ordered
                setOnCheckedChangeListener { _, v ->
                    ordered = v
                    // 勾选这一下还在分发，等它走完再重建面板，免得把正在点的控件拆掉
                    handler.post { render() }
                }
            },
        )

        val hint = hintText()
        if (hint.isNotEmpty()) {
            b.addView(
                Ui.text(ctx, hint, 10.5f, Ui.WARN).apply { setPadding(0, 0, 0, px(2)) },
            )
        }

    }

    /**
     * 面板底部常驻的一条：开始 / 停止 + 最后一次日志。
     *
     * 钉在滚动区外面 —— 游戏里横屏只有 360dp 高，主页面一长「开始」就会被推出屏幕。
     */
    private fun footerView(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.rule(ctx, Ui.LINE))
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(10), px(8), px(10), px(5))
        }
        actions.addView(
            primaryButton("开始") { start() },
            LinearLayout.LayoutParams(0, px(38), 1.25f).apply { marginEnd = px(6) },
        )
        actions.addView(
            secondaryButton("停止") { CaptureService.instance?.stopBotFromUi() },
            LinearLayout.LayoutParams(0, px(38), 1f),
        )
        addView(actions)
        val line = Ui.text(ctx, "", 10f, Ui.DIM, mono = true).apply {
            setPadding(px(10), 0, px(10), px(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        status = line
        addView(line)
    }

    /** 主面板上的「当前边距」：大号等宽数字，一眼看到。 */
    private fun marginValue(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(0, px(2), 0, px(2))
        val big = Ui.text(ctx, borderMargin.toString(), 22f, Ui.GREEN, mono = true)
        marginBig = big
        addView(big)
        addView(
            Ui.text(ctx, if (borderMargin == 0) " 不补偿" else " → 内缩 " + n0(Geom.marginPx) + " px", 10.5f, Ui.DIM)
                .apply { setPadding(px(6), 0, 0, px(3)) },
        )
    }

    private fun renderHandList(b: LinearLayout) {
        b.addView(Ui.label(ctx, "牌型（点一下切换，可多选）"))
        // 按倍率从高到低排，和判定时的优先级一致，勾起来不容易看错
        val names = HAND_ORDER.map { it.cn } + "自定义"
        grid(b, names, 2, 106, selected = { idx ->
            if (idx == HAND_ORDER.size) customOn else handSel.contains(HAND_ORDER[idx])
        }) { idx ->
            if (idx == HAND_ORDER.size) toggleCustom() else toggleHand(HAND_ORDER[idx])
            render()
        }
        b.addView(summaryView())
    }

    private fun renderGroupList(b: LinearLayout) {
        b.addView(Ui.label(ctx, "自定义目标（成任意一组就停）"))
        groups.forEachIndexed { gi, g ->
            // 每组一行标题 + 5 张牌各占一行，点哪张进去改哪张
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(4), 0, 0)
            }
            head.addView(
                Ui.text(ctx, "第 " + (gi + 1) + " 组", 11f, Ui.DIM2),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (groups.size > 1) {
                head.addView(cell("删除这一组", 84) {
                    groups.removeAt(gi)
                    // 删空了就没法编辑了，留一组空壳（没勾自定义时它也不参与判定）
                    if (groups.isEmpty()) groups.add(Group.fresh())
                    render()
                })
            }
            b.addView(head)
            for (i in 0 until Target.MAX_DRAWS) {
                val row = cell("第 " + (i + 1) + " 张   " + g.fmt(i), 222) {
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
        b.addView(wideButton("+ 再加一组") {
            groups.add(Group.fresh())
            render()
        })
        b.addView(summaryView())
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
        b.addView(Ui.label(ctx, "第 " + (editGroup + 1) + " 组 · 第 " + (editSlot + 1) + " 张"))

        // 这一组 5 张横着摆出来，点哪张改哪张，比一页一页翻快
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(2), 0, px(2))
        }
        for (i in 0 until Target.MAX_DRAWS) {
            val c = cell(g.fmt(i), 42, on = i == editSlot) {
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
                    on = !skip && g.point[editSlot] == p && g.suit[editSlot] == 0) { pickSlot(p, 0) },
            )
            for (s in 1..3) {
                prow.addView(
                    cell(p.toString() + Target.SUIT_CN[s], 54,
                        on = !skip && g.point[editSlot] == p && g.suit[editSlot] == s) { pickSlot(p, s) },
                )
            }
            b.addView(prow)
        }
        b.addView(wideButton("返回自定义列表") {
            mode = MODE_GROUPS
            render()
        })
    }

    /**
     * 敌人页：顶部三档（关 / 黑名单 / 白名单），下面按战力点分组列出全部敌人。
     *
     * 每一项是「缩略图 + 名字 + 勾选状态」，点一下切换。名单存 SharedPreferences，
     * 开跑时 CaptureService 读的是同一份 —— 这里改完直接生效，不用重开 App。
     * 「试认」在主面板上（常驻，不用先进这一页）。
     */
    private fun renderEnemy(b: LinearLayout) {
        b.addView(Ui.label(ctx, "敌人名单（点一下勾 / 取消）"))
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
            b.addView(Ui.text(ctx, "没读到模板（assets/enemies/enemies.json 缺了）", 11f, Ui.WARN))
            return
        }
        b.addView(Ui.text(ctx, "已勾 " + enemySel.size + " 个 / 共 " + all.size + " 个", 10.5f, Ui.GREEN, mono = true))
        for (p in 1..5) {
            val list = all.filter { it.point == p }
            if (list.isEmpty()) continue
            b.addView(Ui.label(ctx, p.toString() + " 点（" + list.size + " 个）"))
            for (e in list) b.addView(enemyCell(e))
        }
    }

    /** 一行敌人：缩略图 + 名字 + 勾选状态，整行都能点。 */
    private fun enemyCell(e: Enemy): LinearLayout {
        val on = enemySel.contains(e.name)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(5), px(2), px(5), px(2))
            isClickable = true
            isSelected = on
            background = Ui.pressable(0x1AFFFFFF, 0x4D2FBF71, 0, 0, 0x332FBF71, Ui.GREEN, px(1))
            layoutParams = LinearLayout.LayoutParams(px(222), px(34)).apply {
                topMargin = px(3)
                marginEnd = px(3)
            }
            setOnClickListener { toggleEnemy(e.name) }
        }
        row.addView(ImageView(ctx).apply {
            setImageBitmap(Enemies.thumb(ctx.assets, e.art))
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(px(30), px(30)).apply { marginEnd = px(6) }
        })
        row.addView(TextView(ctx).apply {
            text = e.name
            setTextColor(if (on) Ui.INK else Ui.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = if (on) "✓" else "○"
            setTextColor(if (on) Ui.GREEN else Ui.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        })
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

    // ------------------------------------------------------------ 边距

    /**
     * 边距那一行：输入框 + 粗调 ±10 + 「校准」。
     *
     * 排版取舍：面板只有 250dp 宽，所以「边距」说明放在上面当标签，这一行只放
     * 66dp 的输入框、两个 38dp 的粗调键和一个 62dp 的校准键（合计约 214dp）。
     * 输入框要弹系统输入法，而悬浮窗默认是 FLAG_NOT_FOCUSABLE（不抢游戏输入的焦点），
     * 所以只在它聚焦的那一刻临时摘掉那个 flag，见 [setWindowFocusable]；
     * 万一某个机型上输入法弹不出来，±10 也能把值调到任意 10 的倍数，不至于卡死。
     */
    private fun marginRow(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2), 0, px(2))
        }
        val field = EditText(ctx).apply {
            setText(borderMargin.toString())
            setTextColor(Ui.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Ui.mono()
            gravity = Gravity.CENTER
            background = Ui.block(0x142FBF71, Ui.GREEN, px(1))
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(3))
            setSingleLine()
            setPadding(px(2), 0, px(2), 0)
            layoutParams = LinearLayout.LayoutParams(px(66), px(34)).apply { marginEnd = px(4) }
        }
        // 一边打字一边生效：0~300 内改到哪就是哪，超了按上限算（框里的数字先不动，
        // 免得刚打到「35」就被改成别的），失焦时再把框规整成真正生效的值
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingField) return
                applyMargin(s?.toString().orEmpty())
            }
        })
        field.setOnFocusChangeListener { _, has ->
            if (has) {
                setWindowFocusable(true)
                // 刚摘下 FLAG_NOT_FOCUSABLE 那一下窗口还没重新布局，等一拍再叫输入法
                handler.post {
                    setWindowFocusable(true)
                    // 改窗口 flag 会让窗口重新布局，焦点可能被系统收走，补要一次
                    if (!field.hasFocus()) field.requestFocus()
                    runCatching {
                        imm()?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } else {
                hideIme()
                setWindowFocusable(false)
                syncingField = true
                field.setText(borderMargin.toString())
                field.setSelection(field.text.length)
                syncingField = false
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                true
            } else {
                false
            }
        }
        marginField = field
        row.addView(field)
        row.addView(cell("-10", 38) { bumpMargin(-10) }.apply {
            layoutParams = LinearLayout.LayoutParams(px(38), px(34)).apply { marginEnd = px(2) }
        })
        row.addView(cell("+10", 38) { bumpMargin(10) }.apply {
            layoutParams = LinearLayout.LayoutParams(px(38), px(34)).apply { marginEnd = px(4) }
        })
        row.addView(secondaryButton("校准") {
            val svc = CaptureService.instance
            if (svc == null) {
                BotLog.add("[!] 服务没在跑，先回 App 授权抓屏")
            } else {
                // 校准要看的是游戏画面：先把输入法收掉、焦点还给游戏
                marginField?.clearFocus()
                hideIme()
                svc.probeMarginFromUi()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(px(62), px(34)) })
        return row
    }

    /** 边距下面那行小字：折算成基准像素，并直接给出补偿后的两个点击坐标。 */
    private fun marginTipView(): TextView = Ui.text(ctx, marginTipText(), 10f, 0xFF9BD1FF.toInt()).apply {
        alpha = 0.85f
        setPadding(0, px(1), 0, px(3))
    }

    /** [typed] 是输入框里的原始数字：超上限时提示「按 300 算」，但框里的字先不改。 */
    private fun marginTipText(typed: Int? = null): String {
        val over = if (typed != null && typed > Geom.MARGIN_MAX) "（超过 300，按 300 算）" else ""
        if (borderMargin == 0) return "0 = 不补偿，和以前完全一样" + over
        val m = Geom.marginPx
        return "基准内缩 " + n0(m) + " px：抽牌 x " + n0(Geom.DRAW[0] - m) +
            "，放弃 x " + n0(Geom.FORFEIT[0] - m) + over
    }

    /**
     * 改边距：钳到 0~300 立刻生效（[Geom] 是全局的，下一次点击就用新坐标），
     * 同时写进 SharedPreferences，重启 App 还在。
     */
    private fun applyMargin(raw: String) {
        val typed = raw.trim().toIntOrNull()
        val v = (typed ?: 0).coerceIn(0, Geom.MARGIN_MAX)
        if (v != borderMargin) {
            borderMargin = v
            Geom.setMargin(v)
            MarginStore.save(ctx, v)
            marginBig?.text = v.toString()
        }
        marginTip?.text = marginTipText(typed)
    }

    /** ±10 粗调：输入法在悬浮窗里不好用时也能把值调到任意 10 的倍数。 */
    private fun bumpMargin(delta: Int) {
        val v = (borderMargin + delta).coerceIn(0, Geom.MARGIN_MAX)
        borderMargin = v
        Geom.setMargin(v)
        MarginStore.save(ctx, v)
        syncingField = true
        marginField?.setText(v.toString())
        marginField?.setSelection(v.toString().length)
        syncingField = false
        marginBig?.text = v.toString()
        marginTip?.text = marginTipText()
    }

    /**
     * 悬浮窗默认带 FLAG_NOT_FOCUSABLE（不抢游戏的输入焦点），代价是里面的 EditText 打不了字。
     * 只有边距输入框真的聚焦时才临时摘掉这个 flag，失焦 / 收起面板立刻装回去。
     */
    private fun setWindowFocusable(on: Boolean) {
        val p = params ?: return
        val noFocus = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val flags = if (on) p.flags and noFocus.inv() else p.flags or noFocus
        if (flags == p.flags) return
        p.flags = flags
        // 输入法顶上来时把面板往上让一让，别盖住输入框
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        runCatching { wm.updateViewLayout(root, p) }
    }

    private fun imm(): InputMethodManager? =
        ctx.getSystemService(InputMethodManager::class.java)

    private fun hideIme() {
        val f = marginField ?: return
        runCatching { imm()?.hideSoftInputFromWindow(f.windowToken, 0) }
    }

    /** 不带符号的整数写法（基准像素用）。 */
    private fun n0(v: Float): String = String.format(Locale.US, "%.0f", v)

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

    private fun hintText(): String =
        if (customOn && handSel.isNotEmpty()) "牌型和自定义一起判定，成任意一个就停" else ""

    /** 底部摘要：用和开跑时同一个目标对象，优先级、牌型名都不会跟实际跑的差开。 */
    private fun summaryView(): TextView = Ui.text(
        ctx,
        "当前：" + (currentTarget()?.toString() ?: "未选择"),
        10.5f, Ui.DIM2,
    ).apply {
        setPadding(0, px(6), 0, px(2))
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun start() {
        val svc = CaptureService.instance
        if (svc == null) {
            BotLog.add("[!] 服务没在跑，先回 App 授权抓屏")
            return
        }
        if (handSel.isEmpty() && (!customOn || groups.isEmpty())) {
            BotLog.add("[!] 还没选目标：点开「牌型 / 自定义」勾一个")
            return
        }
        svc.startBotFromUi(specs(), handNames(), ordered)
        collapse()
    }

    // ------------------------------------------------------------ 控件

    /** 格子：固定宽度，选中是绿底 + 绿描边，按下更亮一档。 */
    private fun cell(text: String, w: Int, on: Boolean = false, onClick: () -> Unit): TextView =
        Ui.text(ctx, text, 12.5f, if (on) Ui.INK else Ui.DIM2).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isSelected = on
            background = Ui.pressable(0x1AFFFFFF, 0x4D2FBF71, 0, 0, 0x332FBF71, Ui.GREEN, px(1))
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
            val c = cell(text, cellW, on = selected(i)) { onPick(i) }
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            c.layoutParams = LinearLayout.LayoutParams(px(cellW), px(32)).apply {
                marginEnd = px(4)
                topMargin = px(3)
            }
            row?.addView(c)
        }
    }

    /** 通栏按钮（细线），用在「试认 / 再加一组 / 返回」。 */
    private fun wideButton(text: String, onClick: () -> Unit): TextView =
        secondaryButton(text, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(34),
            ).apply { topMargin = px(5) }
        }

    /** 次要按钮：细线描边、次要字色，按下变亮。 */
    private fun secondaryButton(text: String, onClick: () -> Unit): TextView =
        Ui.text(ctx, text, 12.5f, Ui.DIM2).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = Ui.pressable(0x0DFFFFFF, Ui.PRESS_N, Ui.LINE2, px(1))
            setOnClickListener { onClick() }
        }

    /** 主操作（开始）：填深绿 + 亮绿描边，比别的按钮显眼一档。 */
    private fun primaryButton(text: String, onClick: () -> Unit): TextView =
        Ui.text(ctx, text, 14f, 0xFFEAF6EF.toInt()).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            isClickable = true
            background = Ui.pressable(Ui.GREEN_DK, Ui.GREEN, Ui.GREEN, px(1))
            setOnClickListener { onClick() }
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
                    // 按下就变一点透明度，手指底下有反馈
                    ball.alpha = 0.72f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > px(6) || Math.abs(dy) > px(6)) moved = true
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(root, p) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ball.alpha = 1f
                    if (e.action == MotionEvent.ACTION_UP && !moved) toggle()
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
        // 收面板时把输入焦点还给游戏，不然游戏里点哪都不响应
        marginField?.clearFocus()
        hideIme()
        setWindowFocusable(false)
        panel?.visibility = View.GONE
        params?.let { runCatching { wm.updateViewLayout(root, it) } }
    }

    private val poll = object : Runnable {
        override fun run() {
            val svc = CaptureService.instance
            val running = svc?.isBotRunning() == true
            headText?.apply {
                text = if (running) "运行中" else "空闲"
                setTextColor(if (running) Ui.GREEN else Ui.DIM)
            }
            headDot?.let { Ui.paint(it, if (running) Ui.GREEN else Ui.DIM) }
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

        /** 页签顺序：主 / 牌型 / 自定义 / 敌人。 */
        val TABS = listOf("主", "牌型", "自定义", "敌人")

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

/**
 * 悬浮球里选的目标：主界面也读这一份，免得两处各记一套。
 *
 * 只在当前进程里有效 —— 和 1.2.0 的行为一致：App 重启后目标回到默认那一组
 * （自定义「3 2 5」），不会莫名其妙换掉上一次的选择。悬浮球每次重画都会
 * 把最新的选择写进来，主界面的「目标」行和「开始」按钮读的就是它。
 */
object Selection {

    /** 悬浮球还没开过时用的默认目标（和 1.1.0 一致：自定义 3 2 5）。 */
    private val DEFAULT_SPECS = listOf("3 2 5")

    @Volatile private var hands: List<String> = emptyList()
    @Volatile private var specs: List<String> = DEFAULT_SPECS
    @Volatile private var orderedFlag = false

    /** 悬浮球重画时把最新的选择写进来。 */
    fun publish(h: List<String>, s: List<String>, o: Boolean) {
        hands = h
        specs = s
        orderedFlag = o
    }

    fun hands(): List<String> = hands

    fun specs(): List<String> = specs

    fun ordered(): Boolean = orderedFlag

    /** 一行摘要：和开跑时用的是同一个 Targets.combine，显示和实际不会差开。 */
    fun label(): String {
        val t = runCatching { Targets.combine(hands, specs, orderedFlag) }.getOrNull()
        return t?.toString() ?: ""
    }
}