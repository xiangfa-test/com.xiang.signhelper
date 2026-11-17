package com.xiang.signhelper.util

/**
 * 伪造签名的**文本编解码**：一段用户配置的字符串 <-> 若干条签名。
 *
 * ## 为什么要单独一层
 *
 * 上游（SignHook）允许一个包填**多条**签名（多签名 APK / 签名轮换），配置值
 * 按行拆开。本地之前的约定是「一个包一条 hex」，UI 的 [com.xiang.signhelper.data.SignStore]
 * 还会把输入里的所有空白（含换行）直接过滤掉 —— 两段签名粘进去会被拼成一条
 * 非法 hex，等于静默失效。
 *
 * 所以把"怎么切、怎么规整"抽成这个**纯字符串**对象：hook 端、UI 端共用同一套
 * 规则，也方便写 JVM 单元测试（不碰 Android 类）。
 *
 * ## 规则
 *
 * - 分隔符：换行（`\n` / `\r`）、逗号、分号 —— 用户从日志、文件管理器、
 *   `keytool` 输出里复制过来的形态都能吃；
 * - 每条里再滤掉空白与 `:` `-` 分隔符（`AA:BB:CC` 这种指纹写法），统一小写；
 * - 空行、纯分隔符的行直接丢掉；顺序保持原样（签名顺序在多签名场景有意义）。
 */
object FakeSignatureCodec {

    /** 行分隔符。 */
    private val LINE_SEPARATORS = charArrayOf('\n', '\r', ',', ';')

    /** 一条签名内部允许出现的装饰性字符。 */
    private val NOISE_CHARS = charArrayOf(' ', '\t', ':', '-')

    /**
     * 把配置原文拆成若干条规整后的 hex。
     *
     * @return 顺序不变、已去空、已小写；原文没有内容时返回空列表（不是 `null`）。
     */
    fun splitHexLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>(2)
        for (part in raw.split(*LINE_SEPARATORS)) {
            val clean = normalize(part)
            if (clean.isNotEmpty()) out.add(clean)
        }
        return out
    }

    /** 单条 hex 的规整：去掉空白与 `:` `-`，转小写。 */
    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch in NOISE_CHARS) continue
            out.append(ch.lowercaseChar())
        }
        return out.toString()
    }

    /** 多条 hex 合成配置字符串的规范形态：一行一条。 */
    fun joinLines(lines: List<String>): String = lines.joinToString("\n")

    /**
     * 单条 hex 转字节；非法输入返回 `null`（不抛异常）。
     *
     * 实现委托给 [SignatureUtil.hexToBytes]，两边规则不允许出现第二份。
     */
    fun decodeHex(hex: String): ByteArray? = SignatureUtil.hexToBytes(hex)
}
