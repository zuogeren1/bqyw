package io.github.zuogeren.bqyw

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/** 主界面：填目标 → 授权抓屏 → 开始；日志直接显示在下面。 */
class MainActivity : Activity() {


    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val svc = CaptureService.instance
            val capture = if (svc?.hasProjection() == true) "就绪" else "未授权"
            val tap = if (Tap.ready()) Tap.current() else "无（② 或 ③ 开一个）"
            val bot = if (svc?.isBotRunning() == true) "运行中" else "空闲"
            status.text = "抓屏：" + capture + "   点按：" + tap + "   机器人：" + bot
            logView.text = BotLog.tail(12).joinToString("\n")
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CardReader.load(assets)
        Badge.load(assets)


        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 24, 0, 8)
        }
        logView = TextView(this).apply { textSize = 12f }


        // targetSdk 35+ 强制边到边：内容会画到状态栏底下，自己把 inset 补回来
        val topInset = systemDimen("status_bar_height") + dp(16)
        val bottomInset = systemDimen("navigation_bar_height") + dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), topInset, dp(14), bottomInset)
            gravity = Gravity.TOP
        }
        root.addView(TextView(this).apply {
            text = "目标在悬浮球里设置：进游戏后点悬浮球 → 牌型 / 自定义（可多选）→ 开始"
            textSize = 14f
            setPadding(0, 0, 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "点按方式：② 和 ③ 开一个就能用，同时开着用 ②"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(8))
        })

        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("① 授权抓屏") { requestCapture() }
        button("② 无障碍点按") { openAccessibility() }
        button("③ 授权 Shizuku") { requestShizuku() }
        button("④ 授权悬浮窗") { requestOverlay() }
        button("⑤ 显示悬浮球") { showOverlay() }
        button("⑥ 停止") { stopBot() }

        root.addView(status)
        root.addView(logView)
        setContentView(root)
        // 边到边之后状态栏图标默认是白的，白底上看不见；要等窗口贴上装饰视图才能设
        window.decorView.post { lightStatusBar() }
        handler.post(poll)
    }

    private fun openAccessibility() {
        if (TapService.isReady()) {
            BotLog.add("授权：无障碍服务 " + Auth.a11y() + "，不用再开")
            return
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            BotLog.add(
                "授权：无障碍服务 " + Auth.a11y() +
                    "，去设置列表里找到「" + getString(R.string.app_name) + "」打开它",
            )
        } catch (e: Throwable) {
            BotLog.add("[!] 打不开无障碍设置：" + e.javaClass.simpleName)
        }
    }

    private fun requestOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            BotLog.add("授权：悬浮窗 " + Auth.overlay(this) + "，不用再开")
            showOverlay()
            return
        }
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + packageName),
                )
            )
            BotLog.add("授权：悬浮窗 未允许，去设置里打开「显示在其他应用上层」")
        } catch (e: Throwable) {
            BotLog.add("[!] 打不开悬浮窗设置：" + e.javaClass.simpleName)
        }
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            BotLog.add("授权：悬浮窗 " + Auth.overlay(this))
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_SHOW_OVERLAY
        }
        startForegroundService(intent)
        BotLog.add("悬浮球已显示（拖动移动，点一下展开）")
    }

    private fun lightStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        } catch (_: Throwable) {
            // 设不上就保持默认，不影响使用
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun systemDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            BotLog.add("授权：Shizuku " + Auth.shizuku())
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuLink.bind(packageName)
            BotLog.add("授权：Shizuku 已授权，正在连接用户服务")
        } else {
            BotLog.add("授权：Shizuku 未授权，正在申请")
            Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }



    private fun stopBot() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP_BOT
        }
        startForegroundService(intent)
        BotLog.add("停止：下发停止指令")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SHIZUKU) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            BotLog.add("授权：Shizuku " + (if (ok) "已授权" else "未授权（被拒绝）"))
            if (ok) ShizukuLink.bind(packageName)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            BotLog.add("授权：抓屏（MediaProjection） 已取消")
            return
        }
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        BotLog.add("授权：抓屏（MediaProjection） 已授权")
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
        const val REQ_SHIZUKU = 3
        const val GAME_PKG = "com.hypergryph.endfield"
        const val GAME_ACTIVITY = "com.u8.sdk.U8UnityContext"
    }
}
