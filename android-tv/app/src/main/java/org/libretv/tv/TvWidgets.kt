package org.libretv.tv

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

internal class TvWidgets(val activity: Activity) {
    private val scale = minOf(activity.resources.displayMetrics.widthPixels / 960f, activity.resources.displayMetrics.heightPixels / 540f)
    // 黑白配色（参考 tvOS）：纯黑底、中性灰的半透明材质、白色焦点；不用彩色点缀，强调只靠明暗
    val ink = Color.rgb(245, 245, 247)
    val muted = Color.rgb(152, 152, 159)
    val accent = Color.WHITE
    val navy = Color.rgb(8, 8, 10)
    val lamp = Color.WHITE
    val glass = Color.argb(34, 255, 255, 255)
    val glassSelected = Color.argb(84, 255, 255, 255)
    val sheet = Color.argb(240, 28, 28, 30)
    private val focusStyles=java.util.WeakHashMap<View,TvFocusDecoration.Style>()
    fun decorate(root: ViewGroup) { TvFocusDecoration(root,scale) { focusStyles[it] } }
    fun fontWeight(view: TextView, weight: Int) {
        view.typeface=if(android.os.Build.VERSION.SDK_INT>=28) Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),weight,false) else Typeface.create("sans-serif",if(weight>=600) Typeface.BOLD else Typeface.NORMAL)
    }
    private val focusTag = "libretv-tv-focus"
    fun px(n: Int) = (n * scale).toInt()
    fun text(label: String, size: Int = 16, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        includeFontPadding = false
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun background(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 10) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat(); setStroke(px(2), stroke)
    }
    /** 海报未加载或加载失败时的底：斜向的深灰渐变 */
    fun posterBackground(radius: Int = 14) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(44, 44, 46), Color.rgb(22, 22, 24))).apply { cornerRadius = px(radius).toFloat() }
    fun focusedBackground(base: Int = glass, radius: Int = 12) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), background(lamp, Color.TRANSPARENT, radius))
        addState(intArrayOf(android.R.attr.state_pressed), background(lamp, Color.TRANSPARENT, radius))
        addState(intArrayOf(android.R.attr.state_selected), background(glassSelected, Color.TRANSPARENT, radius))
        addState(intArrayOf(), background(base, Color.TRANSPARENT, radius))
    }
    /** 卡片与首页大按钮：焦点时放大、浮起，边缘一圈细白光 */
    fun homeFocus(view: View, base: Int = glass, radius: Int = 16) {
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), background(if (base == Color.TRANSPARENT) base else Color.argb(60, 255, 255, 255), Color.argb(235, 255, 255, 255), radius))
            addState(intArrayOf(), background(base, Color.TRANSPARENT, radius))
        }
        focusStyles[view]=TvFocusDecoration.Style(radius,true)
        view.tag = focusTag
        view.setOnFocusChangeListener { v, focused ->
            val target = if (focused) (if (v is EditText) 1.02f else 1.08f) else 1f
            TvMotion.scale(v, target, if (focused) px(14).toFloat() else 0f)
            if(v is EditText && !focused) v.showSoftInputOnFocus=false
        }
    }
    // 聚光：一排卡片里焦点那张保持明亮，同排其余压暗；焦点离开这一排后全部恢复
    private val spot = java.util.WeakHashMap<View, (Boolean) -> Unit>()
    private fun spotlight(row: ViewGroup, focused: View?) {
        for (i in 0 until row.childCount) row.getChildAt(i).let { spot[it]?.invoke(focused == null || it === focused) }
    }
    /** 海报卡片：只高亮海报（白色细边 + 投影 + 放大浮起），片名在下方，不画整卡方框 */
    fun cardFocus(card: View, poster: View, title: TextView? = null) {
        val image = (poster as? ViewGroup)?.getChildAt(0)
        spot[card] = { lit ->
            image?.animate()?.alpha(if (lit) 1f else .42f)?.setStartDelay(0)?.setDuration(if (lit) 260 else 380)?.setInterpolator(TvMotion.out)?.start()
            title?.animate()?.alpha(if (lit) 1f else .55f)?.setStartDelay(0)?.setDuration(260)?.start()
        }
        card.background = null; card.tag = focusTag; card.outlineProvider = null
        // 卡片没有焦点态背景，Android 8 起系统会自动叠一层灰色高亮，需关闭
        if (android.os.Build.VERSION.SDK_INT >= 26) card.defaultFocusHighlightEnabled = false
        val rim = background(Color.TRANSPARENT, lamp, 14).apply { setStroke(px(2), Color.argb(235, 255, 255, 255)) }
        card.setOnFocusChangeListener { v, focused ->
            TvMotion.scale(v, if (focused) 1.1f else 1f, if (focused) 1f else 0f)
            poster.foreground = if (focused) rim else null
            poster.elevation = if (focused) px(8).toFloat() else 0f
            title?.setTextColor(if (focused) Color.WHITE else Color.rgb(224, 224, 228))
            val row = v.parent as? ViewGroup ?: return@setOnFocusChangeListener
            if (focused) spotlight(row, v) else v.post { if (row.focusedChild == null) spotlight(row, null) }
        }
    }
    // 连续上下移动时记住的列位置（屏幕 x 坐标）与上一次落点
    private var focusAnchor: Double? = null
    private var focusAnchorView: View? = null
    fun moveFocus(scope: ViewGroup, code: Int): Boolean {
        val direction = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT; KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> return false }
        val current=scope.findFocus()
        val explicitId=when(direction) { View.FOCUS_LEFT -> current?.nextFocusLeftId; View.FOCUS_RIGHT -> current?.nextFocusRightId; View.FOCUS_UP -> current?.nextFocusUpId; else -> current?.nextFocusDownId }
        val explicit=explicitId?.takeIf { it!=View.NO_ID }?.let { scope.findViewById<View>(it) }
        if(explicit!=null && explicit.isShown && explicit.isEnabled && explicit.isFocusable) {
            focusAnchor=null; explicit.requestFocus(); reveal(explicit); return true
        }
        val nodes = mutableListOf<View>()
        fun collect(v: View) {
            if (v.tag == focusTag && v.isShown && v.isEnabled && v.isFocusable) nodes.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(scope)
        // 聚焦放大的控件按未放大的位置计算，避免相邻控件被判成重叠
        val boxes = nodes.map { v ->
            val xy = IntArray(2); v.getLocationOnScreen(xy)
            val w = v.width * v.scaleX.toDouble(); val h = v.height * v.scaleY.toDouble()
            TvFocusRules.Box(xy[0] + (w - v.width) / 2, xy[1] + (h - v.height) / 2, v.width.toDouble(), v.height.toDouble())
        }
        val from = nodes.indexOf(current)
        val vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN
        if (!vertical || current == null || focusAnchorView !== current) {
            // 整行宽的控件（搜索框、进度条）从左侧开始找，其余从自身中心
            val box = boxes.getOrNull(from)
            focusAnchor = if (vertical && box != null) (if (box.width > scope.width * .5) box.x + px(60) else box.x + box.width / 2) else null
        }
        val index = TvFocusRules.next(boxes, from, direction, px(8).toDouble(), focusAnchor)
        nodes.getOrNull(index)?.let { it.requestFocus(); reveal(it); focusAnchorView = if (vertical) it else null }
        return true
    }
    /** 把获得焦点的控件滚到可视区域中间（纵向与横向滚动容器都处理），顶部和末尾自然贴边 */
    fun reveal(view: View) {
        var child: View = view
        var parent = view.parent
        while (parent is ViewGroup) {
            if (parent is ScrollView || parent is HorizontalScrollView) {
                val rect = Rect(0, 0, view.width, view.height)
                parent.offsetDescendantRectToMyCoords(view, rect)
                val content = parent.getChildAt(0) ?: break
                if (parent is ScrollView) {
                    // 第一屏里完整可见的内容（顶栏、大图推荐、第一排货架）回到页面顶部，不把上方内容滚走；更靠下的才居中
                    val target = if (rect.bottom <= parent.height - px(4)) 0 else (rect.centerY() - parent.height / 2).coerceIn(0, maxOf(0, content.height + parent.paddingTop + parent.paddingBottom - parent.height))
                    if (kotlin.math.abs(target - parent.scrollY) > px(4)) parent.smoothScrollTo(0, target)
                } else {
                    val target = (rect.centerX() - parent.width / 2).coerceIn(0, maxOf(0, content.width + parent.paddingLeft + parent.paddingRight - parent.width))
                    if (kotlin.math.abs(target - parent.scrollX) > px(4)) (parent as HorizontalScrollView).smoothScrollTo(target, 0)
                }
            }
            child = parent; parent = parent.parent
        }
    }
    fun button(label: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); tag = focusTag; text = label; isAllCaps = false
        includeFontPadding=false; fontWeight(this,500); focusStyles[this]=TvFocusDecoration.Style(12,false)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.4f * scale); setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(navy, ink, ink)))
        background = focusedBackground(); setPadding(px(16), px(6), px(16), px(6))
        minWidth = 0; minimumWidth = 0; minHeight = px(36); minimumHeight = px(36)
        isFocusable = true; isFocusableInTouchMode = true; stateListAnimator = null
        // 焦点胶囊轻微放大，与卡片的浮起一致
        onFocusChangeListener = View.OnFocusChangeListener { v, focused -> TvMotion.scale(v, if (focused) 1.07f else 1f) }
        setOnClickListener { action() }
    }
    /** 圆形图标按钮（顶栏的搜索、设置） */
    fun iconButton(kind: Int, description: String, action: () -> Unit) = button("", action).apply {
        background = focusedBackground(radius = 20); focusStyles[this] = TvFocusDecoration.Style(20, false)
        foreground = TvIcon(kind, ink, navy); contentDescription = description; setPadding(0, 0, 0, 0)
    }
    fun input(hintText: String) = EditText(activity).apply {
        id = View.generateViewId(); tag = focusTag; hint = hintText; setSingleLine(true)
        showSoftInputOnFocus=false
        imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        setOnClickListener {
            showSoftInputOnFocus=true
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        setOnKeyListener { _,code,event ->
            if(code==KeyEvent.KEYCODE_DPAD_CENTER && !showSoftInputOnFocus) { if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) performClick(); true } else false
        }
        setTextColor(ink); setHintTextColor(muted); setTextSize(TypedValue.COMPLEX_UNIT_PX, 19.2f * scale)
        homeFocus(this, Color.argb(40, 255, 255, 255), 29); setPadding(px(24), 0, px(24), 0)
        inputType = InputType.TYPE_CLASS_TEXT
    }
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    fun margins(width: Int = -1, height: Int = -2, gap: Int = 0) = LinearLayout.LayoutParams(if (width < 0) width else px(width), if (height < 0) height else px(height)).apply { setMargins(px(gap), px(gap), px(gap), px(gap)) }
    fun grid(columns: Int) = object: GridLayout(activity) {
        init { columnCount=columns; useDefaultMargins=false; clipChildren=false; clipToPadding=false }
        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val cell=(View.MeasureSpec.getSize(widthSpec)-paddingLeft-paddingRight)/columns
            for(i in 0 until childCount) {
                val params=getChildAt(i).layoutParams as GridLayout.LayoutParams
                params.width=maxOf(0,cell-params.leftMargin-params.rightMargin)
            }
            super.onMeasure(widthSpec,heightSpec)
        }
    }
    fun gridCell(index: Int, columns: Int, height: Int = 50) = GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
        width = 0; this.height = px(height); setMargins(px(6), px(6), px(6), px(6))
    }
    fun panel(title: String, sheet: Boolean = false, source: Boolean = false): Panel {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = column().apply {
            clipChildren = false; setPadding(px(if (sheet) 48 else 29), px(19), px(if (sheet) 48 else 29), px(16))
            background = if (sheet) GradientDrawable().apply { setColor(this@TvWidgets.sheet); cornerRadii = floatArrayOf(px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), px(26).toFloat(), 0f, 0f, 0f, 0f) }
                else background(this@TvWidgets.sheet, Color.argb(22,255,255,255), 26)
        }
        val heading = row()
        val headingText = column()
        if (source) headingText.addView(text("切换播放源",11,muted).apply { letterSpacing=.12f }, margins(-1,18))
        val titleView=text(title,if (sheet) 22 else 23,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; fontWeight(this,700) }
        headingText.addView(titleView,margins(-1,if(source) 29 else 31))
        val status=text("",if(sheet) 11 else 11,muted)
        if (source) headingText.addView(status,margins(-1,22))
        heading.addView(headingText,if(sheet) LinearLayout.LayoutParams(-2,-2) else LinearLayout.LayoutParams(0,-2,1f))
        if(sheet) { heading.addView(status,margins(-2,-2).apply { leftMargin=px(14) }); heading.addView(View(activity),LinearLayout.LayoutParams(0,1,1f)) }
        val close=button(if (sheet) "收起" else "返回播放") { dialog.dismiss() }
        heading.addView(close,margins(-2,37).apply { leftMargin=px(10); gravity=Gravity.TOP })
        body.addView(heading,margins(-1,-2).apply { bottomMargin=px(if(source) 15 else 14) })
        if (!sheet && !source) body.addView(status,margins(-1,-2).apply { bottomMargin=px(8) })
        if (source) body.addView(View(activity).apply { setBackgroundColor(Color.argb(20,255,255,255)) },margins(-1,1))
        val content=column().apply { if(source) { clipChildren=true; clipToPadding=true } }
        val scroll=ScrollView(activity).apply {
            isFillViewport=false; clipChildren=false; clipToPadding=false; isFocusable=false
            if(!source) addView(content)
        }
        if (source) {
            // 换源左右两栏各自滚动；外层不再嵌套滚动或把内容量成无界高度。
            body.addView(content,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=px(16) })
        } else if (sheet) body.addView(scroll,margins(-1,-2))
        else body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=text("方向键移动焦点 · 确认选择 · 返回继续观看",11,muted)
        if (sheet || source) body.addView(footer,margins(-1,-2).apply { topMargin=px(14) })
        decorate(body)
        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if(sheet) .5f else .78f)
            setWindowAnimations(if(sheet) R.style.Motion_Lumen_Sheet else R.style.Motion_Lumen_Dialog)
        }
        return Panel(dialog, content, status, close, this, body, heading, scroll, footer, sheet, titleView)
    }
    class Panel(val dialog: Dialog, val content: LinearLayout, val status: TextView, val close: Button, private val ui: TvWidgets,
        val body: LinearLayout, val heading: LinearLayout, val scroll: ScrollView, val footer: TextView, val sheet: Boolean, val title: TextView) {
        var width=864
        var height=464
        fun show() {
            dialog.show()
            dialog.window?.apply {
                setLayout(if (sheet) -1 else ui.px(width), if (sheet) -2 else ui.px(height))
                setGravity(if (sheet) Gravity.BOTTOM else Gravity.CENTER)
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            close.requestFocus()
            // 居中面板的内容依次浮现；底部抽屉整体由窗口动画滑入
            if (!sheet) TvMotion.stagger((0 until body.childCount).map { body.getChildAt(it) }, ui.px(16).toFloat(), 60, 80)
        }
    }
}

internal fun TextView.setLabel(value: CharSequence) { if (text.toString() != value.toString()) text = value }
