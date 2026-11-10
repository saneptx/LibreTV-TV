package org.libretv.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
internal class NativeTvUi(private val activity: Activity, private val backend: NativeBackendClient, private val player: NativePlayerHost, private val retryBoot: () -> Unit) {
    private val ui = TvWidgets(activity)
    private val main = Handler(Looper.getMainLooper())
    private val posters = TvPosterLoader()
    val view = FrameLayout(activity)
    private val home = ui.column().apply { setPadding(ui.px(38), ui.px(17), ui.px(38), ui.px(38)) }
    private val homeScroll = ScrollView(activity).apply { isFillViewport = true; isFocusable = false; clipChildren = false; clipToPadding = false; addView(home) }
    private val modeBadge=ui.text("独立运行 · ANDROID TV",12,ui.cyan,true).apply { letterSpacing=.24f; gravity=Gravity.BOTTOM; ui.fontWeight(this,800) }
    private val sourceCount = ui.text("正在加载…", 15, ui.muted)
    private val search = ui.input("搜索电影、剧集或动漫")
    private val searchButton = ui.button("搜索") { startSearch(search.text.toString()) }
    private val watchHistoryButton = ui.button("历史观看") { showWatchHistory() }
    private var historyFocusKey: String? = null
    private val historySection = ui.column()
    private val historyRow = ui.grid(5)
    private val statusPrefix = ui.text("电视模式", 17, bold = true)
    private val status = ui.text("输入片名，按遥控器确认键搜索", 14, ui.muted)
    private val grid = ui.grid(5).apply { id = View.generateViewId() }
    private val empty = ui.column().apply { gravity = Gravity.CENTER; minimumHeight = ui.px(243) }
    private val emptyHint = ui.text("搜索并播放影片后，这里会显示观看记录",16,Color.rgb(112,135,163))
    private val more = ui.button("显示更多") { adapter.showMore() }
    private val adapter = MovieAdapter()
    private var config = JSONObject()
    private var ready = false
    private var homeData = JSONObject()
    private var searchId: String? = null
    private var searched = ""
    private var panel: TvWidgets.Panel? = null
    private var panelId: String? = null
    private val panelRequests=mutableSetOf<String>()
    private var openingId: String? = null
    private var compareId: String? = null
    private var compareText = ""
    private var active: JSONObject? = null
    private var state = JSONObject()
    private var session = ""
    private var lastSaved = 0L
    private var qualityHeight = 0
    private var endedSession = ""
    private var desktopFailureSession = ""
    private var seekPreview: Double? = null
    private var rate = 1.0
    private var fit = "contain"
    private var openingStatus = ""
    private var loadingPlayback = false
    private var disposed = false
    private val playback = FrameLayout(activity).apply { isFocusable = true; isFocusableInTouchMode = true; visibility = View.GONE; id = View.generateViewId() }
    private val buffer=ui.column().apply { gravity=Gravity.CENTER; visibility=View.GONE }
    private val bufferSpinner=android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleSmall).apply { indeterminateTintList=android.content.res.ColorStateList.valueOf(Color.rgb(217,227,239)) }
    private val bufferTitle=ui.text("正在缓冲",16,Color.rgb(217,227,239))
    private val bufferSpeed=ui.text("",19,Color.rgb(101,223,239))
    private val bufferNote=ui.text("",12,Color.rgb(194,207,223))
    private val bufferDetail=ui.text("",10,Color.rgb(150,168,191))
    private var bufferingSince=0L
    private val seekOverlay=ui.column().apply { gravity=Gravity.CENTER; setPadding(ui.px(38),ui.px(16),ui.px(38),ui.px(16)); background=ui.background(Color.argb(204,0,0,0),radius=10); visibility=View.GONE }
    private val seekTime=ui.text("",38,bold=true)
    private val controls=ui.column().apply { setPadding(ui.px(48),ui.px(59),ui.px(48),ui.px(22)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(238,0,0,0),Color.BLACK)) }
    private val top=ui.column().apply { setPadding(ui.px(48),ui.px(22),ui.px(48),ui.px(49)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(204,0,0,0),Color.TRANSPARENT)) }
    private val playerTitle=ui.text("",26,bold=true).apply { isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val playerSubtitle=ui.text("",12,Color.rgb(192,198,208))
    private val playerProcessingVideo=ui.text("",11,Color.rgb(168,220,230))
    private val playerProcessingAds=ui.text("",11,Color.rgb(168,220,230))
    private val time=ui.text("00:00",12,Color.rgb(210,215,223))
    private val duration=ui.text("00:00",12,Color.rgb(210,215,223))
    private val seek=TvTimeline(activity,ui)
    private val controlRow=ui.row()
    private val playButton=ui.button("Ⅱ 暂停") { togglePlay() }
    private val prevButton=ui.button("上一集") { episode(-1) }
    private val nextButton=ui.button("下一集") { episode(1) }
    private val settingsButton=ui.button("设置 · 1×") { showPlayerSettings() }
    private var panelKind=""
    private var reversedEpisodes=false
    private val hideControls = Runnable { if (!state.optBoolean("paused") && state.optString("error").isEmpty() && seekPreview == null && panel == null) showControls(false) }

    init {
        view.background = TvHomeBackground()
        view.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        view.addView(playback, FrameLayout.LayoutParams(-1, -1))
        ui.decorate(view)
        buildHome(); buildPlayback()
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused -> main.removeCallbacks(prefetchFocused); if (focused != null && cardOf[focused] != null) main.postDelayed(prefetchFocused, 800) }
        ui.fontWeight(playerTitle,600); ui.fontWeight(bufferSpeed,500); ui.fontWeight(seekTime,500)
    }
    private fun buildHome() {
        val header = FrameLayout(activity)
        val brand = ui.column()
        brand.addView(modeBadge,ui.margins(-2,24))
        brand.addView(ui.text("LibreTV",48,bold=true).apply { letterSpacing=-.04f; gravity=Gravity.CENTER_VERTICAL; ui.fontWeight(this,800) },ui.margins(-2,72))
        header.addView(brand, FrameLayout.LayoutParams(-2,ui.px(96),Gravity.TOP or Gravity.LEFT))
        val source = ui.row()
        source.addView(sourceCount)
        watchHistoryButton.apply {
            ui.homeFocus(this,radius=11); setTextColor(ui.ink); ui.fontWeight(this,800)
            setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(17).toFloat())
        }
        source.addView(watchHistoryButton,ui.margins(116,53).apply { leftMargin=ui.px(19) })
        val manager = ui.button("片源管理") { showSourceSettings() }.apply { ui.homeFocus(this, radius=11); setTextColor(ui.ink); setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(17).toFloat()); ui.fontWeight(this,800) }
        source.addView(manager, ui.margins(116,53).apply { leftMargin=ui.px(19) })
        header.addView(source, FrameLayout.LayoutParams(-2,ui.px(53),Gravity.TOP or Gravity.RIGHT))
        home.addView(header, ui.margins(-1,96).apply { bottomMargin=ui.px(14) })
        val form=ui.row()
        search.imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        search.setOnEditorActionListener { _, action, event ->
            if (action==EditorInfo.IME_ACTION_SEARCH || event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_DOWN) { startSearch(search.text.toString()); true } else false
        }
        form.addView(search, LinearLayout.LayoutParams(0,ui.px(58),1f).apply { rightMargin=ui.px(10) })
        ui.homeFocus(searchButton, Color.rgb(0,154,201)); searchButton.setTextColor(ui.ink); ui.fontWeight(searchButton,800)
        searchButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(16).toFloat())
        form.addView(searchButton,ui.margins(130,58)); searchButton.isEnabled=false; home.addView(form)
        val historyHeader=ui.row()
        historyHeader.addView(ui.text("最近搜索",12,ui.muted),LinearLayout.LayoutParams(0,ui.px(26),1f))
        historyHeader.addView(ui.button("清空搜索") { backend.request("clear-search-history") { refreshHome() } }.apply {
            ui.homeFocus(this,Color.TRANSPARENT,8); setTextColor(ui.muted); setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(11).toFloat()); minHeight=0; minimumHeight=0; setPadding(0,0,0,0); background=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),ui.background(Color.TRANSPARENT,ui.cyan,8))
                addState(intArrayOf(),ui.background(Color.TRANSPARENT,Color.TRANSPARENT,8))
            }
        },ui.margins(66,26))
        historySection.addView(historyHeader,ui.margins(-1,26).apply { bottomMargin=ui.px(6) })
        historySection.addView(historyRow)
        home.addView(historySection,ui.margins(-1,-2).apply { topMargin=ui.px(14) })
        val statusRow=ui.row().apply { setPadding(0,ui.px(10),0,ui.px(5)) }
        statusRow.addView(statusPrefix,ui.margins(-2,-2).apply { rightMargin=ui.px(12) }); statusRow.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        home.addView(statusRow,ui.margins(-1,40))
        home.addView(grid)
        more.apply { ui.homeFocus(this,radius=24); visibility=View.GONE }
        home.addView(more,ui.margins(288,50).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=ui.px(22) })
        val circle=ui.text("＋",32,ui.cyan).apply { gravity=Gravity.CENTER; background=ui.background(Color.TRANSPARENT,Color.rgb(49,71,96),44) }
        empty.addView(circle,ui.margins(88,88).apply { gravity=Gravity.CENTER_HORIZONTAL })
        empty.addView(emptyHint,ui.margins(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=ui.px(16) })
        home.addView(empty,ui.margins(-1,243))
    }
    private fun buildPlayback() {
        playback.addView(buffer,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply { topMargin=-ui.px(54) })
        val bufferHeading=ui.row().apply { gravity=Gravity.CENTER }
        bufferHeading.addView(bufferSpinner,ui.margins(24,24))
        bufferHeading.addView(bufferTitle,ui.margins(-2,-2).apply { leftMargin=ui.px(10) })
        buffer.addView(bufferHeading,ui.margins(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        listOf(bufferSpeed,bufferNote,bufferDetail).forEachIndexed { i,label ->
            label.gravity=Gravity.CENTER; label.setShadowLayer(ui.px(3).toFloat(),0f,1f,Color.BLACK)
            buffer.addView(label,ui.margins(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=ui.px(if(i==0) 8 else if(i==2) 11 else 5) })
        }
        seekOverlay.addView(seekTime)
        seekOverlay.addView(ui.text("← → 每次调整 10 秒 · 确认跳转 · 返回取消",12),ui.margins(-2,-2).apply { topMargin=ui.px(8) })
        playback.addView(seekOverlay,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply { topMargin=-ui.px(97) })
        top.addView(ui.text("LIBRETV · TV",10,Color.rgb(158,171,185)).apply { letterSpacing=.22f })
        top.addView(playerTitle,ui.margins(-1,-2).apply { topMargin=ui.px(4); bottomMargin=ui.px(5) })
        top.addView(playerSubtitle)
        top.addView(playerProcessingVideo,ui.margins(-1,-2).apply { topMargin=ui.px(5) })
        top.addView(playerProcessingAds,ui.margins(-1,-2).apply { topMargin=ui.px(3) })
        playback.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        playback.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val timeline=ui.row()
        timeline.addView(time)
        timeline.addView(seek,LinearLayout.LayoutParams(0,ui.px(18),1f).apply { setMargins(ui.px(17),0,ui.px(17),0) })
        timeline.addView(duration)
        controls.addView(timeline,ui.margins(-1,18).apply { bottomMargin=ui.px(16) })
        seek.onTap={ fraction ->
            command("seek",JSONObject().put("position",TvPlaybackRules.preview(state.optDouble("duration")*fraction,0,state.optDouble("duration"))))
            seekPreview=null; showControls(true)
        }
        listOf(playButton,prevButton,nextButton).forEach { controlRow.addView(it,ui.margins(-2,37).apply { rightMargin=ui.px(10) }) }
        controlRow.addView(View(activity),LinearLayout.LayoutParams(0,1,1f))
        listOf(ui.button("选集") { showEpisodes() },ui.button("换源") { showSwitchSources() },settingsButton).forEach {
            controlRow.addView(it,ui.margins(-2,37).apply { rightMargin=ui.px(10) })
        }
        controls.addView(controlRow)
        controls.addView(ui.text("确认播放 / 暂停 · ← → 快退 / 快进 · ↑ 选集 · ↓ 控制栏 · 菜单设置 · 返回收起",10,ui.muted),ui.margins(-1,-2).apply { topMargin=ui.px(15) })
        playback.setOnClickListener { togglePlay() }
    }
    fun onBoot(event: JSONObject) {
        if (event.optString("event") == "fatal") { onBootError(event.optString("error")); return }
        ready = true; searchButton.isEnabled = true; search.showSoftInputOnFocus=false
        applyHome(event.optJSONObject("value") ?: JSONObject())
        search.requestFocus()
    }
    fun onBootError(message: String) {
        status.text = message
        val p = newPanel("本地数据加载失败")
        p.status.text = message
        p.content.addView(ui.button("重试") { p.dialog.dismiss(); retryBoot() })
        p.show()
    }
    private fun applyHome(data: JSONObject) {
        homeData = data; applyConfig(data.optJSONObject("config") ?: config)
        val names = data.optJSONArray("searches").objects().map { it.optString("text") }
        val existing=(0 until historyRow.childCount).map { (historyRow.getChildAt(it) as Button).text.toString() }
        if (names!=existing) {
            val previous=(0 until historyRow.childCount).map { historyRow.getChildAt(it) as Button }.associateBy { it.text.toString() }
            val focused=historyRow.findFocus() as? Button
            historyRow.removeAllViews()
            names.forEachIndexed { index,value ->
                val button=previous[value] ?: ui.button(value) { search.setText(value); startSearch(value) }.apply {
                    ui.homeFocus(this,radius=10); setTextColor(ui.ink); gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT
                    setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(12).toFloat()); minHeight=0; minimumHeight=0; setPadding(ui.px(10),ui.px(4),ui.px(10),ui.px(4)); isSingleLine=true
                }
                historyRow.addView(button,ui.gridCell(index,5,31).apply { setMargins(if(index%5==0) 0 else ui.px(5),0,if(index%5==4) 0 else ui.px(5),ui.px(6)) })
            }
            if(focused!=null) {
                if(focused.parent===historyRow) focused.requestFocus() else search.requestFocus()
            }
        }
        historySection.visibility=if (names.isEmpty()) View.GONE else View.VISIBLE
        if (searched.isEmpty()) {
            adapter.submitHistory(data.optJSONArray("history").objects())
            statusPrefix.text="历史观看"
            status.text=if(adapter.count>0) "最近观看 ${adapter.count} 部 · 按确认键继续播放" else "暂无观看记录，输入片名开始搜索"
            emptyHint.text="搜索并播放影片后，这里会显示观看记录"
        }
    }
    private fun showWatchHistory() {
        if(!ready) return
        backend.cancel(searchId); searchId=null; searched=""
        search.setText(""); searchButton.text="搜索"; searchButton.isEnabled=true
        applyHome(homeData); homeScroll.smoothScrollTo(0,0); refreshHome()
    }
    private fun resumeHistory(entry: JSONObject) {
        val item=JSONObject().put("sourceKey",entry.optString("sourceKey"))
            .put("sourceUrl",entry.optString("sourceUrl")).put("sourceName",sourceName(entry.optString("sourceKey")))
            .put("vodId",entry.optString("vodId")).put("name",entry.optString("title")).put("pic",entry.optString("pic"))
        historyFocusKey=entry.optString("id")
        openPlayback(item,entry.optInt("episodeIndex").coerceAtLeast(0))
    }
    private fun applyConfig(value: JSONObject) { config = value; sourceCount.text = "${value.optInt("enabled")} 个来源已启用"; modeBadge.text=if(value.optString("playbackMode")=="desktop") "电脑串流 · ANDROID TV" else "独立运行 · ANDROID TV" }
    private fun sourceName(key: String) = config.optJSONArray("sources").objects().find { it.optString("key") == key }?.optString("name") ?: "观看记录"
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    private fun refreshHome() { backend.request("home") { if (it.optString("event") == "result") applyHome(it.optJSONObject("value") ?: JSONObject()) } }
    private fun startSearch(raw: String) {
        if (!ready) return
        val query = raw.trim().take(100)
        if (query.isEmpty()) { toast("请输入片名"); search.requestFocus(); return }
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        backend.cancel(searchId)
        emptyHint.text="没有找到相关影片，试试其他关键词或片源"
        searched = query; statusPrefix.text=query; status.text="正在搜索 ${config.optInt("enabled")} 个来源…"; adapter.submitCards(emptyList()); searchButton.text="搜索中…"; searchButton.isEnabled=false
        searchId = backend.request("search", JSONObject().put("query", query)) { event ->
            when (event.optString("event")) {
                "search-history" -> event.optJSONObject("value")?.let { applyHome(it) }
                "result", "search-progress" -> {
                    val value = event.optJSONObject("value") ?: return@request
                    adapter.submitCards(value.optJSONArray("groups").objects())
                    val done = value.optBoolean("done")
                    status.text = if (done) "找到 ${adapter.count} 部影片 · ${value.optInt("failures")} 个源失败" else "正在搜索 ${value.optInt("total")} 个来源…"
                    if (done) { searchButton.text="搜索"; searchButton.isEnabled=true; refreshHome() }
                }
                "error" -> { status.text=event.optString("error"); searchButton.text="搜索"; searchButton.isEnabled=true }
            }
        }
    }
    private fun newPanel(title: String, kind: String = ""): TvWidgets.Panel {
        panel?.dialog?.dismiss()
        val previousFocus = activity.currentFocus
        val p = ui.panel(title, sheet=kind=="episodes" || kind=="settings", source=kind=="source"); panel=p; panelKind=kind
        main.removeCallbacks(hideControls)
        if(active!=null) { controls.visibility=View.GONE; top.visibility=View.GONE; buffer.visibility=View.GONE; seekOverlay.visibility=View.GONE }
        p.dialog.setOnDismissListener {
            if (panel === p) {
                backend.cancel(panelId); panelRequests.forEach { backend.cancel(it) }; panelRequests.clear(); panelId=null; panel=null; panelKind=""
                if (loadingPlayback) { backend.cancel(openingId); loadingPlayback = false }
                if (active!=null) { showControls(true); playButton.requestFocus() } else previousFocus?.takeIf { it.isShown }?.requestFocus()
                scheduleControls()
            }
        }
        p.dialog.setOnKeyListener { _,code,event ->
            if (active!=null && playbackKey(code)!=null) {
                if (event.action==KeyEvent.ACTION_DOWN && !(event.repeatCount>0 && code !in setOf(19,20,21,22))) handlePlaybackKey(code)
                true
            } else if(code in setOf(19,20,21,22)) { if(event.action==KeyEvent.ACTION_DOWN) ui.moveFocus(p.body,code); true }
            else if(code in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER) && p.dialog.currentFocus !is EditText) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) p.dialog.currentFocus?.performClick(); true
            } else false
        }
        return p
    }
    private fun openDetail(card: JSONObject) {
        val items = card.optJSONArray("items").objects()
        if (items.isEmpty()) return
        val p = newPanel(card.optString("name"))
        p.close.text="关闭"; p.width=883; p.height=475
        p.body.setPadding(ui.px(21),ui.px(14),ui.px(21),ui.px(16))
        p.body.background=ui.background(Color.rgb(13,25,40),Color.rgb(43,67,94),22)
        p.title.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(29).toFloat())
        (p.title.parent as LinearLayout).addView(ui.text("SELECT EPISODE",12,ui.cyan,true).apply { letterSpacing=.24f },0)
        p.close.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(17).toFloat())
        val tabs=ui.row()
        val sourceButtons=mutableListOf<Button>()
        val details = ui.column()
        p.content.addView(HorizontalScrollView(activity).apply { addView(tabs); isFocusable = false })
        p.content.addView(details)
        fun select(item: JSONObject) {
            sourceButtons.forEachIndexed { n,b -> b.isSelected=items[n]===item }
            backend.cancel(panelId); p.status.visibility=View.VISIBLE; p.status.text="正在加载剧集…"; details.removeAllViews()
            panelId = backend.request("detail", JSONObject().put("item", item)) { event ->
                if (panel !== p) return@request
                if (event.optString("event") == "error") {
                    p.status.text = event.optString("error")
                    details.addView(ui.button("重试") { select(item) }); return@request
                }
                val detail = event.optJSONObject("value")?.optJSONObject("detail") ?: return@request
                val info = detail.optJSONObject("videoInfo") ?: JSONObject()
                val episodes = detail.optJSONArray("episodes") ?: JSONArray()
                p.status.text=""; p.status.visibility=View.GONE
                @Suppress("DEPRECATION")
                val desc = Html.fromHtml(info.optString("desc")).toString().trim().take(240)
                if (desc.isNotEmpty()) details.addView(ui.text(desc, 14, ui.muted).apply { maxLines = 3 }, ui.margins(-1, -2, 6))
                val eps = ui.grid(8)
                for (n in 0 until episodes.length()) eps.addView(ui.button("${n + 1}") { openPlayback(item, n) }, ui.gridCell(n, 8, 56))
                details.addView(eps)
                if (p.dialog.currentFocus === p.close || p.dialog.currentFocus == null) eps.getChildAt(0)?.requestFocus()
            }
        }
        items.forEach { item -> val button=ui.button(item.optString("sourceName")) { select(item) }; sourceButtons.add(button); tabs.addView(button,ui.margins(-2,43,6)) }
        p.show(); select(items[0])
    }
    private var choosingId: String? = null
    // 遥控器停在多来源影片卡片上 0.8 秒后预探各来源码率，确认时多半已有结果
    private val cardOf = java.util.WeakHashMap<View, JSONObject>()
    private val prefetched = mutableSetOf<String>()
    private val prefetchFocused = Runnable {
        val card = activity.currentFocus?.let { cardOf[it] } ?: return@Runnable
        val items = card.optJSONArray("items") ?: return@Runnable
        if (items.length() > 1 && prefetched.add(card.optString("key", card.optString("name")))) backend.request("prefetch-bitrates", JSONObject().put("items", items))
    }
    /** 遥控器确认影片卡片：多个来源时先比较实测码率，播放码率最高的可用来源 */
    private fun playBest(card: JSONObject) {
        val items=card.optJSONArray("items") ?: return
        if (items.length()<=1) { items.optJSONObject(0)?.let { openPlayback(it,0) }; return }
        if (loadingPlayback || choosingId!=null) return
        val before=status.text.toString()
        status.text="正在比较 ${items.length()} 个来源的码率…"
        choosingId=backend.request("best-source",JSONObject().put("items",items)) { event ->
            choosingId=null; status.text=before
            val value=event.optJSONObject("value")
            val item=value?.optJSONObject("item") ?: items.optJSONObject(0) ?: return@request
            if (event.optString("event")=="result" && value.optString("quality").isNotEmpty()) toast("已选码率最高的来源：${item.optString("sourceName")} · ${value.optString("quality")}")
            openPlayback(item,0)
        }
    }
    private fun openPlayback(item: JSONObject, index: Int, position: Double? = null) {
        if (loadingPlayback) return
        loadingPlayback = true; openingStatus=status.text.toString()
        if(config.optString("playbackMode")=="desktop") status.text="正在连接电脑处理视频…"
        val args = JSONObject().put("item", item).put("index", index)
        if (position != null) args.put("position", position)
        openingId = backend.request("open-playback", args) { event ->
            if(event.optString("event")=="processing-progress") {
                val message=event.optJSONObject("value")?.optString("message").orEmpty()
                if(message.isNotEmpty()) { status.text=message; panel?.status?.text=message }
                return@request
            }
            loadingPlayback = false; status.text=openingStatus
            if (event.optString("event") == "error") {
                if (config.optString("playbackMode") == "desktop") showDesktopFailure(event.optString("error"), item, index, position)
                else toast(event.optString("error"))
                return@request
            }
            val descriptor = event.optJSONObject("value") ?: return@request
            savePlayback(); closeDesktopPlayback(); panel?.dialog?.dismiss(); backend.cancel(compareId); compareId=null; compareText=""
            bufferingSince=0L
            active = descriptor; session = "native-${SystemClock.elapsedRealtime()}"; state = JSONObject(); seekPreview = null; qualityHeight = 0; endedSession = ""; desktopFailureSession = ""
            applyConfig(descriptor.optJSONObject("config") ?: config)
            view.background=null; homeScroll.visibility = View.GONE; playback.visibility = View.VISIBLE; playback.requestFocus()
            val delivery = descriptor.optJSONObject("playback") ?: JSONObject().put("url",descriptor.getJSONObject("detail").getJSONArray("episodes").getString(descriptor.optInt("index")))
            val url = delivery.getString("url")
            player.command(JSONObject().put("version", 1).put("sessionId", session).put("type", "load")
                .put("request", JSONObject().put("url", url).put("headers", delivery.optJSONObject("headers") ?: JSONObject()).put("delivery", delivery.optString("delivery", "direct")).put("processing",delivery.optString("processing")).put("label",delivery.optString("label")).put("position", descriptor.optDouble("position")).put("adFilter", delivery.optString("processing")!="enhance" && config.optBoolean("adFilter", true))
                .put("adSegments", if (delivery.optString("processing")=="enhance") JSONArray() else delivery.optJSONArray("adSegments") ?: JSONArray())
                .put("playlists", if (delivery.optString("delivery")=="desktop") JSONArray() else delivery.optJSONArray("playlists") ?: JSONArray())
                .put("timeMap", if (delivery.optString("processing")=="enhance") delivery.optJSONArray("timeMap") ?: JSONArray() else null)))
            command("rate", JSONObject().put("rate", rate)); command("fit", JSONObject().put("fit", fit))
            startComparison(descriptor.getJSONObject("item"), descriptor.optInt("index"), session)
            showControls(true)
        }
    }
    /** 配置了电脑服务时，后台对同一集的其他来源做音频比对，结果交给播放器跳过夹带广告 */
    private fun startComparison(item: JSONObject, index: Int, playback: String) {
        if (!config.optBoolean("adFilter",true) || !config.optBoolean("desktopConfigured")) return
        compareText="正在多来源比对"
        compareId=backend.request("compare-ads",JSONObject().put("item",item).put("index",index)) { event ->
            if (session != playback) return@request
            when (event.optString("event")) {
                "compare-progress" -> compareText="正在多来源比对"
                "error" -> { compareId=null; compareText="多来源比对未完成" }
                "result" -> {
                    compareId=null
                    val value=event.optJSONObject("value") ?: return@request
                    if (!value.optBoolean("available")) { compareText=""; return@request }
                    val intervals=value.optJSONArray("intervals") ?: JSONArray()
                    compareText=if(intervals.length()>0) "多来源比对：跳过 ${intervals.length()} 段 · ${intervals.objects().sumOf { it.optDouble("duration") }.toInt()} 秒" else "多来源比对：未发现夹带广告"
                    command("skip",JSONObject().put("intervals",intervals))
                }
            }
        }
    }
    private fun closeDesktopPlayback() {
        active?.optJSONObject("playback")?.optJSONObject("desktopSession")?.let {
            backend.request("close-desktop",JSONObject().put("session",it))
        }
    }
    private fun showDesktopFailure(message: String, item: JSONObject, index: Int, position: Double?) {
        val sourceFailure=Regex("HTTP (401|403|404|410|429)").containsMatchIn(message)
        val p=newPanel(if(sourceFailure) "当前片源暂不可用" else "电脑串流暂不可用")
        p.status.text=message
        p.content.addView(ui.text(if(sourceFailure) "电脑已连接，但片源拒绝访问或已失效。请换源；切回直连也可能无法播放。" else "可先换源；也可检查电脑服务、地址和网络，或切回独立直连。",15,ui.muted))
        val switchSource=ui.button("换源") { showSwitchSources(item,index) }
        p.content.addView(switchSource,ui.margins(-1,43).apply { topMargin=ui.px(18) })
        val retry=ui.button("重试电脑串流") { openPlayback(item,index,position) }
        p.content.addView(retry,ui.margins(-1,43).apply { topMargin=ui.px(18) })
        p.content.addView(ui.button("切回独立直连") {
            backend.request("settings",JSONObject().put("playbackMode","direct")) { event ->
                event.optJSONObject("value")?.let { applyConfig(it); openPlayback(item,index,position) }
            }
        },ui.margins(-1,43).apply { topMargin=ui.px(12) })
        p.show(); (if(sourceFailure) switchSource else retry).requestFocus()
    }
    private fun command(type: String, values: JSONObject = JSONObject()) {
        values.put("version", 1).put("sessionId", session).put("type", type)
        player.command(values)
    }
    fun onPlaybackState(value: JSONObject) {
        if (disposed || active == null || value.optString("sessionId") != session) return
        state=value
        val actualRate=value.optDouble("rate",rate)
        if(actualRate.isFinite() && actualRate>0) rate=actualRate
        fit=value.optString("fit",fit)
        settingsButton.setLabel("设置 · ${formatRate()}×")
        val a = active ?: return
        val item = a.getJSONObject("item")
        val count = a.getJSONObject("detail").getJSONArray("episodes").length()
        playerTitle.setLabel(item.optString("name"))
        val height = if(a.optJSONObject("playback")?.optString("processing")=="enhance") a.optJSONObject("playback")?.optInt("sourceHeight",value.optInt("height")) ?: value.optInt("height") else value.optInt("height")
        playerSubtitle.setLabel("${if(a.optJSONObject("playback")?.optString("delivery")=="desktop") (a.optJSONObject("playback")?.optString("label","电脑串流") ?: "电脑串流")+" · " else ""}${item.optString("sourceName")}${if(count>1) " · 第 ${a.optInt("index")+1} / $count 集" else " · 电影"} · ${if(value.optBoolean("paused")) "已暂停" else if(value.optBoolean("buffering")) "缓冲中" else "正在播放"}")
        val delivery=a.optJSONObject("playback")
        val presentation=delivery?.optJSONObject("presentation")
        val enhanced=delivery?.optString("processing")=="enhance"
        val dimensions="${value.optInt("width")}×${value.optInt("height")}"
        playerProcessingVideo.setLabel(if(!value.optBoolean("rendered")) "正在读取实际画面分辨率…" else presentation?.optString("video")
            ?: if(enhanced) "电脑增强 · 处理详情未知，请更新电脑服务" else "${if(delivery?.optString("delivery")=="desktop") "电脑原画转发" else "原始直连"} · 未超分 · 当前 $dimensions")
        val adsText=presentation?.optString("ads") ?: if(enhanced) "去广告：处理结果未知，请更新电脑服务" else adsLabel()
        playerProcessingAds.setLabel(listOf(adsText,compareText,presentation?.optString("overlays").orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "))
        playButton.setLabel(if (value.optBoolean("paused")) "▶ 继续播放" else "Ⅱ 暂停")
        prevButton.isEnabled = a.optInt("index") > 0; nextButton.isEnabled = a.optInt("index") < count - 1
        prevButton.alpha = if (prevButton.isEnabled) 1f else .35f; nextButton.alpha = if (nextButton.isEnabled) 1f else .35f
        val position = seekPreview ?: value.optDouble("position")
        time.setLabel(TvPlaybackRules.time(position)); duration.setLabel(TvPlaybackRules.time(value.optDouble("duration")))
        seek.fraction=if(value.optDouble("duration")>0) (position/value.optDouble("duration")).toFloat() else 0f
        seek.buffered=if(value.optDouble("duration")>0) ((value.optDouble("position")+value.optDouble("bufferedDuration"))/value.optDouble("duration")).toFloat() else 0f
        val error=value.optString("error")
        val buffering=value.optBoolean("buffering") && !value.optBoolean("paused") && error.isEmpty()
        if (buffering && bufferingSince==0L) bufferingSince=SystemClock.elapsedRealtime()
        if (!buffering) bufferingSince=0L
        buffer.visibility=if(panel==null && seekPreview==null && (error.isNotEmpty() || buffering && SystemClock.elapsedRealtime()-bufferingSince>=350)) View.VISIBLE else View.GONE
        bufferTitle.setLabel(if(error.isNotEmpty()) error else "正在缓冲")
        bufferSpinner.visibility=if(error.isEmpty()) View.VISIBLE else View.GONE
        bufferSpeed.visibility=if(error.isEmpty()) View.VISIBLE else View.GONE
        bufferSpeed.setLabel(TvPlaybackRules.speed(value.optDouble("bytesPerSecond")))
        bufferNote.setLabel(if(error.isNotEmpty()) "按下键打开控制栏换源" else if(value.optDouble("bytesPerSecond")>0) "视频下载速度" else "等待片源响应，暂未收到数据")
        bufferDetail.setLabel(if(error.isNotEmpty()) "" else "已缓冲 ${"%.1f".format(value.optDouble("bufferedDuration"))} 秒 · 下键可打开控制栏换源")
        seekOverlay.visibility=if(seekPreview!=null && panel==null) View.VISIBLE else View.GONE
        seekTime.setLabel(TvPlaybackRules.time(position))
        if (height > qualityHeight) {
            qualityHeight = height
            backend.request("played-quality", JSONObject().put("sourceKey", item.optString("sourceKey")).put("vodId", item.optString("vodId")).put("height", height))
        }
        if (SystemClock.elapsedRealtime() - lastSaved >= 5000) savePlayback()
        if (error.isNotEmpty()) {
            showControls(true)
            if (a.optJSONObject("playback")?.optString("delivery") == "desktop" && desktopFailureSession != session) {
                desktopFailureSession = session
                showDesktopFailure("电脑串流中断：$error", item, a.optInt("index"), originalPosition(value.optDouble("position"),timeMap()))
            }
        }
        if (value.optBoolean("ended") && endedSession != session) {
            endedSession = session
            if (config.optBoolean("autoplayNext", true) && a.optInt("index") < count - 1) episode(1) else showControls(true)
        }
    }
    /** 电脑增强用服务端时间轴；直连用 Media3 过滤器实际删除的区间 */
    private fun timeMap():JSONArray? = active?.optJSONObject("playback")?.optJSONArray("timeMap") ?: state.optJSONArray("timeMap")
    private fun adsLabel(): String {
        if(!config.optBoolean("adFilter",true)) return "去广告：已关闭"
        val removed=timeMap().objects()
        val summary=active?.optJSONObject("playback")?.optJSONObject("adSummary")
        val extra=listOfNotNull(summary?.optInt("codecGroups")?.takeIf { it>0 }?.let { "编码比对 $it 段" }, summary?.optInt("learnedGroups")?.takeIf { it>0 }?.let { "已学习 $it 段" }, summary?.optInt("repeatGroups")?.takeIf { it>0 }?.let { "重复插入 $it 段" })
        if(removed.isEmpty()) return "去广告：未发现可识别的广告分片（不含画面内广告）"
        return "去广告：已过滤 ${removed.size} 处 · ${removed.sumOf { it.optDouble("duration") }.toInt()} 秒${if(extra.isEmpty()) "" else "（含${extra.joinToString("、")}）"}"
    }
    private fun originalPosition(position:Double, removals:JSONArray?):Double {
        var result=position; var removed=0.0
        removals.objects().forEach { interval -> if(position+.00001>=interval.optDouble("start")-removed) result+=interval.optDouble("duration"); removed+=interval.optDouble("duration") }
        return result
    }
    fun savePlayback() {
        val a = active ?: return
        if (state.optDouble("duration") <= 0) return
        val item = a.getJSONObject("item")
        backend.request("save-progress", JSONObject().put("sourceKey", item.optString("sourceKey")).put("vodId", item.optString("vodId"))
            .put("index", a.optInt("index")).put("position", state.optDouble("position")).put("duration", state.optDouble("duration")).put("timeMap",timeMap() ?: JSONArray()))
        lastSaved = SystemClock.elapsedRealtime()
    }
    private fun episode(delta: Int) {
        val a = active ?: return
        val next = a.optInt("index") + delta
        if (next in 0 until a.getJSONObject("detail").getJSONArray("episodes").length()) openPlayback(a.getJSONObject("item"), next)
    }
    private fun chooseEpisode(index: Int) {
        val a=active ?: return
        if(index==a.optInt("index")) { panel?.dialog?.dismiss(); seekPreview=null; showControls(true); playback.requestFocus() }
        else openPlayback(a.getJSONObject("item"),index)
    }
    private fun showEpisodes() {
        val a=active ?: return
        seekPreview=null; showControls(true)
        val count=a.getJSONObject("detail").getJSONArray("episodes").length()
        val p=newPanel("选集","episodes")
        p.status.text="${a.getJSONObject("item").optString("name")} · 共 $count 集"
        val eps=ui.grid(8)
        p.scroll.layoutParams=ui.margins(-1,minOf(200,((count+7)/8)*43+8))
        p.content.addView(eps)
        var current: Button?=null
        val reverse=ui.button(if(reversedEpisodes) "切为正序" else "切为倒序") {}
        fun render() {
            eps.removeAllViews(); current=null
            val order=(0 until count).toList().let { if(reversedEpisodes) it.reversed() else it }
            order.forEachIndexed { slot,n ->
                val button=ui.button("${if(n==a.optInt("index")) "▶ " else ""}${if(count==1) "正片" else "第 ${n+1} 集"}") { chooseEpisode(n) }
                button.setPadding(ui.px(4),0,ui.px(4),0)
                if(n==a.optInt("index")) { button.isSelected=true; current=button }
                eps.addView(button,ui.gridCell(slot,8,35).apply { setMargins(ui.px(5),ui.px(4),ui.px(5),ui.px(4)) })
            }
        }
        if(count>1) {
            reverse.setOnClickListener { reversedEpisodes=!reversedEpisodes; reverse.text=if(reversedEpisodes) "切为正序" else "切为倒序"; render() }
            p.heading.addView(reverse,p.heading.childCount-1,ui.margins(-2,37))
        }
        render(); p.show(); current?.requestFocus(); current?.post { current?.requestRectangleOnScreen(android.graphics.Rect(0,0,current!!.width,current!!.height),true) }
    }
    private fun showSwitchSources() {
        val a=active ?: return
        showSwitchSources(a.getJSONObject("item"),a.optInt("index"))
    }
    private fun showSwitchSources(item:JSONObject, index:Int) {
        seekPreview=null; if(active!=null) showControls(true)
        val p=newPanel(item.optString("name"),"source")
        p.status.text="正在搜索同名影片…"
        lateinit var picker: TvSourcePicker
        picker=TvSourcePicker(ui,p,posters,item,choose={ result ->
            openPlayback(result.getJSONObject("item"),minOf(index,result.optInt("episodes")-1))
        },retest={ result ->
            val id=backend.request("retest-source",JSONObject().put("item",result.getJSONObject("item"))) { event ->
                if(panel===p) {
                    if(event.optString("event")=="error") picker.update(JSONObject().put("item",result.getJSONObject("item")).put("ok",false).put("error",event.optString("error")))
                    else event.optJSONObject("value")?.let { picker.update(it) }
                }
            }
            panelRequests.add(id)
        })
        val refresh=ui.button("刷新来源") {}
        p.heading.addView(refresh,p.heading.childCount-1,ui.margins(-2,37).apply { rightMargin=ui.px(10); gravity=Gravity.TOP })
        fun load(force: Boolean) {
            backend.cancel(panelId)
            refresh.isEnabled=false
            picker.refreshing()
            panelId=backend.request("switch-sources",JSONObject().put("title",item.optString("name")).put("refresh",force)) { event ->
                if(panel!==p) return@request
                when(event.optString("event")) {
                    "candidates" -> picker.candidates(event.optJSONArray("value").objects())
                    "source-result" -> event.optJSONObject("value")?.let { picker.update(it) }
                    "sources-tested" -> { val v=event.optJSONObject("value"); picker.tested(v?.optBoolean("cached")==true,v?.optBoolean("refreshing")==true) }
                    "quality-result" -> event.optJSONObject("value")?.let { picker.quality(it) }
                    "cache-warning" -> event.optJSONObject("value")?.let { picker.warning(it.optString("message")) }
                    "error" -> { p.status.text=event.optString("error"); refresh.isEnabled=true }
                    "result" -> { picker.complete(); refresh.isEnabled=true }
                }
            }
        }
        refresh.setOnClickListener { load(true) }
        p.show(); load(false)
    }

    private fun showPlayerSettings() {
        seekPreview=null; showControls(true)
        val p=newPanel("播放设置","settings")
        p.status.text="方向键选择，确认键应用"
        fun row(label: String): LinearLayout = ui.row().also {
            it.addView(ui.text(label,13,Color.rgb(186,196,209)),ui.margins(77,-2))
            p.content.addView(it,ui.margins(-1,33).apply { bottomMargin=ui.px(12) })
        }
        fun add(row: LinearLayout,button: Button) { row.addView(button,ui.margins(-2,33).apply { rightMargin=ui.px(10) }) }
        val rates=row("播放速度")
        val rateButtons=mutableListOf<Button>()
        listOf(.75,1.0,1.25,1.5,2.0).forEach { r ->
            val button=ui.button(if(r==1.0) "正常 1×" else "${if(r%1.0==0.0) r.toInt().toString() else r.toString()}×") {
                rate=r; command("rate",JSONObject().put("rate",r)); settingsButton.text="设置 · ${formatRate()}×"
                rateButtons.forEachIndexed { index,b -> b.isSelected=listOf(.75,1.0,1.25,1.5,2.0)[index]==rate }
            }.apply { isSelected=r==rate }
            rateButtons.add(button); add(rates,button)
        }
        val ratios=row("画面比例"); val fitButtons=mutableListOf<Button>()
        listOf("contain" to "原始比例 · 完整显示","cover" to "铺满屏幕 · 裁切边缘").forEach { (value,label) ->
            val button=ui.button(label) { fit=value; command("fit",JSONObject().put("fit",value)); fitButtons.forEachIndexed { index,b -> b.isSelected=if(index==0) fit=="contain" else fit=="cover" } }.apply { isSelected=fit==value }
            fitButtons.add(button); add(ratios,button)
        }
        val flags=row("自动连播")
        fun flag(key: String): Button {
            val button=ui.button(if(config.optBoolean(key,true)) "已开启" else "已关闭") {}.apply { isSelected=config.optBoolean(key,true) }
            button.setOnClickListener {
                val enabled=!config.optBoolean(key,true)
                backend.request("settings",JSONObject().put(key,enabled)) { event ->
                    event.optJSONObject("value")?.let { applyConfig(it); button.text=if(enabled) "已开启" else "已关闭"; button.isSelected=enabled; if(key=="adFilter" || key=="superResolution" || key=="overlayRemoval") {
                        val a=active
                        if(a?.optJSONObject("playback")?.optString("processing")=="enhance") openPlayback(a.getJSONObject("item"),a.optInt("index"),originalPosition(state.optDouble("position"),timeMap()))
                        else if(key=="adFilter") command("filter",JSONObject().put("enabled",enabled))
                    } }
                }
            }
            return button
        }
        add(flags,flag("autoplayNext")); flags.addView(ui.text("广告过滤",13,Color.rgb(186,196,209)),ui.margins(77,-2)); add(flags,flag("adFilter"))
        if(config.optBoolean("adFilter",true)) {
            val learning=row("广告学习")
            add(learning,ui.button("标记当前片段为广告") { markAd(p) })
            val clear=ui.button("清除已学习（${config.optInt("learnedAds")}）") {}
            clear.setOnClickListener {
                backend.request("clear-learned-ads") { event -> event.optJSONObject("value")?.let { applyConfig(it); clear.text="清除已学习（0）"; toast("已清除学习的广告") } }
            }
            add(learning,clear)
        }
        if(config.optString("playbackMode")=="desktop") {
            val superRow=row("AI 超分"); add(superRow,flag("superResolution")); superRow.addView(ui.text("开启升高一档 · 关闭保持原始分辨率",11,ui.muted),ui.margins(-2,-2))
            val overlayRow=row("去水印"); add(overlayRow,flag("overlayRemoval")); overlayRow.addView(ui.text("固定台标/水印与滚动广告字幕 · 处理处会有模糊痕迹",11,ui.muted),ui.margins(-2,-2))
        }
        p.show(); rateButtons.getOrNull(listOf(.75,1.0,1.25,1.5,2.0).indexOf(rate))?.requestFocus()
    }
    /** 广告播放时标记：记下该段指纹，并从段尾重新打开，让本次与以后的播放都去掉它 */
    private fun markAd(p: TvWidgets.Panel) {
        val a=active ?: return
        val index=a.optInt("index")
        val url=a.getJSONObject("detail").getJSONArray("episodes").optString(index)
        p.status.text="正在识别当前片段…"
        backend.request("mark-ad",JSONObject().put("url",url).put("position",originalPosition(state.optDouble("position"),timeMap())).put("title",a.getJSONObject("item").optString("name"))) { event ->
            if(event.optString("event")=="error") { p.status.text=event.optString("error"); return@request }
            val value=event.optJSONObject("value") ?: return@request
            value.optJSONObject("config")?.let { applyConfig(it) }
            p.dialog.dismiss(); toast("已学习这段广告，正在跳过")
            // 面板关闭回调会取消进行中的打开请求，须排在它之后再重新打开
            main.post { openPlayback(a.getJSONObject("item"),index,value.optDouble("end")+.5) }
        }
    }
    private fun formatRate()=if(rate%1.0==0.0) rate.toInt().toString() else rate.toString()
    private fun showSourceSettings(focusMode: Boolean = false) {
        if(!ready) return
        val p=newPanel("片源管理")
        p.width=883; p.height=486; p.close.text="完成"
        p.body.setPadding(ui.px(24),ui.px(11),ui.px(24),ui.px(16))
        p.body.background=ui.background(Color.rgb(14,26,41),Color.rgb(41,66,91),16)
        p.title.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(24).toFloat())
        (p.title.parent as LinearLayout).addView(ui.text(if(config.optString("playbackMode")=="desktop") "电脑负责画面处理和串流" else "独立运行 · 设备直接访问片源，无需电脑服务器",13,ui.muted),ui.margins(-1,-2).apply { topMargin=ui.px(5) })
        p.status.text="已启用 ${config.optInt("enabled")} 个片源。按确认键切换，设置自动保存。"
        fun style(view: View) {
            if(view is Button) { view.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(14).toFloat()); view.setPadding(ui.px(12),ui.px(8),ui.px(12),ui.px(8)) }
            if(view is EditText) { view.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(14).toFloat()); view.setPadding(ui.px(12),0,ui.px(12),0) }
            view.background=ui.focusedBackground(Color.rgb(23,42,62))
        }
        p.content.addView(ui.text("播放模式",17,bold=true),ui.margins(-1,-2).apply { bottomMargin=ui.px(8) })
        val modes=ui.row()
        listOf("direct" to "独立直连", "desktop" to "电脑串流").forEach { (mode,label) ->
            val button=ui.button(label) {
                backend.request("settings",JSONObject().put("playbackMode",mode)) { event ->
                    event.optJSONObject("value")?.let { applyConfig(it); p.dialog.dismiss(); showSourceSettings(true) }
                }
            }.apply { style(this); isSelected=config.optString("playbackMode","direct")==mode }
            modes.addView(button,LinearLayout.LayoutParams(0,ui.px(38),1f).apply { rightMargin=ui.px(12) })
        }
        p.content.addView(modes)
        fun superLabel()="AI 超分：${if(config.optBoolean("superResolution",true)) "已开启 · 自动升高一个档位" else "已关闭 · 保持原始分辨率"}（电脑串流）"
        val superToggle=ui.button(superLabel()) {}.apply { style(this); isSelected=config.optBoolean("superResolution",true) }
        superToggle.setOnClickListener {
            backend.request("settings",JSONObject().put("superResolution",!config.optBoolean("superResolution",true))) { event ->
                event.optJSONObject("value")?.let { applyConfig(it); superToggle.text=superLabel(); superToggle.isSelected=config.optBoolean("superResolution",true) }
            }
        }
        p.content.addView(superToggle,ui.margins(-1,38).apply { topMargin=ui.px(10) })
        fun overlayLabel()="去水印：${if(config.optBoolean("overlayRemoval",true)) "已开启 · 固定台标/水印与滚动广告字幕" else "已关闭 · 保持原画"}（电脑串流）"
        val overlayToggle=ui.button(overlayLabel()) {}.apply { style(this); isSelected=config.optBoolean("overlayRemoval",true) }
        overlayToggle.setOnClickListener {
            backend.request("settings",JSONObject().put("overlayRemoval",!config.optBoolean("overlayRemoval",true))) { event ->
                event.optJSONObject("value")?.let { applyConfig(it); overlayToggle.text=overlayLabel(); overlayToggle.isSelected=config.optBoolean("overlayRemoval",true) }
            }
        }
        p.content.addView(overlayToggle,ui.margins(-1,38).apply { topMargin=ui.px(10) })
        p.content.addView(ui.text("串流需电脑服务持续运行；处理能力以连接检测结果为准。",13,ui.muted),ui.margins(-1,-2).apply { topMargin=ui.px(8); bottomMargin=ui.px(8) })
        val server=ui.input("电脑服务地址，例如 http://192.168.1.10:8090").apply { style(this); setText(config.optString("desktopServerUrl")); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val pairing=ui.input("电脑服务配对码").apply { style(this); setText(config.optString("desktopPairingToken")); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val connection=ui.row()
        connection.addView(server,LinearLayout.LayoutParams(0,ui.px(38),1.5f).apply { rightMargin=ui.px(12) })
        connection.addView(pairing,LinearLayout.LayoutParams(0,ui.px(38),1f).apply { rightMargin=ui.px(12) })
        var connecting=false
        val connect=ui.button("保存并测试") {
            if (!connecting) {
                connecting=true; p.status.text="正在连接电脑服务…"
                val args=JSONObject().put("desktopServerUrl",server.text.toString()).put("desktopPairingToken",pairing.text.toString())
                backend.request("settings",args) { saved ->
                    saved.optJSONObject("value")?.let { applyConfig(it) }
                    backend.request("test-desktop",args) { event ->
                        connecting=false
                        p.status.text=if(event.optString("event")=="error") event.optString("error") else event.optString("value")
                    }
                }
            }
        }.apply { style(this) }
        connection.addView(connect,ui.margins(-2,38)); p.content.addView(connection)
        // Keep the new rows predictable with a remote; wide buttons must not jump into the source grid.
        val direct=modes.getChildAt(0); val desktop=modes.getChildAt(1)
        direct.nextFocusLeftId=direct.id; direct.nextFocusRightId=desktop.id; direct.nextFocusUpId=p.close.id; direct.nextFocusDownId=superToggle.id
        desktop.nextFocusLeftId=direct.id; desktop.nextFocusRightId=desktop.id; desktop.nextFocusUpId=p.close.id; desktop.nextFocusDownId=superToggle.id
        superToggle.nextFocusUpId=if(config.optString("playbackMode")=="desktop") desktop.id else direct.id; superToggle.nextFocusDownId=server.id
        server.nextFocusLeftId=server.id; server.nextFocusRightId=pairing.id; server.nextFocusUpId=superToggle.id
        pairing.nextFocusLeftId=server.id; pairing.nextFocusRightId=connect.id; pairing.nextFocusUpId=superToggle.id
        connect.nextFocusLeftId=pairing.id; connect.nextFocusRightId=connect.id; connect.nextFocusUpId=superToggle.id
        p.close.nextFocusDownId=if(config.optString("playbackMode")=="desktop") desktop.id else direct.id
        val sources=ui.grid(4)
        config.optJSONArray("sources").objects().forEachIndexed { index,source ->
            val key=source.optString("key")
            fun enabled()=config.optJSONArray("selectedKeys").strings().contains(key)
            fun label()="${if(enabled()) "✓ " else "○ "}${source.optString("name")}"
            val button=ui.button(label()) {}.apply { style(this); isSelected=enabled(); gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT }
            if(index<4) button.nextFocusUpId=when(index) { 0,1 -> server.id; 2 -> pairing.id; else -> connect.id }
            button.setOnClickListener {
                backend.request("toggle-source",JSONObject().put("key",key)) { event -> event.optJSONObject("value")?.let {
                    applyConfig(it); button.text=label(); button.isSelected=enabled(); p.status.text="已启用 ${config.optInt("enabled")} 个片源。按确认键切换，设置自动保存。"
                } }
            }
            sources.addView(button,ui.gridCell(index,4,36).apply { setMargins(ui.px(6),ui.px(5),ui.px(6),ui.px(5)) })
        }
        p.content.addView(sources,ui.margins(-1,-2).apply { topMargin=ui.px(11); bottomMargin=ui.px(16) })
        p.content.addView(ui.text("添加 Apple CMS 片源",17,bold=true),ui.margins(-1,-2).apply { bottomMargin=ui.px(11) })
        val name=ui.input("名称（可选）").apply { style(this) }
        val url=ui.input("https://example.com/api.php/provide/vod").apply { style(this); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val add=ui.button("添加") {
            backend.request("add-source",JSONObject().put("name",name.text.toString()).put("url",url.text.toString())) { event ->
                if(event.optString("event")=="error") p.status.text=event.optString("error")
                else { event.optJSONObject("value")?.let { applyConfig(it) }; p.dialog.dismiss(); showSourceSettings(); toast("片源已添加并启用") }
            }
        }.apply { style(this) }
        val form=ui.row()
        form.addView(name,LinearLayout.LayoutParams(0,ui.px(36),1f).apply { rightMargin=ui.px(14) })
        form.addView(url,LinearLayout.LayoutParams(0,ui.px(36),1f).apply { rightMargin=ui.px(14) }); form.addView(add,ui.margins(-2,36)); p.content.addView(form)
        p.content.addView(ui.text("导入或更新订阅",17,bold=true),ui.margins(-1,-2).apply { topMargin=ui.px(11); bottomMargin=ui.px(11) })
        p.content.addView(ui.text("支持 LibreTV SourceList 和 TVBOX 的直连接口；Spider 类订阅暂不支持。",13,ui.muted))
        val subscription=ui.input("https://example.com/sources.json").apply { style(this); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        var busy=false
        val syncButtons=mutableListOf<Button>()
        fun sync(value: String) {
            if(busy) return
            busy=true; syncButtons.forEach { it.isEnabled=false }; p.status.text="正在从设备同步订阅…"
            backend.request("sync-subscription",JSONObject().put("url",value.trim())) { event ->
                busy=false; syncButtons.forEach { it.isEnabled=true }
                if(event.optString("event")=="error") p.status.text=event.optString("error")
                else { p.dialog.dismiss(); backend.request("config") { e -> e.optJSONObject("value")?.let { applyConfig(it); showSourceSettings(); toast("订阅已同步") } } }
            }
        }
        val sync=ui.button("同步订阅") { sync(subscription.text.toString()) }.apply { style(this) }; syncButtons.add(sync)
        val subform=ui.row()
        subform.addView(subscription,LinearLayout.LayoutParams(0,ui.px(36),1f).apply { rightMargin=ui.px(14) }); subform.addView(sync,ui.margins(-2,36)); p.content.addView(subform,ui.margins(-1,-2).apply { topMargin=ui.px(11) })
        config.optJSONArray("subscriptions").objects().forEach { sub ->
            val button=ui.button("更新 ${sub.optString("name").ifEmpty { sub.optString("url") }}") { sync(sub.optString("url")) }.apply { style(this) }
            syncButtons.add(button); p.content.addView(button,ui.margins(-2,36).apply { topMargin=ui.px(5) })
        }
        p.show(); if(focusMode) (if(config.optString("playbackMode")=="desktop") desktop else direct).requestFocus()
    }
    private fun previewSeek(delta: Int) {
        if(!TvPlaybackRules.canSeek(state.optDouble("duration"))) return
        seekPreview = TvPlaybackRules.preview(seekPreview ?: state.optDouble("position"), delta, state.optDouble("duration"))
        showControls(true); seek.requestFocus(); onPlaybackState(state)
    }
    private fun commitSeek() {
        seekPreview?.let { command("seek", JSONObject().put("position", it)) }
        seekPreview=null; onPlaybackState(state); showControls(true); playback.requestFocus()
    }
    private fun togglePlay() { command(if (state.optBoolean("paused")) "play" else "pause"); showControls(true); playButton.requestFocus() }
    private fun showControls(show: Boolean) {
        controls.visibility=if(show && panel==null) View.VISIBLE else View.GONE
        top.visibility=controls.visibility
        if (!show) playback.requestFocus()
        scheduleControls()
    }
    private fun scheduleControls() { main.removeCallbacks(hideControls); if (active != null && controls.visibility == View.VISIBLE) main.postDelayed(hideControls, 5000) }
    private fun exitPlayback() {
        savePlayback(); closeDesktopPlayback(); backend.cancel(openingId); loadingPlayback = false; active = null; session = ""; seekPreview = null
        player.stop(); playback.visibility=View.GONE; view.background=TvHomeBackground(); homeScroll.visibility=View.VISIBLE
        if(searched.isEmpty()) adapter.focusHistory(historyFocusKey) else grid.getChildAt(0)?.requestFocus()
        refreshHome()
    }
    private fun playbackKey(code: Int): TvPlaybackNavigation.Key? = when(code) {
        KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE -> TvPlaybackNavigation.Key.BACK
        KeyEvent.KEYCODE_MENU,KeyEvent.KEYCODE_SETTINGS,KeyEvent.KEYCODE_F2 -> TvPlaybackNavigation.Key.MENU
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_SPACE -> TvPlaybackNavigation.Key.PLAY
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> TvPlaybackNavigation.Key.CONFIRM
        KeyEvent.KEYCODE_DPAD_LEFT -> TvPlaybackNavigation.Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvPlaybackNavigation.Key.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> TvPlaybackNavigation.Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TvPlaybackNavigation.Key.DOWN
        else -> null
    }
    private fun handlePlaybackKey(code: Int) {
        val key=playbackKey(code) ?: return
        val current=activity.currentFocus
        val nav=TvPlaybackNavigation.State(controls.visibility==View.VISIBLE, current!=null && (0 until controlRow.childCount).any { controlRow.getChildAt(it)===current },seekPreview!=null,panel!=null,panelKind=="settings")
        when(TvPlaybackNavigation.action(nav,key)) {
            TvPlaybackNavigation.Action.CLOSE_PANEL -> panel?.dialog?.dismiss()
            TvPlaybackNavigation.Action.SETTINGS -> showPlayerSettings()
            TvPlaybackNavigation.Action.TOGGLE_PLAY -> togglePlay()
            TvPlaybackNavigation.Action.CLICK -> (panel?.dialog?.currentFocus ?: current)?.performClick()
            TvPlaybackNavigation.Action.MOVE_FOCUS -> ui.moveFocus(panel?.body ?: controlRow,code)
            TvPlaybackNavigation.Action.PREVIEW_BACKWARD -> previewSeek(-10)
            TvPlaybackNavigation.Action.PREVIEW_FORWARD -> previewSeek(10)
            TvPlaybackNavigation.Action.COMMIT_SEEK -> commitSeek()
            TvPlaybackNavigation.Action.CANCEL_SEEK -> { seekPreview=null; onPlaybackState(state); showControls(false) }
            TvPlaybackNavigation.Action.HIDE_HUD -> showControls(false)
            TvPlaybackNavigation.Action.EXIT -> exitPlayback()
            TvPlaybackNavigation.Action.FOCUS_PLAY -> { seekPreview=null; onPlaybackState(state); showControls(true); playButton.requestFocus() }
            TvPlaybackNavigation.Action.FOCUS_PROGRESS -> seek.requestFocus()
            TvPlaybackNavigation.Action.EPISODES -> showEpisodes()
        }
        scheduleControls()
    }
    fun dispatchKey(event: KeyEvent): Boolean {
        if(panel?.dialog?.isShowing==true) return false
        val code=event.keyCode
        if(active!=null) {
            if(playbackKey(code)==null) return false
            if(event.action==KeyEvent.ACTION_DOWN && !(event.repeatCount>0 && code !in setOf(19,20,21,22))) handlePlaybackKey(code)
            return true
        }
        if (code in setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_F2)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showSourceSettings()
            return true
        }
        if (code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                if(loadingPlayback) { backend.cancel(openingId); loadingPlayback=false; status.text=openingStatus; toast("已取消电脑处理") } else activity.finish()
            }
            return true
        }
        if (code in setOf(19,20,21,22)) { if(event.action==KeyEvent.ACTION_DOWN) ui.moveFocus(home,code); return true }
        return false
    }
    fun release() { backend.cancel(compareId); closeDesktopPlayback(); disposed=true; posters.close(); panel?.dialog?.dismiss(); main.removeCallbacksAndMessages(null) }
    private inner class MovieAdapter {
        var cards: List<JSONObject> = emptyList()
        var visible=72
        val count get()=cards.size
        private val rows=linkedMapOf<String,LinearLayout>()
        private fun key(card:JSONObject)=card.optJSONObject("history")?.let { "history:${it.optString("id")}" }
            ?: "${card.optString("name")}|${card.optString("year")}"
        fun showMore() { visible+=72; render() }
        fun submitHistory(value:List<JSONObject>) {
            submitCards(value.map { entry -> JSONObject().put("name",entry.optString("title")).put("pic",entry.optString("pic")).put("history",entry) })
        }
        fun focusHistory(id:String?) { (rows["history:$id"] ?: rows.values.firstOrNull())?.requestFocus() }
        fun submitCards(value:List<JSONObject>) { if (cards.isEmpty()) visible=72; cards=value; render() }
        private fun render() {
            val shown=cards.take(visible)
            val keys=shown.map { key(it) }
            val focused=rows.entries.find { it.value.hasFocus() }?.key
            val reorder=keys!=rows.keys.toList()
            if (reorder) {
                val previous=rows.toMap(); grid.removeAllViews(); rows.clear()
                shown.forEachIndexed { index,card ->
                    val k=key(card); val root=previous[k] ?: createCard()
                    rows[k]=root; grid.addView(root,ui.gridCell(index,5,if(card.has("history")) 184 else 308).apply { setMargins(ui.px(6),0,ui.px(6),ui.px(21)) })
                }
            }
            shown.forEach { card -> bind(rows.getValue(key(card)),card) }
            if (reorder && focused!=null) rows[focused]?.requestFocus()
            empty.visibility=if (cards.isEmpty()) View.VISIBLE else View.GONE
            grid.visibility=if (cards.isEmpty()) View.GONE else View.VISIBLE
            more.visibility=if (cards.size>visible) View.VISIBLE else View.GONE
            more.text="显示更多（还有 ${maxOf(0,cards.size-visible)} 部）"
        }
        private fun createCard():LinearLayout=ui.column().apply {
            id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; ui.homeFocus(this,Color.TRANSPARENT,14)
            val poster=FrameLayout(activity).apply { background=ui.background(Color.rgb(17,31,48),radius=14); clipToOutline=true }
            poster.addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
            poster.addView(ui.text("",10,ui.navy,true).apply { setPadding(ui.px(8),ui.px(4),ui.px(8),ui.px(4)); background=ui.background(Color.rgb(91,227,255),radius=20) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.px(10),ui.px(10),0,0) })
            poster.addView(android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=1000; progressTintList=android.content.res.ColorStateList.valueOf(ui.cyan)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(37,58,78)); visibility=View.GONE
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,ui.px(4),Gravity.BOTTOM))
            addView(poster,ui.margins(-1,246))
            addView(ui.text("",16,bold=true).apply { ui.fontWeight(this,750); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,24).apply { topMargin=ui.px(14) })
            addView(ui.text("",12,ui.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,18).apply { topMargin=ui.px(4) })
            addView(ui.text("",12,ui.muted).apply { isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END; visibility=View.GONE },ui.margins(-1,18))
        }
        private fun bind(root:LinearLayout,card:JSONObject) {
            val poster=root.getChildAt(0) as FrameLayout
            val image=poster.getChildAt(0) as ImageView
            if (image.tag!=card.optString("pic")) posters.load(image,card.optString("pic"))
            val entry=card.optJSONObject("history")
            poster.layoutParams=ui.margins(-1,if(entry!=null) 104 else 246)
            val count=card.optJSONArray("items")?.length() ?: 0
            val position=entry?.optDouble("playbackPosition",0.0) ?: 0.0
            val length=entry?.optDouble("duration",0.0) ?: 0.0
            val finished=length>0 && position>=length-15
            (poster.getChildAt(1) as TextView).apply {
                text=if(entry!=null) (if(finished) "已看完" else "继续观看") else "$count 源"
                visibility=if(entry!=null || count>1) View.VISIBLE else View.GONE
            }
            (poster.getChildAt(2) as android.widget.ProgressBar).apply {
                visibility=if(entry!=null && length>0) View.VISIBLE else View.GONE
                progress=if(length>0 && position.isFinite()) ((position/length).coerceIn(0.0,1.0)*1000).toInt() else 0
            }
            (root.getChildAt(1) as TextView).text=card.optString("name")
            var progressLabel=""
            val meta=if(entry!=null) {
                val episode=if(entry.optInt("totalEpisodes")>1) "第 ${entry.optInt("episodeIndex")+1} / ${entry.optInt("totalEpisodes")} 集" else "电影"
                progressLabel=if(finished) "已看完 · 确认重播" else "已看 ${TvPlaybackRules.time(position)}${if(length>0) " / ${TvPlaybackRules.time(length)}" else ""}"
                "$episode · ${sourceName(entry.optString("sourceKey"))}"
            } else listOf(card.optString("year"),card.optString("remarks")).filter { it.isNotEmpty() }.joinToString(" · ")
            (root.getChildAt(2) as TextView).apply { text=meta; isSingleLine=true }
            (root.getChildAt(3) as TextView).apply { text=progressLabel; visibility=if(entry!=null) View.VISIBLE else View.GONE }
            root.contentDescription=if(entry!=null) "历史观看：${card.optString("name")}" else card.optString("name")
            if(entry==null) cardOf[root]=card else cardOf.remove(root)
            root.setOnClickListener { if(entry!=null) resumeHistory(entry) else openDetail(card) }
            root.setOnKeyListener { _,code,event ->
                if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER) {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) { if(entry!=null) resumeHistory(entry) else playBest(card) }
                    true
                } else false
            }
        }
    }
}
