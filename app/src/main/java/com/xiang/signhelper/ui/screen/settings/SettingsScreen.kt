package com.xiang.signhelper.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

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
}
