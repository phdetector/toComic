@file:OptIn(ExperimentalCoroutinesApi::class)

package com.cj2026.planner.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val imageCache = mutableStateMapOf<String, Bitmap>()
private val loadingUrls = mutableStateMapOf<String, Boolean>()
private val logoDispatcher = Dispatchers.IO.limitedParallelism(4)

@Composable
fun RemoteLogo(url: String?, label: String, modifier: Modifier = Modifier) {
    val resolvedUrl = url ?: logoUrlFor(label)
    var bitmap by remember(resolvedUrl) { mutableStateOf(resolvedUrl?.let { imageCache[it] }) }
    LaunchedEffect(resolvedUrl) {
        val target = resolvedUrl ?: return@LaunchedEffect
        if (bitmap == null && loadingUrls[target] != true) {
            loadingUrls[target] = true
            bitmap = runCatching { loadBitmap(target) }.getOrNull()?.also { imageCache[target] = it }
            loadingUrls[target] = false
        }
    }
    Box(
        modifier = modifier
            .size(width = 72.dp, height = 42.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = label.take(8),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private suspend fun loadBitmap(url: String): Bitmap? = withContext(logoDispatcher) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        instanceFollowRedirects = true
    }
    if (connection.responseCode !in 200..299) return@withContext null
    connection.inputStream.use { BitmapFactory.decodeStream(it) }
}

private fun logoUrlFor(label: String): String? {
    val text = label.lowercase()
    val domain = when {
        "playstation" in text || "sony" in text -> "playstation.com"
        "bilibili" in text -> "bilibili.com"
        "netease" in text || "网易" in text -> "neteasegames.com"
        "blizzard" in text -> "blizzard.com"
        "oppo" in text -> "oppo.com"
        "huawei" in text || "华为" in text -> "huawei.com"
        "tencent" in text || "腾讯" in text -> "tencent.com"
        "snapdragon" in text || "骁龙" in text -> "qualcomm.com"
        "xiaomi" in text || "小米" in text -> "mi.com"
        "oneplus" in text || "一加" in text -> "oneplus.com"
        "iqoo" in text -> "iqoo.com"
        "honor" in text -> "honor.com"
        "nubia" in text || "红魔" in text -> "nubia.com"
        "京东" in text -> "jd.com"
        "nikon" in text || "尼康" in text -> "nikon.com"
        "panasonic" in text -> "panasonic.com"
        "sigma" in text -> "sigma-global.com"
        "sandisk" in text || "闪迪" in text -> "sandisk.com"
        "polaroid" in text -> "polaroid.com"
        "smallrig" in text -> "smallrig.com"
        "weibo" in text || "微博" in text -> "weibo.com"
        "快手" in text -> "kuaishou.com"
        "mcdonald" in text -> "mcdonalds.com"
        "hello kitty" in text -> "sanrio.com"
        "pilot" in text -> "pilotpen.com"
        "kioxia" in text || "铠侠" in text -> "kioxia.com"
        "unitree" in text || "宇树" in text -> "unitree.com"
        "autofull" in text || "傲风" in text -> "autofull.com"
        "obsbot" in text -> "obsbot.com"
        "edifier" in text || "漫步者" in text -> "edifier.com"
        "akko" in text -> "akkogear.com"
        "iqunix" in text -> "iqunix.store"
        "dareu" in text || "达尔优" in text -> "dareu.com"
        "black shark" in text -> "blackshark.com"
        "tcl" in text -> "tcl.com"
        "deli" in text || "得力" in text -> "deliworld.com"
        "youku" in text || "优酷" in text -> "youku.com"
        "baidu" in text || "百度" in text -> "baidu.com"
        "顺丰" in text -> "sf-express.com"
        else -> null
    }
    return domain?.let { "https://logo.clearbit.com/$it" }
}
