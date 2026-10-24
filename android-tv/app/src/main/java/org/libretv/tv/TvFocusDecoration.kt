package org.libretv.tv

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import kotlin.math.exp

/** 在根视图绘制旧界面的焦点外圈，避免网格/滚动容器裁掉光晕。无位图或逐帧动画。 */
internal class TvFocusDecoration(private val root: ViewGroup, private val scale: Float,
    private val styleFor: (View) -> Style?) : Drawable(), View.OnAttachStateChangeListener {
    data class Style(val radius: Int, val home: Boolean)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    private val focusRect=RectF()
    private val focusClip=RectF()
    private var style: Style?=null
    private var tree: ViewTreeObserver?=null
    private val focusListener=ViewTreeObserver.OnGlobalFocusChangeListener { _,_ -> update() }
    private val drawListener=ViewTreeObserver.OnPreDrawListener { update(); true }
    init { root.addOnAttachStateChangeListener(this); if(root.isAttachedToWindow) attach() }
    private fun attach() {
        if(tree!=null) return
        root.overlay.add(this)
        tree=root.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(focusListener); it.addOnPreDrawListener(drawListener) }
        update()
    }
    private fun update() {
        val focused=root.findFocus()
        val next=focused?.takeIf { it.isShown && it.isEnabled }?.let(styleFor)
        val rect=RectF()
        val clip=RectF(0f,0f,root.width.toFloat(),root.height.toFloat())
        if(next!=null) {
            val origin=IntArray(2); val target=IntArray(2)
            root.getLocationOnScreen(origin); focused.getLocationOnScreen(target)
            rect.set((target[0]-origin[0]).toFloat(),(target[1]-origin[1]).toFloat(),
                target[0]-origin[0]+focused.width*focused.scaleX,target[1]-origin[1]+focused.height*focused.scaleY)
            // 外圈画在根 overlay 上，也必须遵守启用裁切的滚动视口，不能覆盖固定页脚。
            var parent=focused.parent
            while(parent is ViewGroup && parent!==root) {
                if((parent is ScrollView || parent is HorizontalScrollView) && parent.clipChildren) {
                    val xy=IntArray(2); parent.getLocationOnScreen(xy)
                    val left=(xy[0]-origin[0]+if(parent.clipToPadding) parent.paddingLeft else 0).toFloat()
                    val top=(xy[1]-origin[1]+if(parent.clipToPadding) parent.paddingTop else 0).toFloat()
                    val right=(xy[0]-origin[0]+parent.width-if(parent.clipToPadding) parent.paddingRight else 0).toFloat()
                    val bottom=(xy[1]-origin[1]+parent.height-if(parent.clipToPadding) parent.paddingBottom else 0).toFloat()
                    if(!clip.intersect(left,top,right,bottom)) clip.setEmpty()
                }
                parent=parent.parent
            }
        }
        if(style!=next || focusRect!=rect || focusClip!=clip || bounds.width()!=root.width || bounds.height()!=root.height) {
            style=next; focusRect.set(rect); focusClip.set(clip); setBounds(0,0,root.width,root.height); invalidateSelf()
        }
    }
    override fun draw(canvas: Canvas) {
        val active=style ?: return
        val save=canvas.save()
        canvas.clipRect(focusClip)
        val radius=active.radius*scale
        for(step in 14 downTo 1) {
            val expansion=(step+4)*scale
            val glow=RectF(focusRect).apply { inset(-expansion,-expansion) }
            paint.color=Color.rgb(53,217,255)
            paint.alpha=((if(active.home) 24 else 16)*exp(-step*step/80.0)).toInt()
            paint.strokeWidth=1.2f*scale
            canvas.drawRoundRect(glow,radius+expansion,radius+expansion,paint)
        }
        val expansion=(if(active.home) 2.5f else 3f)*scale
        val outline=RectF(focusRect).apply { inset(-expansion,-expansion) }
        paint.color=if(active.home) Color.argb(77,53,217,255) else Color.rgb(98,228,255)
        paint.strokeWidth=(if(active.home) 5f else 2f)*scale
        canvas.drawRoundRect(outline,radius+expansion,radius+expansion,paint)
        canvas.restoreToCount(save)
    }
    override fun onViewAttachedToWindow(v: View)=attach()
    override fun onViewDetachedFromWindow(v: View) {
        tree?.takeIf { it.isAlive }?.let { it.removeOnGlobalFocusChangeListener(focusListener); it.removeOnPreDrawListener(drawListener) }
        tree=null; root.overlay.remove(this)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
