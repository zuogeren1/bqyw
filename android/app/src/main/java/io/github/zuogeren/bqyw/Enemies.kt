package io.github.zuogeren.bqyw

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import org.json.JSONObject

/** 模板库里的一个敌人：一张 128×128 立绘、一个战力点（每个敌人只在一个点数出现）。 */
data class Enemy(val slug: String, val name: String, val point: Int, val art: String)

/** 一次比对的结果。分数没到阈值也照样返回 —— 日志要写"最像谁、几分"，排查全靠它。 */
data class EnemyHit(val enemy: Enemy, val score: Float) {
    val name: String get() = enemy.name
    val point: Int get() = enemy.point
}

/**
 * 敌人名单：关 / 黑名单 / 白名单。
 *
 * 口径和 PC 版 bqyw/enemies.py 的 Policy 完全一致：
 *  * **黑名单**：名单里的敌人抽到就弃；
 *  * **白名单**：只留名单里的，其余全弃；
 *  * 名单空着**等于关** —— 不然白名单会把每一手牌都弃掉，纯空转。
 */
data class EnemyPolicy(
    val mode: String = Enemies.MODE_OFF,
    val names: List<String> = emptyList(),
) {

    /** 真的要拦牌吗。关着时 [Enemies.Book] 根本不加载模板，零开销。 */
    fun active(): Boolean =
        names.isNotEmpty() && (mode == Enemies.MODE_BAN || mode == Enemies.MODE_ONLY)

    /** 认出来的这个敌人该不该弃。 */
    fun shouldDrop(name: String): Boolean {
        if (!active()) return false
        val inside = name in names
        return if (mode == Enemies.MODE_BAN) inside else !inside
    }

    /** 悬浮球按钮上的写法：「黑名单 3 个」/「关」。 */
    fun label(): String {
        val off = Enemies.MODE_CN[Enemies.MODE_OFF] ?: "关"
        if (mode == Enemies.MODE_OFF) return off
        val cn = Enemies.MODE_CN[mode] ?: off
        return if (names.isEmpty()) cn + "（空）" else cn + " " + names.size + " 个"
    }

    /** 日志里那一行，和 PC 版同一个写法：「黑名单：精锐行刑人」。 */
    override fun toString(): String {
        if (!active()) return Enemies.MODE_CN[Enemies.MODE_OFF] ?: "关"
        return (Enemies.MODE_CN[mode] ?: "") + "：" + names.joinToString("、")
    }

    companion object {
        /** 关着的名单：常量，省得每处都 new 一个。 */
        val OFF = EnemyPolicy()
    }
}

/**
 * 敌人模板库（25 个 128×128 立绘）、比对和名单相关的常量。
 *
 * 只认立绘、不认名字：
 *  * 立绘在「数据溢出」提级后**不变**，Lv 会变；名字条带 \`×N\` 后缀还是居中排的，
 *    同一敌人两次抽到只有 NCC 0.29~0.56，当模板必漏（PC 版实测，见 .notes）。
 *  * 同一敌人 0.84~0.93、不同敌人最高 0.69 —— 阈值 0.78 两边都留余量，
 *    而且**宁可漏弃、不可误弃**：认不准就当没这个敌人，绝不拿错的手牌去放弃。
 *
 * 比对前先按战力点筛候选（牌头已经读出 1~5 点，每个敌人只在一个点数出现），
 * 25 个缩到 4~8 个，每张牌约 1.7ms。纯 Kotlin，不引 OpenCV。
 */
object Enemies {

    /** assets 下的目录名：enemies/enemies.json + enemies/eNN.png。 */
    const val DIR = "enemies"

    // ---------------------------------------------------------------- 立绘框
    //
    // 基准 2800×1260（和 Geom / Fit / CardReader 同一套）。第 slot 张牌的数字中心
    // cx = CardReader.CX0 + STEP * slot，立绘框 = cx + ART_DX0 … cx + ART_DX1，
    // 纵向是基准坐标 ART_DY0 … ART_DY1（5 张牌同一行，不跟 slot 走）。
    //
    // 这几个数是**量出来的**，不是拍脑袋推的：
    //  * 安卓实机截图（refs/安卓端-自定义边距-0.jpg，2800×1260）上，卡面（立绘铺满
    //    的那一块）是 x 360…653、y 290…684，宽 294 高 395；PC 的卡面是 335×453，
    //    缩放 0.877 —— 和 5 张牌的步进比例（362.75 / 367）对得上；
    //  * PC 的立绘框是卡面里的 y 459…705（相对卡面 139…385），按 0.872 缩下来
    //    就是 y 411…626；横向铺满卡面，也就是 360…653。两边的宽高比都是 1.36；
    //  * 换成"相对数字中心"就是 x -32 … +261。
    //
    // 一开始按"PC 卡宽 347 / 步进 367 ≈ 安卓 343 / 362.75"推过一版
    // x -45…+295、y 423…666：但安卓的卡面只有 294 宽（牌与牌之间的空当比 PC 大），
    // 那版比卡面宽了 46px、还偏下 12px，框进了上方的点数/花色卡头和下方的 Lv 行。
    // 拿 PC 实机截图按同一套几何模拟，那版 NCC 只有 0.61（低于阈值，等于名单失效），
    // 现在这版是 0.99。真机上仍请用"试认"复核一遍，见 CaptureService.probeEnemiesFromUi。
    const val ART_DX0 = -32f
    const val ART_DX1 = 261f
    const val ART_DY0 = 411f
    const val ART_DY1 = 626f

