package io.github.zuogeren.bqyw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.Inflater

/**
 * 模板库的 JVM 单测（不碰 Android API）：
 *
 *  1. 25 个模板能读出来，json 里每条 `art` 都真有文件；
 *  2. 每个模板和**自己**的 NCC == 1.0（特征归一化没写错）；
 *  3. 不同敌人两两之间的最高 NCC < 0.7（阈值 0.78 下面要留出空当）。
 *
 * 模板直接从源码树读（assets 不会进 JVM 单测的 classpath）。不引第三方：
 * AGP 的单测编译路径里没有 javax.imageio，所以这里自带一个最小 PNG 解码
 * （8 位、非隔行，够本仓库的模板用），灰度系数和 Android 侧一样走 [EnemyFeat.luma]，
 * 这组数字和真机同源。
 *
 * 跑法：`gradle testReleaseUnitTest`。
 * 坑：仓库路径含中文时，daemon 带着 [-Dfile.encoding=UTF-8] 会把 worker 的
 * classpath @argfile 按 UTF-8 写、JVM 却按系统代码页读，路径解码坏掉，
 * 表现是 "ClassNotFoundException: ...EnemiesTest"。这种机器上加一句
 * `-Dorg.gradle.jvmargs="-Xmx2048m"` （把 file.encoding 摘掉）就正常了。
 */
class EnemiesTest {

    private val dir: File by lazy { findAssets() }

