package com.powerring.hole.core

import de.robv.android.xposed.XposedBridge

/**
 * 统一日志：同时写入 LSPosed 日志与 logcat（TAG 固定，便于 adb logcat -s HolePowerRing）。
 */
object ModuleLog {
    private const val TAG = "HolePowerRing"

    fun i(msg: String) {
        XposedBridge.log("$TAG: $msg")
        android.util.Log.i(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        XposedBridge.log("$TAG: $msg")
        if (t != null) XposedBridge.log(t)
        android.util.Log.e(TAG, msg, t)
    }
}
