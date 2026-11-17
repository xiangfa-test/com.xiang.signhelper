package com.xiang.signhelper.hook

import android.content.pm.PackageInfo
import com.xiang.signhelper.util.LogX
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 在宿主进程里把签名 hook 装上去。**两套装法并存，由用户在设置里选。**
 *
 * ## 原始模式（[Mode.LEGACY]）
 *
 * 与 0.2.x 的行为逐条一致：写死两个重载
 * —— `getPackageInfo(String, int)` 与 `getPackageInfo(String, PackageInfoFlags)`，
 * 只改 `PackageInfo#signatures`。宿主读 `signingInfo` 的校验盖不到，但覆盖面
 * 完全可预期，出问题最容易判断"是不是模块干的"。
 *
 * ## 增强模式（[Mode.ENHANCED]）
 *
 * 照上游 SignHook 的拦截语义重写（不是照抄代码）：
 *
 * 1. **不写死方法签名**，按语义扫 `ApplicationPackageManager`（含父类链）的
 *    `declaredMethods`：返回 `PackageInfo` + 方法名 ∈ {`getPackageInfo`,
 *    `getPackageInfoAsUser`} + 首参是 `String` 或 `VersionedPackage`；
 * 2. 命中后 `signatures` 与 `signingInfo` **一起**伪造（见 [SignatureForge]）；
 * 3. 额外挂 `hasSigningCertificate(String, byte[], int)`，把"问证书"的校验也接上。
 *
 * 增强模式是原始模式的超集，多出来的只有覆盖面；任何一步失败都不影响宿主
 * —— 所有 hook 一律 `PROTECTIVE`，且本类内部再兜一层 `runCatching`。
 *
 * ## 安装期日志（排障靠它）
 *
 * 安装只在进程启动时发生一次，所以这里**每步都打**：
 * 定位实现类 → 选模式 → 扫描候选 → 逐个装 → 汇总。ROM 差异导致"没挂上"时，
 * 日志里能直接看到是哪个方法、因为什么被过滤掉的，不用猜。
 */
