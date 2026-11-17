package org.libretv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 首页与详情页背后的整屏大图。两种画面：
 * - 剧照：主推影片的横版剧照清晰铺满，只在上缘、左侧和下半部分压暗，给顶栏、片名和下面的海报行让出可读性；
 * - 柔光：没有剧照时，把焦点影片的海报缩成极小图再放大（等效强模糊），整体压暗。
 * 切换时淡入。不依赖 RenderEffect（Android 12 起才有），Android 6 以上都可用。 */
internal class TvBackdrop(context: Context) : FrameLayout(context) {
    private val layers = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f } }
    private var front = 0
    private var current: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())
    private val shade = android.view.View(context)
    private val softShade = LayerDrawable(arrayOf(
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(90, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.BLACK)),
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)),
    ))
    private val stillShade = LayerDrawable(arrayOf(
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(10, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.BLACK)),
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.TRANSPARENT)),
    ))
    // 剧照的下载按请求编号作废：焦点快速划过时，只有最后一次请求的图会显示
    private var request = 0
    @Volatile private var closed = false
    init {
        layers.forEach { addView(it, LayoutParams(-1, -1)) }
        shade.background = softShade; addView(shade, LayoutParams(-1, -1))
        isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    /** 柔光：海报的模糊大图 */
    fun show(drawable: Drawable?) {
        request++
        val source = (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled && it.width > 0 }
        if (source == null) { clear(); return }
        val tiny = Bitmap.createScaledBitmap(source, 24, maxOf(2, 24 * source.height / source.width), true)
        val soft = Bitmap.createScaledBitmap(tiny, 96, maxOf(2, 96 * source.height / source.width), true)
        tiny.recycle()
        swap(soft, .55f, softShade)
    }
    /** 剧照：下载后清晰铺满；下载失败时保持现有画面 */
    fun showStill(url: String) {
        val mine = ++request
        Thread {
            val bitmap = try { download(url) } catch (_: Exception) { null } ?: return@Thread
            main.post { if (!closed && mine == request) swap(bitmap, 1f, stillShade) else bitmap.recycle() }
        }.start()
    }
    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 LibreTV-AndroidTV/0.4"); setRequestProperty("Referer", "https://movie.douban.com/")
        }
        val bytes = try {
            if (connection.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
            connection.inputStream.use { input ->
                while (true) { val count = input.read(buffer); if (count < 0) break; if (out.size() + count > 6 * 1024 * 1024) return null; out.write(buffer, 0, count) }
            }
            out.toByteArray()
        } finally { connection.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2200) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }
    private fun swap(bitmap: Bitmap, strength: Float, cover: Drawable) {
        val next = layers[1 - front]; val previous = layers[front]
        // 像银幕的反光：新画面从略大处缓慢收拢并淡入，旧画面同时淡出
        next.animate().cancel(); next.setImageBitmap(bitmap); next.scaleX = 1.06f; next.scaleY = 1.06f
        next.animate().alpha(strength).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(TvMotion.out).start()
        previous.animate().alpha(0f).setDuration(520).withEndAction { if (previous.alpha == 0f) previous.setImageDrawable(null) }.start()
        if (shade.background !== cover) shade.background = cover
        current?.let { old -> postDelayed({ if (old !== current && !old.isRecycled) old.recycle() }, 1200) }
        current = bitmap; front = 1 - front
    }
    fun clear() { request++; layers.forEach { it.animate().alpha(0f).setDuration(240).start() } }
    fun close() { closed = true; main.removeCallbacksAndMessages(null) }
}
