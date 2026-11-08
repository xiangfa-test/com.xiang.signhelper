// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

// ---------------------------------------------------------------------------
// 来源：Miuix 官方示例 example/shared/src/commonMain/kotlin/component/
//       （compose-miuix-ui/miuix，Apache-2.0）
//       原始实现 Adapted from Kyant0/AndroidLiquidGlass (Apache 2.0)。
//
// 本文件为**逐字移植**，仅做两处改动：
//   1. 包名 component.liquid / component.animation → com.xiang.signhelper.ui.liquid
//   2. 按需替换对示例工程 ui.* 的依赖
//
// 许可：Apache-2.0。保留原版权与署名即为合规；本项目整体据此可继续闭源分发。
// ---------------------------------------------------------------------------

package com.xiang.signhelper.ui.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * A [Backdrop] that draws [first] then [second] in order, allowing a tinted/overlay
 * backdrop to be sampled on top of a base backdrop. Mirrors Kyant's `CombinedBackdrop`
 * pattern used in `LiquidBottomTabs` to layer a recorded "tinted tabs" pass over the
 * underlying app background as a single sampling source for an indicator.
 */
@Stable
class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop = remember(first, second) { CombinedBackdrop(first, second) }
