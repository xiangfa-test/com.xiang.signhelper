// Copyright 2026, xiangfa-test
// SPDX-License-Identifier: Apache-2.0

package com.xiang.signhelper.ui.effect

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * The animated HyperOS-style light behind the About page, i.e. the effect KernelSU's About screen
 * uses — which is itself vendored from the Miuix example app (Apache-2.0), so it carries no GPL
 * obligations here. See the `effect` package for the vendored sources.
 *
 * Needs an AGSL runtime shader, so it is a no-op below API 33 — the same condition the Miuix
 * reference uses.
 *
 * @param progress 0 at the top of the page, 1 once the header has scrolled away; the light fades
 *   out with it, exactly like the reference implementation.
 * @param backdrop records the light as it is drawn, so frosted content (the cards, the glass app
 *   name) can blur it.
 */
@Composable
fun AboutBackground(
    progress: Float,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    if (!isRuntimeShaderSupported()) return

    BgEffectBackground(
        dynamicBackground = true,
        modifier = modifier,
        bgModifier = Modifier.layerBackdrop(backdrop),
        isFullSize = true,
        effectBackground = true,
        isOs3Effect = true,
        alpha = { (1f - progress).coerceIn(0f, 1f) },
    ) {
        // Background layer only: the page content is drawn by the caller.
    }
}
