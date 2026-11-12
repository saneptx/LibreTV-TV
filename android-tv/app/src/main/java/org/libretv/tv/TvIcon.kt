package org.libretv.tv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** 顶栏图标按钮里的线条图标（搜索、设置）。画在按钮前景上，按钮获得焦点变白底时图标转为深色。 */
internal class TvIcon(private val kind: Int, private val light: Int, private val dark: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = light }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = if (state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)) dark else light
        if (next == paint.color) return false
        paint.color = next; invalidateSelf(); return true
    }
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY(); val u = minOf(bounds.width(), bounds.height()) / 40f
        paint.strokeWidth = 2f * u
        if (kind == SEARCH) {
            canvas.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6f * u, paint)
            canvas.drawLine(cx + 3f * u, cy + 3f * u, cx + 8f * u, cy + 8f * u, paint)
        } else {
            // 设置：三条滑杆
            for ((row, knob) in listOf(-6f to 3f, 0f to -4f, 6f to 1f)) {
                val y = cy + row * u
                canvas.drawLine(cx - 8f * u, y, cx + 8f * u, y, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(cx + knob * u, y, 2.6f * u, paint); paint.style = Paint.Style.STROKE
            }
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object { const val SEARCH = 0; const val SETTINGS = 1 }
}
