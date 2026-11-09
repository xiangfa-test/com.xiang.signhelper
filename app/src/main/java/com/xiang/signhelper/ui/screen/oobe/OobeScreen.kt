package com.xiang.signhelper.ui.screen.oobe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiang.signhelper.R
import com.xiang.signhelper.config.Config
import com.xiang.signhelper.ui.component.cardPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 初次引导（OOBE）：首启 Intro 动画 → 欢迎 / 基础操作 / 行为 + 免责协议。
 *
 * 交互参考 HyperLyrics-Enhanced 的 Setup 流程：固定步骤、不可自由滑动、
 * 分步切换、逐条内容浮现、底栏「返回」宽度动画、最后一步主色确认按钮。
 */
@Composable
fun OobeScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pickDetail by remember { mutableStateOf(Config.pickToDetail()) }
    var introDone by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(900)
        introDone = true
    }
    LaunchedEffect(exiting) {
        if (exiting) {
            delay(300)
            onDone()
        }
    }
    val exitAlpha by animateFloatAsState(if (exiting) 0f else 1f, tween(250), label = "oobe_exit_alpha")
    val exitScale by animateFloatAsState(if (exiting) 0.97f else 1f, tween(250), label = "oobe_exit_scale")

    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = exitAlpha
                scaleX = exitScale
                scaleY = exitScale
            },
    ) {
        Scaffold(
            topBar = { OobeHeader(pageCount = 3, currentPage = pagerState.targetPage) },
            bottomBar = {
                OobeBottomBar(
                    showBack = pagerState.targetPage > 0,
                    isLast = pagerState.targetPage == 2,
                    onBack = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    },
                    onNext = {
                        if (pagerState.targetPage < 2) {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            Config.setPickToDetail(pickDetail)
                            Config.setOobeDone(true)
                            exiting = true
                        }
                    },
                )
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                userScrollEnabled = false,
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val active = pagerState.currentPage == page
                    when (page) {
                        0 -> WelcomePage(active)
                        1 -> BasicsPage(active)
                        else -> BehaviorAndDisclaimerPage(
                            active = active,
                            pickDetail = pickDetail,
                            onPickChanged = { pickDetail = it },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !introDone,
            exit = fadeOut(animationSpec = tween(300)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.page_home),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight(600),
                        style = MiuixTheme.textStyles.title2,
                    )
                }
            }
        }
    }
}

@Composable
private fun OobeHeader(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(pageCount) { index ->
                val active = index == currentPage
                val width by animateDpAsState(
                    targetValue = if (active) 18.dp else 6.dp,
                    animationSpec = tween(250),
                )
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun OobeBottomBar(
    showBack: Boolean,
    isLast: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        val gap = 12.dp
        val backWidth = (maxWidth - gap) / 2
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = showBack,
                enter = fadeIn(tween(200)) + expandVertically(),
                exit = fadeOut(tween(150)) + shrinkVertically(),
            ) {
                TextButton(
                    text = stringResource(R.string.oobe_prev),
                    onClick = onBack,
                    modifier = Modifier.width(backWidth),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(
                    if (isLast) R.string.oobe_agree else R.string.oobe_next,
                ),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onNext,
                modifier = Modifier.width(backWidth),
            )
        }
    }
}

/** 逐条内容浮现：index 越大延迟越长。 */
@Composable
private fun StaggerItem(active: Boolean, index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active, index) {
        if (active) {
            visible = false
            delay(index * 70L)
            visible = true
        } else {
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically { it / 8 },
        content = { content() },
    )
}

@Composable
private fun WelcomePage(active: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaggerItem(active = active, index = 0) {
            Text(
                text = stringResource(R.string.oobe_welcome_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight(600),
                style = MiuixTheme.textStyles.title1,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }
        StaggerItem(active = active, index = 1) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.oobe_welcome_body),
                    modifier = Modifier.padding(12.dp),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
    }
}

@Composable
private fun BasicsPage(active: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaggerItem(active = active, index = 0) {
            Text(
                text = stringResource(R.string.oobe_basics_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight(600),
                style = MiuixTheme.textStyles.title1,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }
        StaggerItem(active = active, index = 1) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.oobe_basics_body),
                    modifier = Modifier.padding(12.dp),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
    }
}

@Composable
private fun BehaviorAndDisclaimerPage(
    active: Boolean,
    pickDetail: Boolean,
    onPickChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaggerItem(active = active, index = 0) {
            Column {
                Text(
                    text = stringResource(R.string.oobe_pick_title),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight(600),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_pick_detail_summary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        StaggerItem(active = active, index = 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OobeChoiceCard(
                    text = stringResource(R.string.oobe_pick_detail),
                    selected = pickDetail,
                    onClick = { onPickChanged(true) },
                    modifier = Modifier.weight(1f),
                )
                OobeChoiceCard(
                    text = stringResource(R.string.oobe_pick_list),
                    selected = !pickDetail,
                    onClick = { onPickChanged(false) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        StaggerItem(active = active, index = 2) {
            Text(
                text = stringResource(R.string.oobe_disclaimer_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight(600),
                style = MiuixTheme.textStyles.title3,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        StaggerItem(active = active, index = 3) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.oobe_disclaimer_body),
                    modifier = Modifier.padding(12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun OobeChoiceCard(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val container by animateColorAsState(
        targetValue = if (selected) {
            primary.copy(alpha = 0.12f).compositeOver(MiuixTheme.colorScheme.surfaceContainer)
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(250),
        label = "oobe_choice_container",
    )
    Card(
        modifier = modifier,
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Tilt,
        colors = CardDefaults.defaultColors(color = container),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            color = if (selected) primary else MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight(500),
            style = MiuixTheme.textStyles.body1,
            textAlign = TextAlign.Center,
        )
    }
}