internal class SignatureHookInstaller(
    private val module: XposedModule,
    /** 包名 -> 伪造签名文本；未配置返回 `null`。 */
    private val lookup: (String) -> String?,
    private val mode: Mode,
) {

    /** 拦截模式；值与 `Config` 里 `hook_mode` 的字符串一致。 */
    enum class Mode(val value: String) {
        /** 原始逻辑：两个重载 + 只改 signatures。 */
        LEGACY("legacy"),

        /** 增强逻辑：语义扫描 + signatures/signingInfo + hasSigningCertificate。 */
        ENHANCED("enhanced"),
        ;

        companion object {
            fun from(raw: String?): Mode =
                entries.firstOrNull { it.value == raw } ?: ENHANCED
        }
    }

    /** 装 hook，返回实际挂上的个数（0 = 这个 ROM 上没找到目标方法）。 */
    fun install(classLoader: ClassLoader): Int {
        LogX.i(
            "[安装] 开始：模式=${mode.value}，loader=${classLoader.javaClass.simpleName}",
            "[install] start: mode=${mode.value}, loader=${classLoader.javaClass.simpleName}",
        )

        // [1/4] 定位 PackageManager 实现类
        val clazz = runCatching {
            Class.forName(PACKAGE_MANAGER_IMPL, false, classLoader)
        }.getOrElse { throwable ->
            LogX.e(
                "[安装] 1/4 失败：找不到 $PACKAGE_MANAGER_IMPL",
                "[install] 1/4 failed: $PACKAGE_MANAGER_IMPL not found",
                throwable,
            )
            return 0
        }
        LogX.i(
            "[安装] 1/4 已定位 $PACKAGE_MANAGER_IMPL（父类 ${clazz.superclass?.name ?: "-"}）",
            "[install] 1/4 resolved $PACKAGE_MANAGER_IMPL (super=${clazz.superclass?.name ?: "-"})",
        )

        // [2/4] 选装法
        val count = when (mode) {
            Mode.LEGACY -> installLegacy(clazz)
            Mode.ENHANCED -> installEnhanced(clazz)
        }

        // [4/4] 汇总
        if (count == 0) {
            LogX.e(
                "[安装] 4/4 结束：一个 hook 都没挂上（模式=${mode.value}）—— 宿主读签名不会生效",
                "[install] 4/4 done: no hook installed (mode=${mode.value}) — signature reads will not be forged",
            )
        } else {
            LogX.i(
                "[安装] 4/4 结束：共挂上 $count 个 hook（模式=${mode.value}）",
                "[install] 4/4 done: $count hook(s) installed (mode=${mode.value})",
            )
        }
        return count
    }

    // ---------------------------------------------------------------- 原始模式

    /** 只认两个老重载，与 0.2.x 一致。 */
    private fun installLegacy(clazz: Class<*>): Int {
        LogX.i("[安装] 2/4 原始模式：按写死的签名找两个重载", "[install] 2/4 legacy: looking for the two hard-coded overloads")
        val declared = clazz.declaredMethods
        val targets = declared.filter(::isLegacyOverload)
        LogX.i(
            "[安装] 3/4 扫描 ${declared.size} 个方法，命中 ${targets.size} 个重载",
            "[install] 3/4 scanned ${declared.size} methods, matched ${targets.size} overload(s)",
        )
        if (targets.isEmpty()) {
            // 把"名字对但签名不对"的列出来，ROM 改动一看就知道。
            val nearMiss = declared.filter { it.name == GET_PACKAGE_INFO }
                .joinToString("; ") { it.describe() }
                .ifEmpty { "（没有同名方法）" }
            LogX.w(
                "[安装] 没有命中；同名方法实际是：$nearMiss",
                "[install] no match; same-name methods found: $nearMiss",
            )
        }
        return targets.count { method ->
            hookPackageInfo(method, withSigningInfo = false)
        }
    }

    private fun isLegacyOverload(method: Method): Boolean {
        if (method.name != GET_PACKAGE_INFO) return false
        if (method.returnType != PackageInfo::class.java) return false
        val parameters = method.parameterTypes
        if (parameters.size != 2) return false
        if (parameters[0] != String::class.java) return false
        return parameters[1] == Int::class.javaPrimitiveType ||
            parameters[1].simpleName == PACKAGE_INFO_FLAGS
    }

    // ---------------------------------------------------------------- 增强模式

    private fun installEnhanced(clazz: Class<*>): Int {
        LogX.i(
            "[安装] 2/4 增强模式：按语义扫描（PackageInfo 查询 + hasSigningCertificate）",
            "[install] 2/4 enhanced: semantic scan (PackageInfo lookups + hasSigningCertificate)",
        )
        val methods = collectMethods(clazz)
        LogX.i(
            "[安装] 3/4 收集到 ${methods.size} 个方法（含父类链，已去重）",
            "[install] 3/4 collected ${methods.size} methods (superclass chain, de-duplicated)",
        )

        // 先把"名字像但被过滤掉"的方法列出来 —— 增强模式的价值全在覆盖面，
        // 覆盖面收窄时必须能从日志里看出原因。
        val nearMiss = methods.filter { method ->
            method.name in PACKAGE_INFO_METHODS || method.name == HAS_SIGNING_CERTIFICATE
        }.filterNot { isPackageInfoLookup(it) || isHasSigningCertificate(it) }
        if (nearMiss.isNotEmpty()) {
            LogX.i(
                "[安装] 疑似目标但未命中：${nearMiss.joinToString("; ") { it.describe() }}",
                "[install] look-alike but skipped: ${nearMiss.joinToString("; ") { it.describe() }}",
            )
        }

        val lookups = methods.filter(::isPackageInfoLookup)
        val certChecks = methods.filter(::isHasSigningCertificate)
        LogX.i(
            "[安装] 命中统计：PackageInfo 查询 ${lookups.size} 个，hasSigningCertificate ${certChecks.size} 个",
            "[install] hits: ${lookups.size} PackageInfo lookup(s), ${certChecks.size} hasSigningCertificate",
        )
        for (method in lookups) {
            LogX.i(
                "[安装] 候选 → ${method.declaringClass.simpleName}#${method.describe()}",
                "[install] candidate -> ${method.declaringClass.simpleName}#${method.describe()}",
            )
        }
        for (method in certChecks) {
            LogX.i(
                "[安装] 候选 → ${method.declaringClass.simpleName}#${method.describe()}",
                "[install] candidate -> ${method.declaringClass.simpleName}#${method.describe()}",
            )
        }

        var count = lookups.count { method -> hookPackageInfo(method, withSigningInfo = true) }
        count += certChecks.count(::hookHasSigningCertificate)
        return count
    }

    /**
     * 沿继承链收方法并去重。
     *
     * `ApplicationPackageManager` 是 `@hide` 实现类，不同 ROM 会把某些重载挪到父类
     * 或者直接改写，所以不能只看最外层 `declaredMethods`。
     */
    private fun collectMethods(clazz: Class<*>): List<Method> {
        val out = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = runCatching { current.declaredMethods }.getOrElse { throwable ->
                LogX.w(
                    "读取 ${current.name} 的 declaredMethods 失败：${throwable.message}",
                    "Failed to read declaredMethods of ${current.name}: ${throwable.message}",
                )
                return@getOrElse emptyArray()
            }
            for (method in declared) {
                val key = method.name + method.parameterTypes.joinToString(",", "[", "]") { it.name }
                out.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        return out.values.toList()
    }

    private fun isPackageInfoLookup(method: Method): Boolean {
        if (method.returnType != PackageInfo::class.java) return false
        if (method.name !in PACKAGE_INFO_METHODS) return false
        val first = method.parameterTypes.firstOrNull() ?: return false
        return first.isPackageNameLike()
    }

    private fun isHasSigningCertificate(method: Method): Boolean {
        if (method.name != HAS_SIGNING_CERTIFICATE) return false
        val parameters = method.parameterTypes
        if (parameters.size != 3) return false
        if (!parameters[0].isPackageNameLike()) return false
        if (parameters[1] != ByteArray::class.java) return false
        return parameters[2] == Int::class.javaPrimitiveType
    }

    /** `String` 和 `android.content.pm.VersionedPackage` 都是"包名入参"。 */
    private fun Class<*>.isPackageNameLike(): Boolean =
        this == String::class.java || name == VERSIONED_PACKAGE

    // ---------------------------------------------------------------- 装钩子

    /**
     * 后置 hook：先让系统正常返回，再决定要不要改写。
     *
     * 包名优先取**入参**（`String` 或 `VersionedPackage#getPackageName()`），
     * 拿不到才退回 `PackageInfo#packageName` —— 入参才是"宿主想问哪个包"，
     * 系统返回的对象在个别 ROM 上可能和入参不一致。
     */
    private fun hookPackageInfo(method: Method, withSigningInfo: Boolean): Boolean = runCatching {
        val label = method.parameterTypes.lastOrNull()?.simpleName ?: "unknown"
        module.hook(method)
            .setId("signhelper-getPackageInfo-$label")
            // PROTECTIVE：hook 自身抛异常时只记录日志并放行原调用，绝不带走宿主。
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val requested = chain.argPackageName(0)
                    val result = chain.proceed()
                    val info = result as? PackageInfo ?: return result
                    val packageName = requested ?: info.packageName.orEmpty()
                    if (packageName.isEmpty()) return result
                    val hex = lookup(packageName) ?: return result
                    val applied = SignatureForge.applyTo(info, hex, packageName, withSigningInfo)
                    return if (applied) info else result
                }
            })
        LogX.i(
            "[安装] 已挂 → ${method.declaringClass.simpleName}#${method.describe()}（signingInfo=$withSigningInfo）",
            "[install] hooked -> ${method.declaringClass.simpleName}#${method.describe()} (signingInfo=$withSigningInfo)",
        )
        true
    }.getOrElse { throwable ->
        LogX.e("[安装] 已挂失败 → ${method.describe()}", "[install] hook failed -> ${method.describe()}", throwable)
        false
    }

    /**
     * `hasSigningCertificate` 的前置拦截：问的是伪造证书就直接答 `true`，
     * 其余情况 `proceed()` 交还系统。
     */
    private fun hookHasSigningCertificate(method: Method): Boolean = runCatching {
        module.hook(method)
            .setId("signhelper-hasSigningCertificate")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val packageName = chain.argPackageName(0) ?: return chain.proceed()
                    val hex = lookup(packageName) ?: return chain.proceed()
                    val certificate = chain.safeArg(1) as? ByteArray ?: return chain.proceed()
                    val type = chain.safeArg(2) as? Int ?: return chain.proceed()
                    val signatures = SignatureForge.parse(hex) ?: return chain.proceed()
                    val matched = signatures.any { signature ->
                        SignatureForge.matchesCertificate(signature, certificate, type)
                    }
                    return if (matched) true else chain.proceed()
                }
            })
        LogX.i(
            "[安装] 已挂 → ${method.declaringClass.simpleName}#${method.describe()}",
            "[install] hooked -> ${method.declaringClass.simpleName}#${method.describe()}",
        )
        true
    }.getOrElse { throwable ->
        LogX.e("[安装] 已挂失败 → ${method.describe()}", "[install] hook failed -> ${method.describe()}", throwable)
        false
    }

    // ---------------------------------------------------------------- 参数工具

    /** 读第 [index] 个入参；越界 / 类型不符都返回 `null`（不抛）。 */
    private fun XposedInterface.Chain.safeArg(index: Int): Any? =
        runCatching { getArg(index) }.getOrNull()

    /**
     * 把第 [index] 个入参解析成包名。
     *
     * 支持 `String` 与 `VersionedPackage`；后者的 `getPackageName()` 在公开 SDK 里有，
     * 但这里仍然走反射 —— hook 里引用不到那个类型（它只在 `parameterTypes` 里出现）。
     */
    private fun XposedInterface.Chain.argPackageName(index: Int): String? =
        when (val arg = safeArg(index)) {
            null -> null
            is String -> arg.takeIf { it.isNotEmpty() }
            else -> runCatching {
                arg.javaClass.getMethod("getPackageName").invoke(arg) as? String
            }.getOrElse { throwable ->
                LogX.w(
                    "从 ${arg.javaClass.name} 取包名失败：${throwable.message}",
                    "Failed to read a package name from ${arg.javaClass.name}: ${throwable.message}",
                )
                null
            }?.takeIf { it.isNotEmpty() }
        }

    /** 方法签名的人类可读形式，只用于日志。 */
    private fun Method.describe(): String =
        "$name(${parameterTypes.joinToString { it.simpleName }}):${returnType.simpleName}"

    private companion object {
        /** `PackageManager` 的实现类（`@hide`，只存在于 framework）。 */
        const val PACKAGE_MANAGER_IMPL = "android.app.ApplicationPackageManager"

        const val GET_PACKAGE_INFO = "getPackageInfo"

        const val HAS_SIGNING_CERTIFICATE = "hasSigningCertificate"

        const val PACKAGE_INFO_FLAGS = "PackageInfoFlags"

        const val VERSIONED_PACKAGE = "android.content.pm.VersionedPackage"

        /** 增强模式扫描的方法名集合。 */
        val PACKAGE_INFO_METHODS = setOf("getPackageInfo", "getPackageInfoAsUser")
    }
}
