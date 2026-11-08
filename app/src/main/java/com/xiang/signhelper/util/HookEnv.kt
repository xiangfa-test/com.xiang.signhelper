package com.xiang.signhelper.util

import io.github.libxposed.api.XposedModule

/**
 * 单进程内共享的运行时状态。
 *
 * 现代 Xposed API 把 [XposedModule] 实例交给模块，而不是老式的静态桥，
 * 所以框架实例收拢在这里，供日志与远程 preferences 使用。
 */
object HookEnv {

    @Volatile
    var module: XposedModule? = null
        private set

    @Volatile
    private var processName: String? = null

    /**
     * 挂载模块实例。
     *
     * 进程名走 [processName] 参数而不是问 [XposedModule] —— API 102 里进程名挂在
     * `ModuleLoadedParam#getProcessName()` 上，`XposedModule` 本身没有这个属性。
     */
    fun attach(instance: XposedModule, processName: String? = null) {
        module = instance
        if (!processName.isNullOrBlank()) {
            this.processName = processName
        }
        if (this.processName == null) {
            this.processName = readProcessNameFromProc()
        }
    }

    /**
     * 从 `/proc/self/cmdline` 读进程名。
     *
     * 兜底手段：`onModuleLoaded` 一定拿得到进程名，但保险起见留一条不依赖框架的路径。
     */
    private fun readProcessNameFromProc(): String? = runCatching {
        java.io.File("/proc/self/cmdline").readBytes()
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }.getOrNull()

    val currentProcessName: String
        get() = processName ?: "?"
}
