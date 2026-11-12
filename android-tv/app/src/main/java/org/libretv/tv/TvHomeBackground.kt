package org.libretv.tv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 首页底色：纯黑，只在顶部留一点极淡的灰度过渡；色彩全部来自焦点影片的模糊海报。 */
internal class TvHomeBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val h = bounds.height().toFloat()
        if (bounds.width() <= 0 || h <= 0) return
        paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(20,20,22), Color.rgb(6,6,7), Color.BLACK), floatArrayOf(0f,.5f,1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
