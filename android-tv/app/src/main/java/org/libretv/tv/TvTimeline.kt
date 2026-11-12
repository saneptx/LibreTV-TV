package org.libretv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** 与原播放页一致的细进度条；遥控器只预览，确认后才提交跳转。 */
internal class TvTimeline(context: Context, private val ui: TvWidgets) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var fraction=0f; set(value) { field=value.coerceIn(0f,1f); invalidate() }
    var buffered=0f; set(value) { field=value.coerceIn(0f,1f); invalidate() }
    var onTap: ((Float)->Unit)?=null
    init {
        id=generateViewId(); isFocusable=true; isFocusableInTouchMode=true
        contentDescription="播放进度，左右键调整，确认键跳转"
    }
    override fun onDraw(canvas: Canvas) {
        // 白色细进度条：焦点时加粗并显示拖动点
        val cy=height/2f; val half=ui.px(if (hasFocus()) 6 else 4)/2f
        paint.color=Color.argb(64,255,255,255); canvas.drawRoundRect(0f,cy-half,width.toFloat(),cy+half,half,half,paint)
        paint.color=Color.argb(90,255,255,255); canvas.drawRoundRect(0f,cy-half,width*buffered,cy+half,half,half,paint)
        paint.color=Color.WHITE; canvas.drawRoundRect(0f,cy-half,width*fraction,cy+half,half,half,paint)
        if (hasFocus()) {
            // 拖动点：一圈淡淡的光晕 + 白色圆点
            paint.color=Color.argb(56,255,255,255); canvas.drawCircle(width*fraction,cy,ui.px(9).toFloat(),paint)
            paint.color=ui.lamp; canvas.drawCircle(width*fraction,cy,ui.px(5).toFloat(),paint)
        }
    }
    override fun onFocusChanged(gain: Boolean,direction: Int,previous: android.graphics.Rect?) { super.onFocusChanged(gain,direction,previous); invalidate() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.action==MotionEvent.ACTION_UP) { onTap?.invoke((event.x/width).coerceIn(0f,1f)); performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
