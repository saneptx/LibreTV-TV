package org.libretv.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 卡片回收时用 URL tag 隔离迟到图片；缓存和下载量有界。 */
internal class TvPosterLoader {
    private val main = Handler(Looper.getMainLooper())
    // 搜索最多 300 组；容量覆盖完整结果页，避免较早入队的可见封面被后续条目挤掉。
    private val executor = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(320), ThreadPoolExecutor.DiscardOldestPolicy())
    private val connections = java.util.Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    @Volatile private var closed = false
    fun load(view: ImageView, url: String, onLoaded: (() -> Unit)? = null) {
        view.tag = url; view.setImageDrawable(null)
        if (url.isBlank() || closed) return
        cache.get(url)?.let { view.setImageBitmap(it); onLoaded?.invoke(); return }
        executor.execute {
            try {
                if(closed || view.tag!=url || !view.isAttachedToWindow) return@execute
                val target = URL(url)
                if (target.protocol !in setOf("http", "https")) return@execute
                val connection = (target.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 LibreTV-AndroidTV/0.4")
                    // 豆瓣的海报图床校验来路
                    if (target.host.endsWith("doubanio.com")) setRequestProperty("Referer", "https://movie.douban.com/")
                }
                connections.add(connection)
                val bytes = try {
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (!closed) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (out.size() + count > 4 * 1024 * 1024) return@execute
                            out.write(buffer, 0, count)
                        }
                    }
                    out.toByteArray()
                } finally { connections.remove(connection); connection.disconnect() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 720) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@execute
                if (!closed) {
                    cache.put(url, bitmap)
                    main.post { if (!closed && view.tag == url) { view.setImageBitmap(bitmap); onLoaded?.invoke() } }
                }
            } catch (_: Exception) { /* 保留原生占位背景，片源封面失败不影响选片 */ }
        }
    }
    fun close() { closed = true; connections.forEach { it.disconnect() }; executor.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll() }
}
