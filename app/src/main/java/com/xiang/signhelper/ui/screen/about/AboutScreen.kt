package com.xiang.signhelper.ui.screen.about

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.xiang.signhelper.BuildConfig
import com.xiang.signhelper.R
import com.xiang.signhelper.ui.component.CardHint
import com.xiang.signhelper.ui.component.cardPadding
import com.xiang.signhelper.ui.component.pageScroll
import com.xiang.signhelper.ui.effect.AboutBackground
import com.xiang.signhelper.ui.effect.ColorBlendToken
import com.xiang.signhelper.ui.effect.logoBlendColors
import com.xiang.signhelper.ui.liquid.isInDarkTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「关于」页签。结构照 KernelSU / 参考项目 的 About：
 *
 * ## 三个要点
 *
 * **① 标识块是"钉住"的，不跟着列表滚**
 *
 * ```
 * Box
 *  ├ AboutBackground   全页动效
 *  ├ AboutHeader       钉住的标识块（画在列表之前，被列表盖住）
 *  └ LazyColumn
 *       └ Spacer(已测标识块高度)   ← 把列表内容顶下去
 * ```
 *
 * 列表滚动时**内容盖到标识块上**，观感是"内容把标题推走"。
 *
 * **② 滚动进度用"实测高度"当分母**
 *
 * `progress = firstVisibleItemScrollOffset / headerHeightPx`
 *
 * 固定常数当分母会让进度算不到 1，图标就永远不消失。
 *
 * **③ 三个元素按区间依次淡出**（不是整块一起）
 *
 * ```
 * 版本号  0.05 → 0.20
 * 应用名  0.20 → 0.35
 * 图标    0.35 → 0.50
 * ```
 *
 * 和 参考项目 版本的区别：这里是**顶级页签**，没有返回按钮、没有二级页，
 * 所以去掉了 `onBack`，标题栏只在标识块滚走之后才出现，底部要扣掉主导航栏高度。
 *
 * 内容只有三张卡：构建时间、作者、致谢。作者只有 xiangfa 一个人。
 */
