package com.xiang.signhelper.ui.liquid

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 当前是不是深色主题。
 *
 * ## 为什么不用示例工程那一个
 *
 * Miuix 示例里的 `ui.isInDarkTheme()` 读的是示例自己维护的 `LocalColorMode`
 * （一个 0..5 的 Int，由示例的 `AppTheme` 写入）。本模块的主题由
 * `AppearanceState` → `ThemeController` 驱动，没有那个 Int，直接搬会永远拿到
 * 错误答案。
 *
 * 这里改成问 Miuix 自己的 [MiuixTheme.colorSchemeMode] —— 那是
 * [top.yukonga.miuix.kmp.theme.ThemeController] 真正在用的模式，
 * 因此用户手动选了"深色"也会被正确识别（只问系统就会答错）。
 *
 * 取不到模式（理论上不会）时退回系统判断。
 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = when (MiuixTheme.colorSchemeMode) {
    ColorSchemeMode.Light,
    ColorSchemeMode.MonetLight,
    -> false

    ColorSchemeMode.Dark,
    ColorSchemeMode.MonetDark,
    -> true

    ColorSchemeMode.System,
    ColorSchemeMode.MonetSystem,
    null,
    -> isSystemInDarkTheme()
}
