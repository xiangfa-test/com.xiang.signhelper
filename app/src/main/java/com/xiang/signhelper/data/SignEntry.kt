package com.xiang.signhelper.data

import org.json.JSONObject

/**
 * 一条"签名条目"：某个目标包 + 它的原始签名 + 是否要伪造 / 伪造成什么。
 *
 * 字段语义完全对应原版 `cn.xihan.sign` 的 `ApkSignature`（`packageName` /
 * `apkName` / `originalSignature` / `forgedSignature` / `isForged`），
 * 只是换了存储通道（见 [com.xiang.signhelper.config.Config]）。
 *
 * ## 关于签名的表示
 *
 * 两个签名都是**小写、无分隔的 hex 字符串**，也就是
 * `Signature#toCharsString()` 的输出形式，例如：
 *
 * ```
 * 308203...（十六进制）
 * ```
 *
 * 之所以不用 base64 / 原样字节：原版就是 hex，用户的旧配置（如果手动迁移）
 * 也能直接粘进来；而 hook 端把 hex 还原成字节后 `Signature(bytes)` 即可。
 *
 * [isForged] 是"用户打开了开关"，[forgedSignature] 是"填了假签名" —— 两者都满足
 * 才真的会生效，见 [effective]。
 */
data class SignEntry(
    /** 目标包名，例如 `com.tencent.mm`。 */
    val packageName: String,
    /** APK 文件名 / 应用名（展示用）。 */
    val apkName: String,
    /** 原始签名 hex。 */
    val originalSignature: String,
    /** 伪造签名 hex（可为空）。 */
    val forgedSignature: String,
    /** 是否启用伪造。 */
    val isForged: Boolean,
) {

    /** 真正会生效的条件：开关开着 **且** 伪造签名不为空。 */
    val effective: Boolean
        get() = isForged && forgedSignature.isNotBlank()

    /** 序列化成一个 JSON 对象，键名与旧版本保持同名，方便迁移。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PACKAGE_NAME, packageName)
        put(KEY_APK_NAME, apkName)
        put(KEY_ORIGINAL_SIGNATURE, originalSignature)
        put(KEY_FORGED_SIGNATURE, forgedSignature)
        put(KEY_IS_FORGED, isForged)
    }

    companion object {
        private const val KEY_PACKAGE_NAME = "packageName"
        private const val KEY_APK_NAME = "apkName"
        private const val KEY_ORIGINAL_SIGNATURE = "originalSignature"
        private const val KEY_FORGED_SIGNATURE = "forgedSignature"
        private const val KEY_IS_FORGED = "isForged"

        /**
         * 从 JSON 还原；缺字段时用安全默认值。
         *
         * 包名是唯一必要的字段 —— 空的条目没有意义，直接返回 `null` 让调用方丢掉。
         */
        fun fromJson(json: JSONObject): SignEntry? {
            val packageName = json.optString(KEY_PACKAGE_NAME).trim()
            if (packageName.isEmpty()) return null
            return SignEntry(
                packageName = packageName,
                apkName = json.optString(KEY_APK_NAME, packageName),
                originalSignature = json.optString(KEY_ORIGINAL_SIGNATURE, ""),
                forgedSignature = json.optString(KEY_FORGED_SIGNATURE, ""),
                isForged = json.optBoolean(KEY_IS_FORGED, false),
            )
        }
    }
}
