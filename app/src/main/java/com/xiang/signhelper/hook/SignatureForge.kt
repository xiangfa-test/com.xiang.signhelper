package com.xiang.signhelper.hook

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.xiang.signhelper.util.FakeSignatureCodec
import com.xiang.signhelper.util.LogX
import java.lang.reflect.Constructor
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 伪造签名的**合成层**：把配置里的一段 hex 变成宿主真正消费得到的 `PackageInfo`。
 *
 * ## 为什么需要它（相对原始逻辑的缺口）
 *
 * 原始逻辑只干一件事：`PackageInfo#signatures = arrayOf(Signature(hex))`。
 * 这条路径只覆盖「宿主用 `GET_SIGNATURES` 读签名」的旧代码。`targetSdk` 抬到 33+
 * 之后，常见校验换成：
 *
 * ```java
 * var info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
 * var signers = info.signingInfo.getApkContentsSigners();   // ← 这里读的是 signingInfo
 * ```
 *
 * 只改 `signatures` 时，`signingInfo` 仍然是系统填进去的**真签名**，两条路读到
 * 不一致的值；有的校验程序还会交叉比对两个字段，反而更容易被判定为被篡改。
 *
 * 还有第三个入口：`PackageManager#hasSigningCertificate(pkg, byte[], CERT_INPUT_*)`，
 * 它不经 `PackageInfo`，直接在系统里比对证书，原始逻辑完全盖不到。
 *
 * ## 本层做三件事
 *
 * 1. [parse]：把配置文本（可多行）变成 `Array<Signature>`；
 * 2. [applyTo]：同时写 `signatures` 与**重建的** `signingInfo`；
 * 3. [matchesCertificate]：给 `hasSigningCertificate` 的 hook 做证书比对
 *    （RAW_X509 直接比原字节，SHA256 比摘要）。
 *
 * `signingInfo` 的重建只能走反射：`SigningDetails` 是 `@hide` 类，
 * `SigningInfo` 的构造器参数就是它，公开 SDK 里拿不到。
 *
 * ## 日志策略
 *
 * 这一层在 `getPackageInfo` 的热路径上，**不能每调一次打一条**：
 *
 * - 安装/首次调用期的关键结论（解析出几条签名、用了哪个 `SigningDetails` 构造器形状、
 *   证书按哪种类型比对）走**有限配额**的 INFO；
 * - 异常与"这条路走不通"的结论走去重的 WARN/ERROR。
 *
 * 出问题时 `adb logcat -s SignHelper:*` 就能看到卡在哪一步，不必先猜。
 *
 * 所有方法都**不抛异常** —— 它被挂在微信 / QQ 的启动路径上。
 */
internal object SignatureForge {

    /** 去重警告表：同一个 key 只打一次。 */
    private val warnedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 热路径 INFO 配额：打满即静默，避免刷屏。 */
    private val parseBudget = AtomicInteger(PARSE_LOG_BUDGET)
    private val certBudget = AtomicInteger(CERT_LOG_BUDGET)
    private val applyBudget = AtomicInteger(APPLY_LOG_BUDGET)

    /** 是否已经报告过"本次进程造不出 signingInfo"。 */
    private val warnedSigningInfo = AtomicBoolean(false)

    /** 是否已经报告过"用了哪个 SigningDetails 构造器形状"。 */
    private val reportedShape = AtomicBoolean(false)

    // ---------------------------------------------------------------- 解析

