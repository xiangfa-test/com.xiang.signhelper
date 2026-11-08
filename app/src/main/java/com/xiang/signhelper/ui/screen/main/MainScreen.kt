package com.xiang.signhelper.ui.screen.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.ui.component.LocalBottomBlurBackdrop
import com.xiang.signhelper.ui.component.LocalEnableBlur
import com.xiang.signhelper.ui.component.LocalTopBarBlurBackdrop
import com.xiang.signhelper.ui.component.bottombar.FloatingBottomBar
import com.xiang.signhelper.ui.component.bottombar.BottomBarDestination
import com.xiang.signhelper.ui.component.bottombar.bottomBarContentPadding
import com.xiang.signhelper.ui.component.rememberBlurBackdrop
import com.xiang.signhelper.ui.screen.about.AboutScreen
import com.xiang.signhelper.ui.screen.catalog.CatalogScreen
import com.xiang.signhelper.ui.screen.installed.InstalledAppPickerScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 容器页：底部导航 + 两个页签（应用目录 / 关于）。
 *
 * 结构照 KernelSU / 参考项目 的主页：
 *
 * ```
 * Scaffold(bottomBar = FloatingBottomBar)
 *  └ Box(layerBackdrop(bottomBackdrop))   ← 底栏模糊的背板在这一层录
 *      └ HorizontalPager(2)
 *          ├ CatalogScreen（应用目录）
 *          └ AboutScreen（关于）
 * ```
 *
 * ## 为什么底栏背板建在这里，而不是底栏自己建
 *
 * Miuix 的模糊是「先把内容录进 `LayerBackdrop`，再用它糊」。录制必须发生在
 * **内容所在的层级**：底栏自己 `rememberLayerBackdrop()` 得到的是空的，
 * 糊出来自然没有效果（参考项目 的第一版就踩过这个坑，见 `Blur.kt`）。
 *
 * ## 为什么内容不加 `innerPadding`
 *
 * 内容要能滚到**底栏下面**，底栏的模糊才有源可糊。让位交给各页自己的
 * `bottomPadding` 参数（`bottomBarContentPadding()` + 悬浮底栏的 12dp 外边距），
 * 这也和 参考项目 一致。
 */
@Composable
fun MainScreen(
    /** 二级页选中的应用包名；交给应用目录读签名/入库后清空。 */
    pendingInstalledPackage: String? = null,
    onPendingInstalledConsumed: () -> Unit = {},
    onOpenInstalledPicker: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val tabs = BottomBarDestination.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    // 悬浮底栏比标准底栏多出一圈外边距，内容要多让一点位（和 参考项目 一致）
    val bottomPadding = bottomBarContentPadding() + 12.dp

    val enableBlur = LocalEnableBlur.current
    // 底栏一个背板、顶栏一个背板：都由这一层建好往下发，
    // 每个页签各建一个会反复分配离屏纹理（见 Blur.kt 的说明）。
    val bottomBackdrop = rememberBlurBackdrop(enableBlur)
    val topBarBackdrop = rememberBlurBackdrop(enableBlur)

    CompositionLocalProvider(
        LocalBottomBlurBackdrop provides bottomBackdrop,
        LocalTopBarBlurBackdrop provides topBarBackdrop,
    ) {
        Scaffold(
            bottomBar = {
                FloatingBottomBar(
                    selectedIndex = pagerState.currentPage,
                    onSelect = { index ->
                        if (index != pagerState.currentPage) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                )
            },
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (bottomBackdrop != null) {
                            Modifier.layerBackdrop(bottomBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 只有两页，预载邻页就够了；多了纯属浪费一页的构建
                    beyondViewportPageCount = 1,
                    // 纵向回弹由各页的 pageScroll() 提供，pager 不再叠一层
                    overscrollEffect = null,
                ) { page ->
                    when (tabs[page]) {
                        BottomBarDestination.Catalog -> CatalogScreen(
                            bottomPadding = bottomPadding,
                            pendingInstalledPackage = pendingInstalledPackage,
                            onPendingInstalledConsumed = onPendingInstalledConsumed,
                            onOpenInstalledPicker = onOpenInstalledPicker,
                        )

                        BottomBarDestination.About -> AboutScreen(bottomPadding = bottomPadding)
                    }
                }
            }
        }
    }
}
