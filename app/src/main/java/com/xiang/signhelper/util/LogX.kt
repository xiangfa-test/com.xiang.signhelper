package com.xiang.signhelper.util

import android.util.Log

/**
 * 统一日志出口。
 *
 * ## 两路输出
 *
 * 1. **LSPosed 日志**（`XposedModule#log`）—— 用户排查问题时的第一去处；
 * 2. **logcat**（`android.util.Log`，tag = [TAG]）—— 没有 root 的设备读不到
 *    LSPosed 日志文件，但 `adb logcat -s SignHelper` 谁都能看。
 *
 * 热路径（每次 `getPackageInfo` 都会进 hook）里**不能**无条件打 INFO ——
 * 命中伪造的日志由调用方自己做一次性节流，这里只提供出口。
 */
object LogX {

    /** logcat 过滤用的 tag：`adb logcat -s SignHelper:*` */
    const val TAG = "SignHelper"

    fun i(message: String) = emit(Log.INFO, message)

    fun w(message: String) = emit(Log.WARN, message)

    fun e(message: String, t: Throwable? = null) = emit(Log.ERROR, message, t)

    private fun emit(priority: Int, message: String, tr: Throwable? = null) {
        val text = if (tr == null) message else "$message\n${Log.getStackTraceString(tr)}"
        // 1) logcat：任何进程、任何设备都能用 adb 看
        runCatching { Log.println(priority, TAG, text) }
        // 2) LSPosed 日志：被注入进程里才有 module 实例
        runCatching { HookEnv.module?.log(priority, TAG, text) }
    }
}
