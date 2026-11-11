package com.xiang.signhelper.ui.screen.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiang.signhelper.R
import com.xiang.signhelper.data.SignStore
import com.xiang.signhelper.ui.component.AppIcon
import com.xiang.signhelper.ui.component.cardPadding
import com.xiang.signhelper.ui.component.SignatureInfoDialog
import com.xiang.signhelper.ui.component.SignaturePreview
import com.xiang.signhelper.ui.component.rememberAppIcon
import com.xiang.signhelper.util.SignatureUtil
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun isHex(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * 条目详情二级页：原始签名（截断展示）+ 伪造签名编辑 + 启用开关。
 *
 * 进入页面自动从已安装应用刷新一次原始签名，不需要再点「ⓘ → 刷新」。
 * 直接返回时条目仍然保留在列表里（和原来的取消行为一致）。
 */
@Composable
fun EntryDetailScreen(
    packageName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val entry = SignStore.entries.firstOrNull { it.packageName == packageName }
    LaunchedEffect(entry) { if (entry == null) onBack() }
    val current = entry ?: return

    var original by remember(current.packageName) { mutableStateOf(current.originalSignature) }
    var forged by remember(current.packageName) { mutableStateOf(current.forgedSignature) }
    var showInfo by remember { mutableStateOf(false) }
    val prettyOriginal = remember(original) { SignatureUtil.prettifyHex(original) }
    BackHandler(onBack = onBack)

    // 输入即更新本地状态，写盘做防抖；离开页面时再强制落一次，避免丢最后一次编辑。
    val latestForged by rememberUpdatedState(forged)
    LaunchedEffect(forged) {
        if (forged == current.forgedSignature) return@LaunchedEffect
        delay(400)
        if (forged.isBlank()) {
            SignStore.setForged(current.packageName, "")
        } else if (isHex(forged)) {
            SignStore.setForged(current.packageName, forged)
            SignStore.setEnabled(current.packageName, true)
        }
    }
    DisposableEffect(current.packageName) {
        onDispose {
            val value = latestForged
            if (value.isNotBlank() && isHex(value) && value != current.forgedSignature) {
                SignStore.setForged(current.packageName, value)
                SignStore.setEnabled(current.packageName, true)
            }
        }
    }

    val valid = isHex(forged)
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = current.apkName,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .overScrollVertical(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 6.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Card(modifier = Modifier.cardPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(bitmap = rememberAppIcon(current.packageName), size = 56.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = current.apkName,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontWeight = FontWeight(550),
                                style = MiuixTheme.textStyles.title4,
                            )
                            Text(
                                text = current.packageName,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item { SmallTitle(text = stringResource(R.string.detail_original)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    SignaturePreview(
                        signature = original,
                        lines = 5,
                        modifier = Modifier.padding(16.dp),
                        onClick = { showInfo = true },
                        onLongClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(original))
                            Toast.makeText(
                                context,
                                context.getString(R.string.common_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.detail_forged)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TextField(
                            value = forged,
                            onValueChange = { forged = it },
                            label = stringResource(R.string.detail_forged_hint),
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!valid) {
                            Text(
                                text = stringResource(R.string.catalog_edit_error),
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                                color = MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote2,
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showInfo) {
        SignatureInfoDialog(
            show = true,
            title = stringResource(R.string.detail_original),
            apkName = current.apkName,
            packageName = current.packageName,
            signature = original,
            onDismiss = { showInfo = false },
        )
    }
}
