package com.xiang.signhelper.ui.screen.installed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xiang.signhelper.R
import com.xiang.signhelper.ui.component.AppRowCard
import com.xiang.signhelper.ui.component.BlurredBar
import com.xiang.signhelper.ui.component.LocalEnableBlur
import com.xiang.signhelper.ui.component.LocalTopBarBlurBackdrop
import com.xiang.signhelper.ui.component.MessageDialog
import com.xiang.signhelper.ui.component.rememberAppIcon
import com.xiang.signhelper.ui.component.rememberBlurBackdrop
import com.xiang.signhelper.util.SignatureUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 应用范围筛选。 */
private enum class AppScope { USER, SYSTEM, ALL }

/** 排序依据。 */
private enum class AppSort { NAME, UPDATED }

/**
 * 「选择已安装应用」二级页。
 *
 * 版式照 KernelSU「超级用户」：顶栏标题 + 右上角排序/更多菜单 + 搜索框，
 * 下面是应用卡片列表（图标/名称/包名/箭头）。
 *
 * - 排序菜单：应用名称 / 最后更新 + 反向排序
 * - 更多菜单：应用范围 用户 / 系统 / 全部（默认用户）
 * - 默认排序：最后更新（最新在前）
 */
@Composable
fun InstalledAppPickerScreen(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current

    var apps by remember { mutableStateOf<List<SignatureUtil.InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableStateOf(AppScope.USER) }
    var sort by remember { mutableStateOf(AppSort.UPDATED) }
    var reversed by remember { mutableStateOf(false) }
    var showSortPopup by remember { mutableStateOf(false) }
    var showMorePopup by remember { mutableStateOf(false) }

    // 系统（MIUI / HyperOS）限制「获取应用列表」时置 true：列表走 intent 反查，可能不全，
    // 需要弹窗引导用户去应用信息里开启权限。
    var restricted by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionAsked by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var waitingForPermission by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(reloadToken) {
        apps = null
        val result = withContext(Dispatchers.IO) { SignatureUtil.listInstalledApps(context) }
        apps = result.apps
        restricted = result.restricted
        if (result.restricted && !permissionAsked) {
            permissionAsked = true
            showPermissionDialog = true
        }
    }

    // 从系统设置返回后自动重载：用户可能刚把「获取应用列表」打开。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForPermission) {
                waitingForPermission = false
                reloadToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val visibleApps = remember(apps, query, scopeFilter, sort, reversed) {
        val keyword = query.trim()
        val filtered = apps.orEmpty().filter { app ->
            val scopeMatched = when (scopeFilter) {
                AppScope.USER -> !app.isSystem
                AppScope.SYSTEM -> app.isSystem
                AppScope.ALL -> true
            }
            scopeMatched && (
                keyword.isEmpty() ||
                    app.label.contains(keyword, ignoreCase = true) ||
                    app.packageName.contains(keyword, ignoreCase = true)
                )
        }
        val sorted = when (sort) {
            AppSort.NAME -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            AppSort.UPDATED -> filtered.sortedByDescending { it.lastUpdateTime }
        }
        if (reversed) sorted.reversed() else sorted
    }

    val enableBlur = LocalEnableBlur.current
    val sharedBackdrop = LocalTopBarBlurBackdrop.current
    val backdrop = sharedBackdrop ?: rememberBlurBackdrop(enableBlur)
    val blurActive = enableBlur && backdrop != null
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = enableBlur) {
                TopAppBar(
                    title = stringResource(R.string.installed_picker_title),
                    scrollBehavior = scrollBehavior,
                    color = if (blurActive) androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        Box {
                            OverlayListPopup(
                                show = showSortPopup,
                                popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(),
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showSortPopup = false },
                                content = {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            text = stringResource(R.string.installed_filter_sort_name),
                                            optionSize = 3,
                                            isSelected = sort == AppSort.NAME,
                                            index = 0,
                                            onSelectedIndexChange = {
                                                sort = AppSort.NAME
                                                showSortPopup = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.installed_filter_sort_updated),
                                            optionSize = 3,
                                            isSelected = sort == AppSort.UPDATED,
                                            index = 1,
                                            onSelectedIndexChange = {
                                                sort = AppSort.UPDATED
                                                showSortPopup = false
                                            },
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                            thickness = 1.5.dp,
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.installed_filter_reverse),
                                            optionSize = 3,
                                            isSelected = reversed,
                                            index = 2,
                                            onSelectedIndexChange = {
                                                reversed = !reversed
                                                showSortPopup = false
                                            },
                                        )
                                    }
                                },
                            )
                            IconButton(
                                onClick = { showSortPopup = true },
                                holdDownState = showSortPopup,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Sort,
                                    contentDescription = stringResource(R.string.catalog_menu_sort),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        Box {
                            OverlayListPopup(
                                show = showMorePopup,
                                popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(),
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showMorePopup = false },
                                content = {
                                    ListPopupColumn {
                                        val scopes = listOf(
                                            AppScope.USER to R.string.installed_filter_scope_user,
                                            AppScope.SYSTEM to R.string.installed_filter_scope_system,
                                            AppScope.ALL to R.string.installed_filter_scope_all,
                                        )
                                        scopes.forEachIndexed { index, (scopeOption, labelRes) ->
                                            DropdownImpl(
                                                text = stringResource(labelRes),
                                                optionSize = scopes.size,
                                                isSelected = scopeFilter == scopeOption,
                                                index = index,
                                                onSelectedIndexChange = {
                                                    scopeFilter = scopeOption
                                                    showMorePopup = false
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                            IconButton(
                                onClick = { showMorePopup = true },
                                holdDownState = showMorePopup,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    contentDescription = stringResource(R.string.catalog_menu_more),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    bottomContent = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            label = stringResource(R.string.installed_picker_search),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 8.dp),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                apps == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        InfiniteProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            text = stringResource(R.string.installed_picker_loading),
                            modifier = Modifier.padding(top = 12.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote2,
                        )
                    }
                }

                visibleApps.isEmpty() -> {
                    CenteredHint(stringResource(R.string.installed_picker_empty))
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 6.dp,
                            bottom = innerPadding.calculateBottomPadding() + 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        overscrollEffect = null,
                    ) {
                        if (restricted) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    TextButton(
                                        text = stringResource(R.string.installed_permission_banner),
                                        onClick = {
                                            waitingForPermission = true
                                            openAppSettings(context)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        items(visibleApps, key = { it.packageName }) { app ->
                            AppRowCard(
                                title = app.label,
                                summary = app.packageName,
                                icon = rememberAppIcon(app.packageName),
                                onClick = { onPick(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 系统限制了应用列表读取时，弹一次原生「应用信息」页引导用户自己开权限。
    MessageDialog(
        show = showPermissionDialog,
        title = stringResource(R.string.installed_permission_title),
        summary = stringResource(R.string.installed_permission_summary),
        confirmText = stringResource(R.string.installed_permission_settings),
        cancelText = stringResource(R.string.installed_permission_later),
        onConfirm = {
            showPermissionDialog = false
            waitingForPermission = true
            openAppSettings(context)
        },
        onDismiss = { showPermissionDialog = false },
    )
}

/** 打开系统原生的「应用信息」页，由用户自己在权限里开启「获取应用列表」。 */
private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun CenteredHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}
