package com.xiang.signhelper.ui.screen.catalog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.R
import com.xiang.signhelper.data.SignEntry
import com.xiang.signhelper.data.SignStore
import com.xiang.signhelper.ui.component.AppIcon
import com.xiang.signhelper.ui.component.MessageDialog
import com.xiang.signhelper.ui.component.PageScaffold
import com.xiang.signhelper.ui.component.SignatureInfoDialog
import com.xiang.signhelper.ui.component.TextInputDialog
import com.xiang.signhelper.ui.component.cardPadding
import com.xiang.signhelper.ui.component.pageScroll
import com.xiang.signhelper.ui.component.rememberAppIcon
import com.xiang.signhelper.util.SignatureUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 伪造签名只接受「纯十六进制」：0-9 / a-f / A-F。 */
private fun isHexSignature(text: String): Boolean =
    text.isNotEmpty() && text.all { ch ->
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
    }

/**
 * 「应用目录」页签。
 *
 * ## 数据从哪来
 *
 * 直接读 [SignStore.entries] —— 它是 `mutableStateOf` 支撑的 snapshot state，
 * 增删改之后本页自动重组，不需要 ViewModel、也不需要手动刷新。
 *
 * ## 两种添加方式
 *
 * - **选择已安装应用**：跳二级页（[com.xiang.signhelper.ui.screen.installed.InstalledAppPickerScreen]）
 *   挑一个系统里已装的应用，回来由本页读它的真实签名并入库；
 * - **选择 APK**：SAF 选安装包文件，只读取并展示包名/签名原始数据（可复制），
 *   **不会**加入伪装列表；伪装目标只从「选择已安装应用」来。
 *
 * 「选择已安装应用」选中后弹「编辑伪造签名」。
 */
