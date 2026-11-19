package org.libretv.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机界面（纯原生控件）：首页、搜索、详情、片库、设置；播放页见 PhonePlayer，电视伴侣面板见 PhoneTvSheet。
 * 数据来自 CoreBackend（Kotlin 数据层）；手机始终直连片源，不使用电脑串流。
 */
@UnstableApi
internal class PhoneUi(val activity: Activity, val backend: org.libretv.tv.core.CoreBackend, val player: NativePlayerHost, val link: TvLink, private val retryBoot: () -> Unit) {
    val kit = PhoneKit(activity)
    val posters = TvPosterLoader()
    val main = Handler(Looper.getMainLooper())
    var home = JSONObject(); private set
    var config = JSONObject(); private set
    var disposed = false; private set
    /** 全屏播放中：窗口不再给系统栏和刘海留白 */
    var immersive = false
    private var ready = false
    private val hot = TvHotList(activity)
    private var hotTv = emptyList<TvHotList.Item>()
    private var hotMovie = emptyList<TvHotList.Item>()

    private class Overlay(val view: View, val onClose: () -> Unit, val onBack: () -> Boolean)
    private val overlays = ArrayDeque<Overlay>()
    private val pages = FrameLayout(activity)
    private val homePage = kit.column()
    private val libraryPage = kit.column()
    private val settingsPage = kit.column()
    private val tabs = listOf("首页" to PhoneIcon.HOME, "片库" to PhoneIcon.LIBRARY, "设置" to PhoneIcon.SETTINGS)
    private val tabViews = mutableListOf<LinearLayout>()
    private val scrolls = listOf(homePage, libraryPage, settingsPage).map { kit.scroll(it) }
    private var tab = 0
    private val boot = kit.column().apply { gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK); isClickable = true }
    private val bootStatus = kit.text("", 14, kit.muted)
    private val nav = kit.row().apply { setBackgroundColor(Color.rgb(16, 16, 18)) }
    val view = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    val tvSheet = PhoneTvSheet(this)
    val playerPage = PhonePlayer(this)

    init {
        val shell = kit.column()
        scrolls.forEachIndexed { i, s -> s.visibility = if (i == 0) View.VISIBLE else View.GONE; pages.addView(s, kit.flp()) }
        shell.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(kit.divider(), LinearLayout.LayoutParams(-1, 1))
        tabs.forEachIndexed { i, (label, icon) ->
            val cell = kit.column().apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { showTab(i) } }
            cell.addView(android.widget.ImageView(activity).apply { tag = icon }, kit.lp(24, 24))
            cell.addView(kit.text(label, 11, kit.muted, 500), kit.lp(-2, -2, top = 4))
            tabViews.add(cell); nav.addView(cell, LinearLayout.LayoutParams(0, kit.dp(58), 1f))
        }
        shell.addView(nav)
        view.addView(shell, kit.flp())
        boot.addView(kit.text("SuperTV", 34, kit.ink, 700))
        boot.addView(ProgressBar(activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(kit.muted) }, kit.lp(28, 28, top = 28))
        boot.addView(bootStatus, kit.lp(-2, -2, top = 16))
        view.addView(boot, kit.flp())
        showTab(0)
        link.onChange = { syncPeer(); if (ready) renderSettings(); renderHome() }
    }

    // ---- 启动与数据 ----
    fun onBoot(event: JSONObject) {
        if (disposed) return
        when (event.optString("event")) {
            "fatal" -> onBootError(event.optString("error"))
            "library-changed" -> if (ready) applyHome(event.optJSONObject("value") ?: JSONObject())
            else -> {
                ready = true; applyHome(event.optJSONObject("value") ?: JSONObject())
                // 手机不走电脑串流；旧版本可能留下过这个设置
                if (config.optString("playbackMode") == "desktop") backend.request("settings", JSONObject().put("playbackMode", "direct")) { e -> e.optJSONObject("value")?.let { applyConfig(it) } }
                syncPeer()
                hot.load { tv, movie -> if (!disposed) { hotTv = tv; hotMovie = movie; renderHome() } }
                boot.animate().alpha(0f).setDuration(260).withEndAction { boot.visibility = View.GONE }.start()
            }
        }
    }
    fun onBootError(message: String) {
        boot.visibility = View.VISIBLE; boot.alpha = 1f; bootStatus.text = message
        if (boot.findViewWithTag<View>("retry") == null) boot.addView(kit.button("重试", true) { bootStatus.text = "正在重新加载…"; retryBoot() }.apply { tag = "retry" }, kit.lp(-2, 44, top = 20))
    }
    fun applyConfig(value: JSONObject) { config = value; home.put("config", value); if (tab == 2) renderSettings() }
    fun applyHome(value: JSONObject) {
        home = value; config = value.optJSONObject("config") ?: config
        renderHome(); renderLibrary(); if (tab == 2) renderSettings()
    }
    fun refreshHome() { backend.request("home") { if (it.optString("event") == "result") applyHome(it.optJSONObject("value") ?: JSONObject()) } }
    /** 告诉数据层当前连接的电视：资料直接与它同步 */
    private fun syncPeer() {
        if (!ready) return
        val tv = link.selected()
        backend.request("peer-link", if (tv == null) JSONObject() else JSONObject().put("id", tv.id).put("url", tv.url).put("token", tv.token))
    }
    fun sourceName(key: String) = config.optJSONArray("sources").objects().find { it.optString("key") == key }?.optString("name") ?: "片源"
    fun historyItem(entry: JSONObject) = JSONObject().put("sourceKey", entry.optString("sourceKey")).put("sourceUrl", entry.optString("sourceUrl")).put("sourceName", sourceName(entry.optString("sourceKey")))
        .put("vodId", entry.optString("vodId")).put("name", entry.optString("title")).put("pic", entry.optString("pic"))
    private fun titleKey(name: String) = name.trim().lowercase()
    fun historyFor(name: String) = home.optJSONArray("history").objects().firstOrNull { titleKey(it.optString("title")) == titleKey(name) }
    /** 观看记录按片名去重：同一部片换过片源时只留最近看的那条 */
    private fun recentHistory() = home.optJSONArray("history").objects().distinctBy { titleKey(it.optString("title")) }
    fun isFavorite(card: JSONObject) = home.optJSONArray("favorites").objects().any { it.optString("name").trim() == card.optString("name").trim() && it.optString("year") == card.optString("year") }

    // ---- 导航 ----
    private fun showTab(index: Int) {
        tab = index
        scrolls.forEachIndexed { i, s -> s.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { i, cell ->
            val on = i == index
            (cell.getChildAt(0) as android.widget.ImageView).setImageDrawable(PhoneIcon(tabs[i].second, if (on) kit.ink else kit.faint, activity.resources.displayMetrics.density))
            (cell.getChildAt(1) as TextView).setTextColor(if (on) kit.ink else kit.faint)
        }
        if (index == 2 && ready) renderSettings()
    }
    fun push(page: View, onClose: () -> Unit = {}, onBack: () -> Boolean = { false }) {
        hideKeyboard()
        overlays.addLast(Overlay(page, onClose, onBack))
        page.isClickable = true
        view.addView(page, view.childCount - 1, kit.flp())   // 启动幕始终在最上
        page.alpha = 0f; page.translationX = kit.dp(40).toFloat()
        page.animate().alpha(1f).translationX(0f).setDuration(180).start()
    }
    fun pop(page: View? = null) {
        val top = (if (page == null) overlays.lastOrNull() else overlays.lastOrNull { it.view === page }) ?: return
        overlays.remove(top); hideKeyboard(); top.onClose()
        top.view.animate().alpha(0f).translationX(kit.dp(40).toFloat()).setDuration(150).withEndAction { view.removeView(top.view) }.start()
    }
    /** 系统返回键；返回 false 时交给系统（退出应用） */
    fun back(): Boolean {
        val top = overlays.lastOrNull()
        if (top != null) { if (!top.onBack()) pop(); return true }
        if (tab != 0) { showTab(0); return true }
        return false
    }
    fun hideKeyboard() { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0) }
    fun showKeyboard(input: EditText) {
        input.requestFocus()
        input.post { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun input(hint: String, action: Int = EditorInfo.IME_ACTION_DONE) = EditText(activity).apply {
        this.hint = hint; setHintTextColor(kit.faint); setTextColor(kit.ink); textSize = 16f; setSingleLine(); imeOptions = action
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = kit.shape(kit.raised, 12); setPadding(kit.dp(14), 0, kit.dp(14), 0); minHeight = kit.dp(46)
    }
    private fun topBar(title: String, onBack: () -> Unit) = kit.row().apply {
        setPadding(kit.dp(4), 0, kit.dp(12), 0)
        addView(kit.icon(PhoneIcon.BACK) { onBack() })
        addView(kit.text(title, 17, kit.ink, 600, 1), LinearLayout.LayoutParams(0, -2, 1f))
    }

    private val wanted = java.util.WeakHashMap<android.widget.ImageView, String>()
    /** 加载海报；列表测量阶段创建的视图还没挂到窗口，等挂上后再加载 */
    fun loadPoster(image: android.widget.ImageView, url: String) {
        if (wanted[image] == url && image.drawable != null) return
        wanted[image] = url
        if (image.isAttachedToWindow) { posters.load(image, url); return }
        image.setImageDrawable(null)
        image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { image.removeOnAttachStateChangeListener(this); if (wanted[image] == url) posters.load(image, url) }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    // ---- 首页 ----
    private fun section(parent: LinearLayout, title: String, trailing: View? = null) {
        val row = kit.row().apply { addView(kit.heading(title), LinearLayout.LayoutParams(0, -2, 1f)); trailing?.let { addView(it) } }
        parent.addView(row, kit.lp(left = 20, right = 20, top = 28, bottom = 12))
    }
    private fun posterCard(title: String, pic: String, note: String, width: Int, onClick: () -> Unit): View {
        val card = kit.column().apply { isClickable = true; setOnClickListener { onClick() } }
        val image = kit.poster()
        card.addView(image, kit.lp(width, width * 3 / 2))
        card.addView(kit.text(title, 13, kit.ink, 500, 1), kit.lp(width, -2, top = 8))
        if (note.isNotBlank()) card.addView(kit.text(note, 11, kit.muted, lines = 1), kit.lp(width, -2, top = 3))
        loadPoster(image, pic)
        return card
    }
    private fun renderHome() {
        if (disposed) return
        homePage.removeAllViews()
        val tv = link.selected()
        val header = kit.row().apply { setPadding(kit.dp(20), kit.dp(14), kit.dp(8), 0) }
        header.addView(kit.text("SuperTV", 28, kit.ink, 700), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(kit.icon(PhoneIcon.TV, tint = if (tv != null) kit.ink else kit.muted) { tvSheet.show() })
        homePage.addView(header)
        val searchBar = kit.row().apply {
            background = kit.pressable(kit.surface, 14); setPadding(kit.dp(14), 0, kit.dp(14), 0); isClickable = true; setOnClickListener { openSearch("") }
            addView(android.widget.ImageView(activity).apply { setImageDrawable(PhoneIcon(PhoneIcon.SEARCH, kit.muted, activity.resources.displayMetrics.density)) }, kit.lp(20, 20))
            addView(kit.text("搜索电影、剧集、综艺", 15, kit.muted), kit.lp(-2, -2, left = 10))
        }
        homePage.addView(searchBar, kit.lp(-1, 46, left = 20, right = 20, top = 14))

        val history = recentHistory().take(12)
        if (history.isNotEmpty()) {
            section(homePage, "继续观看")
            val row = kit.row().apply { setPadding(kit.dp(20), 0, kit.dp(8), 0) }
            history.forEach { entry ->
                val card = kit.column().apply { isClickable = true; setOnClickListener { playerPage.open(historyItem(entry), entry.optInt("episodeIndex")) }; setOnLongClickListener { confirmRemoveHistory(entry); true } }
                val frame = FrameLayout(activity); kit.round(frame, 12)
                val image = kit.poster(12); frame.addView(image, kit.flp())
                val duration = entry.optDouble("duration", 0.0); val position = entry.optDouble("playbackPosition", 0.0)
                if (duration > 0) frame.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (position / duration * 1000).toInt().coerceIn(0, 1000)
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
                }, FrameLayout.LayoutParams(-1, kit.dp(3), Gravity.BOTTOM))
                card.addView(frame, kit.lp(132, 198))
                card.addView(kit.text(entry.optString("title"), 13, kit.ink, 500, 1), kit.lp(132, -2, top = 8))
                val total = entry.optInt("totalEpisodes")
                card.addView(kit.text(if (total > 1) "第 ${entry.optInt("episodeIndex") + 1} 集 · 共 $total 集" else sourceName(entry.optString("sourceKey")), 11, kit.muted, lines = 1), kit.lp(132, -2, top = 3))
                loadPoster(image, entry.optString("pic"))
                row.addView(card, kit.lp(-2, -2, right = 12))
            }
            homePage.addView(kit.strip(row))
        }
        fun hotRow(title: String, items: List<TvHotList.Item>) {
            if (items.isEmpty()) return
            section(homePage, title)
            val row = kit.row().apply { setPadding(kit.dp(20), 0, kit.dp(8), 0); gravity = Gravity.TOP }
            items.forEach { item ->
                row.addView(posterCard(item.title, item.cover, listOf(item.rate.takeIf { it.isNotBlank() && it != "0" }?.let { "豆瓣 $it" }.orEmpty(), item.note).filter { it.isNotBlank() }.joinToString(" · "), 112) {
                    openDetail(JSONObject().put("name", item.title).put("pic", item.cover).put("items", JSONArray()))
                }, kit.lp(-2, -2, right = 12))
            }
            homePage.addView(kit.strip(row))
        }
        hotRow("热门剧集", hotTv); hotRow("热门电影", hotMovie)
        if (history.isEmpty() && hotTv.isEmpty() && hotMovie.isEmpty())
            homePage.addView(kit.text("搜索片名开始观看", 15, kit.muted).apply { gravity = Gravity.CENTER }, kit.lp(top = 96))
        homePage.addView(View(activity), kit.lp(-1, 28))
    }

    // ---- 搜索 ----
    private var searchId: String? = null
    fun openSearch(initial: String) {
        val page = kit.column().apply { setBackgroundColor(Color.BLACK) }
        val field = input("搜索电影、剧集、综艺", EditorInfo.IME_ACTION_SEARCH)
        val bar = kit.row().apply { setPadding(kit.dp(4), kit.dp(8), kit.dp(16), kit.dp(8)) }
        bar.addView(kit.icon(PhoneIcon.BACK) { pop(page) })
        bar.addView(field, LinearLayout.LayoutParams(0, kit.dp(44), 1f))
        page.addView(bar)
        val status = kit.text("", 13, kit.muted)
        page.addView(status, kit.lp(left = 20, right = 20, bottom = 8))
        val recent = kit.column()
        val cards = mutableListOf<JSONObject>()
        val columns = 3; val gap = 12
        val grid = GridView(activity).apply {
            numColumns = columns; horizontalSpacing = kit.dp(gap); verticalSpacing = kit.dp(18); setPadding(kit.dp(20), kit.dp(4), kit.dp(20), kit.dp(24)); clipToPadding = false
            isVerticalScrollBarEnabled = false; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); visibility = View.GONE
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = cards.size
            override fun getItem(position: Int) = cards[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
                val card = cards[position]
                val cell = (convert as? LinearLayout) ?: kit.column().apply {
                    val frame = FrameLayout(activity); kit.round(frame, 10)
                    frame.addView(kit.poster(), kit.flp())
                    frame.addView(kit.text("", 10, Color.WHITE, 600).apply { background = kit.shape(Color.argb(170, 0, 0, 0), 6); setPadding(kit.dp(6), kit.dp(3), kit.dp(6), kit.dp(3)) },
                        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(kit.dp(6), 0, 0, kit.dp(6)) })
                    val width = (parent.width - kit.dp(40) - kit.dp(gap) * (columns - 1)) / columns
                    addView(frame, LinearLayout.LayoutParams(-1, width * 3 / 2))
                    addView(kit.text("", 13, kit.ink, 500, 1), kit.lp(top = 8))
                    addView(kit.text("", 11, kit.muted, lines = 1), kit.lp(top = 3))
                }
                val frame = cell.getChildAt(0) as FrameLayout; val image = frame.getChildAt(0) as android.widget.ImageView; val badge = frame.getChildAt(1) as TextView
                (cell.getChildAt(1) as TextView).text = card.optString("name")
                (cell.getChildAt(2) as TextView).text = listOf(card.optString("year"), card.optString("typeName"), "${card.optJSONArray("items")?.length() ?: 0} 个来源").filter { it.isNotBlank() }.joinToString(" · ")
                badge.text = card.optString("remarks"); badge.visibility = if (badge.text.isBlank()) View.GONE else View.VISIBLE
                loadPoster(image, card.optString("pic"))
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> cards.getOrNull(position)?.let { openDetail(it) } }
        fun renderRecent() {
            recent.removeAllViews()
            val names = home.optJSONArray("searches").objects().map { it.optString("text") }.filter { it.isNotBlank() }
            if (names.isEmpty()) return
            val head = kit.row()
            head.addView(kit.text("最近搜索", 15, kit.ink, 600), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(kit.text("清空", 13, kit.muted).apply { setPadding(kit.dp(8), kit.dp(8), 0, kit.dp(8)); setOnClickListener { backend.request("clear-search-history") { e -> e.optJSONObject("value")?.let { applyHome(it); renderRecent() } } } })
            recent.addView(head, kit.lp(left = 20, right = 20, top = 8))
            names.forEach { name ->
                recent.addView(kit.text(name, 15, kit.ink, lines = 1).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(kit.dp(20), 0, kit.dp(20), 0); background = kit.pressable(Color.BLACK, 0); isClickable = true
                    setOnClickListener { field.setText(name); field.setSelection(name.length); field.onEditorAction(EditorInfo.IME_ACTION_SEARCH) }
                }, kit.lp(-1, 48))
            }
        }
        fun run(raw: String) {
            val query = raw.trim().take(100)
            if (query.isEmpty()) return
            hideKeyboard(); field.clearFocus(); backend.cancel(searchId)
            recent.visibility = View.GONE; grid.visibility = View.VISIBLE; cards.clear(); adapter.notifyDataSetChanged()
            status.text = "正在搜索 ${config.optInt("enabled")} 个来源…"
            searchId = backend.request("search", JSONObject().put("query", query)) { event ->
                when (event.optString("event")) {
                    "search-history" -> event.optJSONObject("value")?.let { applyHome(it) }
                    "result", "search-progress" -> {
                        val value = event.optJSONObject("value") ?: return@request
                        cards.clear(); cards.addAll(value.optJSONArray("groups").objects()); adapter.notifyDataSetChanged()
                        val done = value.optBoolean("done")
                        status.text = if (!done) "正在搜索 ${value.optInt("total")} 个来源 · 已找到 ${cards.size} 部" else if (cards.isEmpty()) "没有找到相关影片，试试其他关键词" else "找到 ${cards.size} 部影片" + (value.optInt("failures").takeIf { it > 0 }?.let { " · $it 个源没有响应" } ?: "")
                    }
                    "error" -> status.text = event.optString("error")
                }
            }
        }
        field.setOnEditorActionListener { _, _, _ -> run(field.text.toString()); true }
        page.addView(kit.scroll(recent).also { s -> recent.tag = s }, LinearLayout.LayoutParams(-1, -2))
        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        renderRecent()
        push(page, onClose = { backend.cancel(searchId); searchId = null })
        if (initial.isBlank()) showKeyboard(field) else { field.setText(initial); run(initial) }
    }

    // ---- 详情 ----
    fun openDetail(card: JSONObject) {
        val page = kit.column().apply { setBackgroundColor(Color.BLACK) }
        page.addView(topBar("") { pop(page) })
        val body = kit.column().apply { setPadding(kit.dp(20), kit.dp(4), kit.dp(20), kit.dp(32)) }
        page.addView(kit.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        val requests = mutableListOf<String>()
        var current = card
        var selected: JSONObject? = null
        var picked = false
        var episodes = JSONArray()

        val head = kit.row().apply { gravity = Gravity.TOP }
        val image = kit.poster(12)
        head.addView(image, kit.lp(116, 174))
        val info = kit.column()
        val title = kit.text(card.optString("name"), 22, kit.ink, 700, 3)
        val meta = kit.text("", 13, kit.muted); val extra = kit.text("", 13, kit.muted, lines = 3)
        info.addView(title); info.addView(meta, kit.lp(top = 8)); info.addView(extra, kit.lp(top = 6))
        head.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = kit.dp(16) })
        body.addView(head)
        loadPoster(image, card.optString("pic"))

        val note = kit.text("", 13, kit.muted)
        val play = kit.button("播放", true, 16) {}
        val favorite = kit.icon(PhoneIcon.HEART, 48) {}.apply { background = kit.pressable(kit.raised, 24) }
        val cast = kit.icon(PhoneIcon.TV, 48) {}.apply { background = kit.pressable(kit.raised, 24) }
        val actions = kit.row()
        actions.addView(play, LinearLayout.LayoutParams(0, kit.dp(48), 1f))
        actions.addView(favorite, kit.lp(48, 48, left = 10)); actions.addView(cast, kit.lp(48, 48, left = 10))
        body.addView(actions, kit.lp(top = 20))
        body.addView(note, kit.lp(top = 10))
        val sourceTitle = kit.text("片源", 15, kit.ink, 600); val sourceRow = kit.row()
        body.addView(sourceTitle, kit.lp(top = 20, bottom = 10)); body.addView(kit.strip(sourceRow))
        val episodeTitle = kit.text("选集", 15, kit.ink, 600); val episodeGrid = android.widget.GridLayout(activity).apply { columnCount = 5 }
        body.addView(episodeTitle, kit.lp(top = 22, bottom = 10)); body.addView(episodeGrid)
        val descTitle = kit.text("简介", 15, kit.ink, 600); val desc = kit.text("", 14, kit.muted).apply { setLineSpacing(kit.dp(4f), 1f) }
        body.addView(descTitle, kit.lp(top = 22, bottom = 8)); body.addView(desc)
        listOf(sourceTitle, episodeTitle, episodeGrid, descTitle, desc).forEach { it.visibility = View.GONE }

        fun history() = historyFor(current.optString("name"))
        fun resumeIndex(): Int { val h = history() ?: return 0; return if (selected?.optString("sourceKey") == h.optString("sourceKey") || !picked) h.optInt("episodeIndex") else minOf(h.optInt("episodeIndex"), maxOf(0, episodes.length() - 1)) }
        fun refreshFavorite() { favorite.setImageDrawable(PhoneIcon(if (isFavorite(current)) PhoneIcon.HEART_ON else PhoneIcon.HEART, kit.ink, activity.resources.displayMetrics.density)) }
        fun refreshPlay() {
            val h = history()
            play.text = if (h == null) "播放" else if (h.optInt("totalEpisodes") > 1) "继续播放 · 第 ${h.optInt("episodeIndex") + 1} 集" else "继续播放"
            play.isEnabled = selected != null; play.alpha = if (selected != null) 1f else .4f
        }
        fun start(item: JSONObject, index: Int) { playerPage.open(item, index) }
        fun renderEpisodes(item: JSONObject) {
            episodeGrid.removeAllViews()
            val count = episodes.length()
            episodeTitle.visibility = if (count > 1) View.VISIBLE else View.GONE; episodeGrid.visibility = episodeTitle.visibility
            if (count <= 1) return
            episodeTitle.text = "选集 · 共 $count 集"
            val watched = history()?.takeIf { it.optString("sourceKey") == item.optString("sourceKey") && it.optString("vodId") == item.optString("vodId") }?.optInt("episodeIndex") ?: -1
            val width = (view.width - kit.dp(40) - kit.dp(8) * 4) / 5
            for (n in 0 until count) {
                val cell = kit.chip("${n + 1}") { start(item, n) }.apply { setPadding(0, 0, 0, 0) }
                kit.select(cell, n == watched)
                episodeGrid.addView(cell, android.widget.GridLayout.LayoutParams().apply { this.width = width; height = kit.dp(42); setMargins(0, 0, if (n % 5 == 4) 0 else kit.dp(8), kit.dp(8)) })
            }
        }
        val sourceChips = mutableListOf<Pair<JSONObject, TextView>>()
        fun select(item: JSONObject, byUser: Boolean = false) {
            selected = item; picked = picked || byUser; refreshPlay()
            sourceChips.forEach { (it, chip) -> kit.select(chip, it.optString("sourceKey") == item.optString("sourceKey") && it.optString("vodId") == item.optString("vodId")) }
            requests.forEach { backend.cancel(it) }; requests.clear()
            note.text = "正在读取 ${item.optString("sourceName")} 的剧集…"
            requests.add(backend.request("detail", JSONObject().put("item", item)) { event ->
                if (event.optString("event") == "error") { note.text = "${item.optString("sourceName")}：${event.optString("error")}（可换一个片源）"; return@request }
                val detail = event.optJSONObject("value")?.optJSONObject("detail") ?: return@request
                val video = detail.optJSONObject("videoInfo") ?: JSONObject()
                episodes = detail.optJSONArray("episodes") ?: JSONArray()
                note.text = if (episodes.length() == 0) "这个片源没有可播放的剧集，换一个试试" else ""
                meta.text = listOf(video.optString("year").ifBlank { current.optString("year") }, video.optString("area"), video.optString("typeName").ifBlank { current.optString("typeName") }, video.optString("remarks").ifBlank { current.optString("remarks") }).filter { it.isNotBlank() }.joinToString(" · ")
                extra.text = listOf(video.optString("director").takeIf { it.isNotBlank() }?.let { "导演：$it" }.orEmpty(), video.optString("actor").takeIf { it.isNotBlank() }?.let { "主演：$it" }.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
                @Suppress("DEPRECATION")
                desc.text = Html.fromHtml(video.optString("desc")).toString().trim().replace(Regex("\\s+"), " ")
                descTitle.visibility = if (desc.text.isBlank()) View.GONE else View.VISIBLE; desc.visibility = descTitle.visibility
                if (current.optString("pic").isBlank() && video.optString("cover").isNotBlank()) posters.load(image, video.optString("cover"))
                renderEpisodes(item)
            })
        }
        fun renderSourceChips() {
            val items = current.optJSONArray("items").objects(); val chosen = selected
            sourceRow.removeAllViews(); sourceChips.clear()
            sourceTitle.visibility = if (items.size > 1) View.VISIBLE else View.GONE
            if (items.size > 1) { sourceTitle.text = "片源 · ${items.size} 个"; items.forEach { item ->
                val chip = kit.chip(listOf(item.optString("sourceName"), item.optString("remarks")).filter { it.isNotBlank() }.joinToString(" · ")) { select(item, true) }
                kit.select(chip, chosen != null && item.optString("sourceKey") == chosen.optString("sourceKey") && item.optString("vodId") == chosen.optString("vodId"))
                sourceChips.add(item to chip); sourceRow.addView(chip, kit.lp(-2, 36, right = 8))
            } }
        }
        fun renderSources() {
            val items = current.optJSONArray("items").objects()
            renderSourceChips()
            val h = history()
            val preferred = items.firstOrNull { h != null && it.optString("sourceKey") == h.optString("sourceKey") && it.optString("vodId") == h.optString("vodId") }
            (preferred ?: items.firstOrNull())?.let { select(it) }
        }
        play.setOnClickListener {
            val item = selected ?: return@setOnClickListener
            val items = current.optJSONArray("items") ?: JSONArray()
            val h = history()
            // 没看过、也没手动选片源：先比较各来源的实测码率，播最清晰的
            if (h == null && !picked && items.length() > 1) {
                play.text = "正在比较 ${items.length()} 个片源…"; play.isEnabled = false
                requests.add(backend.request("best-source", JSONObject().put("items", items)) { event ->
                    play.isEnabled = true; refreshPlay()
                    val value = event.optJSONObject("value")
                    val best = value?.optJSONObject("item") ?: item
                    if (event.optString("event") == "result" && value?.optString("quality").orEmpty().isNotEmpty()) kit.toast("已选最清晰的片源：${best.optString("sourceName")} · ${value?.optString("quality")}")
                    start(best, 0)
                })
            } else start(item, resumeIndex())
        }
        favorite.setOnClickListener {
            backend.request("toggle-favorite", JSONObject().put("card", current)) { event ->
                val value = event.optJSONObject("value") ?: return@request
                value.optJSONObject("home")?.let { applyHome(it) }; refreshFavorite()
                kit.toast(if (value.optBoolean("favorite")) "已加入片库" else "已从片库移除")
            }
        }
        cast.setOnClickListener {
            val item = selected
            if (item == null) kit.toast("片源还没有准备好") else tvSheet.cast(item, resumeIndex(), 0.0)
        }
        refreshFavorite(); refreshPlay()
        if ((card.optJSONArray("items")?.length() ?: 0) > 0) renderSources()
        else {
            // 热门榜单只有片名：先在已启用的片源里找（海报沿用榜单的，片源给的常常打不开）
            note.text = "正在片源里查找「${card.optString("name")}」…"
            requests.add(backend.request("search", JSONObject().put("query", card.optString("name")).put("record", false)) { event ->
                if (event.optString("event") == "error") { note.text = event.optString("error"); return@request }
                val value = event.optJSONObject("value") ?: return@request
                val groups = value.optJSONArray("groups").objects()
                val match = groups.firstOrNull { titleKey(it.optString("name")) == titleKey(card.optString("name")) } ?: groups.firstOrNull { it.optString("name").contains(card.optString("name")) }
                if (match != null && selected == null) { current = JSONObject(match.toString()).apply { if (card.optString("pic").isNotBlank()) put("pic", card.optString("pic")) }; title.text = current.optString("name"); refreshFavorite(); renderSources() }
                else if (match != null && (match.optJSONArray("items")?.length() ?: 0) > (current.optJSONArray("items")?.length() ?: 0) && match.optString("name") == current.optString("name")) {
                    // 其他片源陆续返回：补进片源列表，不打断已经选中的那个
                    current.put("items", match.optJSONArray("items")); current.put("key", match.optString("key")); renderSourceChips()
                }
                else if (match == null && value.optBoolean("done")) {
                    note.text = "已启用的片源里没有找到这部影片"
                    if (groups.isNotEmpty()) body.addView(kit.button("查看 ${groups.size} 个相近结果") { pop(page); openSearch(card.optString("name")) }, kit.lp(-2, 44, top = 12))
                }
            })
        }
        push(page, onClose = { requests.forEach { backend.cancel(it) } })
    }

    // ---- 片库 ----
    private fun renderLibrary() {
        if (disposed) return
        libraryPage.removeAllViews()
        libraryPage.addView(kit.text("片库", 28, kit.ink, 700), kit.lp(left = 20, right = 20, top = 14))
        val favorites = home.optJSONArray("favorites").objects(); val history = recentHistory()
        if (favorites.isEmpty() && history.isEmpty()) { libraryPage.addView(kit.text("收藏的影片和看过的记录会出现在这里", 15, kit.muted).apply { gravity = Gravity.CENTER }, kit.lp(top = 120)); return }
        if (favorites.isNotEmpty()) {
            section(libraryPage, "我的收藏")
            val row = kit.row().apply { setPadding(kit.dp(20), 0, kit.dp(8), 0); gravity = Gravity.TOP }
            favorites.forEach { card -> row.addView(posterCard(card.optString("name"), card.optString("pic"), listOf(card.optString("year"), card.optString("remarks")).filter { it.isNotBlank() }.joinToString(" · "), 112) { openDetail(card) }, kit.lp(-2, -2, right = 12)) }
            libraryPage.addView(kit.strip(row))
        }
        if (history.isNotEmpty()) {
            section(libraryPage, "观看记录", kit.text("清空", 14, kit.muted).apply { setPadding(kit.dp(12), kit.dp(8), 0, kit.dp(8)); setOnClickListener { confirmClearHistory() } })
            history.forEach { entry ->
                val row = kit.row().apply { background = kit.pressable(Color.BLACK, 0); isClickable = true; setPadding(kit.dp(20), kit.dp(8), kit.dp(8), kit.dp(8)); setOnClickListener { playerPage.open(historyItem(entry), entry.optInt("episodeIndex")) }
                    setOnLongClickListener { confirmRemoveHistory(entry); true } }
                val image = kit.poster(8); row.addView(image, kit.lp(54, 81))
                val textColumn = kit.column()
                textColumn.addView(kit.text(entry.optString("title"), 15, kit.ink, 600, 1))
                val total = entry.optInt("totalEpisodes"); val duration = entry.optDouble("duration", 0.0); val position = entry.optDouble("playbackPosition", 0.0)
                textColumn.addView(kit.text(listOf(if (total > 1) "第 ${entry.optInt("episodeIndex") + 1} 集" else "", sourceName(entry.optString("sourceKey"))).filter { it.isNotBlank() }.joinToString(" · "), 13, kit.muted, lines = 1), kit.lp(top = 6))
                if (duration > 0) textColumn.addView(kit.text(if (position >= duration - 15) "已看完" else "看到 ${kit.time(position)} / ${kit.time(duration)}", 12, kit.faint), kit.lp(top = 6))
                row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = kit.dp(14) })
                row.addView(kit.icon(PhoneIcon.CLOSE, 44, kit.faint) { confirmRemoveHistory(entry) })
                loadPoster(image, entry.optString("pic"))
                libraryPage.addView(row)
            }
        }
        libraryPage.addView(View(activity), kit.lp(-1, 28))
    }

    /** 确认面板：说明 + 一个执行按钮 + 取消 */
    private fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        val (dialog, body) = kit.sheet(title)
        body.addView(kit.text(message, 14, kit.muted).apply { setLineSpacing(kit.dp(3f), 1f) })
        body.addView(kit.button(action, true) { dialog.dismiss(); onConfirm() }, kit.lp(-1, 46, top = 18))
        body.addView(kit.button("取消") { dialog.dismiss() }, kit.lp(-1, 46, top = 10))
        dialog.show()
    }
    private fun confirmRemoveHistory(entry: JSONObject) {
        // 同一部片换过片源时各有一条记录，界面上合并成一条，删除时一起删
        val same = home.optJSONArray("history").objects().filter { titleKey(it.optString("title")) == titleKey(entry.optString("title")) }
        confirm("删除观看记录", "删除「${entry.optString("title")}」的观看记录和播放进度。${if (link.selected() != null) "已连接的电视上也会同步删除。" else ""}", "删除") {
            backend.request("remove-history", JSONObject().put("items", JSONArray(same.map { JSONObject().put("sourceKey", it.optString("sourceKey")).put("vodId", it.optString("vodId")) }))) { event ->
                event.optJSONObject("value")?.let { applyHome(it); kit.toast("已删除") }
            }
        }
    }
    private fun confirmClearHistory() {
        confirm("清空观看记录", "删除全部观看记录和播放进度，片库收藏不受影响。${if (link.selected() != null) "已连接的电视上也会同步清空。" else ""}此操作无法撤销。", "全部清空") {
            backend.request("clear-history") { event -> event.optJSONObject("value")?.let { applyHome(it); kit.toast("已清空") } }
        }
    }

    /** 读剪贴板 → 先说明认出了什么 → 确认后导入并报告结果 */
    private fun importFromClipboard() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) { kit.toast("剪贴板里没有文字，先复制片源或订阅地址"); return }
        backend.request("preview-paste", JSONObject().put("text", text)) { event ->
            if (event.optString("event") == "error") { confirm("没有可导入的内容", event.optString("error") + "\n\n可以粘贴：\n· 每行一个采集接口地址（前面可带名称）\n· 每行一个订阅地址\n· 整段片源列表或 TVBox 配置", "知道了") {}; return@request }
            val value = event.optJSONObject("value") ?: return@request
            val sources = value.optJSONArray("sources").objects(); val fresh = value.optInt("newSources"); val subscriptions = value.optJSONArray("subscriptions").strings()
            val (dialog, body) = kit.sheet("从剪贴板导入", tall = sources.size + subscriptions.size > 6)
            val summary = listOf(if (sources.isNotEmpty()) "${sources.size} 个片源接口" + (if (fresh < sources.size) "（其中 ${sources.size - fresh} 个已存在，会跳过）" else "") else "", if (subscriptions.isNotEmpty()) "${subscriptions.size} 个订阅地址" else "").filter { it.isNotEmpty() }.joinToString("、")
            body.addView(kit.text("识别到 $summary", 15, kit.ink, 600))
            value.optString("note").takeIf { it.isNotBlank() }?.let { body.addView(kit.text(it, 12, kit.muted), kit.lp(top = 6)) }
            val status = kit.text("", 13, kit.muted)
            lateinit var go: TextView
            go = kit.button("导入", true) {
                go.isEnabled = false; go.alpha = .5f; status.text = if (subscriptions.isNotEmpty()) "正在导入并同步订阅…" else "正在导入…"
                backend.request("import-paste", JSONObject().put("text", text)) { done ->
                    if (done.optString("event") == "error") { status.text = done.optString("error"); go.isEnabled = true; go.alpha = 1f; return@request }
                    val r = done.optJSONObject("value") ?: return@request
                    r.optJSONObject("config")?.let { applyConfig(it) }
                    val failed = r.optJSONArray("failed").objects()
                    val parts = listOf(if (r.optInt("added") > 0) "新增 ${r.optInt("added")} 个片源" else "", if (r.optInt("skipped") > 0) "跳过 ${r.optInt("skipped")} 个已存在的" else "",
                        if (r.optInt("subscriptions") > 0) "同步 ${r.optInt("subscriptions")} 个订阅（${r.optInt("subscriptionSources")} 个片源）" else "").filter { it.isNotEmpty() }
                    if (failed.isEmpty()) { dialog.dismiss(); kit.toast(parts.joinToString("，").ifEmpty { "没有新内容" }) }
                    else { go.visibility = View.GONE; status.text = (parts + "${failed.size} 个订阅失败：").joinToString("，") + "\n" + failed.joinToString("\n") { "· ${it.optString("url").take(48)}\n  ${it.optString("error")}" } }
                }
            }
            body.addView(go, kit.lp(-1, 46, top = 14)); body.addView(status, kit.lp(top = 10))
            (sources.map { it.optString("name") to it.optString("url") } + subscriptions.map { "订阅" to it }).take(60).forEach { (name, url) ->
                val row = kit.column().apply { setPadding(0, kit.dp(8), 0, kit.dp(8)) }
                row.addView(kit.text(name, 14, kit.ink, 500, 1)); row.addView(kit.text(url, 12, kit.faint, lines = 1), kit.lp(top = 3))
                body.addView(row)
            }
            dialog.show()
        }
    }

    private fun confirmRemoveSource(source: JSONObject) {
        val key = source.optString("key")
        val note = if (key.startsWith("env_")) "这是内置片源，删除后不再显示和搜索，可以在片源列表底部恢复。" else if (key.startsWith("sub_")) "它来自订阅，删除后该订阅再次同步也不会把它加回来。" else "删除后需要重新添加才能找回。"
        confirm("删除片源", "删除「${source.optString("name")}」。$note\n已有的观看记录不受影响。", "删除") {
            backend.request("remove-source", JSONObject().put("key", key)) { event ->
                if (event.optString("event") == "error") kit.toast(event.optString("error")) else event.optJSONObject("value")?.let { applyConfig(it); kit.toast("已删除") }
            }
        }
    }
    private fun addSubscription(after: () -> Unit = {}) {
        prompt("添加订阅", listOf("订阅地址" to ""), "同步") { values, done ->
            backend.request("sync-subscription", JSONObject().put("url", values[0])) { event ->
                if (event.optString("event") == "error") done(event.optString("error")) else backend.request("config") { e -> e.optJSONObject("value")?.let { applyConfig(it) }; done(null); kit.toast("订阅已同步"); after() }
            }
        }
    }
    /** 订阅列表：重新同步、删除（连同它带来的片源）、再加一个 */
    private fun manageSubscriptions() {
        val (dialog, body) = kit.sheet("订阅片源列表", tall = true)
        fun render() {
            body.removeAllViews()
            val list = config.optJSONArray("subscriptions").objects()
            if (list.isEmpty()) body.addView(kit.text("还没有订阅", 14, kit.muted), kit.lp(top = 8))
            list.forEach { sub ->
                val url = sub.optString("url")
                val card = kit.column().apply { background = kit.shape(kit.raised, 12); setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(10)) }
                card.addView(kit.text(sub.optString("name").ifBlank { runCatching { java.net.URI(url).host }.getOrNull() ?: "订阅" }, 15, kit.ink, 600, 1))
                card.addView(kit.text(url, 12, kit.faint, lines = 1), kit.lp(top = 4))
                val status = kit.text(if (sub.optString("lastStatus") == "error") "上次同步失败：${sub.optString("lastError")}" else "${sub.optJSONObject("lastCounts")?.optInt("vod") ?: 0} 个片源", 12, kit.muted, lines = 2)
                card.addView(status, kit.lp(top = 6))
                val actions = kit.row()
                actions.addView(kit.chip("重新同步") {
                    status.text = "正在同步…"
                    backend.request("sync-subscription", JSONObject().put("url", url)) { event ->
                        if (event.optString("event") == "error") status.text = "同步失败：${event.optString("error")}"
                        else backend.request("config") { e -> e.optJSONObject("value")?.let { applyConfig(it) }; kit.toast("已同步"); render() }
                    }
                }, kit.lp(-2, 34, right = 8))
                actions.addView(kit.chip("删除") {
                    confirm("删除订阅", "删除这个订阅和它带来的全部片源。", "删除") {
                        backend.request("remove-subscription", JSONObject().put("url", url)) { event -> event.optJSONObject("value")?.let { applyConfig(it); kit.toast("已删除"); render() } }
                    }
                }, kit.lp(-2, 34))
                card.addView(actions, kit.lp(top = 10))
                body.addView(card, kit.lp(top = 10))
            }
            body.addView(kit.button("添加订阅", true) { addSubscription { render() } }, kit.lp(-1, 46, top = 16))
        }
        render(); dialog.show()
    }

    // ---- 设置 ----
    private var settingsTab = 0
    private fun group(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(kit.text(title, 13, kit.muted, 600), kit.lp(left = 32, right = 32, top = 26, bottom = 8))
        return kit.column().apply { background = kit.shape(kit.surface, 14); kit.round(this, 14) }.also { parent.addView(it, kit.lp(left = 20, right = 20)) }
    }
    private fun line(group: LinearLayout, title: String, subtitle: String = "", trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        if (group.childCount > 0) group.addView(kit.divider(), LinearLayout.LayoutParams(-1, 1).apply { leftMargin = kit.dp(16) })
        val row = kit.row().apply { setPadding(kit.dp(16), kit.dp(12), kit.dp(12), kit.dp(12)); minimumHeight = kit.dp(54) }
        if (onClick != null) { row.background = kit.pressable(kit.surface, 0); row.isClickable = true; row.setOnClickListener { onClick() } }
        val labels = kit.column(); labels.addView(kit.text(title, 16, kit.ink, lines = 2))
        if (subtitle.isNotBlank()) labels.addView(kit.text(subtitle, 12, kit.muted, lines = 2), kit.lp(top = 4))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); trailing?.let { row.addView(it, kit.lp(-2, -2, left = 10)) }
        group.addView(row); return row
    }
    private fun toggle(on: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(activity).apply {
        isChecked = on
        thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.rgb(174, 174, 178)))
        trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.rgb(142, 142, 147), Color.rgb(58, 58, 60)))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
    private fun prompt(title: String, fields: List<Pair<String, String>>, confirm: String, onDone: (List<String>, (String?) -> Unit) -> Unit) {
        val (dialog, body) = kit.sheet(title)
        val inputs = fields.map { (hint, value) -> input(hint).apply { setText(value) }.also { body.addView(it, kit.lp(-1, 46, top = 10)) } }
        val status = kit.text("", 13, kit.muted); body.addView(status, kit.lp(top = 10))
        lateinit var ok: TextView
        ok = kit.button(confirm, true) {
            ok.isEnabled = false; status.text = "请稍候…"
            onDone(inputs.map { it.text.toString().trim() }) { error -> if (error == null) dialog.dismiss() else { ok.isEnabled = true; status.text = error } }
        }
        body.addView(ok, kit.lp(-1, 46, top = 14))
        dialog.show(); showKeyboard(inputs.first())
    }
    private fun renderSettings() {
        if (disposed) return
        val scrollY = scrolls[2].scrollY
        settingsPage.removeAllViews()
        settingsPage.addView(kit.text("设置", 28, kit.ink, 700), kit.lp(left = 20, right = 20, top = 14))
        val tv = link.selected()
        val tvGroup = group(settingsPage, "电视")
        line(tvGroup, tv?.name ?: "连接电视", if (tv != null) "已连接 · 观看记录和片库自动同步" else "投屏、遥控、替电视打字，并同步观看记录", kit.text(if (tv != null) "遥控" else "连接", 14, kit.muted)) { tvSheet.show() }

        val playGroup = group(settingsPage, "播放")
        fun setting(key: String, title: String, subtitle: String, fallback: Boolean) = line(playGroup, title, subtitle, toggle(config.optBoolean(key, fallback)) { on ->
            backend.request("settings", JSONObject().put(key, on)) { event -> event.optJSONObject("value")?.let { config = it; home.put("config", it) } }
        })
        setting("adFilter", "去除插播广告", "自动识别并跳过片源里夹带的广告片段", true)
        setting("autoplayNext", "自动播放下一集", "", true)
        if (config.optInt("learnedAds") > 0) line(playGroup, "清除已学习的广告", "已记住 ${config.optInt("learnedAds")} 段") { backend.request("clear-learned-ads") { e -> e.optJSONObject("value")?.let { applyConfig(it); kit.toast("已清除") } } }

        val sources = config.optJSONArray("sources").objects(); val selectedKeys = config.optJSONArray("selectedKeys").strings().toSet()
        val sourceGroup = group(settingsPage, "片源 · 已启用 ${config.optInt("enabled")} / ${sources.size} · 长按可删除")
        sources.forEach { source ->
            val key = source.optString("key")
            val origin = if (key.startsWith("env_")) "" else if (key.startsWith("sub_")) "订阅 · " else "自己添加 · "
            line(sourceGroup, source.optString("name"), origin + runCatching { java.net.URI(source.optString("url")).host }.getOrNull().orEmpty(), toggle(key in selectedKeys) {
                backend.request("toggle-source", JSONObject().put("key", key)) { event -> event.optJSONObject("value")?.let { config = it; home.put("config", it) } }
            }).apply {
                background = kit.pressable(kit.surface, 0); isLongClickable = true
                setOnLongClickListener { confirmRemoveSource(source); true }
            }
        }
        if (config.optInt("hiddenSources") > 0) line(sourceGroup, "恢复删除的内置片源", "已删除 ${config.optInt("hiddenSources")} 个") {
            backend.request("restore-sources") { event -> event.optJSONObject("value")?.let { applyConfig(it); kit.toast("已恢复 ${it.optInt("restored")} 个片源") } }
        }
        line(sourceGroup, "添加片源", "填写采集接口地址") {
            prompt("添加片源", listOf("名称（可不填）" to "", "接口地址，以 http:// 或 https:// 开头" to ""), "添加") { values, done ->
                backend.request("add-source", JSONObject().put("name", values[0]).put("url", values[1])) { event ->
                    if (event.optString("event") == "error") done(event.optString("error")) else { event.optJSONObject("value")?.let { applyConfig(it) }; done(null); kit.toast("已添加") }
                }
            }
        }
        line(sourceGroup, "从剪贴板批量导入", "支持多行接口地址、订阅地址，或整段配置内容") { importFromClipboard() }
        val subscriptions = config.optJSONArray("subscriptions")
        line(sourceGroup, "订阅片源列表", if ((subscriptions?.length() ?: 0) > 0) "已订阅 ${subscriptions?.length()} 个 · 点开管理" else "填写订阅地址，一次导入多个片源") {
            if ((config.optJSONArray("subscriptions")?.length() ?: 0) > 0) manageSubscriptions() else addSubscription()
        }
        val about = group(settingsPage, "关于")
        line(about, "SuperTV", "版本 ${BuildConfig.VERSION_NAME}")
        settingsPage.addView(View(activity), kit.lp(-1, 32))
        scrolls[2].post { scrolls[2].scrollTo(0, scrollY) }
    }

    fun release() {
        disposed = true; main.removeCallbacksAndMessages(null)
        playerPage.release(); tvSheet.release(); posters.close(); hot.close()
    }
}
