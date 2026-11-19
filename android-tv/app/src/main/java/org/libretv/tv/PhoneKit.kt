package org.libretv.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 手机界面的基础控件：黑白灰配色（与电视端同一套），尺寸按 dp，触摸反馈用水波纹。 */
internal class PhoneKit(val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    val ink = Color.rgb(245, 245, 247)
    val muted = Color.rgb(152, 152, 159)
    val faint = Color.rgb(99, 99, 102)
    val surface = Color.rgb(28, 28, 30)
    val raised = Color.rgb(44, 44, 46)
    val line = Color.argb(28, 255, 255, 255)
    fun dp(n: Int) = (n * density + .5f).toInt()
    fun dp(n: Float) = n * density

    fun text(label: CharSequence, size: Int = 15, color: Int = ink, weight: Int = 400, lines: Int = 0) = TextView(activity).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat()); setTextColor(color); includeFontPadding = false
        typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight, false) else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }
    fun shape(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }
    /** 带水波纹的背景 */
    fun pressable(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(if (color == Color.WHITE) 60 else 46, if (color == Color.WHITE) 0 else 255, if (color == Color.WHITE) 0 else 255, if (color == Color.WHITE) 0 else 255)), shape(color, radius, stroke), shape(Color.WHITE, radius))
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun lp(width: Int = -1, height: Int = -2, weight: Float = 0f, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(if (width > 0) dp(width) else width, if (height > 0) dp(height) else height, weight).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }
    fun flp(width: Int = -1, height: Int = -1, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(if (width > 0) dp(width) else width, if (height > 0) dp(height) else height, gravity)

    /** 胶囊按钮；primary 为白底黑字 */
    fun button(label: String, primary: Boolean = false, size: Int = 15, onClick: () -> Unit) = text(label, size, if (primary) Color.BLACK else ink, 600).apply {
        gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); minHeight = dp(44); maxLines = 1
        background = pressable(if (primary) Color.WHITE else raised, 22); isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }
    /** 小号选项（片源、倍速、剧集） */
    fun chip(label: String, onClick: () -> Unit) = text(label, 13, ink, 500).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); minHeight = dp(36); maxLines = 1
        isClickable = true; setOnClickListener { onClick() }; select(this, false)
    }
    fun select(chip: TextView, selected: Boolean) {
        chip.isSelected = selected; chip.setTextColor(if (selected) Color.BLACK else ink)
        chip.background = pressable(if (selected) Color.WHITE else raised, 18)
    }
    fun icon(kind: Int, size: Int = 44, tint: Int = ink, onClick: () -> Unit) = ImageView(activity).apply {
        setImageDrawable(PhoneIcon(kind, tint, density)); scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), null, shape(Color.WHITE, size / 2))
        isClickable = true; setOnClickListener { onClick() }; layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
    }
    fun poster(radius: Int = 10) = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(44, 44, 46), Color.rgb(22, 22, 24))).apply { cornerRadius = dp(radius).toFloat() }
        round(this, radius)
    }
    fun round(view: View, radius: Int) {
        view.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, outline: Outline) { outline.setRoundRect(0, 0, v.width, v.height, dp(radius).toFloat()) } }
        view.clipToOutline = true
    }
    fun scroll(content: View) = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true; addView(content, ViewGroup.LayoutParams(-1, -2)) }
    fun strip(content: View) = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false; addView(content) }
    fun divider() = View(activity).apply { setBackgroundColor(line) }
    fun heading(label: String) = text(label, 20, ink, 700)

    /** 自底部升起的面板；返回 (对话框, 内容列) */
    fun sheet(title: String, tall: Boolean = false): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
        val body = column().apply { setPadding(dp(20), dp(8), dp(20), dp(20)) }
        val panel = column().apply {
            background = GradientDrawable().apply { setColor(surface); cornerRadii = floatArrayOf(dp(20f), dp(20f), dp(20f), dp(20f), 0f, 0f, 0f, 0f) }
            addView(View(activity).apply { background = shape(faint, 3) }, lp(36, 5, top = 8).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(title, 18, ink, 700), lp(top = 14, left = 20, right = 20, bottom = 6))
            addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val root = FrameLayout(activity).apply {
            setOnClickListener { dialog.dismiss() }
            addView(panel, FrameLayout.LayoutParams(-1, if (tall) -1 else -2, Gravity.BOTTOM).apply { if (tall) topMargin = dp(72) })
            panel.isClickable = true
        }
        // 系统导航栏和输入法占用的区域留出来
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0); panel.setPadding(0, 0, 0, bars.bottom)
            } else @Suppress("DEPRECATION") { v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0); panel.setPadding(0, 0, 0, insets.systemWindowInsetBottom) }
            insets
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.argb(140, 0, 0, 0))); setLayout(-1, -1)
            setWindowAnimations(R.style.Motion_Lumen_Sheet); navigationBarColor = surface
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (!tall) panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val max = root.height - dp(72)
            if (max > 0 && panel.height > max) panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { height = max }
        }
        return dialog to body
    }
    fun toast(message: String) { if (message.isNotBlank()) android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show() }
    fun time(seconds: Double): String {
        val total = seconds.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 0
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60) else "%02d:%02d".format(total / 60, total % 60)
    }
}

