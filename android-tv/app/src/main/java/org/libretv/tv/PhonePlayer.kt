package org.libretv.tv

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject

/** 手机播放页：竖屏时上方画面、下方选集；可全屏横屏。单击显示控制，双击两侧快退 / 快进，横向拖动调进度。 */
@UnstableApi
internal class PhonePlayer(private val ui: PhoneUi) {
    private val kit = ui.kit
    private val activity = ui.activity
    private val density = activity.resources.displayMetrics.density
    var active: JSONObject? = null; private set
    private var state = JSONObject()
    private var session = ""
    private var openingId: String? = null
    private var loading = false
    private var fullscreen = false
    private var rate = 1.0
    private var lastSaved = 0L
    private var qualitySent = false
    private var endedSession = ""
    private var dragging = false
    private var seekPreview: Double? = null
    private var pending: Triple<JSONObject, Int, Double?>? = null
    private val prefs = activity.getSharedPreferences("supertv_phone", Context.MODE_PRIVATE)
    /** 全屏时裁剪画面铺满屏幕（否则保持完整画面，两侧可能留黑边）；记住上次的选择 */
    private var fillScreen = prefs.getBoolean("fill_screen", false)

    /** 竖屏时保持 16:9，全屏时铺满 */
    private inner class VideoBox(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            if (fullscreen) super.onMeasure(widthSpec, heightSpec)
            else super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec) * 9 / 16, MeasureSpec.EXACTLY))
        }
    }
    private val page = kit.column().apply { setBackgroundColor(Color.BLACK) }
    private val box = VideoBox(activity).apply { setBackgroundColor(Color.BLACK) }
    private val controls = FrameLayout(activity)
    private val cover = kit.column().apply { gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK); setPadding(kit.dp(24), 0, kit.dp(24), 0) }
    private val coverSpinner = ProgressBar(activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
    private val coverText = kit.text("", 14, kit.muted).apply { gravity = Gravity.CENTER }
    private val coverAction = kit.button("换源", true, 14) { showSources() }
    private val buffering = ProgressBar(activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); visibility = View.GONE }
    private val hint = kit.text("", 15, Color.WHITE, 600).apply { background = kit.shape(Color.argb(170, 0, 0, 0), 10); setPadding(kit.dp(14), kit.dp(9), kit.dp(14), kit.dp(9)); visibility = View.GONE }
    private val topTitle = kit.text("", 15, Color.WHITE, 600, 1)
    private val playIcon = ImageView(activity)
    private val elapsed = kit.text("00:00", 12, Color.WHITE, 500)
    private val total = kit.text("00:00", 12, Color.WHITE, 500)
    private val seek = SeekBar(activity).apply {
        max = 10000; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        secondaryProgressTintList = android.content.res.ColorStateList.valueOf(Color.argb(150, 255, 255, 255)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
    }
    private val fullIcon = ImageView(activity)
    private val fitChip = kit.text("", 13, Color.WHITE, 600).apply {
        gravity = Gravity.CENTER; setPadding(kit.dp(12), 0, kit.dp(12), 0); background = kit.pressable(Color.argb(90, 0, 0, 0), 16, Color.argb(150, 255, 255, 255)); isClickable = true; visibility = View.GONE
        setOnClickListener { fillScreen = !fillScreen; prefs.edit().putBoolean("fill_screen", fillScreen).apply(); applyFit(); flash(if (fillScreen) "裁剪铺满" else "完整画面"); schedule() }
    }
    private val info = kit.column().apply { setPadding(kit.dp(20), kit.dp(16), kit.dp(20), kit.dp(32)) }
    private val infoScroll = kit.scroll(info)
    private val title = kit.text("", 20, kit.ink, 700, 2)
    private val subtitle = kit.text("", 13, kit.muted, lines = 2)
    private val adLine = kit.text("", 12, kit.faint, lines = 2).apply { visibility = View.GONE }
    private val rateChip = kit.chip("倍速 1.0×") { showRates() }
    private val episodeTitle = kit.text("选集", 15, kit.ink, 600)
    private val episodeGrid = GridLayout(activity).apply { columnCount = 5 }
    private val episodeCells = mutableListOf<TextView>()
    private lateinit var topRow: LinearLayout
    private lateinit var bottomRow: LinearLayout
    private val hideControls = Runnable { if (!state.optBoolean("paused") && !dragging) showControls(false) }
    private var shown = false
    private fun icon(kind: Int) = PhoneIcon(kind, Color.WHITE, density)

    init {
        // 控制层：上下渐变压暗，保证白色图标在亮画面上可见
        controls.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, kit.dp(76), Gravity.TOP))
        controls.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, kit.dp(88), Gravity.BOTTOM))
        val top = kit.row().apply { setPadding(kit.dp(4), kit.dp(2), kit.dp(4), 0) }
        top.addView(kit.icon(PhoneIcon.BACK, tint = Color.WHITE) { if (fullscreen) setFullscreen(false) else ui.pop(page) })
        top.addView(topTitle, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(kit.icon(PhoneIcon.TV, tint = Color.WHITE) { castCurrent() })
        topRow = top; controls.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val center = kit.row().apply { gravity = Gravity.CENTER }
        center.addView(kit.icon(PhoneIcon.REWIND, 52, Color.WHITE) { seekBy(-10.0) })
        playIcon.apply { scaleType = ImageView.ScaleType.CENTER; background = kit.pressable(Color.argb(110, 0, 0, 0), 32); isClickable = true; setOnClickListener { toggle() } }
        center.addView(playIcon, kit.lp(64, 64, left = 28, right = 28))
        center.addView(kit.icon(PhoneIcon.FORWARD, 52, Color.WHITE) { seekBy(10.0) })
        controls.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val bottom = kit.row().apply { setPadding(kit.dp(14), 0, kit.dp(4), kit.dp(2)) }
        bottom.addView(elapsed); bottom.addView(seek, LinearLayout.LayoutParams(0, kit.dp(40), 1f)); bottom.addView(total)
        fullIcon.apply { scaleType = ImageView.ScaleType.CENTER; isClickable = true; setOnClickListener { setFullscreen(!fullscreen) } }
        bottom.addView(fitChip, kit.lp(-2, 32, left = 10))
        bottom.addView(fullIcon, kit.lp(44, 44))
        bottomRow = bottom; controls.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        controls.visibility = View.GONE

        cover.addView(coverSpinner, kit.lp(30, 30)); cover.addView(coverText, kit.lp(-2, -2, top = 14)); cover.addView(coverAction, kit.lp(-2, 40, top = 14))
        box.addView(controls, kit.flp()); box.addView(buffering, kit.flp(34, 34, Gravity.CENTER)); box.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); box.addView(cover, kit.flp())
        val back = kit.icon(PhoneIcon.BACK, tint = Color.WHITE) { if (fullscreen) setFullscreen(false) else ui.pop(page) }
        box.addView(back, FrameLayout.LayoutParams(kit.dp(44), kit.dp(44), Gravity.TOP or Gravity.START).apply { setMargins(kit.dp(4), kit.dp(2), 0, 0) })
        cover.tag = back

        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showControls(!shown); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (active == null) return true
                when { e.x < box.width / 3f -> seekBy(-10.0); e.x > box.width * 2 / 3f -> seekBy(10.0); else -> toggle() }
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val start = e1 ?: return false; val duration = state.optDouble("duration", 0.0)
                if (active == null || duration <= 0) return false
                val moved = e2.x - start.x
                if (seekPreview == null && (Math.abs(moved) < kit.dp(18) || Math.abs(moved) < Math.abs(e2.y - start.y))) return false
                // 拖满整个宽度约等于 3 分钟（长片上限为总时长的四分之一）
                val span = minOf(180.0, duration / 4).coerceAtLeast(30.0)
                val target = (state.optDouble("position", 0.0) + moved / box.width * span).coerceIn(0.0, duration - 1)
                seekPreview = target; hint.text = "${kit.time(target)} / ${kit.time(duration)}"; hint.visibility = View.VISIBLE
                return true
            }
        })
        box.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                seekPreview?.let { if (event.actionMasked == MotionEvent.ACTION_UP) command("seek", JSONObject().put("position", it)) }
                seekPreview = null; hint.visibility = View.GONE
            }
            true
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) elapsed.text = kit.time(state.optDouble("duration", 0.0) * progress / 10000) }
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; ui.main.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                val duration = state.optDouble("duration", 0.0)
                if (duration > 0) command("seek", JSONObject().put("position", duration * bar.progress / 10000))
                schedule()
            }
        })

        info.addView(title); info.addView(subtitle, kit.lp(top = 8)); info.addView(adLine, kit.lp(top = 6))
        val actions = kit.row()
        actions.addView(kit.chip("换源") { showSources() }, kit.lp(-2, 36, right = 8))
        actions.addView(rateChip, kit.lp(-2, 36, right = 8))
        actions.addView(kit.chip("投到电视") { castCurrent() }, kit.lp(-2, 36))
        info.addView(kit.strip(actions), kit.lp(top = 16))
        info.addView(episodeTitle, kit.lp(top = 24, bottom = 10)); info.addView(episodeGrid)
        page.addView(box, LinearLayout.LayoutParams(-1, -2))
        page.addView(infoScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fullIcon.setImageDrawable(icon(PhoneIcon.FULL)); playIcon.setImageDrawable(icon(PhoneIcon.PAUSE))
    }

    private fun command(type: String, values: JSONObject = JSONObject()) {
        if (session.isEmpty()) return
        ui.player.command(values.put("version", 1).put("sessionId", session).put("type", type))
    }
    private fun removals() = state.optJSONArray("timeMap").objects().map { PlaylistFilter.Removal(it.optDouble("start"), it.optDouble("duration")) }
    /** 换算成原片时间（去掉的广告不计入播放器进度） */
    private fun originalPosition() = AdTimeMap.original(state.optDouble("position", 0.0), removals())
    private fun toggle() { if (active != null) { command(if (state.optBoolean("paused")) "play" else "pause"); schedule() } }
    private fun seekBy(delta: Double) {
        val duration = state.optDouble("duration", 0.0); if (active == null || duration <= 0) return
        command("seek", JSONObject().put("position", (state.optDouble("position", 0.0) + delta).coerceIn(0.0, duration - 1)))
        hint.text = if (delta < 0) "后退 ${-delta.toInt()} 秒" else "快进 ${delta.toInt()} 秒"; hint.visibility = View.VISIBLE
        ui.main.removeCallbacks(hideHint); ui.main.postDelayed(hideHint, 700); schedule()
    }
    private val hideHint = Runnable { if (seekPreview == null) hint.visibility = View.GONE }
    private fun flash(text: String) { hint.text = text; hint.visibility = View.VISIBLE; ui.main.removeCallbacks(hideHint); ui.main.postDelayed(hideHint, 900) }
    /** 竖屏的小窗始终显示完整画面；全屏时按用户选择裁剪铺满或保持完整 */
    private fun applyFit() {
        fitChip.text = if (fillScreen) "完整画面" else "铺满"
        command("fit", JSONObject().put("fit", if (fullscreen && fillScreen) "cover" else "contain"))
    }
    /** 去广告结果：播放器实际从片源里删掉的片段（来自它解析后的播放列表） */
    private fun renderAds() {
        val removals = removals(); val seconds = removals.sumOf { it.duration }
        adLine.text = if (!ui.config.optBoolean("adFilter", true)) "去广告已关闭" else if (removals.isEmpty()) "去广告：这一集没有发现插播广告" else "已去掉 ${removals.size} 段插播广告 · 共 ${Math.round(seconds)} 秒"
        adLine.visibility = if (active != null && state.optDouble("duration", 0.0) > 0) View.VISIBLE else View.GONE
    }
    private fun schedule() { ui.main.removeCallbacks(hideControls); if (shown) ui.main.postDelayed(hideControls, 3500) }
    private fun showControls(show: Boolean) {
        if (active == null || cover.visibility == View.VISIBLE) { controls.visibility = View.GONE; shown = false; return }
        shown = show; controls.animate().cancel()
        if (show) { controls.visibility = View.VISIBLE; controls.animate().alpha(1f).setDuration(140).start(); schedule() }
        else controls.animate().alpha(0f).setDuration(180).withEndAction { controls.visibility = View.GONE }.start()
    }
    private fun cover(text: String, busy: Boolean, action: Boolean = false) {
        cover.visibility = View.VISIBLE; coverText.text = text; coverSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        coverAction.visibility = if (action) View.VISIBLE else View.GONE; (cover.tag as View).visibility = View.VISIBLE
        controls.visibility = View.GONE; shown = false; buffering.visibility = View.GONE
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on; ui.immersive = on
        val window = activity.window
        activity.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // 全屏时画面铺到屏幕边缘（含刘海 / 曲面屏两侧），系统栏收起，从边缘滑动可临时唤出
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (on) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c ->
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) c.hide(android.view.WindowInsets.Type.systemBars()) else c.show(android.view.WindowInsets.Type.systemBars())
        } else @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = if (on) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION else 0
        }
        infoScroll.visibility = if (on) View.GONE else View.VISIBLE
        box.layoutParams = LinearLayout.LayoutParams(-1, if (on) -1 else -2)
        fullIcon.setImageDrawable(icon(if (on) PhoneIcon.SHRINK else PhoneIcon.FULL))
        topTitle.visibility = if (on) View.VISIBLE else View.INVISIBLE
        fitChip.visibility = if (on) View.VISIBLE else View.GONE; applyFit()
        // 控制按钮避开屏幕圆角、刘海和底部手势条
        // （只缩进按钮所在的两行，压暗用的渐变仍铺到屏幕边缘）
        topRow.setPadding(kit.dp(if (on) 40 else 4), kit.dp(if (on) 12 else 2), kit.dp(if (on) 40 else 4), 0)
        bottomRow.setPadding(kit.dp(if (on) 50 else 14), 0, kit.dp(if (on) 40 else 4), kit.dp(if (on) 16 else 2))
        (cover.tag as View).let { back -> (back.layoutParams as FrameLayout.LayoutParams).setMargins(kit.dp(if (on) 40 else 4), kit.dp(if (on) 12 else 2), 0, 0); back.requestLayout() }
        ui.view.rootView.requestApplyInsets(); box.requestLayout()
    }

    /** 打开一集；已在播放页时原地换集 */
    fun open(item: JSONObject, index: Int, position: Double? = null) {
        if (ui.disposed) return
        if (page.parent == null) {
            (ui.player.container.parent as? ViewGroup)?.removeView(ui.player.container)
            box.addView(ui.player.container, 0, kit.flp())
            topTitle.visibility = View.INVISIBLE
            ui.push(page, onClose = { close() }, onBack = { if (fullscreen) { setFullscreen(false); true } else false })
        }
        if (active != null) { save(); command("pause") }
        ui.backend.cancel(openingId); loading = true; pending = Triple(item, index, position)
        title.text = item.optString("name"); subtitle.text = item.optString("sourceName")
        if (active == null) { episodeGrid.removeAllViews(); episodeCells.clear(); episodeTitle.visibility = View.GONE }
        cover("正在连接${item.optString("sourceName").ifBlank { "片源" }}…", true)
        val args = JSONObject().put("item", item).put("index", index).put("decoders", DecoderCapabilities.json)
        if (position != null) args.put("position", position)
        openingId = ui.backend.request("open-playback", args) { event ->
            if (event.optString("event") == "processing-progress") return@request
            loading = false; openingId = null
            if (event.optString("event") == "error") { cover(event.optString("error").ifBlank { "打不开这个片源" }, false, true); return@request }
            val descriptor = event.optJSONObject("value") ?: return@request
            if (page.parent == null) return@request
            active = descriptor; session = "phone-${SystemClock.elapsedRealtime()}"; state = JSONObject(); qualitySent = false; endedSession = ""; pending = null
            ui.applyConfig(descriptor.optJSONObject("config") ?: ui.config)
            val delivery = descriptor.optJSONObject("playback") ?: JSONObject().put("url", descriptor.getJSONObject("detail").getJSONArray("episodes").getString(descriptor.optInt("index")))
            ui.player.command(JSONObject().put("version", 1).put("sessionId", session).put("type", "load")
                .put("request", JSONObject().put("url", delivery.getString("url")).put("headers", delivery.optJSONObject("headers") ?: JSONObject()).put("delivery", "direct")
                    .put("position", descriptor.optDouble("position")).put("adFilter", ui.config.optBoolean("adFilter", true))
                    .put("adSegments", delivery.optJSONArray("adSegments") ?: JSONArray()).put("playlists", delivery.optJSONArray("playlists") ?: JSONArray())))
            command("rate", JSONObject().put("rate", rate)); applyFit(); adLine.visibility = View.GONE
            cover("正在缓冲…", true); renderInfo()
        }
    }
    private fun renderInfo() {
        val a = active ?: return
        val item = a.getJSONObject("item"); val index = a.optInt("index"); val count = a.optJSONObject("detail")?.optJSONArray("episodes")?.length() ?: 0
        title.text = item.optString("name"); topTitle.text = listOf(item.optString("name"), if (count > 1) "第 ${index + 1} 集" else "").filter { it.isNotBlank() }.joinToString(" · ")
        // 宽银幕片源的高度偏小（1920×800），按宽度折算成常见档位
        val lines = maxOf(state.optInt("height"), state.optInt("width") * 9 / 16)
        val quality = when { lines >= 2000 -> "4K"; lines >= 1400 -> "2K"; lines >= 1000 -> "1080P"; lines >= 700 -> "720P"; lines >= 460 -> "480P"; lines > 0 -> "标清"; else -> "" }
        subtitle.text = listOf(item.optString("sourceName"), if (count > 1) "第 ${index + 1} 集 / 共 $count 集" else "", quality).filter { it.isNotBlank() }.joinToString(" · ")
        episodeTitle.visibility = if (count > 1) View.VISIBLE else View.GONE; episodeGrid.visibility = episodeTitle.visibility
        if (count > 1 && episodeCells.size != count) {
            episodeGrid.removeAllViews(); episodeCells.clear()
            val width = (ui.view.width.coerceAtMost(ui.view.height).takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels).let { (it - kit.dp(40) - kit.dp(8) * 4) / 5 }
            for (n in 0 until count) {
                val cell = kit.chip("${n + 1}") { active?.let { open(it.getJSONObject("item"), n) } }.apply { setPadding(0, 0, 0, 0) }
                episodeCells.add(cell)
                episodeGrid.addView(cell, GridLayout.LayoutParams().apply { this.width = width; this.height = kit.dp(42); setMargins(0, 0, if (n % 5 == 4) 0 else kit.dp(8), kit.dp(8)) })
            }
        }
        episodeCells.forEachIndexed { n, cell -> kit.select(cell, n == index) }
    }

    fun onState(value: JSONObject) {
        if (ui.disposed || active == null || value.optString("sessionId") != session) return
        state = value
        val a = active ?: return
        val duration = value.optDouble("duration", 0.0); val position = value.optDouble("position", 0.0)
        val error = value.optString("error")
        if (error.isNotEmpty()) { cover("播放出错：$error", false, true); return }
        if (cover.visibility == View.VISIBLE && !loading && value.optBoolean("rendered")) {
            cover.visibility = View.GONE; (cover.tag as View).visibility = View.GONE; showControls(true); renderInfo()
        }
        if (!dragging) {
            if (duration > 0) { seek.progress = (position / duration * 10000).toInt(); seek.secondaryProgress = (value.optDouble("bufferedPosition", 0.0) / duration * 10000).toInt() }
            elapsed.text = kit.time(position)
        }
        total.text = kit.time(duration)
        renderAds()
        playIcon.setImageDrawable(icon(if (value.optBoolean("paused")) PhoneIcon.PLAY else PhoneIcon.PAUSE))
        buffering.visibility = if (cover.visibility != View.VISIBLE && value.optBoolean("buffering")) View.VISIBLE else View.GONE
        rate = value.optDouble("rate", rate).takeIf { it.isFinite() && it > 0 } ?: rate
        val height = value.optInt("height")
        if (height > 0 && !qualitySent) {
            qualitySent = true; renderInfo()
            val item = a.getJSONObject("item")
            ui.backend.request("played-quality", JSONObject().put("sourceKey", item.optString("sourceKey")).put("vodId", item.optString("vodId")).put("height", height))
        }
        if (!value.optBoolean("paused") && SystemClock.elapsedRealtime() - lastSaved > 10_000) save()
        if (value.optBoolean("ended") && endedSession != session) {
            endedSession = session; save()
            val count = a.optJSONObject("detail")?.optJSONArray("episodes")?.length() ?: 0
            if (ui.config.optBoolean("autoplayNext", true) && a.optInt("index") + 1 < count) open(a.getJSONObject("item"), a.optInt("index") + 1, 0.0)
            else showControls(true)
        }
    }
    fun save() {
        val a = active ?: return
        if (state.optDouble("duration", 0.0) <= 0) return
        val item = a.getJSONObject("item")
        ui.backend.request("save-progress", JSONObject().put("sourceKey", item.optString("sourceKey")).put("vodId", item.optString("vodId"))
            .put("index", a.optInt("index")).put("position", state.optDouble("position")).put("duration", state.optDouble("duration")).put("timeMap", state.optJSONArray("timeMap") ?: JSONArray()))
        lastSaved = SystemClock.elapsedRealtime()
    }
    fun pause() { if (active != null && !state.optBoolean("paused")) command("pause") }
    private fun close() {
        save(); ui.backend.cancel(openingId); openingId = null; loading = false
        ui.main.removeCallbacks(hideControls); ui.main.removeCallbacks(hideHint)
        ui.player.stop(); active = null; session = ""; state = JSONObject(); pending = null
        if (fullscreen) setFullscreen(false)
        controls.visibility = View.GONE; shown = false
        ui.main.postDelayed({ if (!ui.disposed) ui.refreshHome() }, 300)
    }

    private fun castCurrent() {
        val a = active
        val item = a?.getJSONObject("item") ?: pending?.first ?: return
        ui.tvSheet.cast(item, a?.optInt("index") ?: pending?.second ?: 0, if (a != null) originalPosition() else 0.0) { pause() }
    }
    private fun showRates() {
        val (dialog, body) = kit.sheet("播放速度")
        val row = kit.row()
        listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { r ->
            val chip = kit.chip("$r×") { rate = r; command("rate", JSONObject().put("rate", r)); rateChip.text = "倍速 $r×"; dialog.dismiss() }
            kit.select(chip, Math.abs(r - rate) < .01); row.addView(chip, LinearLayout.LayoutParams(0, kit.dp(40), 1f).apply { rightMargin = kit.dp(8) })
        }
        body.addView(row, kit.lp(top = 8)); dialog.show()
    }
    /** 换源：列出其他片源里的同一部影片，附实测清晰度和响应时间 */
    private fun showSources() {
        val a = active
        val item = a?.getJSONObject("item") ?: pending?.first ?: return
        val index = a?.optInt("index") ?: pending?.second ?: 0
        val resume = if (a != null && state.optDouble("duration", 0.0) > 0) originalPosition() else pending?.third
        val (dialog, body) = kit.sheet("换源 · ${item.optString("name")}", tall = true)
        val status = kit.text("正在查找其他片源…", 13, kit.muted); body.addView(status, kit.lp(bottom = 8))
        val rows = LinkedHashMap<String, Pair<JSONObject, TextView>>()
        fun key(i: JSONObject) = "${i.optString("sourceKey")}|${i.optString("vodId")}"
        fun add(candidate: JSONObject) {
            if (rows.containsKey(key(candidate))) return
            val current = key(candidate) == key(item)
            val row = kit.row().apply { background = kit.pressable(if (current) kit.raised else kit.surface, 12); setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12)); isClickable = true }
            val labels = kit.column()
            labels.addView(kit.text(candidate.optString("sourceName") + if (current) "（当前）" else "", 15, kit.ink, 600, 1))
            val detail = kit.text(candidate.optString("remarks").ifBlank { "正在检测…" }, 12, kit.muted, lines = 1); labels.addView(detail, kit.lp(top = 4))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener { dialog.dismiss(); if (!current || active == null) open(candidate, index, resume) }
            rows[key(candidate)] = candidate to detail; body.addView(row, kit.lp(top = 8))
        }
        fun update(value: JSONObject) {
            val candidate = value.optJSONObject("item") ?: return
            add(candidate); val detail = rows[key(candidate)]?.second ?: return
            detail.text = if (value.has("ok") && !value.optBoolean("ok")) "不可用：${value.optString("error").ifBlank { "没有响应" }}"
            else listOf(value.optString("quality"), value.optInt("episodes").takeIf { it > 0 }?.let { "$it 集" }.orEmpty(), value.optInt("ms").takeIf { it > 0 }?.let { "${it} 毫秒" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { detail.text }
        }
        val id = ui.backend.request("switch-sources", JSONObject().put("title", item.optString("name")).put("refresh", false)) { event ->
            when (event.optString("event")) {
                "candidates" -> event.optJSONArray("value").objects().forEach { it.optJSONObject("item")?.let(::add) }
                "source-result", "quality-result" -> event.optJSONObject("value")?.let(::update)
                "sources-tested" -> status.text = "找到 ${rows.size} 个片源，正在检测清晰度…"
                "error" -> status.text = event.optString("error")
                "result" -> status.text = if (rows.isEmpty()) "没有找到其他片源" else "共 ${rows.size} 个片源，点一个切换"
            }
        }
        dialog.setOnDismissListener { ui.backend.cancel(id) }
        dialog.show()
    }
    fun release() { ui.main.removeCallbacks(hideControls); ui.main.removeCallbacks(hideHint) }
}
