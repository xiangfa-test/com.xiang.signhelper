package com.xiang.signhelper.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * 导航器：包一层 miuix-nav 的 [NavBackStack]，对外只暴露 push / pop 这几个动作。
 *
 * 直接照 KernelSU 的 `Navigator` 写（同一个 miuix-nav 版本、同一套语义）：
 *
 *  - [push] 是**幂等**的 —— 连着点两下同一个入口不会压两个相同的 key。
 *    miuix-nav 不允许同一个 key 在栈里出现两次（contentKey 冲突会直接抛异常），
 *    所以这里主动挡住；真要多个实例就把参数写进 `data class` 路由里。
 *  - [pop] 在只剩一个 key 时什么都不做，让系统返回事件能落到 Activity 上（= 退出应用），
 *    这正是 Android 的预期行为。
 *  - [popUntil] 用于"回到某个已知页面"，例如设置页里点完子页想一次回到设置根。
 */
class Navigator(val backStack: NavBackStack) {

    fun push(key: NavKey) {
        if (key !in backStack) backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    /** 一直弹到满足条件的那个 key 成为栈顶（至少保留一个）。 */
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.size > 1 && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun current(): NavKey? = backStack.lastOrNull()

    val size: Int get() = backStack.size
}

/**
 * 建一个以 [startRoute] 为栈底的导航器。
 *
 * 用 `navBackStackOf` + `remember`，**不用** `rememberNavBackStack`。
 * 后者要求路由是 `@Serializable` 并用 `rememberSaveable` 持久化 back stack ——
 * 那在 R8 混淆后的 release 包里会让子类序列化器被裁掉，界面启动即崩
 * （详见 [Route] 的注释）。代价只是"进程被回收后回到主页面"，
 * 对一个设置界面来说完全可以接受。
 */
@Composable
fun rememberNavigator(startRoute: Route): Navigator {
    val backStack = remember { navBackStackOf(startRoute) }
    return remember(backStack) { Navigator(backStack) }
}

/** 全局导航器。由 `MainActivity` 提供，页面里用 `LocalNavigator.current` 取。 */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator 未提供：请在 setContent 里用 CompositionLocalProvider 注入")
}
