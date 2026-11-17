package com.xiang.signhelper.ui.screen.detail

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiang.signhelper.R
import com.xiang.signhelper.data.SignStore
import com.xiang.signhelper.ui.component.AppIcon
import com.xiang.signhelper.ui.component.SignatureInfoDialog
import com.xiang.signhelper.ui.component.SignaturePreview
import com.xiang.signhelper.ui.component.cardPadding
import com.xiang.signhelper.ui.component.rememberAppIcon
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun isHexText(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * 多签名应用的专属详情页。
 *
 * 什么时候进来：条目的原始签名是多行（`SignEntry.isMultiSigner`），也就是这个包
 * 真实带了多张证书（多签名 APK / 签名轮换）。单签名应用仍然走 [EntryDetailScreen]。
 *
 * 与单签名页的差别：
 *  - 逐张列出原始签名（每张单独一张卡片，点开看全文、长按复制）；
 *  - 伪造输入按"一行一条"校验，并实时提示「已填 N 条 / 目标 M 条」——少填或多填
 *    都会让签名集合与真实值不一致，校验方比较张数时会直接判定失败。
 */
@Composable
fun MultiSignerDetailScreen(
    packageName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val entry = SignStore.entries.firstOrNull { it.packageName == packageName }
    LaunchedEffect(entry) { if (entry == null) onBack() }
    val current = entry ?: return

    // 原始签名逐张拆开；空行忽略。
    val originals = remember(current.originalSignature) {
        current.originalSignature.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }
    var forged by remember(current.packageName) { mutableStateOf(current.forgedSignature) }
    var infoIndex by remember { mutableStateOf(-1) }
    BackHandler(onBack = onBack)

    val forgedLines = remember(forged) {
        forged.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }
    val allHex = forgedLines.isNotEmpty() && forgedLines.all(::isHexText)
    val countMatches = forgedLines.size == originals.size

    // 与单签名页同样的策略：输入即更新本地状态，写盘做防抖，离开页面强制落一次。
    val latestForged by rememberUpdatedState(forged)
    LaunchedEffect(forged) {
        if (forged == current.forgedSignature) return@LaunchedEffect
        delay(400)
        if (forged.isBlank()) {
            SignStore.setForged(current.packageName, "")
        } else if (allHex) {
            SignStore.setForged(current.packageName, forged)
            SignStore.setEnabled(current.packageName, true)
        }
    }
    DisposableEffect(current.packageName) {
        onDispose {
            val value = latestForged
            if (value.isNotBlank() && value != current.forgedSignature &&
                value.lineSequence().map(String::trim).filter(String::isNotEmpty).all(::isHexText)
            ) {
                SignStore.setForged(current.packageName, value)
                SignStore.setEnabled(current.packageName, true)
            }
        }
    }

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
            contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // 头部：应用信息 + 多签名徽标
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
                        Column(modifier = Modifier.weight(1f)) {
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
                        Text(
                            text = stringResource(R.string.detail_multi_badge, originals.size),
                            color = MiuixTheme.colorScheme.primary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }

            // 多签名说明
            item {
                Card(modifier = Modifier.cardPadding()) {
                    Text(
                        text = stringResource(R.string.detail_multi_summary, originals.size),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }

            item { SmallTitle(text = stringResource(R.string.detail_multi_signers, originals.size)) }

            // 逐张原始签名
            for (index in originals.indices) {
                val hex = originals[index]
                item(key = "signer-$index") {
                    Card(modifier = Modifier.cardPadding()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_multi_signer, index + 1),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight(500),
                                    style = MiuixTheme.textStyles.body1,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = stringResource(R.string.detail_multi_copy_hint),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote2,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            SignaturePreview(
                                signature = hex,
                                lines = 3,
                                onClick = { infoIndex = index },
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(hex))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.common_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }
            }

            // 伪造签名：一行一条
            item { SmallTitle(text = stringResource(R.string.detail_forged)) }
            item {
                Card(modifier = Modifier.cardPadding()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TextField(
                            value = forged,
                            onValueChange = { forged = it },
                            label = stringResource(R.string.detail_multi_forged_hint, originals.size),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.detail_multi_count,
                                forgedLines.size,
                                originals.size,
                            ),
                            modifier = Modifier.padding(start = 4.dp),
                            color = if (countMatches) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                            style = MiuixTheme.textStyles.footnote2,
                        )
                        if (!allHex && forged.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.catalog_edit_error),
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
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

    if (infoIndex in originals.indices) {
        val hex = originals[infoIndex]
        SignatureInfoDialog(
            show = true,
            title = stringResource(R.string.detail_multi_signer, infoIndex + 1),
            apkName = current.apkName,
            packageName = current.packageName,
            signature = hex,
            onDismiss = { infoIndex = -1 },
        )
    }
}
