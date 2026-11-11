package com.xiang.signhelper.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图标位图的目标像素尺寸，比 44dp 大一点，避免高密度屏上发糊。 */
private const val ICON_PX = 144

/**
 * 图标缓存：详情页/列表反复进入时不重复查 PackageManager、不重复解码 drawable。
 * 只在主线程读写（Compose 组合阶段），容量很小。
 */
private val iconCache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
        size > 64
}

/**
 * 取某个包的应用图标（未安装时退回本应用图标）。
 *
 * **异步加载**：PackageManager 查询与 drawable → bitmap 解码都放到 IO，
 * 首帧先留空位。否则从列表进入详情页时会在主线程解码图标，卡住转场动画。
 */
@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = iconCache[packageName],
        key1 = packageName,
    ) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = SignatureUtil.loadAppIcon(context, packageName)
                    ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher)
                drawable?.toBitmap(ICON_PX, ICON_PX)?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) iconCache[packageName] = loaded
        value = loaded
    }
    return bitmap
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
        // 图标还在异步加载（或极端情况下拿不到）时留占位，避免行高跳动
        Spacer(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    }
}
