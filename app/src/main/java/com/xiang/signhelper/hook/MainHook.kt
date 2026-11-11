package com.xiang.signhelper.hook

import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.SystemClock
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.util.HookEnv
import com.xiang.signhelper.util.LogX
import com.xiang.signhelper.util.SignatureUtil
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 现代（libxposed API 102）模块入口。
 *
 * 由 `META-INF/xposed/java_init.list` 注册；框架实例化这个类并注入框架接口后
 * 才开始生命周期回调。
 *
 * ## 复刻的行为（原版 `cn.xihan.sign` 2.0.3）
 *
 * 原版用 YukiHookAPI 在作用域应用里挂
 * `android.app.ApplicationPackageManager#getPackageInfo(String, int)` 的**后置**
 * hook：拿到返回的 [PackageInfo]，如果它的 `packageName` 在"已伪造"集合里，
 * 就把 `PackageInfo#signatures` 换成 `arrayOf(Signature(伪造签名))`。
 *
 * 原版逻辑（反编译确认）：
 *
 * ```java
 * for (String str : forgedPackageNames) {
 *     String hex = prefs.getString(str, "");          // 该包的伪造签名
 *     if (packageInfo.packageName.equals(str) && hex.length() > 0) {
 *         packageInfo.signatures = new Signature[]{ new Signature(hex) };
 *     }
 * }
 * ```
 *
 * 本实现一一对应，只是配置来源换成 libxposed 的远程 preferences：
 * `forged_packages`（`\n` 分隔的包名）+ `sig:<包名>`（伪造签名 hex）。
 *
 * ## 只挂两个重载
 *
 *  - `getPackageInfo(String, int)` —— 从 API 1 到现在都有，微信 / QQ 用的就是它；
 *  - `getPackageInfo(String, PackageManager.PackageInfoFlags)` —— API 33+ 的新重载。
 *
 * 两个都在 `ApplicationPackageManager` 里实现（`android.app.ApplicationPackageManager`
 * 是**隐藏类**，公开 SDK 的 android.jar 里没有，所以只能用 `Class.forName` 反查），
 * 找不到就跳过：宿主用哪个我们就覆盖哪个。
 *
 * ## 异常一律 PROTECTIVE
 *
 * hook 内部抛异常不能带走宿主 —— 尤其微信 / QQ 的启动路径上任何崩溃都是灾难。
 * 除了框架层的 [XposedInterface.ExceptionMode.PROTECTIVE]，本类里所有可能失败
 * 的地方也都自行 `runCatching` 兜底了。
 */
class MainHook : XposedModule() {

    /** 一个进程只装一次 hook（onPackageLoaded / onPackageReady 都可能先到）。 */
    private val installed = AtomicBoolean(false)

    /** 伪造表缓存：包名 -> 伪造签名 hex。空 = 当前没有任何伪造项。 */
    @Volatile
    private var forgedSignatures: Map<String, String> = emptyMap()

    /** 已经打过"命中"日志的包名，避免宿主每次 `getPackageInfo` 都刷一条。 */
    private val loggedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 已经打过警告的键，避免坏配置在热路径上刷日志。 */
    private val warnedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 上次重新拉取伪造表的时间（elapsedRealtime）。 */
    @Volatile
    private var lastRefreshAt = 0L

    /** 远程 preferences 的变更监听是否已经挂上。 */
    @Volatile
    private var listenerRegistered = false

    /** 持有监听器强引用 —— SharedPreferences 内部用弱引用存监听器，lambda 不留住会被 GC。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshForgedSignatures()
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookEnv.attach(this, param.processName)
        LogX.i(
            "模块已加载：process=${param.processName} api=$apiVersion framework=$frameworkName $frameworkVersion",
            "Module loaded: process=${param.processName} api=$apiVersion framework=$frameworkName $frameworkVersion",
        )
    }

    /** 包的默认 ClassLoader 出现时触发（API 29+）。 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        HookEnv.attach(this, HookEnv.currentProcessName)
        installForPackage(param.packageName, param.defaultClassLoader)
    }

    /** 真正的 ClassLoader 就绪、Application 即将创建时触发。 */
    override fun onPackageReady(param: PackageReadyParam) {
        HookEnv.attach(this, HookEnv.currentProcessName)
        installForPackage(param.packageName, param.classLoader)
    }

    // ------------------------------------------------------------------ 装 hook

