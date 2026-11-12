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

/** 首页大图推荐：左侧是小标签、细体大标题、信息、进度和按钮；右侧是完整的竖版海报（不裁成横幅）。
 * 自身没有底色，背后是整屏的模糊海报背景。内容跟随首页卡片的焦点切换，默认显示最近看过的一部。 */
internal class TvHero(private val ui: TvWidgets, private val posters: TvPosterLoader, private val onPoster: (Drawable?) -> Unit) : FrameLayout(ui.activity) {
    class Feature(val key: String, val caption: String, val title: String, val meta: String, val note: String,
        val fraction: Float?, val pic: String, val action: String, val play: () -> Unit,
        val secondary: (() -> String)? = null, val onSecondary: (() -> Unit)? = null)
    private val images = List(2) { ImageView(ui.activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } }
    private var front = 0
    private val column = ui.column()
    private val caption = ui.text("", 11, Color.rgb(190, 190, 196)).apply { letterSpacing = .28f; ui.fontWeight(this, 500) }
    private val title = ui.text("", 44).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; letterSpacing = .01f; ui.fontWeight(this, 300) }
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
        val frame = FrameLayout(ui.activity).apply { background = ui.posterBackground(14); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND; elevation = ui.px(18).toFloat() }
        images.forEach { frame.addView(it, LayoutParams(-1, -1)) }
        addView(frame, LayoutParams(ui.px(196), ui.px(294), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = ui.px(40) })
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
        column.addView(actions, ui.margins(-1, -2).apply { topMargin = ui.px(22) })
        addView(column, LayoutParams(ui.px(560), -2, Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = ui.px(6) })
    }
    fun show(feature: Feature) {
        val changed = feature.key != current?.key
        val picture = changed || feature.pic != current?.pic
        current = feature
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
            next.scaleX = 1.05f; next.scaleY = 1.05f
            next.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(700).setInterpolator(TvMotion.out).start()
            previous.animate().alpha(0f).setStartDelay(0).setDuration(420).start()
            front = 1 - front
        }
    }
    fun clear() { current = null }
}
