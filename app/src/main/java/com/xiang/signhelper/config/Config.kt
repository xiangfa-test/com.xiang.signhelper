package com.xiang.signhelper.config

import android.content.Context
import android.content.SharedPreferences
import com.xiang.signhelper.util.HookEnv
import com.xiang.signhelper.util.LogX
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 统一的配置读写层。
 *
 * ## 两个进程、两份存储
 *
 * | 谁 | 读哪一份 | 写哪一份 |
 * | --- | --- | --- |
 * | **模块进程**（用户点开的模块界面） | 本 APK 的 SharedPreferences（[local]） | [local] + **框架的可写镜像**（[remoteWritable]） |
 * | **被注入进程**（微信 / QQ） | `XposedInterface#getRemotePreferences` —— 只读镜像 | 写不了（只读，`edit()` 会抛异常），本模块也没有"注入进程改配置"的需求 |
 *
 * ## 关键：模块进程必须用 `io.github.libxposed.service` 那套 API
 *
 * 只写本 APK 的 SharedPreferences 是**没用**的：被注入进程读的是 LSPosed daemon
 * 里那份，而它只会被 `XposedService.updateRemotePreferences` 更新 —— 也就是必须
 * 通过 [XposedServiceHelper] 拿到 [XposedService]，再用
 * `service.getRemotePreferences(group)`（**可写**）写一次。
 *
 * 不这么做的表现是：模块界面里一切正常，但注入进程拿到的永远是空 map，
 * 于是伪造签名一条都不生效。
 *
 * ## 只用 String / Boolean / Int / Long
 *
 * libxposed 的远程 preferences 对 `StringSet` 的兼容性在各框架实现里并不一致，
 * 所以本模块的键值约定里**没有集合类型**：
 *
 *  - [Key.ENTRIES_JSON]：UI 的完整条目列表（JSON array 文本），只有 UI 读；
 *  - [Key.FORGED_PACKAGES]：已启用的目标包名，换行分隔，`""` 表示空；
 *  - [Key.SIG_PREFIX]`<包名>`：该包的伪造签名 hex。
 *
 * 本对象里所有读写都**不抛异常** —— 它会被 UI 线程直接调用。
 */
object Config {

    /** 远程 preferences 的 group 名，注入进程按同一个名字读。 */
    const val GROUP = "signhelper_config"

    /** 模块进程的本地副本（没有框架时也是唯一真值）。 */
    @Volatile
    private var local: SharedPreferences? = null

    /**
     * 模块进程从框架拿到的**可写**镜像。
     *
     * 写它 = 写 LSPosed daemon 里那份 = 被注入进程下一次读到的值。
     */
    @Volatile
    private var remoteWritable: SharedPreferences? = null

    /** 注入进程读到的只读镜像。 */
    @Volatile
    private var remoteReadOnly: SharedPreferences? = null

    @Volatile
    private var listenerRegistered = false

    /**
     * 模块进程初始化（必须在 `Application#onCreate` 里调）。
     *
     * 这里除了本地 SharedPreferences 还要把框架服务接上 —— 不接上，后面所有写入
     * 都传不到被注入进程。
     */
    fun init(context: Context) {
        val appContext = context.applicationContext ?: context
        local = appContext.getSharedPreferences(GROUP, Context.MODE_PRIVATE)
        bindFrameworkService()
    }

    /**
     * 挂上 [XposedServiceHelper]。
     *
     * 服务是异步绑定的：界面可能在绑定完成之前就来读写了，所以 [onServiceBind]
     * 里要把本地已有的值整体推一遍（[pushAllToFramework]），既补上先前的写入，
     * 也完成"本地 -> 框架"的迁移。
     */
    private fun bindFrameworkService() {
        if (listenerRegistered) return
        listenerRegistered = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    val prefs = runCatching { service.getRemotePreferences(GROUP) }
                        .onFailure { LogX.w("拿框架远程 preferences 失败：${it.message}") }
                        .getOrNull() ?: return
                    remoteWritable = prefs
                    LogX.i("已接上框架的可写远程 preferences（group=$GROUP），开始同步本地配置")
                    pushAllToFramework()
                }

