package com.xiang.signhelper.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * APK 签名读取工具。
 *
 * ## 为什么要把 SAF Uri 复制成临时文件
 *
 * `PackageManager#getPackageArchiveInfo` 只认**文件路径**，不认 content Uri；
 * 而用户是通过 `ACTION_OPEN_DOCUMENT` 拿到 content Uri 的（可能在别的应用/
 * 云盘里）。所以先 `openInputStream` 复制到 `cacheDir/pick.apk`，读完签名即可
 * 丢弃（文件留在 cache 里，系统会自己回收）。
 *
 * ## 签名 = 小写 hex
 *
 * 统一用 `Signature#toCharsString()` 的形态（小写、无分隔 hex），这样：
 *  - 和原版 `cn.xihan.sign` 的存储格式一致；
 *  - 用户能直接从 MT 管理器 / 甲壳虫等工具里复制粘贴；
 *  - hook 端 [hexToBytes] 还原成原始字节后 `Signature(bytes)` 即可。
 */
object SignatureUtil {

    /** 缓存临时 APK 的文件名（契约规定，别改）。 */
    private const val TEMP_APK_NAME = "pick.apk"

    private const val HEX_CHARS = "0123456789abcdef"

    /** 从 APK 里读出来的元信息。 */
    data class ApkMeta(
        /** APK 文件名（SAF 提供的 display name）/ 已安装应用的应用名，展示用。 */
        val apkName: String,
        /** 清单里的包名。 */
        val packageName: String,
        /** 第一个签名的 hex（小写、无分隔）。 */
        val signatureHex: String,
    )

    /** 「已安装应用」选择器的一行数据。 */
    data class InstalledApp(
        val packageName: String,
        val label: String,
        /** 系统应用还是用户应用（安装器 / 系统分区标记）。 */
        val isSystem: Boolean,
        /** 最后安装/更新时间（`PackageInfo.lastUpdateTime`，毫秒）。 */
        val lastUpdateTime: Long,
    )

    /**
     * 一次列取的结果。
     *
     * [restricted] = true 表示系统（MIUI / HyperOS）把「获取应用列表」权限关掉了：
     * 此时 `getInstalledPackages` 只会返回应用自己，列表是**通过 intent 反查**拼出来的，
     * 可能缺一些没有组件（activity/service/receiver/provider）的包，需要引导用户去开启权限。
     */
    data class InstalledAppsResult(
        val apps: List<InstalledApp>,
        val restricted: Boolean,
    )

    /**
     * 列出本机已安装的应用。
     *
     * 常规路径用 `getInstalledPackages(0)`；在 MIUI / HyperOS 上如果「获取应用列表」
     * 权限没开，这个 API 会**静默**只返回本应用自己。检测到这种情况就退回到 intent
     * 反查（拿着包名再逐个 `getPackageInfo`，单包查询不受该限制），并把
     * [InstalledAppsResult.restricted] 带出去给 UI 弹提示。
     *
     * 这里**不排序**——筛选页自己按用户选择（用户/系统/全部 + 名称/最后更新 + 反向）排序。
     */
    fun listInstalledApps(context: Context): InstalledAppsResult {
        val pm = context.packageManager
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var restricted = false

        val apps = runCatching {
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(0)
            // 只有自己 = 被系统限制（正常设备上随便也有几十个包）
            restricted = packages.size <= 1
            val names = if (restricted) {
                LogX.w("读取已安装应用被系统限制，改用 intent 反查包名")
                queryPackageNames(pm)
            } else {
                packages.map { it.packageName }
            }
            names.asSequence()
                .filter { it != context.packageName }
                .mapNotNull { packageName ->
                    runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()?.let { info ->
                        val appInfo = info.applicationInfo
                        if (appInfo == null) {
                            null
                        } else {
                            val label = runCatching { appInfo.loadLabel(pm).toString() }
                                .getOrDefault(info.packageName)
                                .ifBlank { info.packageName }
                            InstalledApp(
                                packageName = info.packageName,
                                label = label,
                                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                lastUpdateTime = info.lastUpdateTime,
                            )
                        }
                    }
                }
                .distinctBy { it.packageName }
                .toList()
        }.onFailure { LogX.e("读取已安装应用列表失败", it) }
            .getOrDefault(emptyList())

        LogX.i(
            "已安装应用 ${apps.size} 个" +
                (if (restricted) "（受限：改用 intent 反查）" else "") +
                "，耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
        )
        return InstalledAppsResult(apps, restricted)
    }