@Composable
fun CatalogScreen(
    bottomPadding: Dp = 0.dp,
    /** 从已安装应用二级页带回来的包名；消费后由调用方清空。 */
    pendingInstalledPackage: String? = null,
    onPendingInstalledConsumed: () -> Unit = {},
    onOpenInstalledPicker: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 唯一数据源。读它就是订阅：SignStore 内部改了，本页重组。
    val entries = SignStore.entries

    // 文案在组合期取好，回调里直接用 —— 避免在非组合上下文里用 LocalContext 取资源
    // （配置变化时可能拿到旧值）。
    val failedInstalledText = stringResource(R.string.catalog_pick_installed_failed)
    val failedApkText = stringResource(R.string.catalog_pick_failed)
    val noPickerText = stringResource(R.string.catalog_pick_no_picker)

    // 对话框状态。用「目标条目」而不是布尔值：对话框标题/初值直接从目标上取。
    var editing by remember { mutableStateOf<SignEntry?>(null) }
    var deleting by remember { mutableStateOf<SignEntry?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    // 「选择 APK」只读取并展示签名原始数据，不加入伪装列表。
    var apkSignature by remember { mutableStateOf<SignatureUtil.ApkMeta?>(null) }
    // 列表条目的签名信息对话框（可重新读取原始签名）。
    var signatureView by remember { mutableStateOf<SignEntry?>(null) }
    // 备份/恢复结果提示。
    var backupTitle by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    // 右上角「更多」菜单（备份 / 恢复）。
    var showMoreMenu by remember { mutableStateOf(false) }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SignStore.exportBackupJson().toByteArray(Charsets.UTF_8))
            } ?: error("无法打开输出流")
        }.isSuccess
        backupTitle = context.getString(R.string.catalog_backup)
        backupMessage = if (ok) {
            context.getString(R.string.catalog_backup_done, SignStore.entries.size)
        } else {
            context.getString(R.string.catalog_backup_failed)
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val result = text?.let { SignStore.restoreBackupJson(it) }
        backupTitle = context.getString(R.string.catalog_restore)
        backupMessage = when {
            text == null -> context.getString(R.string.catalog_restore_read_failed)
            result == null -> context.getString(R.string.catalog_restore_failed)
            else -> context.getString(
                R.string.catalog_restore_done,
                result.added,
                result.replaced,
                result.invalid,
            )
        }
    }

    /** 把一条应用信息写进列表；同包名已存在时合并（保留已填的伪造签名）。 */
    fun upsert(meta: SignatureUtil.ApkMeta): SignEntry {
        val existing = SignStore.entries.firstOrNull { it.packageName == meta.packageName }
        val entry = existing?.copy(
            apkName = meta.apkName,
            originalSignature = meta.signatureHex,
        ) ?: SignEntry(
            packageName = meta.packageName,
            apkName = meta.apkName,
            originalSignature = meta.signatureHex,
            forgedSignature = "",
            isForged = false,
        )
        SignStore.add(entry)
        return entry
    }

    // 从「选择已安装应用」二级页回来：读签名 → 入库 → 弹编辑框。
    LaunchedEffect(pendingInstalledPackage) {
        val packageName = pendingInstalledPackage ?: return@LaunchedEffect
        // 注意：这里**先**读签名、入库，最后才 consume。
        // 如果在 withContext 之前就把 pending 清空，key 从包名变成 null 会立刻
        // 取消这个 effect，签名根本没读、条目也不会进列表。
        val meta = withContext(Dispatchers.IO) {
            runCatching { SignatureUtil.readInstalledApp(context, packageName) }.getOrNull()
        }
        if (meta == null) {
            pickError = failedInstalledText
            onPendingInstalledConsumed()
            return@LaunchedEffect
        }
        editing = upsert(meta)
        onPendingInstalledConsumed()
    }

    // SAF 选 APK。用 OpenDocument 而不是 GetContent：不需要 READ_EXTERNAL_STORAGE，
    // 也不需要用户先把 APK 放进某个固定目录。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching { SignatureUtil.readSignatureFromApk(context, uri) }.getOrNull()
            }
            reading = false
            if (meta == null) {
                pickError = failedApkText
                return@launch
            }
            apkSignature = meta
        }
    }

    PageScaffold(
        titleRes = R.string.page_catalog,
        bottomPadding = bottomPadding,
        actions = {
            Box {
                OverlayListPopup(
                    show = showMoreMenu,
                    popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(),
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { showMoreMenu = false },
                    content = {
                        ListPopupColumn {
                            DropdownImpl(
                                text = stringResource(R.string.catalog_backup),
                                optionSize = 2,
                                isSelected = false,
                                index = 0,
                                onSelectedIndexChange = {
                                    showMoreMenu = false
                                    runCatching { exportBackup.launch("signhelper-backup.json") }
                                        .onFailure { pickError = noPickerText }
                                },
                            )
                            DropdownImpl(
                                text = stringResource(R.string.catalog_restore),
                                optionSize = 2,
                                isSelected = false,
                                index = 1,
                                onSelectedIndexChange = {
                                    showMoreMenu = false
                                    runCatching {
                                        importBackup.launch(
                                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                                        )
                                    }.onFailure { pickError = noPickerText }
                                },
                            )
                        }
                    },
                )
                IconButton(
                    onClick = { showMoreMenu = true },
                    holdDownState = showMoreMenu,
                ) {
                    Icon(
                        imageVector = MiuixIcons.MoreCircle,
                        contentDescription = stringResource(R.string.catalog_menu_more),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { contentPadding, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageScroll(scrollBehavior),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            overscrollEffect = null,
        ) {
            // ---------------------------------------------------------- 操作卡
            item {
                Card(modifier = Modifier.cardPadding()) {
                    ArrowPreference(
                        title = stringResource(R.string.catalog_pick_installed),
                        summary = stringResource(R.string.catalog_pick_installed_summary),
                        enabled = !reading,
                        onClick = onOpenInstalledPicker,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.catalog_pick),
                        summary = stringResource(
                            if (reading) R.string.catalog_pick_reading else R.string.catalog_pick_summary,
                        ),
                        enabled = !reading,
                        onClick = {
                            runCatching {
                                picker.launch(arrayOf(APK_MIME_TYPE))
                            }.onFailure {
                                pickError = noPickerText
                            }
                        },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.catalog_clear),
                        summary = stringResource(R.string.catalog_clear_summary),
                        enabled = entries.isNotEmpty(),
                        onClick = { showClearDialog = true },
                    )
                }
            }

            // ---------------------------------------------------------- 列表
            if (entries.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    SmallTitle(
                        text = stringResource(R.string.catalog_list_title, entries.size),
                    )
                }
                items(entries, key = { it.packageName }) { entry ->
                    SignEntryCard(
                        entry = entry,
                        onClick = { editing = entry },
                        onLongClick = { deleting = entry },
                        onToggle = { enabled ->
                            // 还没填伪造签名就打开开关的话，先让用户把签名填了 ——
                            // 否则开关是"开"的但 effective 依然是 false，容易误解。
                            if (enabled && entry.forgedSignature.isBlank()) {
                                editing = entry
                            } else {
                                SignStore.setEnabled(entry.packageName, enabled)
                            }
                        },
                        onShowSignature = { signatureView = entry },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 对话框
        // 保存时 setForged + setEnabled(true)，也就是"填了就生效"。
        val editTarget = editing
        TextInputDialog(
            show = editTarget != null,
            title = stringResource(R.string.catalog_edit_title),
            summary = editTarget?.let {
                stringResource(R.string.catalog_edit_summary, it.apkName, it.packageName)
            },
            initial = editTarget?.forgedSignature.orEmpty(),
            validate = ::isHexSignature,
            errorMessage = stringResource(R.string.catalog_edit_error),
            onAccept = { hex ->
                editTarget?.let {
                    SignStore.setForged(it.packageName, hex)
                    SignStore.setEnabled(it.packageName, true)
                }
            },
            onDismiss = { editing = null },
        )

        // 长按删除
        val deleteTarget = deleting
        MessageDialog(
            show = deleteTarget != null,
            title = stringResource(R.string.catalog_delete_title),
            summary = deleteTarget?.let {
                stringResource(R.string.catalog_delete_message, it.apkName)
            },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                deleteTarget?.let { SignStore.remove(it.packageName) }
                deleting = null
            },
            onDismiss = { deleting = null },
        )

        // 清空确认
        MessageDialog(
            show = showClearDialog,
            title = stringResource(R.string.catalog_clear_title),
            summary = stringResource(R.string.catalog_clear_message),
            confirmText = stringResource(R.string.common_clear),
            onConfirm = {
                SignStore.clear()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )

        // APK 原始签名信息（只读，不进伪装列表）
        val pickedApk = apkSignature
        SignatureInfoDialog(
            show = pickedApk != null,
            title = stringResource(R.string.catalog_apk_signature_title),
            apkName = pickedApk?.apkName.orEmpty(),
            packageName = pickedApk?.packageName.orEmpty(),
            signature = pickedApk?.signatureHex.orEmpty(),
            onDismiss = { apkSignature = null },
        )

        // 读取失败
        MessageDialog(
            show = pickError != null,
            title = stringResource(R.string.catalog_pick_failed_title),
            summary = pickError,
            onDismiss = { pickError = null },
        )

        // 列表条目的签名信息（带"重新获取"）
        val viewTarget = signatureView
        SignatureInfoDialog(
            show = viewTarget != null,
            title = stringResource(R.string.catalog_signature_info),
            apkName = viewTarget?.apkName.orEmpty(),
            packageName = viewTarget?.packageName.orEmpty(),
            signature = viewTarget?.originalSignature.orEmpty(),
            summary = stringResource(R.string.catalog_signature_info_summary),
            onRefresh = {
                val target = viewTarget
                if (target != null) {
                    scope.launch {
                        val meta = withContext(Dispatchers.IO) {
                            runCatching { SignatureUtil.readInstalledApp(context, target.packageName) }.getOrNull()
                        }
                        val current = signatureView
                        if (meta != null && current != null) {
                            SignStore.updateOriginal(target.packageName, meta.apkName, meta.signatureHex)
                            signatureView = current.copy(apkName = meta.apkName, originalSignature = meta.signatureHex)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.catalog_signature_refreshed),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.catalog_signature_refresh_failed),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onDismiss = { signatureView = null },
        )

        // 备份/恢复结果
        MessageDialog(
            show = backupMessage != null,
            title = backupTitle,
            summary = backupMessage,
            onDismiss = { backupMessage = null },
        )
    }
}

/**
 * 条目卡：图标 + 名称 + 包名 + 两份签名 + 启用开关。
 *
 * 点击与长按都交给 Miuix `Card` 自己的 `onClick` / `onLongPress`（它带
 * [PressFeedbackType.Sink] 的按压下沉反馈）；长按删除比在卡片里再塞一个删除按钮
 * 更符合 Miuix / 系统设置的习惯。内部的 `Switch` 自己消费点击，
 * 不会把"切换开关"误当成"打开编辑对话框"。
 */
@Composable
private fun SignEntryCard(
    entry: SignEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onShowSignature: () -> Unit,
) {
    val icon = rememberAppIcon(entry.packageName)
    val forged = entry.forgedSignature

    Card(
        modifier = Modifier
            .cardPadding()
            .fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onLongClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon)

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.apkName,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.packageName,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.catalog_original_signature,
                        SignatureUtil.prettifyHex(entry.originalSignature),
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (forged.isBlank()) {
                        stringResource(R.string.catalog_forged_unset)
                    } else {
                        stringResource(
                            R.string.catalog_forged_signature,
                            SignatureUtil.prettifyHex(forged),
                        )
                    },
                    color = if (forged.isBlank()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(
                onClick = onShowSignature,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.catalog_signature_info),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Switch(
                checked = entry.isForged,
                onCheckedChange = onToggle,
            )
        }
    }
}

/** 空态：一个大标题 + 一句引导，不是什么都没有的白板。 */
@Composable
private fun EmptyState() {
    Card(modifier = Modifier.cardPadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.catalog_empty),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.title3,
            )
            Text(
                text = stringResource(R.string.catalog_empty_hint),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** APK 在 SAF 里的 MIME 类型。 */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
