package com.xiang.signhelper.ui.screen.installed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.R
import com.xiang.signhelper.ui.component.AppRowCard
import com.xiang.signhelper.ui.component.BlurredBar
import com.xiang.signhelper.ui.component.LocalEnableBlur
import com.xiang.signhelper.ui.component.LocalTopBarBlurBackdrop
import com.xiang.signhelper.ui.component.rememberAppIcon
import com.xiang.signhelper.ui.component.rememberBlurBackdrop
import com.xiang.signhelper.util.SignatureUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
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

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        if (apps == null) {
            apps = withContext(Dispatchers.IO) { SignatureUtil.listInstalledApps(context) }
        }
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
                    CenteredHint(stringResource(R.string.installed_picker_loading))
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
