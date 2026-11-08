package com.xiang.signhelper.ui.navigation3

import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 类型安全的路由。
 *
 * 与 KernelSU 同一套做法：只用不带参数的 `data object`，
 * 不玩 kotlinx.serialization（R8 混淆后子类序列化器会被裁掉）；
 * back stack 用 `navBackStackOf` + remember，进程被回收后回到主页即可。
 *
 * 本模块只有两层：
 *  - [Main]：页签容器（应用目录 + 关于，底部悬浮栏）；
 *  - [InstalledPicker]：选择已安装应用的二级页（进入/返回走 miuix-nav 的转场动画）。
 */
sealed interface Route : NavKey {

    data object Main : Route

    data object InstalledPicker : Route
}
