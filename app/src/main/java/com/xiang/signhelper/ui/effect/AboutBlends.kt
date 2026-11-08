// Copyright 2026, xiangfa-test
// SPDX-License-Identifier: Apache-2.0
//
// The logo/name glass recipe from the Miuix example app (Apache-2.0):
// example/shared/src/commonMain/kotlin/AboutPage.kt. KernelSU's About screen uses the very same
// values, but they originate here — so this is taken from the Apache-2.0 upstream instead of from
// the GPL-3.0 fork. Keep these notices when distributing.

package com.xiang.signhelper.ui.effect

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

/**
 * Colour-blend recipe for the About page's app name, which is drawn as a `DstIn` mask over the
 * blurred background (a "glass" name). The card recipe is a different one — see [ColorBlendToken].
 */
fun logoBlendColors(isDark: Boolean): List<BlendColorEntry> = if (isDark) {
    listOf(
        BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
        BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
    )
} else {
    listOf(
        BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
        BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
        BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
    )
}
