package org.libretv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.libretv.tv.core.HotList
import org.libretv.tv.core.Net

/** 海报加载：同时最多 6 个请求，解码结果按地址缓存（最多 240 张）。 */
object Images {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 240 }
    private val failed = HashSet<String>()
    private val gate = Semaphore(6)
    fun cached(url: String) = synchronized(cache) { cache[url] }
    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank() || !url.startsWith("http")) return null
        cached(url)?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val res = Net.fetch(url, headers = mapOf("User-Agent" to "Mozilla/5.0") + HotList.imageHeaders(url), timeoutMs = 8000, maxBytes = 6 * 1024 * 1024)
                    if (!res.ok) null else org.jetbrains.skia.Image.makeFromEncoded(res.body).toComposeImageBitmap().also { synchronized(cache) { cache[url] = it } }
                } catch (_: Exception) { null }
            }
        }.also { if (it == null) synchronized(failed) { failed.add(url) } }
    }
}

@Composable
fun Poster(url: String, modifier: Modifier = Modifier, radius: Dp = 12.dp, overlay: @Composable () -> Unit = {}) {
    var image by remember(url) { mutableStateOf(Images.cached(url)) }
    LaunchedEffect(url) { if (image == null) image = Images.load(url) }
    Box(modifier.clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(Color(0xFF2C2C2E), Color(0xFF161618))))) {
        // 加载完成时淡入（已缓存的直接显示）
        val alpha by androidx.compose.animation.core.animateFloatAsState(if (image != null) 1f else 0f, androidx.compose.animation.core.tween(320))
        image?.let { Image(it, null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
        overlay()
    }
}
