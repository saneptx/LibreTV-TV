package org.libretv.tv

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

/** 首页主推（与网页端同一版式）：整屏大图在背后（TvBackdrop），这里只放压在大图左下的小标签、粗体大标题、信息、进度、按钮和一排圆点。
 * 内容跟随首页卡片的焦点切换；焦点停在这里的按钮上时由首页每隔几秒换下一部。海报不显示，只用来给没有剧照的影片生成柔光背景。 */
internal class TvHero(private val ui: TvWidgets, private val posters: TvPosterLoader, private val onFeature: (Feature) -> Unit, private val onPoster: (Drawable?) -> Unit) : FrameLayout(ui.activity) {
    class Feature(val key: String, val caption: String, val title: String, val meta: String, val note: String,
        val fraction: Float?, val pic: String, val action: String, val play: () -> Unit,
        val secondary: (() -> String)? = null, val onSecondary: (() -> Unit)? = null, val doubanId: String = "", val tv: Boolean = true)
    private val images = List(2) { ImageView(ui.activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } }
    private var front = 0
    private val column = ui.column()
    private val caption = ui.text("", 11, Color.rgb(190, 190, 196)).apply { letterSpacing = .28f; ui.fontWeight(this, 500) }
    private val title = ui.text("", 46).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; ui.fontWeight(this, 800); setShadowLayer(ui.px(10).toFloat(), 0f, ui.px(2).toFloat(), Color.argb(150, 0, 0, 0)) }
    private val dots = ui.row()
    private val meta = ui.text("", 14, Color.rgb(206, 206, 212)).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val bar = ProgressBar(ui.activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(Color.WHITE); progressBackgroundTintList = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val note = ui.text("", 12, ui.muted)
    private val progress = ui.row()
    var current: Feature? = null; private set
    val play = ui.button("▶   播放") { current?.play?.invoke() }.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(15).toFloat()); ui.fontWeight(this, 600); setPadding(ui.px(24), 0, ui.px(24), 0) }
    private val extra = ui.button("") { current?.onSecondary?.invoke(); current?.secondary?.let { setExtra(it()) } }.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(15).toFloat()); setPadding(ui.px(22), 0, ui.px(22), 0) }
    private fun setExtra(label: String) { extra.setLabel(label) }
    init {
        visibility = View.GONE; clipChildren = false; clipToPadding = false
        // 海报只用来取色：放在一个看不见的 1 像素容器里（加载器要求视图已挂到窗口上）
        val frame = FrameLayout(ui.activity).apply { alpha = 0f }
        images.forEach { frame.addView(it, LayoutParams(-1, -1)) }
        addView(frame, LayoutParams(1, 1))
        val label = ui.row()
        label.addView(View(ui.activity).apply { setBackgroundColor(Color.argb(200, 255, 255, 255)) }, ui.margins(22, 1).apply { rightMargin = ui.px(10) })
        label.addView(caption)
        column.addView(label)
        column.addView(title, ui.margins(-1, -2).apply { topMargin = ui.px(10) })
        column.addView(meta, ui.margins(-1, -2).apply { topMargin = ui.px(8) })
        progress.addView(bar, ui.margins(170, 3).apply { rightMargin = ui.px(12) }); progress.addView(note)
        column.addView(progress, ui.margins(-1, 18).apply { topMargin = ui.px(12) })
        val actions = ui.row()
        actions.addView(play, ui.margins(-2, 44).apply { rightMargin = ui.px(12) }); actions.addView(extra, ui.margins(-2, 44))
        column.addView(actions, ui.margins(-1, -2).apply { topMargin = ui.px(18) })
        column.addView(dots, ui.margins(-1, 6).apply { topMargin = ui.px(18) })
        addView(column, LayoutParams(ui.px(620), -2, Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin = ui.px(6); bottomMargin = ui.px(6) })
    }
    fun show(feature: Feature) {
        val changed = feature.key != current?.key
        val picture = changed || feature.pic != current?.pic
        current = feature; onFeature(feature)
        caption.setLabel(feature.caption); title.setLabel(feature.title); meta.setLabel(feature.meta); note.setLabel(feature.note); play.setLabel(feature.action)
        meta.visibility = if (feature.meta.isEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (feature.fraction != null) View.VISIBLE else View.GONE
        progress.visibility = if (feature.fraction != null || feature.note.isNotEmpty()) View.VISIBLE else View.GONE
        bar.progress = ((feature.fraction ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
        extra.visibility = if (feature.secondary != null) View.VISIBLE else View.GONE
        feature.secondary?.let { setExtra(it()) }
        if (changed) {
            // 文字从右侧轻轻滑入；按钮不动，避免焦点光晕跟着跳
            listOf(caption, title, meta, progress).forEachIndexed { i, v ->
                v.alpha = 0f; v.translationX = ui.px(16).toFloat()
                v.animate().alpha(1f).translationX(0f).setStartDelay(i * 40L).setDuration(520).setInterpolator(TvMotion.out).start()
            }
        }
        if (picture) {
            val next = images[1 - front]; val previous = images[front]
            posters.load(next, feature.pic) { if (current === feature) onPoster(next.drawable) }
            previous.setImageDrawable(null); front = 1 - front
        }
    }
    fun clear() { current = null }
    /** 圆点：count 部轮播影片里现在是第 index 部；不在轮播里（焦点在某张卡片上）时传 -1 隐藏 */
    fun page(count: Int, index: Int) {
        dots.visibility = if (count > 1 && index >= 0) View.VISIBLE else View.INVISIBLE
        while (dots.childCount > count) dots.removeViewAt(dots.childCount - 1)
        while (dots.childCount < count) dots.addView(View(ui.activity), ui.margins(6, 6).apply { rightMargin = ui.px(6) })
        for (i in 0 until dots.childCount) dots.getChildAt(i).let { dot ->
            val on = i == index
            dot.background = ui.background(if (on) Color.WHITE else Color.argb(90, 255, 255, 255), radius = 3)
            dot.layoutParams = (dot.layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = ui.px(if (on) 20 else 6) }
        }
    }
}
