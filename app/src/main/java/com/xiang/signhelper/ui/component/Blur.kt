package com.xiang.signhelper.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「界面模糊」总开关。
 *
 * 本模块没有外观设置，默认开启（[com.xiang.signhelper.ui.theme.AppTheme] 注入 true）；
 * 设备不支持 RenderEffect 时 `rememberBlurBackdrop` 会自己返回 null，各条栏退回实色。
 */
val LocalEnableBlur = staticCompositionLocalOf { true }

/**
 * 底部导航用的模糊背板，由 `MainScreen` 提供。
 *
 * ## 为什么必须由外面提供，而不是底栏自己建
 *
 * Miuix 的模糊是"**录下来再模糊**"：`LayerBackdrop` 需要由**内容**通过
 * `Modifier.layerBackdrop(backdrop)` 把绘制结果录进去，然后栏那边用
 * `textureBlur(backdrop)` 才能糊出后面的东西。
 *
 * 我第一版让底栏自己 `rememberLayerBackdrop()` 再直接 `textureBlur` ——
 * backdrop 里什么都没有，糊出来当然是空的。**这就是"底栏没有模糊"的原因。**
 *
 * 所以背板必须在"内容所在的那一层"创建，再往下发给栏用（KSU 也是这个接法）。
 */
val LocalBottomBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 顶栏（以及页面内容）共用的模糊背板，由 `MainScreen` 提供。
 *
 * ## 为什么要共享，而不是每页各建一个
 *
 * `LayerBackdrop` 不是个轻量对象：它要分配离屏纹理，并且**持续录制**被标记内容的
 * 每一次绘制。之前 `PageScaffold` 自己每页建一个，于是：
 *
 * - 每个页签 = 1 份离屏纹理 + 1 份录制的绘制工作；
 * - 切页时旧的销毁、新的创建，分配与首帧录制都落在主线程 —— 这就是切页卡顿的来源。
 *
 * KernelSU 的做法是**只在 MainActivity 建一次**，往下传给所有页面。
 * 这里改成同样的结构：能用共享的就用，取不到（独立子页如 About）才自己建。
 */
val LocalTopBarBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 建一个模糊背板。
 *
 * `drawContent()` 是关键 —— 它把这一层的绘制结果录进 backdrop，
 * 后面栏上的 `textureBlur` 才有源可模糊。
 *
 * 设备不支持 RenderEffect 时返回 null，调用方据此走实色分支。
 */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 把内容包进"实时模糊层"。
 *
 * `blurRadius = 25f` + `surface.copy(alpha = 0.87f)` 的混合色沿用 KernelSU 的取值 ——
 * 这是 MIUI / HyperOS 系统栏的观感：透得出后面的内容轮廓，但文字仍然清晰。
 *
 * `backdrop == null` 或 [blurActive] 为 false 时不套任何修饰符，
 * 由调用方自己决定用什么实色。
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    blurRadius: Float = 25f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = blurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.87f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