    /** 权限被限制时，靠 intent / provider 反查包名。 */
    private fun queryPackageNames(pm: PackageManager): List<String> {
        val names = LinkedHashSet<String>()
        fun add(block: () -> List<String>) {
            runCatching { names += block() }.onFailure { LogX.e("枚举安装包失败", it) }
        }
        add {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN),
                PackageManager.MATCH_ALL,
            ).mapNotNull { it.activityInfo?.packageName }
        }
        add {
            pm.queryIntentServices(
                android.content.Intent(android.content.Intent.ACTION_MAIN),
                PackageManager.MATCH_ALL,
            ).mapNotNull { it.serviceInfo?.packageName }
        }
        add {
            pm.queryBroadcastReceivers(
                android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED),
                PackageManager.MATCH_ALL,
            ).mapNotNull { it.activityInfo?.packageName }
        }
        add {
            pm.queryContentProviders(null, 0, PackageManager.MATCH_ALL).map { it.packageName }
        }
        return names.toList()
    }

    /**
     * 读取**已安装应用**的包名/应用名/签名。
     *
     * 与 [readSignatureFromApk] 的区别：不需要用户提供 APK 文件，直接问系统要
     * 这个包当前的签名 —— "对已安装应用进行伪装"就是靠它拿到原始签名。
     *
     * @return 应用没装 / 没有签名 / 查询失败时返回 `null`。
     */
    fun readInstalledApp(context: Context, packageName: String): ApkMeta? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.onFailure { LogX.e("读取已安装应用失败：$packageName", it) }
            .getOrNull() ?: return null

        val signatures = pickSignatures(info)
        if (signatures.isEmpty()) {
            LogX.w("已安装应用没有签名：$packageName")
            return null
        }
        val label = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }
            .getOrDefault(packageName)
            .ifBlank { packageName }
        return ApkMeta(
            apkName = label,
            packageName = info.packageName ?: packageName,
            signatureHex = signatureHex(signatures[0]),
        )
    }

    /**
     * 从 SAF Uri 读 APK 的包名与签名。
     *
     * @return 读不出来（不是 APK / 无签名 / 无权限）时返回 `null`，不抛异常。
     */
    fun readSignatureFromApk(context: Context, uri: Uri): ApkMeta? {
        val apkName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "unknown.apk"

        val tempApk = runCatching {
            copyToCache(context, uri)
        }.onFailure {
            LogX.e("复制 APK 到缓存失败：$uri", it)
        }.getOrNull() ?: return null

        val archive = readArchiveInfo(context.packageManager, tempApk.absolutePath)
        if (archive == null) {
            LogX.w("不是可解析的 APK：$apkName")
            return null
        }
        val packageName = archive.first.packageName.orEmpty().trim()
        if (packageName.isEmpty()) {
            LogX.w("APK 没有包名：$apkName")
            return null
        }
        val signatures = archive.second
        if (signatures.isEmpty()) {
            LogX.w("APK 没有签名：$apkName")
            return null
        }
        val hex = signatureHex(signatures[0])
        if (hex.isEmpty()) {
            LogX.w("签名转换失败：$apkName")
            return null
        }
        return ApkMeta(apkName = apkName, packageName = packageName, signatureHex = hex)
    }

    /**
     * 把 [Signature] 转成小写、无分隔的 hex。
     *
     * 等价于 `Signature#toCharsString()`，但显式实现，不依赖那个被标注隐藏的实现细节。
     */
    private fun signatureHex(sig: Signature): String = bytesToHex(sig.toByteArray())

    /** 字节数组转小写 hex。 */
    private fun bytesToHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = CharArray(bytes.size * 2)
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out[index++] = HEX_CHARS[value ushr 4]
            out[index++] = HEX_CHARS[value and 0x0F]
        }
        return String(out)
    }

    /**
     * hex 转字节数组；非法输入返回 `null`（不抛异常）。
     *
     * hook 里用它把配置里的伪造签名还原成 `Signature` 的入参 —— 因此必须对
     * "用户手输 / 粘贴了奇形怪状的字符串"完全免疫。
     */
    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /**
     * 展示用格式化：每 [group] 个字符一行（默认 32 = 16 字节）。
     *
     * 不改大小写、不丢字符 —— 界面想截断自己截，工具方法不替它做决定。
     */
    fun prettifyHex(hex: String, group: Int = 32): String {
        val clean = hex.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ""
        val size = if (group <= 0) 32 else group
        return clean.chunked(size).joinToString("\n")
    }

    /**
     * 读已安装应用的图标；应用没装（条目是用户从 APK 文件挑的）时返回 `null`，
     * 界面自己退回 `ic_launcher`。
     */
    fun loadAppIcon(context: Context, packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 从 [PackageInfo] 里取签名：优先老字段 `signatures`（v1/v2），
     * 为空时退回 `signingInfo.apkContentsSigners`（v3 / 签名轮换）。
     */
    private fun pickSignatures(info: PackageInfo): Array<Signature> {
        @Suppress("DEPRECATION")
        val legacy = info.signatures
        if (!legacy.isNullOrEmpty()) return legacy
        return runCatching { info.signingInfo?.apkContentsSigners }.getOrNull() ?: emptyArray()
    }

    /**
     * 读 APK 归档的包信息与签名。
     *
     * 两条路都试：
     *  1. `GET_SIGNATURES`（老字段 `PackageInfo.signatures`，v1/v2 都能拿到）；
     *  2. `GET_SIGNING_CERTIFICATES`（API 28+，v3 签名 / 签名轮换时只有它有值）。
     *
     * 某些 ROM 上第二条会抛异常，所以全部 runCatching。
     */
    @Suppress("DEPRECATION")
    private fun readArchiveInfo(
        pm: PackageManager,
        apkPath: String,
    ): Pair<PackageInfo, Array<Signature>>? {
        val legacy = runCatching { pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES) }
            .getOrNull()
        val legacySignatures = legacy?.signatures
        if (legacy != null && !legacySignatures.isNullOrEmpty()) {
            return legacy to legacySignatures
        }

        val modern = runCatching {
            pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        val modernSigners = runCatching { modern?.signingInfo?.apkContentsSigners }.getOrNull()
        if (modern != null && !modernSigners.isNullOrEmpty()) {
            return modern to modernSigners
        }
        return null
    }

    /** 把 content Uri 复制到 `cacheDir/pick.apk`。 */
    private fun copyToCache(context: Context, uri: Uri): File {
        val target = File(context.cacheDir, TEMP_APK_NAME)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("openInputStream 返回 null（没有读取权限？）")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink) }
        }
        return target
    }

    /** 取 SAF 的 display name（文件名）；拿不到返回 `null`。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
