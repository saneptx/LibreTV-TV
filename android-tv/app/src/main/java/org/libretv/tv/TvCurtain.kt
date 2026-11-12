package org.libretv.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** 开场片头：画面转黑，中央浮现片名与一道细线；画面出来后黑幕淡出。启动应用和每次开始播放都用它过渡。 */
internal class TvCurtain(private val ui: TvWidgets) : FrameLayout(ui.activity) {
    private val card = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val caption = ui.text("", 11, Color.rgb(152, 152, 159)).apply { letterSpacing = .42f; ui.fontWeight(this, 600) }
    private val title = ui.text("", 40, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; ui.fontWeight(this, 800) }
    private val line = View(ui.activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 255, 255), Color.TRANSPARENT)) }
    private val subtitle = ui.text("", 14, Color.rgb(200, 200, 206)).apply { gravity = Gravity.CENTER }
    private val note = ui.text("", 12, ui.muted).apply { gravity = Gravity.CENTER }
    var raised = false; private set
    var since = 0L; private set
    init {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE; isFocusable = false; isClickable = true
        card.addView(caption, ui.margins(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(title, ui.margins(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.px(16) })
        card.addView(line, ui.margins(220, 1).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.px(18) })
        card.addView(subtitle, ui.margins(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.px(16) })
        card.addView(note, ui.margins(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.px(26) })
        addView(card, LayoutParams(ui.px(720), -2, Gravity.CENTER))
    }
    /** 落幕并显示片头。instant 用于冷启动：直接是黑幕，不做渐暗 */
    fun raise(captionText: String, titleText: String, subtitleText: String = "", status: String = "", instant: Boolean = false) {
        caption.text = captionText; title.text = titleText; subtitle.text = subtitleText; note.text = status
        subtitle.visibility = if (subtitleText.isEmpty()) View.GONE else View.VISIBLE
        raised = true; since = android.os.SystemClock.elapsedRealtime()
        animate().cancel(); visibility = View.VISIBLE
        if (instant) alpha = 1f else animate().alpha(1f).setStartDelay(0).setDuration(420).setInterpolator(TvMotion.inOut).withEndAction(null).start()
        card.scaleX = 1f; card.scaleY = 1f
        val d = ui.px(14).toFloat()
        TvMotion.rise(caption, 120, d, 700); TvMotion.rise(title, 220, d, 800); TvMotion.rise(subtitle, 380, d, 800); TvMotion.rise(note, 520, d, 700)
        line.scaleX = 0f; line.alpha = 0f
        line.animate().scaleX(1f).alpha(1f).setStartDelay(300).setDuration(1100).setInterpolator(TvMotion.out).start()
    }
    fun status(text: String) { note.setLabel(text) }
    /** 画面就绪：片头略微放大并淡出，露出下面的画面 */
    fun lift(duration: Long = 720) {
        if (!raised) return
        raised = false
        card.animate().scaleX(1.05f).scaleY(1.05f).setStartDelay(0).setDuration(duration).setInterpolator(TvMotion.inOut).start()
        animate().alpha(0f).setStartDelay(0).setDuration(duration).setInterpolator(TvMotion.inOut).withEndAction { visibility = View.GONE }.start()
    }
}
