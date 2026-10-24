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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

internal class TvWidgets(val activity: Activity) {
    private val scale = minOf(activity.resources.displayMetrics.widthPixels / 960f, activity.resources.displayMetrics.heightPixels / 540f)
    val ink = Color.rgb(242, 248, 255)
    val muted = Color.rgb(151, 174, 199)
    val cyan = Color.rgb(73, 222, 255)
    val navy = Color.rgb(8, 22, 32)
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
    fun focusedBackground(base: Int = Color.argb(18, 255, 255, 255)) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), background(Color.rgb(233, 251, 255), cyan, 6))
        addState(intArrayOf(android.R.attr.state_pressed), background(Color.rgb(233, 251, 255), cyan, 6))
        addState(intArrayOf(android.R.attr.state_selected), background(Color.argb(31, 52, 206, 213), cyan, 6))
        addState(intArrayOf(), background(base, Color.argb(40,255,255,255), 6))
    }
    fun homeFocus(view: View, base: Int = Color.argb(235, 19, 32, 50), radius: Int = 16) {
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), background(base, Color.rgb(98,228,255), radius))
            addState(intArrayOf(), background(base, Color.argb(71,126,156,190), radius))
        }
        focusStyles[view]=TvFocusDecoration.Style(radius,true)
        view.tag = focusTag
        view.setOnFocusChangeListener { v, focused ->
            v.scaleX = if (focused) 1.045f else 1f; v.scaleY = v.scaleX
            v.translationZ = if (focused) px(6).toFloat() else 0f
            if(v is EditText && !focused) v.showSoftInputOnFocus=false
        }
    }
    fun moveFocus(scope: ViewGroup, code: Int): Boolean {
        val direction = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT; KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> return false }
        val current=scope.findFocus()
        val explicitId=when(direction) { View.FOCUS_LEFT -> current?.nextFocusLeftId; View.FOCUS_RIGHT -> current?.nextFocusRightId; View.FOCUS_UP -> current?.nextFocusUpId; else -> current?.nextFocusDownId }
        val explicit=explicitId?.takeIf { it!=View.NO_ID }?.let { scope.findViewById<View>(it) }
        if(explicit!=null && explicit.isShown && explicit.isEnabled && explicit.isFocusable) {
            explicit.requestFocus(); explicit.requestRectangleOnScreen(Rect(0,0,explicit.width,explicit.height),false); return true
        }
        val nodes = mutableListOf<View>()
        fun collect(v: View) {
            if (v.tag == focusTag && v.isShown && v.isEnabled && v.isFocusable) nodes.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(scope)
        val boxes = nodes.map { v -> val xy = IntArray(2); v.getLocationOnScreen(xy); TvFocusRules.Box(xy[0].toDouble(), xy[1].toDouble(), v.width.toDouble(), v.height.toDouble()) }
        val index = TvFocusRules.next(boxes, nodes.indexOf(scope.findFocus()), direction, px(8).toDouble())
        nodes.getOrNull(index)?.let { it.requestFocus(); it.requestRectangleOnScreen(Rect(0,0,it.width,it.height), false) }
        return true
    }
    fun button(label: String, action: () -> Unit) = Button(activity).apply {
        id = View.generateViewId(); tag = focusTag; text = label; isAllCaps = false
        includeFontPadding=false; fontWeight(this,400); focusStyles[this]=TvFocusDecoration.Style(6,false)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13.4f * scale); setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(navy, cyan, ink)))
        background = focusedBackground(); setPadding(px(15), px(6), px(15), px(6))
        minWidth = 0; minimumWidth = 0; minHeight = px(36); minimumHeight = px(36)
        isFocusable = true; isFocusableInTouchMode = true
        setOnClickListener { action() }
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
        homeFocus(this); setPadding(px(17), 0, px(17), 0)
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
            background = background(if (sheet) Color.argb(245,16,24,36) else Color.rgb(16,25,37), Color.argb(31,255,255,255), if (sheet) 0 else 10)
        }
        val heading = row()
        val headingText = column()
        if (source) headingText.addView(text("切换播放源",11,cyan).apply { letterSpacing=.12f }, margins(-1,18))
        val titleView=text(title,if (sheet) 22 else 21,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
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
            setDimAmount(if(sheet) .4f else .67f)
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
        }
    }
}

internal fun TextView.setLabel(value: CharSequence) { if (text.toString() != value.toString()) text = value }