@Composable
fun AboutScreen(bottomPadding: Dp = 0.dp) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    // 原作者 xihan 的发布页/仓库暂时无从确认，点它时给一句说明而不是假装有链接。
    val unknownAuthorLinkHint = stringResource(R.string.about_credit_xihan_unknown)
    val listState = rememberLazyListState()
    var headerHeightPx by remember { mutableIntStateOf(0) }

    // 进度 = 已滚过的距离 / 标识块高度。用实测高度，不是固定值。
    val headerProgress by remember {
        derivedStateOf {
            when {
                headerHeightPx <= 0 -> 0f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset.toFloat() / headerHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    // 磨砂玻璃：动效先录进 backdrop，卡片再糊这个 backdrop
    val backdrop = rememberLayerBackdrop()
    val isDark = isInDarkTheme()
    val aboutBlur = AboutBlur(
        backdrop = backdrop,
        colors = if (isDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light,
        logoColors = logoBlendColors(isDark),
        active = isRuntimeShaderSupported(),
    )

    val scrollBehavior = MiuixScrollBehavior()
    val titleAlpha = ((headerProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.page_about),
                scrollBehavior = scrollBehavior,
                // 标识区还在时标题栏完全透明，让动效透上来；滚没了才变实色
                color = if (headerProgress >= 1f) MiuixTheme.colorScheme.surface else Color.Transparent,
                titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = titleAlpha),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 全页动效，作为列表的兄弟节点铺在后面
            AboutBackground(
                progress = headerProgress,
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
            )

            // 钉住的标识块：画在列表之前，所以会被列表盖住
            AboutHeader(
                progress = headerProgress,
                contentPadding = innerPadding,
                blur = aboutBlur,
                onHeightChanged = { headerHeightPx = it },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageScroll(scrollBehavior),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                overscrollEffect = null,
            ) {
                // 给钉住的标识块留位，列表内容才会从它下面开始、滚动时盖上去
                item {
                    Spacer(
                        modifier = Modifier.height(
                            maxOf(
                                with(LocalDensity.current) { headerHeightPx.toDp() },
                                ABOUT_HEADER_MIN_HEIGHT,
                            ),
                        ),
                    )
                }

                // 构建时间。版本号已经在标识区里，这里不重复。
                item {
                    AboutCard(blur = aboutBlur, modifier = Modifier.cardPadding()) {
                        ArrowPreference(
                            title = stringResource(R.string.about_build_time),
                            summary = buildTime(),
                            enabled = false,
                        )
                    }
                }

                item { SmallTitle(text = stringResource(R.string.about_author)) }

                // 作者只有一个人 —— 这个项目没有别的参与者。
                item {
                    AboutCard(blur = aboutBlur, modifier = Modifier.cardPadding()) {
                        ArrowPreference(
                            title = stringResource(R.string.about_author_name),
                            summary = stringResource(R.string.about_role_author),
                            startAction = { AuthorAvatar(R.drawable.avatar_xiangfa) },
                            onClick = { uriHandler.openUri(AUTHOR_URL) },
                        )
                    }
                }

                item { SmallTitle(text = stringResource(R.string.about_thanks)) }

                item {
                    AboutCard(blur = aboutBlur, modifier = Modifier.cardPadding()) {
                        ACKNOWLEDGEMENTS.forEach { entry ->
                            val url = entry.url
                            ArrowPreference(
                                title = stringResource(entry.title),
                                onClick = {
                                    if (url != null) {
                                        uriHandler.openUri(url)
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            unknownAuthorLinkHint,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                    }
                }

                // 底部填充 = 实测标识块高度 + 常量：
                // 只留常量的话，矮屏上标识块可能滚不走；只留高度的话，滚到底会紧贴最后一行。
                item {
                    Spacer(
                        modifier = Modifier.height(
                            with(LocalDensity.current) { headerHeightPx.toDp() } + ABOUT_BOTTOM_FILLER,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 钉住的标识块：图标 + 应用名 + 版本号，按区间依次淡出。
 */
@Composable
private fun AboutHeader(
    progress: Float,
    contentPadding: PaddingValues,
    blur: AboutBlur,
    onHeightChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(context) {
        runCatching {
            context.packageManager
                .getApplicationIcon(context.packageName)
                .toBitmap(ICON_PX, ICON_PX)
                .asImageBitmap()
        }.getOrNull()
    }

    Box(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onHeightChanged(it.height) }
                .padding(top = ABOUT_HEADER_TOP_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            // 图标最后消失
                            val faded = 1f - fadeOut(progress, ABOUT_ICON_FADE_START, ABOUT_ICON_FADE_END)
                            alpha = 1f - faded
                            scaleX = 1f - faded * 0.05f
                            scaleY = 1f - faded * 0.05f
                        }
                        .size(96.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    // 原版 签名助手 的启动图标是「圆角方块 + 应用名」的满幅图，
                    // 用 140dp 放大后再被 96dp 的圆角框裁剪会把文字切掉。
                    // 这里让图标恰好填满框（ContentScale.Crop），既不出白边也不裁字。
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Text(
                text = stringResource(R.string.AppName),
                color = MiuixTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                modifier = Modifier
                    .graphicsLayer {
                        val faded = 1f - fadeOut(progress, ABOUT_NAME_FADE_START, ABOUT_NAME_FADE_END)
                        alpha = 1f - faded
                        scaleX = 1f - faded * 0.05f
                        scaleY = 1f - faded * 0.05f
                    }
                    // 玻璃字：用 DstIn 让文字自身当遮罩，透出被糊过的背景
                    .then(
                        if (blur.active) {
                            Modifier.textureBlur(
                                backdrop = blur.backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = ABOUT_NAME_BLUR_RADIUS,
                                colors = BlurColors(blendColors = blur.logoColors),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                modifier = Modifier.graphicsLayer {
                    // 版本号最先消失
                    val faded = 1f - fadeOut(progress, ABOUT_VERSION_FADE_START, ABOUT_VERSION_FADE_END)
                    alpha = 1f - faded
                },
            )
        }
    }
}

/** 磨砂卡片所用的一组参数。 */
private class AboutBlur(
    val backdrop: LayerBackdrop,
    val colors: List<BlendColorEntry>,
    val logoColors: List<BlendColorEntry>,
    val active: Boolean,
)

/**
 * 关于页的磨砂卡片：透明表面 + 糊过的背景。
 *
 * 效果不可用时退回不透明卡片色，保证内容仍可读。
 */
@Composable
private fun AboutCard(
    blur: AboutBlur,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.then(
            if (blur.active) {
                Modifier.textureBlur(
                    backdrop = blur.backdrop,
                    shape = RoundedCornerShape(CardDefaults.CornerRadius),
                    blurRadius = ABOUT_BLUR_RADIUS,
                    colors = BlurColors(blendColors = blur.colors),
                )
            } else {
                Modifier
            },
        ),
        colors = CardDefaults.defaultColors(
            color = if (blur.active) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            contentColor = Color.Transparent,
        ),
        content = content,
    )
}

/** 作者头像。资源来自原版作者本人，不重复造。 */
@Composable
private fun AuthorAvatar(drawableRes: Int) {
    Image(
        painter = painterResource(drawableRes),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(end = 12.dp)
            .size(40.dp)
            .clip(CircleShape),
    )
}

// ---------------------------------------------------------------------------
// 常量与工具
// ---------------------------------------------------------------------------

/** 作者主页。全模块只有这一个作者。 */
private const val AUTHOR_URL = "https://github.com/xiangfa-test"

private val ABOUT_HEADER_TOP_PADDING = 32.dp

/** 标识块的最小占位高度：屏幕太矮时也要保证列表能把它推走。 */
private val ABOUT_HEADER_MIN_HEIGHT = 260.dp

private const val ABOUT_VERSION_FADE_START = 0.05f
private const val ABOUT_VERSION_FADE_END = 0.20f
private const val ABOUT_NAME_FADE_START = 0.20f
private const val ABOUT_NAME_FADE_END = 0.35f
private const val ABOUT_ICON_FADE_START = 0.35f
private const val ABOUT_ICON_FADE_END = 0.50f

private const val ABOUT_BLUR_RADIUS = 60f
private const val ABOUT_NAME_BLUR_RADIUS = 150f

/** 列表末尾留白，够标识块滚走又不至于空一整屏。 */
private val ABOUT_BOTTOM_FILLER = 200.dp

/** 取应用图标的目标像素尺寸。 */
private const val ICON_PX = 192

/**
 * 在 [start, end] 区间内从 1 线性降到 0；区间外分别是 1 和 0。
 */
private fun fadeOut(progress: Float, start: Float, end: Float): Float =
    1f - ((progress - start) / (end - start)).coerceIn(0f, 1f)

/** 一个致谢条目：名称 + 可选仓库地址（没有地址的只显示名字）。 */
private data class Acknowledgement(
    @param:StringRes val title: Int,
    val url: String? = null,
)

private val ACKNOWLEDGEMENTS = listOf(
    Acknowledgement(title = R.string.about_credit_xihan),
    Acknowledgement(
        title = R.string.about_lib_miuix,
        url = "https://github.com/compose-miuix-ui/miuix",
    ),
    Acknowledgement(
        title = R.string.about_lib_xposed,
        url = "https://github.com/libxposed/api",
    ),
    Acknowledgement(
        title = R.string.about_lib_lsposed,
        url = "https://github.com/LSPosed/LSPosed",
    ),
    Acknowledgement(
        title = R.string.about_lib_hyperlyrics,
        url = "https://github.com/juren233/HyperLyrics-Enhanced",
    ),
)

private fun buildTime(): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(BuildConfig.BUILD_TIME.toLong()))
}.getOrDefault(BuildConfig.BUILD_TIME)