    /**
     * 在任意被注入的包里安装 hook。
     *
     * **不在代码里限定作用域**：哪些应用会被注入，由 LSPosed 里给模块勾选的作用域决定
     * （`META-INF/xposed/scope.list` 只提供默认建议：微信 / QQ）。用户完全可以把
     * 哔哩哔哩、QQ 音乐或任何应用加进作用域——只要宿主应用经由 `PackageManager`
     * 读取了列表里某个包的签名，本模块就替换它。
     *
     * 这样"让 A 应用读取 B 应用的签名时拿到伪造值"就与具体应用名解耦了。
     *
     * 注意：**不能**在这里加载宿主自己的类（应用自己的类在 `onPackageReady`
     * 之后才可用），只碰框架类 `ApplicationPackageManager`，所以没有初始化顺序问题。
     */
    private fun installForPackage(packageName: String, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        LogX.i(
            "命中被注入包：$packageName（进程 ${HookEnv.currentProcessName}）",
            "Hooked package: $packageName (process ${HookEnv.currentProcessName})",
        )
        // 读伪造表 + 顺手挂上变更监听（远程 prefs 若此刻还不可用，会在下次刷新时补挂）
        refreshForgedSignatures()
        installPackageInfoHooks(classLoader)
    }

    /**
     * 在 [PACKAGE_MANAGER_IMPL] 上挂 `getPackageInfo` 的后置 hook。
     *
     * 该类是 `@hide` 的（只存在于 framework，不在公开 android.jar 里），所以用
     * `Class.forName` + 沿继承链找方法，而不是直接引用类型。
     */
    private fun installPackageInfoHooks(classLoader: ClassLoader) {
        val clazz = runCatching { Class.forName(PACKAGE_MANAGER_IMPL, false, classLoader) }.getOrNull()
        if (clazz == null) {
            LogX.e(
                "找不到 $PACKAGE_MANAGER_IMPL，无法挂 getPackageInfo hook",
                "Cannot find $PACKAGE_MANAGER_IMPL; getPackageInfo hooks not installed",
            )
            return
        }

        var count = 0
        val intClass = Int::class.javaPrimitiveType
        if (intClass != null) {
            findMethod(clazz, GET_PACKAGE_INFO, String::class.java, intClass)?.let { method ->
                if (hookAfterPackageInfo(method)) count++
            }
        }

        // API 33+ 的 PackageInfoFlags 重载：老系统上没有这个类，存在才挂。
        val flagsClass = runCatching {
            Class.forName("android.content.pm.PackageManager\$PackageInfoFlags", false, classLoader)
        }.getOrNull()
        if (flagsClass != null) {
            findMethod(clazz, GET_PACKAGE_INFO, String::class.java, flagsClass)?.let { method ->
                if (hookAfterPackageInfo(method)) count++
            }
        }

        if (count == 0) {
            LogX.e(
                "getPackageInfo 一个重载都没挂上（系统版本 / ROM 差异？）",
                "No getPackageInfo overload was hooked (OS/ROM difference?)",
            )
        } else {
            LogX.i(
            "getPackageInfo hook 安装完成：$count 个重载",
            "getPackageInfo hooks installed: $count overloads",
        )
        }
    }

