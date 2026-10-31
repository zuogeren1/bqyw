package io.github.zuogeren.bqyw

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * 授权现状：日志里统一写成「授权：<项> <状态>」。
 *
 * 只报代码里真实存在的四项：无障碍、悬浮窗、抓屏、Shizuku。
 * 开跑前打一遍，缺哪一项一眼能看出来。
 */
object Auth {

    /** 无障碍服务（点按方式 ②）。 */
    fun a11y(): String = if (TapService.isReady()) "已开启" else "未开启"

    /** 悬浮窗：悬浮球靠它显示。 */
    fun overlay(ctx: Context): String =
        if (Settings.canDrawOverlays(ctx)) "已允许" else "未允许"

    /** 抓屏（MediaProjection）：服务里拿到 ImageReader 才算数。 */
    fun capture(): String =
        if (CaptureService.instance?.hasProjection() == true) "已授权" else "未授权"

    /** Shizuku（点按方式 ③）：服务没跑、没授权、用户服务没连上，分三种说清楚。 */
    fun shizuku(): String = try {
        when {
            !Shizuku.pingBinder() -> "服务未运行"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "未授权"
            ShizukuLink.ready() -> "已连接"
            else -> "已授权"
        }
    } catch (_: Throwable) {
        "不可用"
    }

    /** 四项一次打完。 */
    fun log(ctx: Context) {
        BotLog.add("授权：无障碍服务 " + a11y())
        BotLog.add("授权：悬浮窗 " + overlay(ctx))
        BotLog.add("授权：抓屏（MediaProjection） " + capture())
        BotLog.add("授权：Shizuku " + shizuku())
    }
}