    /**
     * 配置文本 -> 签名数组。空 / 全非法时返回 `null`。
     *
     * 多行 = 多签名（多签名 APK、签名轮换）；单行就是最常见的一条。
     * 单条解码失败的只是被跳过，不影响其余几条。
     */
    fun parse(hexText: String): Array<Signature>? {
        val lines = FakeSignatureCodec.splitHexLines(hexText)
        if (lines.isEmpty()) {
            warnOnce("parse-empty", "伪造签名配置为空，跳过", "Fake signature config is empty; skipped")
            return null
        }
        val signatures = ArrayList<Signature>(lines.size)
        var skipped = 0
        for (line in lines) {
            val bytes = FakeSignatureCodec.decodeHex(line) ?: run {
                skipped++
                null
            } ?: continue
            if (bytes.isEmpty()) {
                skipped++
                continue
            }
            val signature = runCatching { Signature(bytes) }.getOrNull()
            if (signature == null) {
                skipped++
                continue
            }
            signatures.add(signature)
        }
        if (skipped > 0) {
            warnOnce(
                "parse-skip",
                "伪造签名有 $skipped 条不是合法 hex，已跳过（共 ${lines.size} 条）",
                "$skipped fake signature(s) are not valid hex and were skipped (of ${lines.size})",
            )
        }
        if (signatures.isEmpty()) return null
        if (parseBudget.getAndDecrement() > 0) {
            LogX.i(
                "[签名合成] 解析完成：${signatures.size} 条有效签名（原文 ${lines.size} 行）",
                "[forge] Parsed ${signatures.size} valid signature(s) from ${lines.size} line(s)",
            )
        }
        return signatures.toTypedArray()
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 就地替换 [info] 的签名。
     *
     * @param withSigningInfo 是否同时重建 `signingInfo`。**原始模式传 `false`** ——
     *   这样关掉增强后行为与旧版本逐字节一致，出问题可以一个开关地退回。
     * @return 是否成功写入（`false` 时调用方应原样返回系统给的对象）。
     */
    @Suppress("DEPRECATION")
    fun applyTo(
        info: PackageInfo,
        hexText: String,
        packageName: String,
        withSigningInfo: Boolean,
    ): Boolean {
        val signatures = parse(hexText) ?: return false
        return runCatching {
            // `signatures` 虽然被标 deprecated，但 GET_SIGNATURES 路径填充的仍是它，
            // 老校验代码读的也是它 —— 必须照旧赋值。
            info.signatures = signatures

            var signingInfoState = "未启用（原始模式）"
            if (withSigningInfo) {
                val rebuilt = buildSigningInfo(signatures)
                if (rebuilt != null) {
                    info.signingInfo = rebuilt
                    signingInfoState = "已重建"
                } else {
                    signingInfoState = "重建失败"
                    if (warnedSigningInfo.compareAndSet(false, true)) {
                        LogX.w(
                            "signingInfo 重建失败（ROM 差异？），本次进程只伪造 signatures：$packageName",
                            "Failed to rebuild signingInfo (ROM difference?); signatures-only in this process: $packageName",
                        )
                    }
                }
            }
            if (applyBudget.getAndDecrement() > 0) {
                LogX.i(
                    "[签名合成] 写入 $packageName：signatures=${signatures.size} 条，signingInfo=$signingInfoState",
                    "[forge] Applied to $packageName: signatures=${signatures.size}, signingInfo=$signingInfoState",
                )
            }
            true
        }.getOrElse { throwable ->
            LogX.e("替换 $packageName 的签名失败", "Failed to replace signature for $packageName", throwable)
            false
        }
    }

    // ---------------------------------------------------------------- 证书比对

    /**
     * `hasSigningCertificate` 的答案：这条伪造签名是否"就是"宿主问的那张证书。
     *
     * 只处理两种标准输入类型；其余类型（扩展值）一律 `false`，让调用方
     * `proceed()` 交还给系统。
     */
    fun matchesCertificate(signature: Signature, certificate: ByteArray, type: Int): Boolean {
        val matched = runCatching {
            when (type) {
                PackageManager.CERT_INPUT_RAW_X509 ->
                    signature.toByteArray().contentEquals(certificate)

                PackageManager.CERT_INPUT_SHA256 ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .contentEquals(certificate)

                else -> false
            }
        }.getOrElse { throwable ->
            LogX.e("证书比对异常（type=$type）", "Certificate comparison failed (type=$type)", throwable)
            false
        }
        if (certBudget.getAndDecrement() > 0) {
            LogX.i(
                "[签名合成] hasSigningCertificate：type=$type 长度=${certificate.size} -> $matched",
                "[forge] hasSigningCertificate: type=$type length=${certificate.size} -> $matched",
            )
        }
        if (type != PackageManager.CERT_INPUT_RAW_X509 && type != PackageManager.CERT_INPUT_SHA256) {
            warnOnce(
                "cert-type-$type",
                "hasSigningCertificate 收到未支持的证书类型 $type，交还系统处理",
                "Unsupported certificate input type $type; deferring to the system",
            )
        }
        return matched
    }

    // ---------------------------------------------------------------- signingInfo 合成

    /** 用一组 [Signature] 造一个 `SigningInfo`；失败返回 `null`。 */
    private fun buildSigningInfo(signatures: Array<Signature>): SigningInfo? {
        val detailsClass = signingDetailsClass()
        if (detailsClass == null) {
            warnOnce(
                "no-details-class",
                "找不到 SigningDetails 类（SigningInfo 无法重建）",
                "SigningDetails class not found; cannot rebuild SigningInfo",
            )
            return null
        }
        val details = createSigningDetails(detailsClass, signatures)
        if (details == null) {
            warnOnce(
                "no-details-ctor",
                "SigningDetails 所有构造器都不匹配（API 版本差异）",
                "No SigningDetails constructor matched (API difference)",
            )
            return null
        }
        val constructor = SigningInfo::class.java.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 1 && candidate.parameterTypes[0] == detailsClass
        }
        if (constructor == null) {
            warnOnce(
                "no-signinginfo-ctor",
                "SigningInfo 没有接受 SigningDetails 的构造器",
                "SigningInfo has no constructor accepting SigningDetails",
            )
            return null
        }
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(details) as? SigningInfo
        }.getOrElse { throwable ->
            LogX.e("构造 SigningInfo 失败", "Failed to construct SigningInfo", throwable)
            null
        }
    }

    /**
     * 找 `SigningDetails` 类。
     *
     * Android 9 起是 `android.content.pm.SigningDetails`；更老的系统里它叫
     * `android.content.pm.PackageParser$SigningDetails`。两个都试一遍，
     * 避免在某些 ROM 上因为改名而整套增强失效。
     */
    private fun signingDetailsClass(): Class<*>? {
        runCatching { Class.forName("android.content.pm.SigningDetails") }
            .onSuccess { return it }
            .onFailure {
                warnOnce(
                    "details-fallback",
                    "android.content.pm.SigningDetails 不存在，退回 PackageParser\$SigningDetails",
                    "android.content.pm.SigningDetails missing; falling back to PackageParser\$SigningDetails",
                )
            }
        return runCatching { Class.forName("android.content.pm.PackageParser\$SigningDetails") }.getOrNull()
    }

    /**
     * 按构造器逐个试出能用的那个。
     *
     * `SigningDetails` 的构造器在不同 API 上有 2 / 4 / 5 参数几种形态，
     * 且 5 参数版本里参数顺序也有两种。逐个尝试、谁成功用谁，比按
     * `Build.VERSION` 分支稳 —— 各 ROM 的回移补丁经常把顺序搞乱。
     */
    private fun createSigningDetails(
        detailsClass: Class<*>,
        signatures: Array<Signature>,
    ): Any? {
        // 公钥集合：SigningDetails 用它做证书链判定；拿不到就退化成"只给签名"的构造器。
        val publicKeys = runCatching { signatures.toPublicKeySet() }
            .onFailure {
                LogX.w(
                    "signatures 转公钥集失败，将退回不需要公钥集的构造器：${it.message}",
                    "Failed to build the public key set; falling back to a constructor without it: ${it.message}",
                )
            }
            .getOrNull()
        val candidates = detailsClass.declaredConstructors.sortedByDescending { it.parameterTypes.size }
        for (constructor in candidates) {
            val details = runCatching { constructor.newSigningDetails(signatures, publicKeys) }.getOrNull()
            if (details != null) {
                if (reportedShape.compareAndSet(false, true)) {
                    LogX.i(
                        "[签名合成] SigningDetails 构造器形状：${constructor.parameterTypes.joinToString { it.simpleName }}",
                        "[forge] SigningDetails constructor shape: ${constructor.parameterTypes.joinToString { it.simpleName }}",
                    )
                }
                return details
            }
        }
        return null
    }

    /** `android.util.ArraySet<PublicKey>`，只能反射构造。 */
    private fun Array<Signature>.toPublicKeySet(): Any {
        val arraySetClass = Class.forName("android.util.ArraySet")
        val keys = arraySetClass.getConstructor().newInstance()
        val add = arraySetClass.getMethod("add", Any::class.java)
        val getPublicKey = Signature::class.java.getMethod("getPublicKey")
        var added = 0
        for (signature in this) {
            // 单条证书解析不出公钥（DER 不合法）时只跳过这条，不整体失败。
            runCatching { add.invoke(keys, getPublicKey.invoke(signature)) }
                .onSuccess { added++ }
                .onFailure {
                    warnOnce(
                        "pubkey-skip",
                        "有签名解析不出公钥，已跳过（signingInfo 仍会重建，但公钥集可能不全）",
                        "A signature exposed no public key; skipped (signingInfo still rebuilt, key set may be partial)",
                    )
                }
        }
        if (added == 0) {
            warnOnce("pubkey-empty", "公钥集为空", "Public key set is empty")
        }
        return keys
    }

    /**
     * 一次构造尝试。参数形状对不上就返回 `null`，由调用方试下一个。
     *
     * 已知形态（按 AOSP 源码）：
     * ```
     * (Signature[], int, int, ArraySet<PublicKey>, Signature[])   // 新版本，含 minSchemeVersion
     * (Signature[], int, ArraySet<PublicKey>, Signature[], CertCapabilities[])
     * (Signature[], int, ArraySet<PublicKey>, Signature[])
     * (Signature[], int)
     * ```
     */
    private fun Constructor<*>.newSigningDetails(
        signatures: Array<Signature>,
        publicKeys: Any?,
    ): Any? {
        val parameterTypes = parameterTypes
        // 前两个参数必须是 (Signature[], int)，否则肯定不是我们要的构造器。
        if (parameterTypes.firstOrNull() != signatures.javaClass) return null
        if (parameterTypes.getOrNull(1) != Int::class.javaPrimitiveType) return null
        // 需要公钥集的形态但没有公钥 -> 构造出来也是错的，交给别的候选。
        if (publicKeys == null && parameterTypes.size > 2) return null

        val args: Array<Any?> = when {
            parameterTypes.size == 5 && parameterTypes[2] == Int::class.javaPrimitiveType ->
                arrayOf(signatures, SIGNATURE_SCHEME_VERSION, SIGNATURE_SCHEME_MINOR_VERSION, publicKeys, null)

            parameterTypes.size == 5 ->
                arrayOf(signatures, SIGNATURE_SCHEME_VERSION, publicKeys, null, null)

            parameterTypes.size == 4 ->
                arrayOf(signatures, SIGNATURE_SCHEME_VERSION, publicKeys, null)

            parameterTypes.size == 2 ->
                arrayOf(signatures, SIGNATURE_SCHEME_VERSION)

            else -> return null
        }
        isAccessible = true
        return runCatching { newInstance(*args) }.getOrNull()
    }

    /** 去重告警。 */
    private fun warnOnce(key: String, zh: String, en: String) {
        if (warnedKeys.add(key)) LogX.w(zh, en)
    }

    private const val SIGNATURE_SCHEME_VERSION = 2
    private const val SIGNATURE_SCHEME_MINOR_VERSION = 0

    private const val PARSE_LOG_BUDGET = 3
    private const val CERT_LOG_BUDGET = 5
    private const val APPLY_LOG_BUDGET = 3
}
