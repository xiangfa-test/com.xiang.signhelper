package com.xiang.signhelper.hook

import android.content.SharedPreferences
import android.os.SystemClock
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.util.HookEnv
import com.xiang.signhelper.util.LogX
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 现代（libxposed API 102）模块入口。
 *
 * 由 `META-INF/xposed/java_init.list` 注册；框架实例化这个类并注入框架接口后
 * 才开始生命周期回调。
 *
 * ## 职责只剩三件
 *
 * 1. 生命周期回调里**一次**装好 hook；
 * 2. 从框架的远程 preferences 读出「哪些包要伪造、伪造成什么」；
 * 3. 按用户选的**拦截模式**决定交给哪套装法（[SignatureHookInstaller]）。
 *
 * 真正的改写逻辑在 [SignatureForge]，装法在 [SignatureHookInstaller] —— 本类
 * 不碰反射细节，方便单独回归。
 *
 * ## 两套拦截模式并存
 *
 * | 模式 | 覆盖面 | 与 0.2.x 的关系 |
 * | --- | --- | --- |
 * | **原始**（`legacy`） | `getPackageInfo` 两个重载 + 只改 `signatures` | 逐条等价 |
 * | **增强**（`enhanced`，默认） | 语义扫描 `getPackageInfo` / `getPackageInfoAsUser` / `VersionedPackage` 重载 + `signatures` 与 `signingInfo` 同步伪造 + `hasSigningCertificate` | 超集 |
 *
 * 模式存在远程 preferences 的 [Config.Key.HOOK_MODE] 里，用户在设置页切换
 * （「设置 → 行为 → 拦截模式」）。切完需要**强制停止被 hook 的应用**再打开 ——
 * hook 是进程启动时装上的，改模式不影响已经在跑的进程。
 *
 * ## 配置：本地契约不动
 *
 * `forged_packages`（`\n` 分隔的包名）+ `sig:<包名>`（伪造签名）依旧是主协议，
 * 由 `SignStore` 写、本类读。另外**只读地**兼容上游 SignHook 的键
 * （`fakeSignature$$<包名>` / `fakeSignatureEnabled$$<包名>`），用于迁移或从
 * 上游配置导入；同包名时本地键优先。写协议不跟着改，老配置零迁移。
 *
 * ## 异常一律 PROTECTIVE
 *
 * hook 内部抛异常不能带走宿主 —— 尤其微信 / QQ 的启动路径上任何崩溃都是灾难。
 * 除了框架层的 [io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE]，
 * 所有可能失败的地方也都自行 `runCatching` 兜底了。
 */
class MainHook : XposedModule() {

    /** 一个进程只装一次 hook（onPackageLoaded / onPackageReady 都可能先到）。 */
    private val installed = AtomicBoolean(false)

    /** 伪造表缓存：包名 -> 伪造签名文本（可多行）。空 = 当前没有任何伪造项。 */
    @Volatile
    private var forgedSignatures: Map<String, String> = emptyMap()

    /** 当前拦截模式；默认增强，用户可在设置里切回原始。 */
    @Volatile
    private var mode: SignatureHookInstaller.Mode = SignatureHookInstaller.Mode.ENHANCED

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

    /**
     * "配置被改过"的标记。
     *
     * 监听器**不直接重建**伪造表：模块界面推一批配置时会写好几个键
     * （`forged_packages`、`sig:*`、`hook_mode`…），一次 commit 一个变更通知，
     * 直接重建会连着重建七八次（真机实测如此），纯属浪费。
     *
     * 改成置位，然后由**下一次 `getPackageInfo` 查询**合并刷新一次 —— 宿主真正
     * 用到签名时配置必然已经是最新的，中间不做无用功。
     */
    private val refreshPending = AtomicBoolean(false)