    /** 认定阈值：0.78。PC 实测同敌 0.843~0.929、异敌最高 0.691。 */
    const val MIN_NCC = 0.78f

    /** 一眼定案线：0.78~0.88 之间先记下，下一帧还是同一个敌人才弃。 */
    const val CONFIRM_NCC = 0.88f

    /** 连弃这么多次还没出目标，大概是名单配错了，停手提醒。 */
    const val MAX_DROPS = 20

    /** 试认时扫偏移的范围 / 步长（基准像素）。 */
    const val PROBE_RANGE = 24f
    const val PROBE_STEP = 8f

    const val MODE_OFF = "off"
    const val MODE_BAN = "ban"
    const val MODE_ONLY = "only"

    val MODE_CN = linkedMapOf(MODE_OFF to "关", MODE_BAN to "黑名单", MODE_ONLY to "白名单")

    /** 读 enemies.json（缺文件就是 null）。 */
    internal fun json(assets: AssetManager): JSONObject? = try {
        assets.open(DIR + "/enemies.json").use {
            JSONObject(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Throwable) {
        null
    }

    /** json → 敌人清单，不碰立绘。 */
    internal fun parseAll(doc: JSONObject): List<Enemy> {
        val arr = doc.optJSONArray("enemies") ?: return emptyList()
        val out = ArrayList<Enemy>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val art = o.optString("art", "")
            val name = o.optString("name", "")
            if (art.isEmpty() || name.isEmpty()) continue
            out.add(Enemy(o.optString("slug", ""), name, o.optInt("point", 0), art))
        }
        return out
    }

    /** 只要名字和点数（悬浮球的名单页用，不解码立绘）。 */
    fun catalog(assets: AssetManager): List<Enemy> = json(assets)?.let { parseAll(it) } ?: emptyList()

    private val thumbs = HashMap<String, Bitmap?>()

    /** 名单页的缩略图，按文件名缓存。解不出来就返回 null，界面只显示名字。 */
    fun thumb(assets: AssetManager, art: String): Bitmap? {
        if (thumbs.containsKey(art)) return thumbs[art]
        val bmp = try {
            assets.open(DIR + "/" + art).use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            null
        }
        thumbs[art] = bmp
        return bmp
    }

    /**
     * 模板库 + 比对。
     *
     * 名单关着的时候没人会 new 它（[CaptureService] 就是这么做的），25 张立绘不解码、零开销。
     */
    class Book(private val assets: AssetManager) {

        val enemies = ArrayList<Enemy>()

        private val feats = ArrayList<FloatArray>()
        private val byPoint = HashMap<Int, IntArray>()

        /** 不按点数筛时的全量下标（拿来兜底）。 */
        private val allIdx: IntArray by lazy { IntArray(enemies.size) { it } }

        /** 认定阈值，默认 [MIN_NCC]，以 enemies.json 里的为准。 */
        var threshold = MIN_NCC
            private set

        /** 模板都读到了没。读不到（assets 缺文件）就是 false，名单不生效。 */
        var ready = false
            private set

        init {
            load()
        }

        val size: Int get() = enemies.size

        fun names(): List<String> = enemies.map { it.name }

        /** 某个战力点的候选（每个敌人只在一个点数出现，所以只有 4~8 个）。 */
        fun candidates(point: Int): List<Enemy> =
            (byPoint[point] ?: IntArray(0)).map { enemies[it] }

        private fun load() {
            val doc = json(assets) ?: return
            threshold = doc.optDouble("threshold", MIN_NCC.toDouble()).toFloat()
            val idx = HashMap<Int, MutableList<Int>>()
            for (e in parseAll(doc)) {
                val f = feature(e.art) ?: continue
                idx.getOrPut(e.point) { ArrayList() }.add(enemies.size)
                enemies.add(e)
                feats.add(f)
            }
            byPoint.clear()
            for ((k, v) in idx) byPoint[k] = v.toIntArray()
            ready = enemies.isNotEmpty()
        }

        /** 模板 PNG → 特征。解码不动就当作没有这个模板，跳过就行。 */
        private fun feature(art: String): FloatArray? {
            val bmp = try {
                assets.open(DIR + "/" + art).use { BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) {
                null
            } ?: return null
            return try {
                val g = grayOf(bmp, 0, 0, bmp.width, bmp.height) ?: return null
                EnemyFeat.of(g, bmp.width, bmp.height)
            } finally {
                bmp.recycle()
            }
        }

        /** 抠一块像素转灰度；越界或空块返回 null。 */
        private fun grayOf(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): FloatArray? {
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            try {
                bmp.getPixels(px, 0, w, x, y, w, h)
            } catch (e: Throwable) {
                return null
            }
            val g = FloatArray(px.size)
            for (i in px.indices) g[i] = EnemyFeat.luma(px[i])
            return g
        }

        /**
         * 第 [slot] 张牌的立绘框，实际像素、已裁到画面里。
         *
         * [dx] / [dy] 是**基准坐标**下的偏移，只有"试认"校准的时候不为 0。
         */
        fun rectOf(frame: Bitmap, slot: Int, dx: Float = 0f, dy: Float = 0f): Rect {
            val fit = Fit(frame.width, frame.height)
            val cx = CardReader.CX0 + CardReader.STEP * slot
            var x0 = fit.px(cx + ART_DX0 + dx)
            var x1 = fit.px(cx + ART_DX1 + dx)
            var y0 = fit.py(ART_DY0 + dy)
            var y1 = fit.py(ART_DY1 + dy)
            x0 = x0.coerceIn(0, frame.width - 1)
            x1 = x1.coerceIn(x0 + 1, frame.width)
            y0 = y0.coerceIn(0, frame.height - 1)
            y1 = y1.coerceIn(y0 + 1, frame.height)
            return Rect(x0, y0, x1, y1)
        }

        /** 第 [slot] 张牌最像的敌人。[point] 非 0 时只在该点数的候选里找。 */
        fun best(frame: Bitmap, slot: Int, point: Int): EnemyHit? =
            bestAt(frame, slot, point, 0f, 0f)

        /** 带偏移的比对（试认校准用），返回值和 [best] 一样。 */
        fun bestAt(frame: Bitmap, slot: Int, point: Int, dx: Float, dy: Float): EnemyHit? {
            if (!ready) return null
            val r = rectOf(frame, slot, dx, dy)
            val g = grayOf(frame, r.left, r.top, r.width(), r.height()) ?: return null
            val f = EnemyFeat.of(g, r.width(), r.height()) ?: return null
            val pool = if (point > 0) byPoint[point] else null
            val idx = pool ?: allIdx
            var bestK = -1
            var bestS = -2f
            for (k in idx) {
                val s = EnemyFeat.dot(f, feats[k])
                if (s > bestS) {
                    bestS = s
                    bestK = k
                }
            }
            if (bestK < 0) return null
            return EnemyHit(enemies[bestK], bestS)
        }

        /**
         * 在 ±[range] 内按 [step] 扫一遍立绘框偏移，返回 (最高分, dx, dy)。
         *
         * 真机校准就靠它：框对了分数应该 0.85 以上、最优偏移接近 0；
         * 最优偏移明显不为 0 就说明那几个 ART_D* 常量要跟着挪。
         */
        fun bestOffset(
            frame: Bitmap,
            slot: Int,
            point: Int,
            range: Float = PROBE_RANGE,
            step: Float = PROBE_STEP,
        ): Triple<Float, Float, Float> {
            var bestS = -2f
            var bestX = 0f
            var bestY = 0f
            var dy = -range
            while (dy <= range + 0.001f) {
                var dx = -range
                while (dx <= range + 0.001f) {
                    val hit = bestAt(frame, slot, point, dx, dy)
                    if (hit != null && hit.score > bestS) {
                        bestS = hit.score
                        bestX = dx
                        bestY = dy
                    }
                    dx += step
                }
                dy += step
            }
            return Triple(bestS, bestX, bestY)
        }
    }
}

/**
 * 名单的持久化：SharedPreferences。
 *
 * 存"模式 + 名字列表"，重启 App 还在。名字就是主键 —— 可读可改，
 * 立绘只用来匹配（和 PC 版 config.json 里 enemies 那段同一套字段）。
 */
object EnemyStore {

    private const val FILE = "enemy_policy"
    private const val KEY_MODE = "mode"
    private const val KEY_NAMES = "names"
    private const val SEP = "、"

    fun load(ctx: Context): EnemyPolicy {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val rawMode = sp.getString(KEY_MODE, Enemies.MODE_OFF) ?: Enemies.MODE_OFF
        val mode = if (Enemies.MODE_CN.containsKey(rawMode)) rawMode else Enemies.MODE_OFF
        return EnemyPolicy(mode, parseNames(sp.getString(KEY_NAMES, "") ?: ""))
    }

    fun save(ctx: Context, policy: EnemyPolicy) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, policy.mode)
            .putString(KEY_NAMES, policy.names.joinToString(SEP))
            .apply()
    }

    /** `、` `，` `,` `/` 空格都能分隔 —— 和 PC 版 parse_names 一个口径。 */
    fun parseNames(text: String): List<String> =
        text.split('、', '，', ',', '/', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
