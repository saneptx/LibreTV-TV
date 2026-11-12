package org.libretv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.FrameLayout
import android.widget.ImageView

/** 首页与详情页背后的大幅背景：焦点影片海报缩成极小图再放大（等效强模糊），叠暗色渐变，切换时淡入。
 * 不依赖 RenderEffect（Android 12 起才有），Android 6 以上都可用。 */
internal class TvBackdrop(context: Context) : FrameLayout(context) {
    private val layers = List(2) { ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f } }
    private var front = 0
    private var current: Bitmap? = null
    init {
        layers.forEach { addView(it, LayoutParams(-1, -1)) }
        // 上方保留背景色彩，下方与左侧压暗，保证文字与卡片可读
        foreground = LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(90, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.BLACK)),
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)),
        ))
        isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    fun show(drawable: Drawable?) {
        val source = (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled && it.width > 0 }
        if (source == null) { clear(); return }
        val tiny = Bitmap.createScaledBitmap(source, 24, maxOf(2, 24 * source.height / source.width), true)
        val soft = Bitmap.createScaledBitmap(tiny, 96, maxOf(2, 96 * source.height / source.width), true)
        tiny.recycle()
        val next = layers[1 - front]; val previous = layers[front]
        // 像银幕的反光：新画面从略大处缓慢收拢并淡入，旧画面同时淡出
        next.setImageBitmap(soft); next.scaleX = 1.12f; next.scaleY = 1.12f
        next.animate().alpha(.55f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(TvMotion.out).start()
        previous.animate().alpha(0f).setDuration(520).withEndAction { if (previous.alpha == 0f) previous.setImageDrawable(null) }.start()
        current?.let { old -> postDelayed({ if (old !== current) old.recycle() }, 1000) }
        current = soft; front = 1 - front
    }
    fun clear() { layers.forEach { it.animate().alpha(0f).setDuration(240).start() } }
}