/** 线条图标，统一 24dp 画布 */
internal class PhoneIcon(private val kind: Int, color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = (24 * density).toInt()
    override fun getIntrinsicHeight() = (24 * density).toInt()
    override fun draw(canvas: Canvas) {
        val u = density; val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        fun stroke() { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * u }
        fun fill() { paint.style = Paint.Style.FILL }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(cx + x1 * u, cy + y1 * u, cx + x2 * u, cy + y2 * u, paint)
        fun poly(vararg p: Float) = Path().apply { moveTo(cx + p[0] * u, cy + p[1] * u); for (i in 2 until p.size step 2) lineTo(cx + p[i] * u, cy + p[i + 1] * u) }
        stroke()
        when (kind) {
            BACK -> canvas.drawPath(poly(3f, -8f, -5f, 0f, 3f, 8f), paint)
            SEARCH -> { canvas.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6.5f * u, paint); line(3.5f, 3.5f, 8.5f, 8.5f) }
            TV -> { canvas.drawRoundRect(RectF(cx - 10 * u, cy - 8 * u, cx + 10 * u, cy + 5 * u), 2.5f * u, 2.5f * u, paint); line(-5f, 9f, 5f, 9f) }
            PLAY -> { fill(); canvas.drawPath(poly(-5f, -8f, 9f, 0f, -5f, 8f).apply { close() }, paint) }
            PAUSE -> { fill(); canvas.drawRoundRect(RectF(cx - 7 * u, cy - 8 * u, cx - 2 * u, cy + 8 * u), u, u, paint); canvas.drawRoundRect(RectF(cx + 2 * u, cy - 8 * u, cx + 7 * u, cy + 8 * u), u, u, paint) }
            FULL -> { canvas.drawPath(poly(-8f, -3f, -8f, -8f, -3f, -8f), paint); canvas.drawPath(poly(3f, -8f, 8f, -8f, 8f, -3f), paint); canvas.drawPath(poly(8f, 3f, 8f, 8f, 3f, 8f), paint); canvas.drawPath(poly(-3f, 8f, -8f, 8f, -8f, 3f), paint) }
            SHRINK -> { canvas.drawPath(poly(-9f, -3f, -4f, -3f, -4f, -8f), paint); canvas.drawPath(poly(4f, -8f, 4f, -3f, 9f, -3f), paint); canvas.drawPath(poly(9f, 3f, 4f, 3f, 4f, 8f), paint); canvas.drawPath(poly(-4f, 8f, -4f, 3f, -9f, 3f), paint) }
            HEART, HEART_ON -> {
                val path = Path().apply {
                    moveTo(cx, cy + 8 * u); cubicTo(cx - 14 * u, cy - 1 * u, cx - 7 * u, cy - 11 * u, cx, cy - 4 * u); cubicTo(cx + 7 * u, cy - 11 * u, cx + 14 * u, cy - 1 * u, cx, cy + 8 * u); close()
                }
                if (kind == HEART_ON) paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
            }
            HOME -> { canvas.drawPath(poly(-9f, -1f, 0f, -9f, 9f, -1f), paint); canvas.drawPath(poly(-6f, -2f, -6f, 8f, 6f, 8f, 6f, -2f), paint) }
            LIBRARY -> { canvas.drawRoundRect(RectF(cx - 9 * u, cy - 8 * u, cx - 3 * u, cy + 8 * u), 1.5f * u, 1.5f * u, paint); canvas.drawRoundRect(RectF(cx + 1 * u, cy - 8 * u, cx + 9 * u, cy + 8 * u), 1.5f * u, 1.5f * u, paint) }
            SETTINGS -> for ((row, knob) in listOf(-6f to 3f, 0f to -4f, 6f to 1f)) { stroke(); line(-9f, row, 9f, row); fill(); canvas.drawCircle(cx + knob * u, cy + row * u, 2.8f * u, paint) }
            CLOSE -> { line(-6f, -6f, 6f, 6f); line(6f, -6f, -6f, 6f) }
            REWIND, FORWARD, FORWARD30 -> {
                // 开口朝上的圆弧，箭头在顶端沿弧线方向（后退朝左、快进朝右），圆心写秒数
                val s = if (kind == REWIND) -1f else 1f; val r = 8f * u; val oy = cy + 1.5f * u
                canvas.drawArc(RectF(cx - r, oy - r, cx + r, oy + r), -90f + s * 42f, s * 276f, false, paint)
                fill(); canvas.drawPath(poly(s * 5.2f, -6.5f, s * 0.2f, -10.3f, s * 0.2f, -2.7f).apply { close() }, paint)
                paint.textAlign = Paint.Align.CENTER; paint.textSize = 7.6f * u; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                val metrics = paint.fontMetrics
                canvas.drawText(if (kind == FORWARD30) "30" else "10", cx, oy - (metrics.ascent + metrics.descent) / 2, paint)
            }
            NEXT -> { fill(); canvas.drawPath(poly(-7f, -7f, 4f, 0f, -7f, 7f).apply { close() }, paint); stroke(); line(7f, -7f, 7f, 7f) }
            PREV -> { fill(); canvas.drawPath(poly(7f, -7f, -4f, 0f, 7f, 7f).apply { close() }, paint); stroke(); line(-7f, -7f, -7f, 7f) }
            STOP -> { fill(); canvas.drawRoundRect(RectF(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u), 2 * u, 2 * u, paint) }
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    companion object {
        const val BACK = 0; const val SEARCH = 1; const val TV = 2; const val PLAY = 3; const val PAUSE = 4; const val FULL = 5; const val SHRINK = 6
        const val HEART = 7; const val HEART_ON = 8; const val HOME = 9; const val LIBRARY = 10; const val SETTINGS = 11; const val CLOSE = 12
        const val REWIND = 13; const val FORWARD = 14; const val NEXT = 15; const val PREV = 16; const val STOP = 17; const val FORWARD30 = 18
    }
}
