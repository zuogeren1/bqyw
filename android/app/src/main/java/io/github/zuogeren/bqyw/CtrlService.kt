package io.github.zuogeren.bqyw

import android.os.Binder
import android.os.Parcel

/**
 * 跑在 Shizuku 的 shell 进程里的用户服务。
 *
 * 注意：Shizuku 的用户服务不是 Android 的 Service —— 它把这个类直接当 IBinder 用，
 * 所以必须继承 Binder（文档里的写法是 extends IUserService.Stub）。
 * 它也不需要写进 manifest。
 *
 * 这里拿到的是 shell 身份，所以能直接调 input tap —— 和 PC 版「管理员权限」是一回事。
 * 故意不用 AIDL：AGP 的 aidl 任务在中文路径下会挂，而这个仓库的目录名就是中文。
 */
class CtrlService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TX_TAP) {
            data.enforceInterface(DESCRIPTOR)
            val x = data.readInt()
            val y = data.readInt()
            Runtime.getRuntime()
                .exec(arrayOf("input", "tap", x.toString(), y.toString()))
                .waitFor()
            reply?.writeNoException()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        const val DESCRIPTOR = "io.github.zuogeren.bqyw.Ctrl"
        const val TX_TAP = 1
    }
}
