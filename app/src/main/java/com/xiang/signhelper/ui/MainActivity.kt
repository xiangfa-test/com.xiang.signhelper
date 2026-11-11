package com.xiang.signhelper.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.data.SignEntry
import com.xiang.signhelper.util.SignatureUtil
import com.xiang.signhelper.data.SignStore
import com.xiang.signhelper.ui.navigation3.LocalNavigator
import com.xiang.signhelper.ui.navigation3.Route
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xiang.signhelper.ui.navigation3.rememberNavigator
import com.xiang.signhelper.ui.screen.detail.EntryDetailScreen
import com.xiang.signhelper.ui.screen.installed.InstalledAppPickerScreen
import com.xiang.signhelper.ui.screen.main.MainScreen
import com.xiang.signhelper.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.runtime.NavChange
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * 模块界面入口。
 *
 * 结构照 KernelSU / 参考项目：`NavDisplay` 一层导航栈，
 *
 * ```
 * Route.Main             容器页（应用目录 + 关于，底部悬浮液态玻璃栏）
 *   └ Route.InstalledPicker  二级页（选择已安装应用）
 * ```
 *
 * 转场动画、侧滑返回、系统返回键都由 miuix-nav 统一接管 —— 与之前的 参考项目
 * 是同一套参数（`NavDisplay` 默认弹簧 + `NavDisplayEffects` 的系统圆角裁剪 +
 * `NavSwipeDirection` 侧滑方向），没有自己手搓动画。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // 条目仓库绑定 Context 并读一次磁盘；界面第一次组合就能拿到数据。
        runCatching { SignStore.init(applicationContext) }

        setContent {
            val darkMode = isSystemInDarkTheme()

            // 状态栏/导航栏透明并跟随深浅色。必须放在 setContent 里：
            // isSystemInDarkTheme() 是 @Composable，参与这个副作用的重启条件。
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)

            // 二级页选中的应用先存这里：选完 pop 回主页，再交给应用目录读签名/入库。
            var pendingInstalledPackage by remember { mutableStateOf<String?>(null) }

            // Miuix 的转场预设不带"可返回方向"，侧滑返回要按页注册（RTL 镜像）。
            val swipeDismiss = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                NavSwipeDirection.RightToLeft
            } else {
                NavSwipeDirection.LeftToRight
            }

            // 选择页转场与 MiuixDefault 一致；MultiPop（主页模式：详情+选择页一起弹掉）
            // 时让选择页彻底透明，避免多段 pop 期间闪一下应用列表。
            val pickerTransition = remember {
                navGraphicsTransition { scope ->
                    val d = scope.relativeDepth
                    val width = scope.layoutSize.width.toFloat()
                    val rtl = scope.layoutDirection == LayoutDirection.Rtl
                    if (d <= 0f) {
                        translationX = ((if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width)
                            .roundToInt().toFloat()
                    } else {
                        translationX = (if (rtl) 1f else -1f) * d.coerceIn(0f, 1f) * width * 0.25f
                        alpha = if (scope.change is NavChange.MultiPop) {
                            0f
                        } else {
                            1f - 0.1f * d.coerceIn(0f, 1f)
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalNavigator provides navigator) {
                AppTheme {
                    Scaffold { _ ->
                        NavDisplay(
                            backStack = navigator.backStack,
                            // 圆角裁剪半径跟随设备（折叠屏/平板取系统值）
                            effects = NavDisplayEffects(
                                cornerClipRadius = rememberNavSystemCornerRadius(),
                            ),
                            onBack = { navigator.pop() },
                        ) {
                            // 容器页是栈底，不做侧滑返回
                            entry<Route.Main>(swipeDismiss = null) {
                                MainScreen(
                                    pendingInstalledPackage = pendingInstalledPackage,
                                    onPendingInstalledConsumed = { pendingInstalledPackage = null },
                                    onOpenInstalledPicker = { navigator.push(Route.InstalledPicker) },
                                )
                            }
                            entry<Route.InstalledPicker>(transition = pickerTransition, swipeDismiss = swipeDismiss) {
                                InstalledAppPickerScreen(
                                    onBack = { navigator.pop() },
                                    onPick = { packageName ->
                                        // 先入库再 push：详情页打开时条目必须已经存在。
                                        // 选择页保留在栈里，返回时才能直接回到列表。
                                        lifecycleScope.launch {
                                            val meta = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    SignatureUtil.readInstalledApp(this@MainActivity, packageName)
                                                }.getOrNull()
                                            }
                                            if (meta != null) {
                                                val existing = SignStore.entries.firstOrNull {
                                                    it.packageName == meta.packageName
                                                }
                                                SignStore.add(
                                                    existing?.copy(
                                                        apkName = meta.apkName,
                                                        originalSignature = meta.signatureHex,
                                                    ) ?: SignEntry(
                                                        packageName = meta.packageName,
                                                        apkName = meta.apkName,
                                                        originalSignature = meta.signatureHex,
                                                        forgedSignature = "",
                                                        isForged = false,
                                                    ),
                                                )
                                            }
                                            pendingInstalledPackage = packageName
                                            // 始终 push：让 NavDisplay 跑真正的 MiuixDefault 转场，
                                            // 选择页在下层做视差；主页模式返回时再一并弹出。
                                            navigator.push(Route.EntryDetail(packageName, fromPicker = true))
                                        }
                                    },
                                )
                            }
                            entry<Route.EntryDetail>(swipeDismiss = swipeDismiss) { entry ->
                                EntryDetailScreen(
                                    packageName = entry.packageName,
                                    onBack = {
                                        if (entry.fromPicker && Config.pickTarget() != "list") {
                                            // 主页模式：详情和选择页一次性弹掉；选择页在 MultiPop 中透明。
                                            navigator.popUntil { it is Route.Main }
                                        } else {
                                            navigator.pop()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