    /** 持有监听器强引用 —— SharedPreferences 内部用弱引用存监听器，lambda 不留住会被 GC。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshPending.set(true)
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
     * 注意：**不能**在这里加载宿主自己的类（应用自己的类在 `onPackageReady`
     * 之后才可用），只碰框架类 `ApplicationPackageManager`，所以没有初始化顺序问题。
     */
    private fun installForPackage(packageName: String, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        LogX.i(
            "[启动] 1/3 命中被注入包：$packageName（进程 ${HookEnv.currentProcessName}）",
            "[boot] 1/3 hooked package: $packageName (process ${HookEnv.currentProcessName})",
        )
        // 先读配置（顺带确定模式 + 挂上变更监听），再装 hook。
        refreshForgedSignatures()
        LogX.i(
            "[启动] 2/3 配置就绪：${forgedSignatures.size} 条伪造项，模式=${mode.value}",
            "[boot] 2/3 config ready: ${forgedSignatures.size} forged entries, mode=${mode.value}",
        )

        val count = SignatureHookInstaller(
            module = this,
            lookup = ::lookupForged,
            mode = mode,
        ).install(classLoader)

        if (count == 0) {
            LogX.e(
                "[启动] 3/3 getPackageInfo 一个重载都没挂上（系统版本 / ROM 差异？）",
                "[boot] 3/3 no getPackageInfo overload was hooked (OS/ROM difference?)",
            )
        } else {
            LogX.i(
                "[启动] 3/3 hook 安装完成：$count 个目标，模式=${mode.value}",
                "[boot] 3/3 hooks installed: $count targets, mode=${mode.value}",
            )
        }
    }

    /**
     * 伪造表查询（hook 热路径）。
     *
     * 两件事在这里发生：
     * 1. **合并刷新**：监听器只置位（[refreshPending]），真正的重建推迟到这里 ——
     *    配置一批写完只重建一次，而不是每个键重建一次；
     * 2. **空表兜底**：伪造表为空时按 [REFRESH_INTERVAL_MS] 低频重试，覆盖"hook
     *    装好那刻远程 prefs 还没备好"的框架差异。
     */
    private fun lookupForged(packageName: String): String? {
        if (refreshPending.compareAndSet(true, false)) {
            LogX.i(
                "检测到配置变更，按需刷新伪造表",
                "Config change detected; refreshing the forged table",
            )
            refreshForgedSignatures()
        } else if (forgedSignatures.isEmpty()) {
            maybeRefreshForgedSignatures()
        }
        val hex = forgedSignatures[packageName] ?: return null
        if (loggedPackages.add(packageName)) {
            LogX.i(
                "已伪造签名：$packageName（进程 ${HookEnv.currentProcessName}）",
                "Forged signature: $packageName (process ${HookEnv.currentProcessName})",
            )
        }
        return hex
    }

    // ------------------------------------------------------------------ 配置

    /** 注入进程看到的远程 preferences（只读）。 */
    private fun remotePreferences(): SharedPreferences? =
        runCatching { getRemotePreferences(Config.GROUP) }.getOrNull()

