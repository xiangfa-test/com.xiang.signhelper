package com.xiang.signhelper.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.util.LogX
import org.json.JSONArray
import org.json.JSONObject

/**
 * 条目仓库：界面唯一的读写入口，同时负责把"生效条目"投影成 hook 端的键。
 *
 * ## 三份数据、一次修改
 *
 * | 键 | 谁读 | 内容 |
 * | --- | --- | --- |
 * | `entries_json` | 只有 UI | 全部条目的 JSON array（含未启用、未填签名的） |
 * | `forged_packages` | hook | **生效**包名，`\n` 分隔 |
 * | `sig:<包名>` | hook | 该包的伪造签名 hex |
 *
 * 每次增删改都要三件事一起做：落盘 `entries_json` → 重写 hook 键 → 更新
 * [entries]（Compose 快照状态，界面自动重组）。顺序不能反：先写 hook 键再发布
 * 状态，界面重组的瞬间看到的已经是新配置。
 *
 * ## 为什么 [entries] 能触发重组
 *
 * 它背后是 [mutableStateOf]；Compose 在组合期间读 `entries` 就记录了一次快照读，
 * 之后任何写都会让读过它的界面重组。**不要**把 `entries` 复制到
 * `remember { mutableStateOf(...) }` 里再读，那样就断了。
 *
 * 本对象只在**模块进程**使用（注入进程里没有 Context，也不该有 UI 状态）。
 */
object SignStore {

    /** 内部快照状态；UI 通过 [entries] 读。 */
    private val entriesState = mutableStateOf<List<SignEntry>>(emptyList())

    /** Compose 可观察的条目列表；读它就会在变化时重组。 */
    val entries: List<SignEntry>
        get() = entriesState.value

    private val lock = Any()

    /**
     * 初始化：绑定 Context、确保 [Config] 已就绪、读一遍磁盘。
     *
     * 可以在 `MainActivity#onCreate` 里重复调用（幂等）。
     */
    fun init(context: Context) {
        Config.init(context)
        reload()
    }