                override fun onServiceDied(service: XposedService) {
                    remoteWritable = null
                    LogX.w("框架服务断开，暂停远程写入（本地仍然写）")
                }
            })
        }.onFailure { LogX.e("注册框架服务监听失败", it) }
    }

    /** 把本地那份整体推给框架。 */
    private fun pushAllToFramework() {
        val src = local ?: return
        val dst = remoteWritable ?: return
        runCatching {
            val editor = dst.edit()
            for ((key, value) in src.all) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    else -> continue
                }
            }
            // 必须 commit：界面写完后用户会立刻冷启动微信 / QQ，异步落盘可能丢。
            editor.commit()
            LogX.i("已把本地配置推给框架（${src.all.size} 项）")
        }.onFailure { LogX.w("推送本地配置失败：${it.message}") }
    }

    /** 当前进程能不能落盘（注入进程为 `false`）。 */
    val writable: Boolean
        get() = local != null || remoteWritable != null

    /**
     * 当前进程读哪一份。
     *
     * 注入进程优先用远程（框架那份才是真值）；模块进程用本地 —— 本地是它自己刚写下
     * 的，立刻可读，不受框架绑定时机影响。
     */
    private fun prefs(): SharedPreferences? {
        local?.let { return it }
        remoteReadOnly?.let { return it }
        HookEnv.module?.let { module ->
            runCatching { module.getRemotePreferences(GROUP) }
                .onSuccess { remoteReadOnly = it }
                .onFailure { LogX.w("remotePreferences unavailable: ${it.message}") }
        }
        return remoteReadOnly
    }

    // ---------------------------------------------------------------- 泛型读写

    fun getString(key: String, def: String? = null): String? =
        runCatching { prefs()?.getString(key, def) ?: def }.getOrDefault(def)

    /**
     * 写入一个键。[value] 为 `null` 表示 remove。
     *
     * - **模块进程**：写本地 + 写框架的可写镜像（后者才是被注入进程看的那一份）。
     * - **注入进程**：本地与可写镜像都没有 → 直接返回 `false`（只读镜像不碰）。
     *
     * @return 是否至少有一份写成功。
     */
    fun set(key: String, value: Any?): Boolean {
        val localPrefs = local
        val remotePrefs = remoteWritable
        if (localPrefs == null && remotePrefs == null) {
            // 注入进程只有只读镜像，edit() 会抛 UnsupportedOperationException，
            // 而且是在宿主主线程上抛 —— 所以这里绝不能尝试写。
            LogX.w("Config.set($key) 跳过：当前进程不可写")
            return false
        }
        var ok = false
        if (localPrefs != null) ok = writeInto(localPrefs, key, value) || ok
        if (remotePrefs != null) {
            ok = writeInto(remotePrefs, key, value) || ok
        } else {
            // 本地写成功、但推不到框架 = 注入进程永远读不到 → 功能不会生效。
            LogX.w("[WARN] 框架服务未绑定，$key 只写到了本地：被注入进程读不到")
        }
        return ok
    }

    /** 往一份具体的 preferences 里写一个键；失败只记日志。 */
    private fun writeInto(target: SharedPreferences, key: String, value: Any?): Boolean {
        val editor = runCatching { target.edit() }.getOrNull() ?: return false
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            // 其余类型（集合等）本模块不使用，转成字符串兜底，避免静默丢键。
            else -> editor.putString(key, value.toString())
        }
        return runCatching { editor.commit() }.getOrElse {
            LogX.e("Config.set($key) 失败", it)
            false
        }
    }

    /** 当前可见的全部键值（注入进程里是只读镜像的内容）。 */
    fun all(): Map<String, Any?> {
        val p = prefs() ?: return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, Any?>()
            for ((key, value) in p.all) out[key] = value
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * 本模块用到的键。
     *
     * 注意 `FORGED_PACKAGES` / `SIG_PREFIX` 是**注入进程读的那份**契约，
     * 改名等于换协议，[com.xiang.signhelper.hook.MainHook] 会一起坏掉。
     */
    object Key {
        /** 完整条目列表（JSON array 文本），只有 UI 读。 */
        const val ENTRIES_JSON = "entries_json"

        /** 已启用的目标包名，`\n` 分隔；`""` 表示空。 */
        const val FORGED_PACKAGES = "forged_packages"

        /** 前缀：`sig:<包名>` = 该包的伪造签名 hex。 */
        const val SIG_PREFIX = "sig:"
    }
}
