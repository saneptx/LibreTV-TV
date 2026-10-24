package org.libretv.tv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** 原 /tv 的线性底色及两个径向光晕。 */
internal class TvHomeBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        paint.shader = LinearGradient(0f, 0f, w * .36f, h, intArrayOf(Color.rgb(7,16,29), Color.rgb(11,21,36), Color.rgb(16,24,39)), floatArrayOf(0f,.55f,1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(w * .12f, h * .35f, w * .3f, Color.argb(31,255,60,120), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(w * .78f, h * .05f, w * .28f, Color.argb(46,0,204,255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
