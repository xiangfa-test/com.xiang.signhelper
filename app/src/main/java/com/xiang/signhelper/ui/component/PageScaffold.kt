package com.xiang.signhelper.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiang.signhelper.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 超过这个宽度（dp）就用固定的小标题栏，窄屏用可折叠的大标题栏。 */
private const val WIDE_SCREEN_DP = 600

/** 页面底部收尾留白，避免最后一张卡片贴住手势条。 */
private val PAGE_END_PADDING = 24.dp

/**
 * 每个页面共用的外壳：标题栏 + 滚动行为 + 单一布局宿主。
 *
 * 三个关键点，都是为了让滚动跟系统一致：
 *
 * 1. **`MiuixScrollBehavior` 交给标题栏** —— 大标题栏随滚动折叠，小标题栏只加阴影，
 *    两者都由同一个 behavior 驱动。
 * 2. **`pageScroll()`** 装上 MIUI 风格的纵向回弹并接管嵌套滚动；
 *    因此 `LazyColumn` 必须 `overscrollEffect = null`，否则拉伸会叠两次。
 * 3. **`Box` 包一层** —— 页面可以把自己的对话框作为额外子节点发出去，
 *    不会和可滚动内容互相挤压布局。
 *
 * @param titleRes      标题
 * @param onBack        返回回调；传 null 表示根/页签页（不显示返回按钮）
 * @param subtitle      副标题（可空）
 * @param bottomPadding 底部被别的东西占掉的高度（例如底部导航栏），会并进 [contentPadding]
 * @param content       收到「已含底边距的 contentPadding」与 scrollBehavior
 */
@Composable
fun PageScaffold(
    titleRes: Int,
    onBack: (() -> Unit)? = null,
    subtitle: String = "",
    bottomPadding: Dp = 0.dp,
    content: @Composable (contentPadding: PaddingValues, scrollBehavior: ScrollBehavior) -> Unit,
) {
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    val scrollBehavior = MiuixScrollBehavior()

    // 顶栏模糊：背板在这一层建，并把**内容**录进去（下面的 layerBackdrop），
    // 标题栏那边才有源可糊。和底栏是同一套机制，见 Blur.kt 的说明。
    val enableBlur = LocalEnableBlur.current
    // 优先复用 MainScreen 下发的共享背板；只有独立子页（如 About）才自己建。
    // 自己建的代价见 LocalTopBarBlurBackdrop 的注释。
    val sharedBackdrop = LocalTopBarBlurBackdrop.current
    val ownBackdrop = if (sharedBackdrop == null) rememberBlurBackdrop(enableBlur) else null
    val barBackdrop = sharedBackdrop ?: ownBackdrop
    val blurActive = enableBlur && barBackdrop != null
    // 模糊时标题栏底色透明，让糊出来的内容透上来；否则用 Miuix 默认的 surface
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = barBackdrop, blurActive = enableBlur) {
                if (isWideScreen) {
                    SmallTopAppBar(
                        title = stringResource(titleRes),
                        subtitle = subtitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = navigationIcon,
                        color = barColor,
                    )
                } else {
                    TopAppBar(
                        title = stringResource(titleRes),
                        subtitle = subtitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = navigationIcon,
                        color = barColor,
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 内容录进背板：列表会滚到标题栏下面，所以糊出来的正是它
                .then(if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier),
        ) {
            content(
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomPadding + PAGE_END_PADDING,
                ),
                scrollBehavior,
            )
        }
    }
}

/**
 * 页面的滚动修饰符：MIUI 风格纵向回弹 + 把滚动事件喂给标题栏的折叠逻辑。
 * 配套要求：`LazyColumn(overscrollEffect = null)`。
 */
fun Modifier.pageScroll(scrollBehavior: ScrollBehavior): Modifier = this
    .overScrollVertical()
    .nestedScroll(scrollBehavior.nestedScrollConnection)

/**
 * 卡片外边距。
 *
 * Miuix 的 `Card` 自带内部留白，外面只需要一点间距；12dp / 6dp 是
 * Miuix 官方示例与系统设置页用的值。
 */
fun Modifier.cardPadding(): Modifier = padding(horizontal = 12.dp, vertical = 6.dp)

/**
 * 卡片内的说明文字。
 *
 * 用它而不是 `ArrowPreference` 的 summary：这类文字通常有好几行，
 * 塞进条目会让 title 与 summary 的基线间距变得很难看。
 */
@Composable
fun CardHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
    )
}

// ---------------------------------------------------------------------------
// 对话框
// ---------------------------------------------------------------------------

/**
 * 窗口级对话框：不需要外层 `Scaffold` 也能用，任意页面都能直接发。
 * 有 [onConfirm] 时是「取消 / 确认」双按钮，否则只有「确定」。
 */
@Composable
fun MessageDialog(
    show: Boolean,
    title: String,
    summary: String?,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    /** 左侧按钮文案；为 null 时用「取消」。 */
    cancelText: String? = null,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            if (confirmText != null && onConfirm != null) {
                TextButton(
                    text = cancelText ?: stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            } else {
                TextButton(
                    text = stringResource(R.string.common_ok),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 单行文本输入对话框，带实时校验提示。 */
@Composable
fun TextInputDialog(
    show: Boolean,
    title: String,
    summary: String?,
    initial: String,
    validate: (String) -> Boolean,
    errorMessage: String,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 输入框内右侧的小按钮（例如"选择目录"）。 */
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    var text by remember(show, initial) { mutableStateOf(initial) }
    val valid = validate(text)

    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = title,
                singleLine = true,
                trailingIcon = trailingIcon,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.common_ok),
                    enabled = valid,
                    onClick = {
                        if (valid) {
                            onAccept(text)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!valid) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
        }
    }
}
