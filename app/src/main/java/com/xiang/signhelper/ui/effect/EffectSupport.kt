// Copyright 2026, xiangfa-test
// SPDX-License-Identifier: Apache-2.0
//
// Support code for the vendored Miuix effect sources (see the other files in this package):
// `isInDarkTheme` maps to Compose's isSystemInDarkTheme, and `shouldShowSplitPane` is copied from
// the Miuix example's utils/AdaptiveUtils.kt (Apache-2.0). The shader helpers the vendored sources
// use (`isRuntimeShaderSupported`, `RuntimeShader`, `asBrush`) come from `miuix-blur`, the same
// dependency the upstream example uses.

package com.xiang.signhelper.ui.effect

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/** Miuix's reference check for whether the wide (pad) preset should be used. */
@Composable
internal fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) {
        val widthDp = windowInfo.containerSize.width.toDp()
        val heightDp = windowInfo.containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}
