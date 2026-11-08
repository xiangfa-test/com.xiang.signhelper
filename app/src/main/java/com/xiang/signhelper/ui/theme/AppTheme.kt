package com.xiang.signhelper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.xiang.signhelper.ui.component.LocalEnableBlur
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 用 Miuix（澎湃 OS / MIUI 设计体系）包住整个界面。
 *
 * 本模块**没有外观设置页**：配色固定跟随系统深浅色，动态取色（Monet）不开 ——
 * 这是用户要求的"只留两个页签"。原来的 `ThemePrefs` / `AppearanceState`
 * 那套「可观察外观状态 + 共享偏好」的管线因此一并去掉，避免留下永远读不到的开关。
 *
 * [LocalEnableBlur] 仍然是有的：顶栏/底栏的实时模糊要由 `Blur.kt` 统一开关，
 * 设备不支持 RenderEffect 时 `rememberBlurBackdrop` 会自己返回 null。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()

    // 只在深浅色变化时重建 controller：它内部状态可变，
    // 每次重组都新建会让 Miuix 里的 remember 缓存全部失效。
    val controller = remember(dark) {
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        )
    }

    CompositionLocalProvider(LocalEnableBlur provides true) {
        MiuixTheme(controller = controller, content = content)
    }
}
