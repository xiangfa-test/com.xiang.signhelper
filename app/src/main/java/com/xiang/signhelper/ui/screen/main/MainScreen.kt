package com.xiang.signhelper.ui.screen.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.ui.component.LocalBottomBlurBackdrop
import com.xiang.signhelper.ui.component.LocalEnableBlur
import com.xiang.signhelper.ui.component.LocalTopBarBlurBackdrop
import com.xiang.signhelper.ui.component.bottombar.BottomBarDestination
import com.xiang.signhelper.ui.component.bottombar.FloatingBottomBar
import com.xiang.signhelper.ui.component.bottombar.bottomBarContentPadding
import com.xiang.signhelper.ui.component.rememberBlurBackdrop
import com.xiang.signhelper.ui.navigation3.LocalNavigator
import com.xiang.signhelper.ui.navigation3.Route
import com.xiang.signhelper.ui.screen.about.AboutScreen
import com.xiang.signhelper.ui.screen.catalog.CatalogScreen
import com.xiang.signhelper.ui.screen.oobe.OobeScreen
import com.xiang.signhelper.ui.screen.settings.SettingsScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 容器页：底部导航 + 三个页签（应用目录 / 设置 / 关于）。
 *
 * 条目详情通过 miuix-nav 的 [Route.EntryDetail] 进入，转场和侧滑返回交给框架，
 * 不再自己写 AnimatedContent。
 */
@Composable
fun MainScreen(
    /** 二级页选中的应用包名；交给应用目录读签名/入库后清空。 */
    pendingInstalledPackage: String? = null,
    onPendingInstalledConsumed: () -> Unit = {},
    onOpenInstalledPicker: () -> Unit = {},
) {
    var oobeDone by remember { mutableStateOf(Config.isOobeDone()) }
    if (!oobeDone) {
        OobeScreen(onDone = { oobeDone = true })
        return
    }

    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val tabs = BottomBarDestination.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val bottomPadding = bottomBarContentPadding() + 12.dp

    val enableBlur = LocalEnableBlur.current
    val bottomBackdrop = rememberBlurBackdrop(enableBlur)
    val topBarBackdrop = rememberBlurBackdrop(enableBlur)

    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "main_fade_in",
    )

    Box(modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { alpha = contentAlpha }) {
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
                    beyondViewportPageCount = 1,
                    overscrollEffect = null,
                ) { page ->
                    when (tabs[page]) {
                        BottomBarDestination.Catalog -> CatalogScreen(
                            bottomPadding = bottomPadding,
                            pendingInstalledPackage = pendingInstalledPackage,
                            onPendingInstalledConsumed = onPendingInstalledConsumed,
                            onOpenInstalledPicker = onOpenInstalledPicker,
                            onOpenDetail = { packageName, fromPicker ->
                                navigator.push(Route.EntryDetail(packageName, fromPicker))
                            },
                        )

                        BottomBarDestination.Settings -> SettingsScreen(
                            bottomPadding = bottomPadding,
                            onReplayOobe = { oobeDone = false },
                        )

                        BottomBarDestination.About -> AboutScreen(bottomPadding = bottomPadding)
                    }
                }
            }
        }
    }
    }
}