    /**
     * 从远程 preferences 重建伪造表（顺便确保变更监听已挂上、并同步拦截模式）。
     *
     * 数据来源有两份，**本地键优先**：
     * 1. 本模块的主协议：`forged_packages` + `sig:<包名>`；
     * 2. 上游 SignHook 的键（只读兼容）：`fakeSignature$$<包名>` + `fakeSignatureEnabled$$<包名>`。
     */
    private fun refreshForgedSignatures() {
        // [1/4] 拿远程 preferences
        val prefs = remotePreferences() ?: run {
            warnOnce(
                "prefs-unavailable",
                "远程 preferences 不可用，伪造表保持为空",
                "Remote preferences unavailable; the forged table stays empty",
            )
            return
        }
        // 远程 prefs 在 onPackageLoaded 阶段偶尔还没备好；哪次拿到就在哪次挂监听。
        registerListenerOnce(prefs)

        val rebuilt = HashMap<String, String>()

        // [2/4] 主协议
        val packed = runCatching { prefs.getString(Config.Key.FORGED_PACKAGES, "") }
            .getOrElse { throwable ->
                LogX.w(
                    "[配置] 2/4 读取 ${Config.Key.FORGED_PACKAGES} 失败：${throwable.message}",
                    "[config] 2/4 failed to read ${Config.Key.FORGED_PACKAGES}: ${throwable.message}",
                )
                null
            }
            .orEmpty()
        var missingSignature = 0
        for (line in packed.split('\n', '\r')) {
            val packageName = line.trim()
            if (packageName.isEmpty()) continue
            val hex = runCatching { prefs.getString(Config.Key.SIG_PREFIX + packageName, "") }
                .getOrElse { throwable ->
                    LogX.w(
                        "[配置] 读取 sig:$packageName 失败：${throwable.message}",
                        "[config] failed to read sig:$packageName: ${throwable.message}",
                    )
                    null
                }
                .orEmpty()
                .trim()
            if (hex.isEmpty()) {
                missingSignature++
                continue
            }
            rebuilt[packageName] = hex
        }
        LogX.i(
            "[配置] 2/4 主协议：清单 ${packed.split('\n', '\r').count { it.isNotBlank() }} 条，" +
                "有效 ${rebuilt.size} 条，缺签名 $missingSignature 条",
            "[config] 2/4 primary: ${packed.split('\n', '\r').count { it.isNotBlank() }} listed, " +
                "${rebuilt.size} usable, $missingSignature missing a signature",
        )

        // [3/4] 上游键兼容读（同包名以主协议为准）
        val imported = mergeUpstreamEntries(prefs, rebuilt)

        // [4/4] 拦截模式
        val rawMode = runCatching { prefs.getString(Config.Key.HOOK_MODE, "") }
            .getOrElse { throwable ->
                LogX.w(
                    "[配置] 4/4 读取 ${Config.Key.HOOK_MODE} 失败，按默认增强模式：${throwable.message}",
                    "[config] 4/4 failed to read ${Config.Key.HOOK_MODE}; defaulting to enhanced: ${throwable.message}",
                )
                null
            }
        mode = SignatureHookInstaller.Mode.from(rawMode)
        LogX.i(
            "[配置] 4/4 拦截模式=${mode.value}（原始值=${rawMode.orEmpty().ifEmpty { "未设置" }}，默认增强）",
            "[config] 4/4 mode=${mode.value} (raw=${rawMode.orEmpty().ifEmpty { "unset" }}, default enhanced)",
        )

        val changed = rebuilt != forgedSignatures
        forgedSignatures = rebuilt
        lastRefreshAt = SystemClock.elapsedRealtime()
        if (changed) {
            LogX.i(
                "伪造表已更新：${rebuilt.keys.joinToString().ifEmpty { "（空）" }}",
                "Forged table updated: ${rebuilt.keys.joinToString().ifEmpty { "(empty)" }}",
            )
        }
        if (imported > 0) {
            LogX.i(
                "已从上游键兼容读入 $imported 条（fakeSignature\$\$ 前缀）",
                "Imported $imported entries from upstream keys (fakeSignature\$\$ prefix)",
            )
        }
    }

    /** 挂一次变更监听。 */
    private fun registerListenerOnce(prefs: SharedPreferences) {
        if (listenerRegistered) return
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

    /**
     * 把上游 SignHook 的键并进 [target]（只补空缺，不覆盖）。
     *
     * 上游的形态是「每个包两个键」：签名 + 布尔开关。这里**只读**，不写回，
     * 所以不会污染本地协议；同包名时本地键已经在 [target] 里，自然优先。
     *
     * @return 实际补进来的条数。
     */
    private fun mergeUpstreamEntries(
        prefs: SharedPreferences,
        target: MutableMap<String, String>,
    ): Int {
        val all = runCatching { prefs.all }.getOrNull() ?: return 0
        var imported = 0
        for ((key, value) in all) {
            if (!key.startsWith(UPSTREAM_SIG_PREFIX)) continue
            val packageName = key.removePrefix(UPSTREAM_SIG_PREFIX)
            if (packageName.isEmpty() || packageName in target) continue
            // 开关缺省按"有签名即可用"处理：手工搬配置时通常不会连布尔一起搬。
            val enabled = when (val flag = all[UPSTREAM_ENABLED_PREFIX + packageName]) {
                is Boolean -> flag
                null -> true
                else -> flag.toString().toBooleanStrictOrNull() ?: true
            }
            if (!enabled) continue
            val hex = value?.toString()?.trim().orEmpty()
            if (hex.isEmpty()) continue
            target[packageName] = hex
            imported++
        }
        return imported
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

    /** 同一类警告只打一次，避免热路径刷屏。 */
    private fun warnOnce(key: String, zh: String, en: String) {
        if (warnedKeys.add(key)) LogX.w(zh, en)
    }

    private companion object {
        /** 伪造表为空时重新拉取配置的最小间隔。 */
        const val REFRESH_INTERVAL_MS = 5_000L

        /** 上游 SignHook 的签名键前缀（只读兼容）。 */
        const val UPSTREAM_SIG_PREFIX = "fakeSignature\$\$"

        /** 上游 SignHook 的启用开关键前缀（只读兼容）。 */
        const val UPSTREAM_ENABLED_PREFIX = "fakeSignatureEnabled\$\$"
    }
}
