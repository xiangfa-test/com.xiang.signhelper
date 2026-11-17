package com.xiang.signhelper.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.R
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.ui.component.PageScaffold
import com.xiang.signhelper.ui.component.cardPadding
import com.xiang.signhelper.ui.component.pageScroll
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页：行为 / 日志 / 语言 / 开发者。
 */
@Composable
fun SettingsScreen(
    bottomPadding: Dp = 0.dp,
    onReplayOobe: () -> Unit = {},
) {
    val context = LocalContext.current
    var pickTarget by remember { mutableStateOf(Config.pickTarget()) }
    var enhancedHook by remember { mutableStateOf(Config.isEnhancedHook()) }
    var showEnhancedDetail by remember { mutableStateOf(false) }
    var logLevel by remember { mutableStateOf(Config.logLevel()) }
    var logLang by remember { mutableStateOf(Config.logLanguage()) }
    var appLang by remember { mutableStateOf(Config.appLanguage()) }

    fun applyAppLanguage(tag: String) {
        appLang = tag
        Config.setAppLanguage(tag)
        runCatching {
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            lm.applicationLocales = if (tag == "system") {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(tag)
            }
        }
        (context as? android.app.Activity)?.recreate()
    }

    PageScaffold(
        titleRes = R.string.page_settings,
        bottomPadding = bottomPadding,
    ) { contentPadding, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageScroll(scrollBehavior),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            overscrollEffect = null,
        ) {
            item { SmallTitle(text = stringResource(R.string.settings_behavior)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    // 一个开关：开 = 增强（新版逻辑），关 = 旧版逻辑。
                    // 右侧那个小图标是「详细说明」入口，点开是对话框；点行本身只切换开关。
                    SwitchPreference(
                        checked = enhancedHook,
                        onCheckedChange = { enabled ->
                            enhancedHook = enabled
                            Config.setEnhancedHook(enabled)
                        },
                        title = stringResource(R.string.settings_enhanced_mode),
                        summary = stringResource(R.string.settings_enhanced_mode_summary),
                        endActions = {
                            IconButton(onClick = { showEnhancedDetail = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = stringResource(R.string.settings_enhanced_mode_detail_title),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_pick_target),
                        summary = stringResource(R.string.settings_pick_detail_summary),
                        items = listOf(
                            stringResource(R.string.settings_pick_target_detail),
                            stringResource(R.string.settings_pick_target_list),
                        ),
                        selectedIndex = if (pickTarget == "home") 0 else 1,
                        onSelectedIndexChange = { index ->
                            pickTarget = if (index == 0) "home" else "list"
                            Config.setPickTarget(pickTarget)
                        },
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.settings_log_section)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_log_output),
                        items = listOf(
                            stringResource(R.string.settings_log_off),
                            stringResource(R.string.settings_log_normal),
                            stringResource(R.string.settings_log_verbose),
                        ),
                        selectedIndex = logLevel.coerceIn(0, 2),
                        onSelectedIndexChange = { index ->
                            logLevel = index.coerceIn(0, 2)
                            Config.setLogLevel(logLevel)
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_log_lang),
                        items = listOf(
                            stringResource(R.string.settings_log_lang_en),
                            stringResource(R.string.settings_log_lang_zh),
                        ),
                        selectedIndex = if (logLang == "zh") 1 else 0,
                        onSelectedIndexChange = { index ->
                            logLang = if (index == 1) "zh" else "en"
                            Config.setLogLanguage(logLang)
                        },
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.settings_language)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_language),
                        items = listOf(
                            stringResource(R.string.settings_language_system),
                            stringResource(R.string.settings_language_zh),
                            stringResource(R.string.settings_language_en),
                        ),
                        selectedIndex = when (appLang) {
                            "zh-CN" -> 1
                            "en" -> 2
                            else -> 0
                        },
                        onSelectedIndexChange = { index ->
                            applyAppLanguage(
                                when (index) {
                                    1 -> "zh-CN"
                                    2 -> "en"
                                    else -> "system"
                                },
                            )
                        },
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.settings_developer)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_replay_oobe),
                        onClick = {
                            Config.setOobeDone(false)
                            onReplayOobe()
                        },
                    )
                }
            }
        }
    }

    // 「增强模式」的详细说明：点开关右侧的小图标弹出，只读不写。
    EnhancedModeDialog(
        show = showEnhancedDetail,
        onDismiss = { showEnhancedDetail = false },
    )
}

/**
 * 增强模式的说明对话框。
 *
 * 正文是左对齐、可滚动的一段说明 —— 用通用 [MessageDialog] 会被居中排版，
 * 条目一多就不好读，小屏上还会把最后一段挤出对话框。
 */
@Composable
private fun EnhancedModeDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.settings_enhanced_mode_detail_title),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.settings_enhanced_mode_detail),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            TextButton(
                text = stringResource(R.string.common_ok),
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