    /** 给一个 `getPackageInfo` 重载挂后置 hook；成功返回 `true`。 */
    private fun hookAfterPackageInfo(method: Method): Boolean = runCatching {
        val flagName = method.parameterTypes.lastOrNull()?.simpleName ?: "unknown"
        hook(method)
            .setId("signhelper-getPackageInfo-$flagName")
            // PROTECTIVE：hook 自身抛异常时只记录日志并放行原调用，绝不带走宿主。
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    return forgeIfNeeded(result)
                }
            })
        LogX.i(
            "已挂 hook：${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString { it.simpleName }})",
            "Hooked: ${method.declaringClass.name}#${method.name}(${method.parameterTypes.joinToString { it.simpleName }})",
        )
        true
    }.getOrElse { throwable ->
        LogX.e("挂 hook 失败：$method", throwable)
        false
    }

    // ------------------------------------------------------------------ 伪造

    /**
     * 后置处理：返回的 [PackageInfo] 命中伪造表就替换 `signatures`。
     *
     * 返回原值 / 新值都走同一条路径 —— 就地改 `signatures` 字段并返回同一个对象，
     * 和原版行为一致（调用方拿到的就是被改过的那份）。
     *
     * 注意 `PackageInfo#signatures` 在 API 33 被标注 deprecated（引导用 `signingInfo`），
     * 但它的字段仍然存在且会被 `GET_SIGNATURES` 填充 —— 微信 / QQ 的老代码路径读的
     * 就是它，所以这里必须照旧用字段赋值。
     */
    @Suppress("DEPRECATION")
    private fun forgeIfNeeded(result: Any?): Any? {
        val info = result as? PackageInfo ?: return result
        val packageName = info.packageName.orEmpty()
        if (packageName.isEmpty()) return result

        if (forgedSignatures.isEmpty()) {
            // 首次读配置可能失败（框架还没把远程 prefs 备好），这里做一次低频重试。
            maybeRefreshForgedSignatures()
        }
        val hex = forgedSignatures[packageName] ?: return result
        val bytes = SignatureUtil.hexToBytes(hex) ?: run {
            warnOnce("hex:$packageName", "伪造签名不是合法 hex，已跳过：$packageName = $hex")
            return result
        }

        return runCatching {
            info.signatures = arrayOf(Signature(bytes))
            if (loggedPackages.add(packageName)) {
                LogX.i(
                    "已伪造签名：$packageName（进程 ${HookEnv.currentProcessName}）",
                    "Forged signature: $packageName (process ${HookEnv.currentProcessName})",
                )
            }
            info
        }.getOrElse { throwable ->
            LogX.e("替换 $packageName 的签名失败", "Failed to replace signature for $packageName", throwable)
            result
        }
    }

    // ------------------------------------------------------------------ 配置

    /** 注入进程看到的远程 preferences（只读）。 */
    private fun remotePreferences(): SharedPreferences? =
        runCatching { getRemotePreferences(Config.GROUP) }.getOrNull()

    /** 从远程 preferences 重建伪造表（顺便确保变更监听已挂上）。 */
    private fun refreshForgedSignatures() {
        val prefs = remotePreferences() ?: run {
            warnOnce("prefs-unavailable", "远程 preferences 不可用，伪造表保持为空")
            return
        }
        // 远程 prefs 在 onPackageLoaded 阶段偶尔还没备好；哪次拿到就在哪次挂监听。
        if (!listenerRegistered) {
            listenerRegistered = runCatching {
                prefs.registerOnSharedPreferenceChangeListener(prefsListener)
                true
            }.getOrElse {
                LogX.w(
                    "注册远程 preferences 监听失败：${it.message}",
                    "Failed to register remote preferences listener: ${it.message}",
                )
                false
            }
        }
        val packed = runCatching { prefs.getString(Config.Key.FORGED_PACKAGES, "") }
            .getOrNull()
            .orEmpty()
        val rebuilt = HashMap<String, String>()
        for (line in packed.split('\n', '\r')) {
            val packageName = line.trim()
            if (packageName.isEmpty()) continue
            val hex = runCatching { prefs.getString(Config.Key.SIG_PREFIX + packageName, "") }
                .getOrNull()
                .orEmpty()
                .trim()
            if (hex.isEmpty()) continue
            rebuilt[packageName] = hex
        }
        val changed = rebuilt != forgedSignatures
        forgedSignatures = rebuilt
        lastRefreshAt = SystemClock.elapsedRealtime()
        if (changed) {
            LogX.i("伪造表已更新：${rebuilt.keys.joinToString().ifEmpty { "（空）" }}")
        }
    }

    /**
     * 低频重试：只在伪造表为空时被热路径调用，最多每 [REFRESH_INTERVAL_MS] 一次。
     *
     * 目的：hook 安装那一刻远程 preferences 偶尔还没备好（框架差异），
     * 之后只要有一次刷新成功，伪造表就能就位。
     */
    private fun maybeRefreshForgedSignatures() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        refreshForgedSignatures()
    }

    // ------------------------------------------------------------------ 工具

    /** 沿继承链找声明的方法（框架类的方法不一定声明在最外层类上）。 */
    private fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val found = runCatching { current.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current = current.superclass
        }
        return null
    }

    /** 同一类警告只打一次，避免热路径刷屏。 */
    private fun warnOnce(key: String, message: String) {
        if (warnedKeys.add(key)) LogX.w(message)
    }

    private companion object {
        /** `PackageManager` 的实现类（`@hide`，只存在于 framework）。 */
        const val PACKAGE_MANAGER_IMPL = "android.app.ApplicationPackageManager"

        const val GET_PACKAGE_INFO = "getPackageInfo"

        /** 伪造表为空时重新拉取配置的最小间隔。 */
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
