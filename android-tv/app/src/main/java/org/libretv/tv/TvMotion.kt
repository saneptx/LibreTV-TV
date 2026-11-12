package org.libretv.tv

import android.view.View
import android.view.animation.PathInterpolator
import java.util.WeakHashMap

/** 全应用共用的动效曲线与时长：进入用长尾减速，焦点带一点回弹，退出更短。只用属性动画，不逐帧重绘。 */
internal object TvMotion {
    val out = PathInterpolator(.16f, 1f, .3f, 1f)
    val inOut = PathInterpolator(.65f, 0f, .35f, 1f)
    val spring = PathInterpolator(.25f, 1.2f, .45f, 1f)
    private val shown = WeakHashMap<View, Boolean>()

    /** 焦点缩放。ViewPropertyAnimator 会记住上一次的延迟和曲线，这里每次都重设 */
    fun scale(view: View, target: Float, lift: Float = 0f) {
        view.animate().scaleX(target).scaleY(target).translationZ(lift).setStartDelay(0)
            .setDuration(if (target > 1f) 260 else 180).setInterpolator(if (target > 1f) spring else out).start()
    }
    /** 入场：自下而上浮现 */
    fun rise(view: View, delay: Long = 0, distance: Float = 28f, duration: Long = 560) {
        view.alpha = 0f; view.translationY = distance
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration).setInterpolator(out).withEndAction(null).start()
    }
    fun stagger(views: List<View>, distance: Float, step: Long = 55, first: Long = 0) = views.filter { it.visibility == View.VISIBLE }.forEachIndexed { i, v -> rise(v, first + i * step, distance) }
    /** 浮层显隐：渐显并滑入 / 渐隐并滑出后才设为 GONE。重复设置同一状态不会重启动画 */
    fun visible(view: View, show: Boolean, dy: Float = 0f) {
        if ((shown[view] ?: (view.visibility == View.VISIBLE)) == show) return
        shown[view] = show
        if (show) {
            if (view.visibility != View.VISIBLE) { view.alpha = 0f; view.translationY = dy; view.visibility = View.VISIBLE }
            view.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(300).setInterpolator(out).withEndAction(null).start()
        } else view.animate().alpha(0f).translationY(dy).setStartDelay(0).setDuration(200).setInterpolator(inOut).withEndAction { view.visibility = View.GONE }.start()
    }
    fun isVisible(view: View) = shown[view] ?: (view.visibility == View.VISIBLE)
}
