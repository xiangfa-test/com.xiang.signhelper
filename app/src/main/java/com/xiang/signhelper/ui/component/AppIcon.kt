package com.xiang.signhelper.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.xiang.signhelper.R
import com.xiang.signhelper.util.SignatureUtil

/** 图标位图的目标像素尺寸，比 44dp 大一点，避免高密度屏上发糊。 */
private const val ICON_PX = 144

/**
 * 取某个包的应用图标（未安装时退回本应用图标）。
 *
 * 结果按包名 remember：列表滚动/重组不会反复查 PackageManager。
 */
@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            val drawable = SignatureUtil.loadAppIcon(context, packageName)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher)
            drawable?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
        }.getOrNull()
    }
}

/**
 * 应用图标。用标准 [Image] 而不是 Miuix 的 `Icon`：
 * 这里要的是**原色位图**，不该被 Icon 的默认 tint 染色。
 */
@Composable
fun AppIcon(
    bitmap: ImageBitmap?,
    size: Dp = 44.dp,
    cornerRadius: Dp = 12.dp,
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        // 连兜底图标都拿不到（极小概率）时留一块占位，避免行高跳动
        Spacer(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    }
}