    /** 从 user.dir 往上找 src/main/assets/enemies（Gradle 单测的工作目录是模块目录）。 */
    private fun findAssets(): File {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            for (rel in listOf("src/main/assets/enemies", "app/src/main/assets/enemies")) {
                val f = File(d, rel)
                if (File(f, "enemies.json").isFile) return f
            }
            d = d.parentFile
        }
        throw IllegalStateException(
            "找不到 assets/enemies（user.dir=" + System.getProperty("user.dir") + "）",
        )
    }

    private fun artFiles(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".png") }
            .sortedBy { it.name }

    /** PNG → 灰度 → 128×128 → 减均值 → L2 归一化。 */
    private fun feature(file: File): FloatArray {
        val img = Png.decode(file)
        val gray = FloatArray(img.w * img.h)
        for (i in gray.indices) gray[i] = EnemyFeat.luma(img.px[i])
        return EnemyFeat.of(gray, img.w, img.h)
            ?: throw AssertionError(file.name + " 归一化失败（整块纯色？）")
    }

    @Test
    fun templatesLoad() {
        val files = artFiles()
        val json = File(dir, "enemies.json").readText(Charsets.UTF_8)
        val arts = Regex("\"art\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(json).map { it.groupValues[1] }.toList()
        assertEquals("模板 PNG 数量", 25L, files.size.toLong())
        assertEquals("json 里的敌人数量", 25L, arts.size.toLong())
        assertEquals("文件名不重复", 25, arts.toSet().size)
        for (a in arts) {
            assertTrue("模板文件缺了：" + a, File(dir, a).isFile)
        }
        // 每个模板都是 128×128，和 PC 版取模板时的归一化尺寸一致
        for (f in files) {
            val img = Png.decode(f)
            assertEquals(f.name + " 宽", 128, img.w)
            assertEquals(f.name + " 高", 128, img.h)
        }
    }

    @Test
    fun everyTemplateMatchesItself() {
        val files = artFiles()
        assertTrue("没有模板可测", files.isNotEmpty())
        for (f in files) {
            val v = feature(f)
            val d = EnemyFeat.dot(v, v)
            assertEquals(f.name + " 自比应该正好 1.0", 1.0, d.toDouble(), 1e-3)
        }
    }

    @Test
    fun differentEnemiesStayBelowThreshold() {
        val feats = artFiles().map { it.name to feature(it) }
        var worst = 0.0
        var pair = "—"
        for (i in feats.indices) {
            for (j in i + 1 until feats.size) {
                val s = EnemyFeat.dot(feats[i].second, feats[j].second).toDouble()
                if (s > worst) {
                    worst = s
                    pair = feats[i].first + " / " + feats[j].first
                }
            }
        }
        println("不同敌人最高相似度：" + pair + " = " + String.format(Locale.US, "%.4f", worst))
        assertTrue("不同敌人最高 " + worst + "（" + pair + "）不该到 0.7", worst < 0.7)
        assertTrue(
            "异敌相似度离阈值 " + Enemies.MIN_NCC + " 太近，真机会误判",
            worst < Enemies.MIN_NCC - 0.05,
        )
    }

    /**
     * 立绘框常量钉死。
     *
     * 2800×1260 的安卓实机截图（refs/安卓端-自定义边距-0.jpg）上量出来的卡面是
     * x 360…653、y 411…626；换成「相对卡槽数字中心」就是 x -32…+261、y 411…626。
     * 谁要动这几个常量，这条会先红 —— 别凭感觉改。
     */
    @Test
    fun artBoxMatchesMeasuredCardFace() {
        val fit = Fit(2800, 1260)
        val cx = CardReader.CX0
        assertEquals("立绘框左", 360L, fit.px(cx + Enemies.ART_DX0).toLong())
        assertEquals("立绘框右", 653L, fit.px(cx + Enemies.ART_DX1).toLong())
        assertEquals("立绘框上", 411L, fit.py(Enemies.ART_DY0).toLong())
        assertEquals("立绘框下", 626L, fit.py(Enemies.ART_DY1).toLong())
        // 20:9 的机器上缩放就是 宽/2800、没有黑边；换个尺寸也按同一套等比换算
        assertEquals(0f, fit.ox, 1e-3f)
        assertEquals(0f, fit.oy, 1e-3f)
        val small = Fit(2400, 1080)
        assertEquals(0f, small.ox, 1e-3f)
        assertEquals(0f, small.oy, 1e-3f)
        assertEquals(
            "缩放后的左边界",
            Math.round(360f * small.s).toLong(),
            small.px(cx + Enemies.ART_DX0).toLong(),
        )
    }

    @Test
    fun flatImageCannotBeRecognized() {
        // 纯色块没有纹理：必须返回 null，而不是给出一个和谁都像的假分数
        assertEquals(null, EnemyFeat.of(FloatArray(64 * 64) { 128f }, 64, 64))
        val v = feature(artFiles().first())
        assertEquals(EnemyFeat.CANON * EnemyFeat.CANON, v.size)
    }

    /**
     * 最小 PNG 解码：8 位、非隔行，色型支持 灰度 / RGB / RGBA。
     *
     * 只给单测用 —— 真机走 BitmapFactory，不进这条路径。
     */
    private class Png(val w: Int, val h: Int, val px: IntArray) {

        companion object {
            fun decode(file: File): Png {
                val data = file.readBytes()
                require(data.size > 8 && (data[0].toInt() and 0xFF) == 0x89) { "不是 PNG：" + file.name }
                var pos = 8
                var w = 0
                var h = 0
                var depth = 0
                var ctype = 0
                var interlace = 0
                val idat = ByteArrayOutputStream()
                while (pos + 8 <= data.size) {
                    val len = be32(data, pos)
                    val type = String(data, pos + 4, 4, Charsets.US_ASCII)
                    val body = pos + 8
                    when (type) {
                        "IHDR" -> {
                            w = be32(data, body)
                            h = be32(data, body + 4)
                            depth = data[body + 8].toInt() and 0xFF
                            ctype = data[body + 9].toInt() and 0xFF
                            interlace = data[body + 12].toInt() and 0xFF
                        }
                        "IDAT" -> idat.write(data, body, len)
                    }
                    pos = body + len + 4
                    if (type == "IEND") break
                }
                require(depth == 8) { file.name + "：只支持 8 位 PNG（现在 " + depth + "）" }
                require(interlace == 0) { file.name + "：不支持隔行 PNG" }
                val bpp = when (ctype) {
                    0 -> 1
                    2 -> 3
                    6 -> 4
                    else -> throw IllegalArgumentException(file.name + "：不支持的 colorType " + ctype)
                }
                val stride = w * bpp
                val need = h * (1 + stride)
                val raw = ByteArray(need)
                val inf = Inflater()
                try {
                    inf.setInput(idat.toByteArray())
                    var n = 0
                    while (n < need && !inf.finished()) {
                        val k = inf.inflate(raw, n, need - n)
                        if (k == 0 && inf.needsInput()) break
                        n += k
                    }
                    require(n == need) { file.name + "：解压出 " + n + " 字节，应该是 " + need }
                } finally {
                    inf.end()
                }
                val cur = ByteArray(stride)
                val prev = ByteArray(stride)
                val px = IntArray(w * h)
                var src = 0
                for (y in 0 until h) {
                    val filter = raw[src++].toInt() and 0xFF
                    System.arraycopy(raw, src, cur, 0, stride)
                    src += stride
                    unfilter(filter, cur, prev, bpp, stride)
                    for (x in 0 until w) {
                        val i = x * bpp
                        val r: Int
                        val g: Int
                        val b: Int
                        if (bpp == 1) {
                            r = cur[i].toInt() and 0xFF
                            g = r
                            b = r
                        } else {
                            r = cur[i].toInt() and 0xFF
                            g = cur[i + 1].toInt() and 0xFF
                            b = cur[i + 2].toInt() and 0xFF
                        }
                        px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    System.arraycopy(cur, 0, prev, 0, stride)
                }
                return Png(w, h, px)
            }

            private fun be32(d: ByteArray, p: Int): Int =
                ((d[p].toInt() and 0xFF) shl 24) or
                    ((d[p + 1].toInt() and 0xFF) shl 16) or
                    ((d[p + 2].toInt() and 0xFF) shl 8) or
                    (d[p + 3].toInt() and 0xFF)

            private fun paeth(a: Int, b: Int, c: Int): Int {
                val p = a + b - c
                val pa = Math.abs(p - a)
                val pb = Math.abs(p - b)
                val pc = Math.abs(p - c)
                return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
            }

            private fun unfilter(f: Int, cur: ByteArray, prev: ByteArray, bpp: Int, stride: Int) {
                when (f) {
                    0 -> {}
                    1 -> for (i in bpp until stride) {
                        cur[i] = ((cur[i].toInt() and 0xFF) + (cur[i - bpp].toInt() and 0xFF)).toByte()
                    }
                    2 -> for (i in 0 until stride) {
                        cur[i] = ((cur[i].toInt() and 0xFF) + (prev[i].toInt() and 0xFF)).toByte()
                    }
                    3 -> for (i in 0 until stride) {
                        val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                        val b = prev[i].toInt() and 0xFF
                        cur[i] = ((cur[i].toInt() and 0xFF) + (a + b) / 2).toByte()
                    }
                    4 -> for (i in 0 until stride) {
                        val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                        val b = prev[i].toInt() and 0xFF
                        val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                        cur[i] = ((cur[i].toInt() and 0xFF) + paeth(a, b, c)).toByte()
                    }
                    else -> throw IllegalArgumentException("未知 PNG 过滤器 " + f)
                }
            }
        }
    }
}