    /**
     * 新增条目；同包名已存在则**替换**（契约要求）。
     *
     * @return 是否成功落盘（内存状态无论如何都会更新）。
     */
    fun add(entry: SignEntry): Boolean {
        val normalized = normalize(entry)
        if (normalized == null) {
            LogX.w("SignStore.add：包名为空，已忽略")
            return false
        }
        synchronized(lock) {
            val list = entriesState.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == normalized.packageName }
            if (index >= 0) list[index] = normalized else list.add(normalized)
            return commit(list)
        }
    }

    /** 删除一条；不存在时安静返回（同时也会清掉可能残留的 `sig:` 键）。 */
    fun remove(packageName: String) {
        synchronized(lock) {
            val current = entriesState.value
            val list = current.filterNot { it.packageName == packageName }
            if (list.size == current.size) {
                // 条目本来就不在，但 prefs 里可能还有旧版本留下的 sig: 键，顺手清一遍。
                syncHookKeys(current)
                return
            }
            commit(list)
        }
    }

    /** 清空全部条目，并清掉 hook 端所有伪造键。 */
    fun clear() {
        synchronized(lock) {
            commit(emptyList())
        }
    }

    /**
     * 修改某包的伪造签名。
     *
     * 只改 [SignEntry.forgedSignature]，**不动**开关 —— 是否启用由 [setEnabled] 决定
     * （界面上的保存流程是 `setForged` + `setEnabled(true)`）。
     */
    fun setForged(packageName: String, forgedSignature: String) {
        synchronized(lock) {
            val list = entriesState.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == packageName }
            if (index < 0) {
                LogX.w("SignStore.setForged：不存在 $packageName")
                return
            }
            list[index] = list[index].copy(forgedSignature = normalizeHex(forgedSignature))
            commit(list)
        }
    }

    /** 打开/关闭某包的伪造开关。 */
    fun setEnabled(packageName: String, enabled: Boolean) {
        synchronized(lock) {
            val list = entriesState.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == packageName }
            if (index < 0) {
                LogX.w("SignStore.setEnabled：不存在 $packageName")
                return
            }
            list[index] = list[index].copy(isForged = enabled)
            commit(list)
        }
    }

    /** 备份恢复的统计结果。 */
    data class RestoreResult(val added: Int, val replaced: Int, val invalid: Int)

    /** 把全部条目导出成带版本号的 JSON 文本（备份用，含未启用/未填签名的条目）。 */
    fun exportBackupJson(): String {
        synchronized(lock) {
            val array = JSONArray()
            entriesState.value.forEach { array.put(it.toJson()) }
            return JSONObject().apply {
                put("format", "signhelper-backup")
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
                put("entries", array)
            }.toString(2)
        }
    }

    /**
     * 从备份 JSON 恢复：按包名合并（同包名覆盖，其余保留）。
     *
     * 兼容 `{"entries": [...]}` 与裸数组两种格式；返回 null 表示文件根本不是备份。
     */
    fun restoreBackupJson(raw: String): RestoreResult? {
        if (raw.isBlank()) return null
        val array = runCatching { JSONObject(raw).optJSONArray("entries") }.getOrNull()
            ?: runCatching { JSONArray(raw) }.getOrNull()
            ?: return null
        var invalid = 0
        val parsed = ArrayList<SignEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val entry = obj?.let { SignEntry.fromJson(it) }?.let { normalize(it) }
            if (entry == null) {
                invalid++
            } else {
                parsed.add(entry)
            }
        }
        val merged = synchronized(lock) {
            val result = mergeEntries(entriesState.value, parsed)
            commit(result.entries)
            result
        }
        LogX.i(
            "恢复备份：新增 ${merged.added}，覆盖 ${merged.replaced}，跳过 $invalid",
            "Restore complete: ${merged.added} added, ${merged.replaced} replaced, $invalid skipped",
        )
        return RestoreResult(merged.added, merged.replaced, invalid)
    }

    /** 更新条目的应用名与原始签名（列表里「重新获取签名信息」用）。 */
    fun updateOriginal(packageName: String, apkName: String, originalSignature: String): Boolean {
        synchronized(lock) {
            val list = entriesState.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == packageName }
            if (index < 0) {
                LogX.w("SignStore.updateOriginal：不存在 $packageName")
                return false
            }
            list[index] = list[index].copy(
                apkName = apkName.trim().ifEmpty { packageName },
                originalSignature = normalizeHex(originalSignature),
            )
            return commit(list)
        }
    }

    /**
     * 从本地/远程 preferences 重新读一遍 `entries_json`。
     *
     * 读完之后会顺手重写一遍 hook 键（自愈）：即使用户手动改过 prefs，或者上一次
     * 写入因为框架没绑定只落了本地，这里也能把 `forged_packages` / `sig:*` 拉回一致。
     */
    private fun reload() {
        synchronized(lock) {
            val parsed = parse(Config.getString(Config.Key.ENTRIES_JSON)).mapNotNull { normalize(it) }
            entriesState.value = parsed
            syncHookKeys(parsed)
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 一次修改的三步：落盘 → 同步 hook 键 → 发布状态。 */
    private fun commit(list: List<SignEntry>): Boolean {
        val ok = persist(list)
        syncHookKeys(list)
        entriesState.value = list
        return ok
    }

    /** 把完整条目列表写成 `entries_json`。 */
    private fun persist(list: List<SignEntry>): Boolean {
        val array = JSONArray()
        for (entry in list) array.put(entry.toJson())
        val ok = Config.set(Config.Key.ENTRIES_JSON, array.toString())
        if (!ok) LogX.w("entries_json 落盘失败（框架未绑定？），界面状态仍已更新")
        return ok
    }

    /**
     * 把"生效条目"投影成 hook 端读取的两个键。
     *
     * 顺序很重要：
     * 1. 先删掉不再生效的 `sig:*`，避免 hook 读到过期签名；
     * 2. 再写当前的 `sig:*`；
     * 3. **最后**写 `forged_packages` —— hook 以它为准遍历，它最后写能保证
     *    读到的每个包名都已经有对应签名。
     */
    private fun syncHookKeys(list: List<SignEntry>) {
        if (!Config.writable) return
        val active = list.filter { it.effective }
        val activePackages = active.mapTo(HashSet()) { it.packageName }

        // 1) 清理"曾经存在但已删除/已禁用"的签名键
        val stalePackages = Config.all().keys
            .asSequence()
            .filter { it.startsWith(Config.Key.SIG_PREFIX) }
            .map { it.removePrefix(Config.Key.SIG_PREFIX) }
            .filter { it.isNotEmpty() && it !in activePackages }
            .toList()
        for (packageName in stalePackages) {
            Config.set(Config.Key.SIG_PREFIX + packageName, null)
        }

        // 2) 写当前生效的签名
        for (entry in active) {
            Config.set(Config.Key.SIG_PREFIX + entry.packageName, normalizeHex(entry.forgedSignature))
        }

        // 3) 最后写包名清单；空列表写 ""（契约规定，不能用 remove）
        Config.set(Config.Key.FORGED_PACKAGES, active.joinToString("\n") { it.packageName })
    }

    /** 解析 `entries_json`；任何坏数据都只记日志、不抛出。 */
    private fun parse(raw: String?): List<SignEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<SignEntry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                SignEntry.fromJson(obj)?.let(out::add)
            }
            out
        }.onFailure { LogX.e("解析 entries_json 失败，按空列表处理", it) }
            .getOrDefault(emptyList())
    }

    /** 去掉首尾空白 + 统一小写；包名为空或非法（纯空白）时返回 `null`。 */
    private fun normalize(entry: SignEntry): SignEntry? {
        val packageName = entry.packageName.trim()
        if (packageName.isEmpty()) return null
        return entry.copy(
            packageName = packageName,
            apkName = entry.apkName.trim().ifEmpty { packageName },
            originalSignature = normalizeHex(entry.originalSignature),
            forgedSignature = normalizeHex(entry.forgedSignature),
        )
    }

    /** hex 统一成小写、去掉空白与常见分隔符（用户从日志/文件管理器里粘过来的会有）。 */
    private fun normalizeHex(hex: String): String =
        hex.filterNot { it.isWhitespace() || it == ':' || it == '-' }.lowercase()
}

/** 合并结果：最终列表 + 新增/覆盖条数。 */
internal data class MergeOutcome(
    val entries: List<SignEntry>,
    val added: Int,
    val replaced: Int,
)

/**
 * 按包名把 [incoming] 合并进 [existing]：同包名覆盖，其他条目保持原顺序。
 * 抽成纯函数便于单元测试，不触碰 SharedPreferences / Compose 状态。
 */
internal fun mergeEntries(existing: List<SignEntry>, incoming: List<SignEntry>): MergeOutcome {
    val merged = existing.toMutableList()
    var added = 0
    var replaced = 0
    incoming.forEach { entry ->
        val index = merged.indexOfFirst { it.packageName == entry.packageName }
        if (index >= 0) {
            merged[index] = entry
            replaced++
        } else {
            merged.add(entry)
            added++
        }
    }
    return MergeOutcome(merged, added, replaced)
}
