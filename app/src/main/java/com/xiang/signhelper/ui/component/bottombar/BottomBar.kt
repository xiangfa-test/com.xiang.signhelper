package com.xiang.signhelper.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info

/**
 * 底部导航的两个页签。
 *
 * 顺序与 `MainScreen` 里 `HorizontalPager` 的页序一一对应，
 * 所以这里用枚举的序号当页号，避免两处各写一遍索引。
 *
 * 本模块**只有这两页**：应用目录、关于。没有设置 / 外观 / 调试 / 状态入口。
 */
enum class BottomBarDestination(
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    /** 页签 0：应用目录（已加进列表的 APK + 它们的签名）。 */
    Catalog(R.string.page_catalog, MiuixIcons.GridView),

    /** 页签 1：关于。 */
    About(R.string.page_about, MiuixIcons.Info),
}

/** 底部导航占掉的高度，页面用它做内容底边距，避免最后一条被挡住。 */
@Composable
fun bottomBarContentPadding(): androidx.compose.ui.unit.Dp {
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 56dp ≈ Miuix NavigationBar 的自身高度；加上导航条安全区
    return 56.dp + navBar
}
