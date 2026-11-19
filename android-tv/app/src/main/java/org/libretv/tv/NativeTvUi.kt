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
internal class NativeTvUi(private val activity: Activity, private val backend: org.libretv.tv.core.CoreBackend, private val player: NativePlayerHost, private val retryBoot: () -> Unit) {
    private val ui = TvWidgets(activity)
    private val main = Handler(Looper.getMainLooper())
    private val posters = TvPosterLoader()
    val view = FrameLayout(activity)
    private val home = ui.column().apply { setPadding(ui.px(38), ui.px(17), ui.px(38), ui.px(38)) }
    private val homeScroll = ScrollView(activity).apply { isFillViewport = true; isFocusable = false; clipChildren = false; clipToPadding = false; addView(home) }
    private val modeBadge=ui.text("独立运行",12,ui.muted).apply { letterSpacing=.08f; gravity=Gravity.BOTTOM; ui.fontWeight(this,500) }
    private val sourceCount = ui.text("正在加载…", 13, ui.muted)
    private val backdrop = TvBackdrop(activity)
    // 片头黑幕：冷启动和每次开始播放时落下，画面就绪后淡出
    val curtain = TvCurtain(ui)
    private var curtainSession = ""
    private val liftCurtain = Runnable { curtain.lift() }
    // 首页大图推荐：内容跟随卡片焦点（稍作延迟，快速划过时不闪）
    private val hero: TvHero = TvHero(ui, posters, { showStill(it) }) { if (stillKey != hero.current?.key) backdrop.show(it) }
    // 背景当前显示的是哪一部的剧照；为空表示显示的是柔光（模糊海报）
    private var stillKey: String? = null
    // 主推轮播：最近看的一部加本周热门的前几部；焦点停在主推按钮上时每 8 秒换一部
    private var slides = emptyList<TvHero.Feature>()
    private val rotate = object : Runnable { override fun run() {
        main.postDelayed(this, 8000)
        // 焦点在某张影片卡片上时主推跟着卡片走，不轮播
        if (page != "home" || active != null || panel != null || searched.isNotEmpty() || slides.size < 2 || !view.hasWindowFocus() || view.findFocus()?.let { featureOf[it] } != null) return
        val index = slides.indexOfFirst { it.key == hero.current?.key }
        hero.show(slides[(index + 1) % slides.size])
    } }
    private fun showStill(feature: TvHero.Feature) {
        hero.page(slides.size, slides.indexOfFirst { it.key == feature.key })
        if (stillKey == feature.key) return
        stillKey = null
        if (feature.doubanId.isEmpty()) return
        hot.still(feature.doubanId, feature.tv) { url -> if (url != null && hero.current?.key == feature.key && page == "home") { stillKey = feature.key; backdrop.showStill(url) } }
    }
    // 顶栏三个页面（首页 / 片库 / 已下载）加搜索页；同一时刻只显示一个
    private var page = "home"
    private val tabs = linkedMapOf<String, Button>()
    private val homePage = ui.column()
    private val searchPage = ui.column().apply { visibility = View.GONE }
    private val favPage = ui.column().apply { visibility = View.GONE }
    private val downloadsPage = ui.column().apply { visibility = View.GONE }
    private val favorites = TvFavorites(activity)
    private val hot = TvHotList(activity)
    private var hotItems = emptyList<TvHotList.Item>()
    private val watchSection = ui.column().apply { visibility = View.GONE }
    private val watchCount = ui.text("", 13, ui.muted)
    private val hotSection = ui.column()
    private val favTitle = ui.text("我的片库", 34).apply { ui.fontWeight(this, 300) }
    private val favCount = ui.text("", 13, ui.muted)
    private val favGrid = ui.grid(6)
    private val downloadsHint = ui.text("", 14, ui.muted)
    private val featureOf = java.util.WeakHashMap<View, TvHero.Feature>()
    private var pendingFeature: TvHero.Feature? = null
    private val applyFeature = Runnable { pendingFeature?.let { if(active==null && searched.isEmpty()) hero.show(it) } }
    private val downloadsButton = ui.button("电脑下载") { showDownloads() }
    private val search = ui.input("搜索电影、剧集或动漫")
    private val searchButton = ui.button("搜索") { startSearch(search.text.toString()) }
    private var historyFocusKey: String? = null
    private val historySection = ui.column()
    // 首页「已下载」：电脑片库里的影片，确认键直接播放已处理好的版本
    private val librarySection = ui.column().apply { visibility = View.GONE }
    private val libraryTitle = ui.text("已下载", 22, bold = true).apply { ui.fontWeight(this,700) }
    private val libraryRow = ui.row().apply { gravity = Gravity.TOP }
    // 历史观看：横向货架，一行显示全部，左右滚动
    private val watchRow = ui.row().apply { gravity = Gravity.TOP }
    private val watchShelf = shelf(watchRow)
    private var libraryKeys = emptyList<String>()
    private val libraryIds = mutableMapOf<View,String>()
    private val playerDownloadButton = ui.button("下载") { downloadCurrent() }
    private val historyRow = ui.grid(5)
    private var searchNames: List<String>? = null
    private val clearSearches = ui.button("✕  清空搜索记录") { backend.request("clear-search-history") { refreshHome() } }.apply {
        background=ui.focusedBackground(Color.TRANSPARENT,16); gravity=Gravity.CENTER; setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.rgb(20,20,24),Color.rgb(150,150,158))))
        setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(12).toFloat()); minHeight=0; minimumHeight=0; setPadding(ui.px(12),ui.px(4),ui.px(12),ui.px(4)); isSingleLine=true
    }
    private val statusPrefix = ui.text("电视模式", 22, bold = true).apply { ui.fontWeight(this,700) }
    private val status = ui.text("输入片名，按遥控器确认键搜索", 14, ui.muted)
    private val grid = ui.grid(5).apply { id = View.generateViewId() }
    private val empty = ui.column().apply { gravity = Gravity.CENTER; minimumHeight = ui.px(243) }
    private val emptyHint = ui.text("搜索并播放影片后，这里会显示观看记录",16,ui.muted)
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
    // 播放中换集：选中后立刻暂停当前这集、显示加载片头；取消或失败时回到原来那集（原来在播就接着播）
    private var switching = false
    private var resumeAfterSwitch = false
    private var keepLoading = false
    private var disposed = false
    private val playback = FrameLayout(activity).apply { isFocusable = true; isFocusableInTouchMode = true; visibility = View.GONE; id = View.generateViewId() }
    private val buffer=ui.column().apply { gravity=Gravity.CENTER; visibility=View.GONE }
    private val bufferSpinner=android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleSmall).apply { indeterminateTintList=android.content.res.ColorStateList.valueOf(Color.rgb(217,227,239)) }
    private val bufferTitle=ui.text("正在缓冲",16,Color.rgb(217,227,239))
    private val bufferSpeed=ui.text("",19,Color.WHITE)
    private val bufferNote=ui.text("",12,Color.rgb(194,207,223))
    private val bufferDetail=ui.text("",10,Color.rgb(150,168,191))
    private var bufferingSince=0L
    private val seekOverlay=ui.column().apply { gravity=Gravity.CENTER; setPadding(ui.px(40),ui.px(18),ui.px(40),ui.px(18)); background=ui.background(Color.argb(210,28,28,32),radius=24); visibility=View.GONE }
    private val seekTime=ui.text("",38,bold=true)
    // 播放器：上下两条柔和的渐暗区；片名与处理信息在上，进度条与胶囊按钮在下
    private val controls=ui.column().apply { setPadding(ui.px(56),ui.px(72),ui.px(56),ui.px(26)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(170,0,0,0),Color.argb(230,0,0,0))) }
    private val top=ui.column().apply { setPadding(ui.px(56),ui.px(28),ui.px(56),ui.px(56)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(190,0,0,0),Color.TRANSPARENT)) }
    private val playerTitle=ui.text("",30,bold=true).apply { isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val playerSubtitle=ui.text("",13,Color.rgb(200,200,206))
    private val playerProcessingVideo=ui.text("",11,Color.rgb(170,170,178))
    private val playerProcessingAds=ui.text("",11,Color.rgb(170,170,178))
    private val time=ui.text("00:00",13,Color.rgb(225,225,230))
    private val duration=ui.text("00:00",13,Color.rgb(225,225,230))
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
        view.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        view.addView(homeScroll, FrameLayout.LayoutParams(-1, -1))
        view.addView(playback, FrameLayout.LayoutParams(-1, -1))
        ui.decorate(view)
        buildHome(); buildPlayback()
        home.alpha=0f
        curtain.raise("私人放映厅","SuperTV",status="正在准备放映…",instant=true)
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            main.removeCallbacks(prefetchFocused)
            if (focused != null && cardOf[focused] != null) main.postDelayed(prefetchFocused, 800)
            if (focused != null && active == null) featureOf[focused]?.let { pendingFeature=it; main.removeCallbacks(applyFeature); main.postDelayed(applyFeature,140) }
            // 焦点移到影片卡片：背景换成该片海报的模糊大图
            if (focused != null && active == null && focused.contentDescription != null && focused is LinearLayout && focused.childCount > 0 && focused.getChildAt(0) is FrameLayout)
                ((focused.getChildAt(0) as FrameLayout).getChildAt(0) as? ImageView)?.drawable?.let { if (stillKey == null || stillKey != featureOf[focused]?.key) { stillKey = null; backdrop.show(it) } }
        }
        ui.fontWeight(playerTitle,800); ui.fontWeight(bufferSpeed,500); ui.fontWeight(seekTime,600)
        // 往下翻到海报行时把背景压暗，免得清晰的剧照和海报抢画面
        homeScroll.setOnScrollChangeListener { _, _, y, _, _ -> backdrop.alpha = 1f - .8f * (y / ui.px(220).toFloat()).coerceIn(0f, 1f) }
        main.postDelayed(rotate, 8000)
    }
    private fun sectionTitle(label: String, trailing: View? = null) = ui.row().apply {
        setPadding(0,ui.px(10),0,ui.px(5))
        addView(ui.text(label,20).apply { ui.fontWeight(this,500) },ui.margins(-2,-2).apply { rightMargin=ui.px(14) })
        if(trailing!=null) addView(trailing,LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun buildHome() {
        // 顶栏：品牌 · 居中的页面切换（首页 / 片库 / 已下载）· 搜索与设置图标
        val header = FrameLayout(activity).apply { clipChildren=false }
        val brand = ui.row()
        brand.addView(ui.text("SuperTV",21,bold=true).apply { letterSpacing=.06f; ui.fontWeight(this,700) },ui.margins(-2,-2))
        brand.addView(modeBadge,ui.margins(-2,-2).apply { leftMargin=ui.px(12) })
        header.addView(brand, FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL or Gravity.LEFT))
        val segment = ui.row().apply { background=ui.background(Color.argb(26,255,255,255),radius=24); setPadding(ui.px(4),ui.px(4),ui.px(4),ui.px(4)) }
        listOf("home" to "首页","library" to "片库","downloads" to "已下载").forEach { (key,label) ->
            val tab=ui.button(label) { showPage(key) }.apply {
                background=ui.focusedBackground(Color.TRANSPARENT,20); setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(14).toFloat()); ui.fontWeight(this,600); setPadding(ui.px(22),0,ui.px(22),0)
            }
            tabs[key]=tab; segment.addView(tab,ui.margins(-2,38))
        }
        header.addView(segment, FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val actions = ui.row()
        actions.addView(ui.iconButton(TvIcon.SEARCH,"搜索") { showPage("search"); search.requestFocus() },ui.margins(40,40).apply { rightMargin=ui.px(10) })
        actions.addView(ui.iconButton(TvIcon.SETTINGS,"设置") { showSourceSettings() },ui.margins(40,40))
        header.addView(actions, FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL or Gravity.RIGHT))
        home.addView(header, ui.margins(-1,48).apply { bottomMargin=ui.px(12) })

        // 首页：大图推荐 · 继续观看 · 本周热门
        homePage.addView(hero, ui.margins(-1,350))
        watchSection.addView(sectionTitle("继续观看",watchCount),ui.margins(-1,52).apply { topMargin=ui.px(6) })
        watchSection.addView(watchShelf,shelfParams())
        homePage.addView(watchSection); homePage.addView(hotSection)
        home.addView(homePage)

        // 搜索页：大输入框 · 最近搜索 · 结果
        searchPage.addView(ui.text("搜索",11,ui.muted).apply { letterSpacing=.28f },ui.margins(-1,-2).apply { topMargin=ui.px(18); bottomMargin=ui.px(10) })
        val form=ui.row()
        search.imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        search.setOnEditorActionListener { _, action, event ->
            if (action==EditorInfo.IME_ACTION_SEARCH || event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_DOWN) { startSearch(search.text.toString()); true } else false
        }
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(22).toFloat())
        form.addView(search, LinearLayout.LayoutParams(0,ui.px(60),1f).apply { rightMargin=ui.px(12) })
        searchButton.isEnabled=false; searchButton.background=ui.focusedBackground(radius=30); searchButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(16).toFloat()); ui.fontWeight(searchButton,600)
        form.addView(searchButton,ui.margins(120,60)); searchPage.addView(form)
        historySection.addView(ui.text("最近搜索",13,ui.muted).apply { ui.fontWeight(this,600) },ui.margins(-1,26).apply { bottomMargin=ui.px(6) })
        historySection.addView(historyRow)
        searchPage.addView(historySection,ui.margins(-1,-2).apply { topMargin=ui.px(16) })
        val statusRow=ui.row().apply { setPadding(0,ui.px(10),0,ui.px(5)) }
        statusRow.addView(statusPrefix,ui.margins(-2,-2).apply { rightMargin=ui.px(14) }); statusRow.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        searchPage.addView(statusRow,ui.margins(-1,52).apply { topMargin=ui.px(6) })
        searchPage.addView(grid)
        more.apply { visibility=View.GONE; background=ui.focusedBackground(radius=25) }
        searchPage.addView(more,ui.margins(288,50).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=ui.px(22) })
        val circle=ui.text("＋",32,ui.muted).apply { gravity=Gravity.CENTER; background=ui.background(ui.glass,Color.TRANSPARENT,44) }
        empty.addView(circle,ui.margins(88,88).apply { gravity=Gravity.CENTER_HORIZONTAL })
        empty.addView(emptyHint,ui.margins(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=ui.px(16) })
        empty.visibility=View.GONE
        searchPage.addView(empty,ui.margins(-1,243))
        home.addView(searchPage)

        // 片库页：收藏的影片
        favPage.addView(ui.text("我的收藏",11,ui.muted).apply { letterSpacing=.28f },ui.margins(-1,-2).apply { topMargin=ui.px(18) })
        favPage.addView(favTitle,ui.margins(-1,-2).apply { topMargin=ui.px(6) })
        favPage.addView(favCount,ui.margins(-1,-2).apply { topMargin=ui.px(6); bottomMargin=ui.px(18) })
        favPage.addView(favGrid)
        home.addView(favPage)

        // 已下载页：电脑片库里处理好的影片
        downloadsPage.addView(ui.text("电脑片库",11,ui.muted).apply { letterSpacing=.28f },ui.margins(-1,-2).apply { topMargin=ui.px(18) })
        libraryTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(34).toFloat()); ui.fontWeight(libraryTitle,300)
        downloadsPage.addView(libraryTitle,ui.margins(-1,-2).apply { topMargin=ui.px(6) })
        downloadsPage.addView(downloadsHint,ui.margins(-1,-2).apply { topMargin=ui.px(6); bottomMargin=ui.px(18) })
        librarySection.addView(shelf(libraryRow),shelfParams())
        downloadsPage.addView(librarySection,ui.margins(-1,-2))
        home.addView(downloadsPage)
        applyPage()
    }
    private fun applyPage() {
        tabs.forEach { (key,tab) -> tab.isSelected=key==page }
        homePage.visibility=if(page=="home") View.VISIBLE else View.GONE
        searchPage.visibility=if(page=="search") View.VISIBLE else View.GONE
        favPage.visibility=if(page=="library") View.VISIBLE else View.GONE
        downloadsPage.visibility=if(page=="downloads") View.VISIBLE else View.GONE
    }
    /** 切换页面：离开搜索页时清掉搜索结果；新页面整体浮现 */
    private fun showPage(key: String) {
        if(!ready || key==page) return
        if(page=="search") leaveSearch()
        page=key; applyPage(); homeScroll.scrollTo(0,0)
        if(key!="home" && stillKey!=null) { stillKey=null; backdrop.clear() }
        when(key) {
            "library" -> renderFavorites()
            "downloads" -> { downloadsHint.text=if(config.optBoolean("desktopConfigured")) "正在读取电脑片库…" else "还没有连接电脑服务：在设置里填写电脑地址后，可以把影片交给电脑下载和处理"; refreshLibrary() }
            "home" -> refreshHome()
        }
        val shown=when(key) { "home" -> homePage; "search" -> searchPage; "library" -> favPage; else -> downloadsPage }
        TvMotion.rise(shown,0,ui.px(16).toFloat(),460)
    }
    private fun leaveSearch() {
        backend.cancel(searchId); searchId=null; searched=""
        search.setText(""); searchButton.text="搜索"; searchButton.isEnabled=true
        applyHome(homeData)
    }
    private fun searchFor(title: String) { showPage("search"); search.setText(title); startSearch(title) }
    /** 竖版海报卡片：海报、片名、一行信息，可带左上角标签 */
    private fun posterCard(pic: String, name: String, sub: String, badge: String, posterHeight: Int, rank: Int = 0, action: () -> Unit): LinearLayout {
        val card=ui.column().apply { id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true }
        val poster=FrameLayout(activity).apply { background=ui.posterBackground(); clipToOutline=true; outlineProvider=android.view.ViewOutlineProvider.BACKGROUND }
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        poster.addView(image,FrameLayout.LayoutParams(-1,-1)); image.post { posters.load(image,pic) }
        if(badge.isNotEmpty()) poster.addView(ui.text(badge,10,Color.WHITE,true).apply {
            setPadding(ui.px(8),ui.px(3),ui.px(8),ui.px(3)); background=ui.background(Color.argb(150,0,0,0),radius=20)
        },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.px(8),ui.px(8),0,0) })
        // 榜单名次：海报左下角的大号数字，底下压一层渐暗保证看得清
        if(rank>0) {
            poster.addView(View(activity).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Color.argb(190,0,0,0),Color.TRANSPARENT)) },FrameLayout.LayoutParams(-1,ui.px(70),Gravity.BOTTOM))
            poster.addView(ui.text(rank.toString(),40,Color.WHITE).apply { ui.fontWeight(this,800); setShadowLayer(ui.px(6).toFloat(),0f,ui.px(1).toFloat(),Color.argb(170,0,0,0)) },
                FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(ui.px(10),0,0,ui.px(6)) })
        }
        card.addView(poster,ui.margins(-1,posterHeight))
        val title=ui.text(name,14,bold=true).apply { ui.fontWeight(this,600); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        card.addView(title,ui.margins(-1,22).apply { topMargin=ui.px(11); leftMargin=ui.px(3); rightMargin=ui.px(3) })
        card.addView(ui.text(sub,12,ui.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,18).apply { topMargin=ui.px(3); leftMargin=ui.px(3); rightMargin=ui.px(3) })
        ui.cardFocus(card,poster,title); card.contentDescription=name
        card.setOnClickListener { action() }
        return card
    }
    /** 本周热门：豆瓣的热门剧集与热门电影，确认键用片名搜索片源 */
    private fun renderHot(tv: List<TvHotList.Item>, movie: List<TvHotList.Item>) {
        val focusedTitle=hotSection.findFocus()?.contentDescription
        hotItems=tv+movie; hotSection.removeAllViews()
        listOf("剧集热播榜 Top 10" to tv.take(10),"电影热播榜 Top 10" to movie.take(10)).filter { it.second.isNotEmpty() }.forEach { (label,items) ->
            hotSection.addView(sectionTitle(label,ui.text("来自豆瓣 · 确认键查看详情",13,ui.muted)),ui.margins(-1,52).apply { topMargin=ui.px(6) })
            val row=ui.row().apply { gravity=Gravity.TOP }
            items.forEachIndexed { index,item ->
                val card=posterCard(item.cover,item.title,hotMeta(item),if(item.rate.isNotEmpty()) "豆瓣 ${item.rate}" else "",186,index+1) { openHotDetail(hotCard(item)) }
                featureOf[card]=hotFeature(item)
                row.addView(card,shelfCell(244))
                if(item.title==focusedTitle) card.post { card.requestFocus() }
            }
            hotSection.addView(shelf(row),shelfParams())
        }
        if(hero.current==null && searched.isEmpty()) applyHome(homeData)
    }
    private fun hotMeta(item: TvHotList.Item)=listOf(if(item.tv) "剧集" else "电影",item.note).filter { it.isNotEmpty() }.joinToString(" · ")
    private fun hotFeature(item: TvHotList.Item)=TvHero.Feature("hot:${item.title}","本周热门",item.title,
        listOf(if(item.tv) "剧集" else "电影",if(item.rate.isNotEmpty()) "豆瓣 ${item.rate}" else "",item.note).filter { it.isNotEmpty() }.joinToString("   ·   "),
        "",null,item.cover,"查看详情",{ openHotDetail(hotCard(item)) },doubanId=item.id,tv=item.tv)
    private fun renderFavorites() {
        val items=favorites.all()
        favCount.text=if(items.isEmpty()) "还没有收藏。在影片详情页或首页大图上选择「加入片库」" else "${items.size} 部收藏影片 · 确认键查看详情"
        val focusedName=favGrid.findFocus()?.contentDescription
        favGrid.removeAllViews()
        items.forEachIndexed { index,card ->
            val count=card.optJSONArray("items")?.length() ?: 0
            val view=posterCard(card.optString("pic"),card.optString("name"),listOf(card.optString("year"),card.optString("typeName")).filter { it.isNotEmpty() }.joinToString(" · "),if(count>1) "$count 源" else "",196) { openDetail(card, "我的片库") }
            favGrid.addView(view,ui.gridCell(index,6,254).apply { setMargins(ui.px(6),0,ui.px(6),ui.px(22)) })
            if(card.optString("name")==focusedName) view.post { view.requestFocus() }
        }
    }
    /** 横向货架：内容可滚出页面左右留白直到屏幕边缘，焦点卡片放大时不被裁切 */
    private fun shelf(row: LinearLayout) = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled=false; isFocusable=false; clipChildren=false; clipToPadding=false; overScrollMode=View.OVER_SCROLL_NEVER
        setPadding(ui.px(32),ui.px(10),ui.px(32),0); addView(row)
    }
    private fun shelfParams() = ui.margins(-1,-2).apply { leftMargin=-ui.px(38); rightMargin=-ui.px(38); topMargin=-ui.px(10) }
    private fun shelfCell(height: Int) = LinearLayout.LayoutParams(ui.px(126),ui.px(height)).apply { setMargins(ui.px(6),0,ui.px(6),ui.px(21)) }
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
        top.addView(ui.text("SuperTV",11,Color.rgb(150,150,158)).apply { letterSpacing=.08f; ui.fontWeight(this,600) })
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
        listOf(playerDownloadButton,ui.button("选集") { showEpisodes() },ui.button("换源") { showSwitchSources() },settingsButton).forEach {
            controlRow.addView(it,ui.margins(-2,37).apply { rightMargin=ui.px(10) })
        }
        controls.addView(controlRow)
        controls.addView(ui.text("确认播放 / 暂停 · ← → 快退 / 快进 · ↑ 选集 · ↓ 控制栏 · 菜单设置 · 返回收起",10,Color.rgb(140,140,148)),ui.margins(-1,-2).apply { topMargin=ui.px(15) })
        playback.setOnClickListener { togglePlay() }
    }
    fun onBoot(event: JSONObject) {
        if (event.optString("event") == "fatal") { onBootError(event.optString("error")); return }
        // 其他设备同步进来的修改：只刷新首页和片库，不重走开场
        if (event.optString("event") == "library-changed") { if (ready) { applyHome(event.optJSONObject("value") ?: JSONObject()); refreshLibrary() }; return }
        ready = true; searchButton.isEnabled = true; search.showSoftInputOnFocus=false
        applyHome(event.optJSONObject("value") ?: JSONObject())
        refreshLibrary()
        if(hero.visibility==View.VISIBLE) hero.play.requestFocus() else tabs["home"]?.requestFocus()
        hot.load { tv,movie -> if(!disposed) renderHot(tv,movie) }
        // 亮灯：片头至少停留片刻，然后黑幕淡出，首页各区块自上而下依次浮现
        main.postDelayed({
            curtain.lift(620); home.alpha=1f
            TvMotion.stagger(listOf(home.getChildAt(0))+(0 until homePage.childCount).map { homePage.getChildAt(it) },ui.px(22).toFloat(),80,180)
        },maxOf(0L,1100-(SystemClock.elapsedRealtime()-curtain.since)))
    }
    fun onBootError(message: String) {
        status.text = message; curtain.lift(240); home.alpha=1f
        val p = newPanel("本地数据加载失败")
        p.status.text = message
        p.content.addView(ui.button("重试") { p.dialog.dismiss(); retryBoot() })
        p.show()
    }
    private fun applyHome(data: JSONObject) {
        homeData = data; applyConfig(data.optJSONObject("config") ?: config)
        val names = data.optJSONArray("searches").objects().map { it.optString("text") }
        if (names!=searchNames) {
            val previous=(0 until historyRow.childCount).map { historyRow.getChildAt(it) as Button }.filter { it!==clearSearches }.associateBy { it.text.toString() }
            searchNames=names
            val focused=historyRow.findFocus() as? Button
            historyRow.removeAllViews()
            names.forEachIndexed { index,value ->
                val button=previous[value] ?: ui.button(value) { search.setText(value); startSearch(value) }.apply {
                    background=ui.focusedBackground(radius=16); gravity=Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(12).toFloat()); minHeight=0; minimumHeight=0; setPadding(ui.px(12),ui.px(4),ui.px(12),ui.px(4)); isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END
                }
                historyRow.addView(button,ui.gridCell(index,5,31).apply { setMargins(if(index%5==0) 0 else ui.px(5),0,if(index%5==4) 0 else ui.px(5),ui.px(6)) })
            }
            // 「清空」排在搜索词之后，不单独占一行
            val index=names.size
            historyRow.addView(clearSearches,ui.gridCell(index,5,31).apply { setMargins(if(index%5==0) 0 else ui.px(5),0,if(index%5==4) 0 else ui.px(5),ui.px(6)) })
            if(focused!=null) {
                if(focused.parent===historyRow) focused.requestFocus() else if(page=="search") search.requestFocus()
            }
        }
        historySection.visibility=if (names.isEmpty()) View.GONE else View.VISIBLE
        if (searched.isEmpty()) {
            adapter.submitHistory(data.optJSONArray("history").objects())
            statusPrefix.text=""; status.text="输入片名，按确认键搜索 ${config.optInt("enabled")} 个片源"
            watchCount.text="最近观看 ${adapter.count} 部 · 确认键继续播放"
            // 大图推荐：焦点在某张卡片上就显示它（进度可能刚更新），否则显示最近看过的一部
            slides=listOfNotNull(data.optJSONArray("history").objects().firstOrNull()?.let { historyFeature(it) })+hotItems.filter { it.tv }.take(6).map { hotFeature(it) }
            val feature=home.findFocus()?.let { featureOf[it] } ?: slides.find { it.key==hero.current?.key } ?: slides.firstOrNull()
            if(feature!=null) hero.show(feature) else hero.clear()
            hero.visibility=if(feature!=null) View.VISIBLE else View.GONE
        }
    }
    private fun historyFeature(entry: JSONObject): TvHero.Feature {
        val position=entry.optDouble("playbackPosition",0.0); val length=entry.optDouble("duration",0.0)
        val finished=length>0 && position>=length-15
        val episode=if(entry.optInt("totalEpisodes")>1) "第 ${entry.optInt("episodeIndex")+1} / ${entry.optInt("totalEpisodes")} 集" else "电影"
        // 收藏时存成一张只有这一个来源的影片卡片
        val card=JSONObject().put("name",entry.optString("title")).put("pic",entry.optString("pic")).put("items",JSONArray().put(JSONObject().put("sourceKey",entry.optString("sourceKey"))
            .put("sourceUrl",entry.optString("sourceUrl")).put("sourceName",sourceName(entry.optString("sourceKey"))).put("vodId",entry.optString("vodId")).put("name",entry.optString("title")).put("pic",entry.optString("pic"))))
        return TvHero.Feature("history:${entry.optString("id")}",if(finished) "已看完" else "继续观看",entry.optString("title"),"$episode  ·  ${sourceName(entry.optString("sourceKey"))}",
            if(finished) "确认键重新播放" else "已看 ${TvPlaybackRules.time(position)}${if(length>0) " / ${TvPlaybackRules.time(length)}" else ""}",
            if(length>0 && position.isFinite()) (position/length).toFloat() else null,entry.optString("pic"),if(finished) "▶   重新播放" else "▶   继续播放",{ resumeHistory(entry) },
            { if(favorites.has(card)) "✓   已在片库" else "＋   加入片库" },{ toast(if(favorites.toggle(card)) "已加入片库" else "已移出片库") })
    }
    private fun resumeHistory(entry: JSONObject) {
        val item=JSONObject().put("sourceKey",entry.optString("sourceKey"))
            .put("sourceUrl",entry.optString("sourceUrl")).put("sourceName",sourceName(entry.optString("sourceKey")))
            .put("vodId",entry.optString("vodId")).put("name",entry.optString("title")).put("pic",entry.optString("pic"))
        historyFocusKey=entry.optString("id")
        openPlayback(item,entry.optInt("episodeIndex").coerceAtLeast(0))
    }
    private fun applyConfig(value: JSONObject) {
        config = value; sourceCount.text = "${value.optInt("enabled")} 个来源已启用"; modeBadge.text="${if(value.optString("playbackMode")=="desktop") "电脑串流" else "独立运行"} · ${value.optInt("enabled")} 源"
        playerDownloadButton.visibility=if(value.optBoolean("desktopConfigured")) View.VISIBLE else View.GONE
    }
    private fun sourceName(key: String) = config.optJSONArray("sources").objects().find { it.optString("key") == key }?.optString("name") ?: "观看记录"
    private fun toast(message: String) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
    private fun refreshHome() { backend.request("home") { if (it.optString("event") == "result") applyHome(it.optJSONObject("value") ?: JSONObject()) }; refreshLibrary() }
    /** 读取电脑片库，刷新首页「已下载」；有未完成的下载时每 10 秒刷新进度 */
    private fun refreshLibrary() {
        if(!config.optBoolean("desktopConfigured")) { librarySection.visibility=View.GONE; libraryTitle.text="已下载"; return }
        backend.request("desktop-downloads") { event ->
            if(disposed) return@request
            val items=event.optJSONObject("value")?.optJSONArray("items").objects()
            renderLibrary(items)
            main.removeCallbacks(libraryRefresh)
            if(items.any { it.optString("state") !in setOf("done","error") }) main.postDelayed(libraryRefresh,10_000)
        }
    }
    private val libraryRefresh = Runnable { if(active==null) refreshLibrary() }
    private fun libraryItem(meta: JSONObject) = JSONObject().put("sourceKey",meta.optString("sourceKey")).put("sourceName",meta.optString("sourceName")).put("sourceUrl",meta.optString("sourceUrl"))
        .put("vodId",meta.optString("vodId")).put("name",meta.optString("name")).put("pic",meta.optString("pic"))
    private fun renderLibrary(items: List<JSONObject>) {
        val shown=items.filter { it.optString("state")!="error" }.take(10)
        librarySection.visibility=if(shown.isEmpty()) View.GONE else View.VISIBLE
        downloadsHint.text=if(shown.isEmpty()) "还没有下载。播放时在控制栏选择「下载」，电脑会在后台处理并保存整集" else "确认键播放已处理好的版本 · 末尾的「管理下载」可查看进度和删除"
        val done=shown.count { it.optString("state")=="done" }
        libraryTitle.text=if(shown.isEmpty()) "已下载" else if(done==shown.size) "已下载 ${shown.size} 部" else "已下载 ${done} 部 · ${shown.size-done} 部处理中"
        val keys=shown.map { "${it.optString("id")}|${it.optString("state")}|${it.optInt("done")}" }
        if(keys==libraryKeys) return
        libraryKeys=keys
        val focusedId=libraryRow.findFocus()?.let { libraryIds[it] }
        libraryRow.removeAllViews(); libraryIds.clear()
        shown.forEachIndexed { index,entry ->
            val meta=entry.optJSONObject("meta") ?: JSONObject()
            val ready=entry.optString("state")=="done"
            val total=entry.optInt("segments"); val finished=entry.optInt("done")
            val card=ui.column().apply { id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true }.also { libraryIds[it]=entry.optString("id") }
            val poster=FrameLayout(activity).apply { background=ui.posterBackground(); clipToOutline=true; outlineProvider=android.view.ViewOutlineProvider.BACKGROUND }
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            poster.addView(image,FrameLayout.LayoutParams(-1,-1)); image.post { posters.load(image,meta.optString("pic")) }
            poster.addView(ui.text(if(ready) "已下载" else if(total>0) "处理中 ${finished*100/total}%" else "排队中",10,Color.WHITE,true).apply {
                setPadding(ui.px(9),ui.px(4),ui.px(9),ui.px(4)); background=ui.background(Color.argb(150,0,0,0),radius=20)
            },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.px(10),ui.px(10),0,0) })
            if(!ready && total>0) poster.addView(android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=total; progress=finished; progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(90,255,255,255)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,ui.px(4),Gravity.BOTTOM).apply { setMargins(ui.px(10),0,ui.px(10),ui.px(10)) })
            card.addView(poster,ui.margins(-1,186))
            val title=ui.text(entry.optString("title"),15,bold=true).apply { ui.fontWeight(this,600); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            card.addView(title,ui.margins(-1,24).apply { topMargin=ui.px(12); leftMargin=ui.px(4); rightMargin=ui.px(4) })
            card.addView(ui.text(entry.optString("subtitle"),12,ui.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,18).apply { topMargin=ui.px(3); leftMargin=ui.px(4); rightMargin=ui.px(4) })
            ui.cardFocus(card,poster,title)
            card.contentDescription="已下载：${entry.optString("title")}"
            featureOf[card]=TvHero.Feature("library:${entry.optString("id")}",if(ready) "已下载" else "电脑处理中",entry.optString("title"),entry.optString("subtitle"),
                if(ready) "已保存在电脑，播放时无需实时处理" else if(total>0) "已处理 ${finished*100/total}%" else "排队中",if(!ready && total>0) finished.toFloat()/total else null,
                meta.optString("pic"),if(ready) "▶   播放" else "查看进度",{ if(ready) openPlayback(libraryItem(meta),meta.optInt("index"),fromLibrary=true) else showDownloads() })
            card.setOnClickListener { if(ready) openPlayback(libraryItem(meta),meta.optInt("index"),fromLibrary=true) else showDownloads() }
            libraryRow.addView(card,shelfCell(244))
        }
        // 货架末尾的「管理下载」卡片：查看进度、删除、失败重试
        val manage=ui.column().apply { id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true }.also { libraryIds[it]="manage" }
        val tile=FrameLayout(activity).apply { background=ui.background(Color.argb(22,255,255,255),Color.argb(40,255,255,255),14); clipToOutline=true; outlineProvider=android.view.ViewOutlineProvider.BACKGROUND }
        tile.addView(ui.text("⋯",34,Color.rgb(200,200,208)).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
        manage.addView(tile,ui.margins(-1,186))
        val manageTitle=ui.text("管理下载",15,bold=true).apply { ui.fontWeight(this,600) }
        manage.addView(manageTitle,ui.margins(-1,24).apply { topMargin=ui.px(12); leftMargin=ui.px(4) })
        manage.addView(ui.text("查看进度 · 删除",12,ui.muted),ui.margins(-1,18).apply { topMargin=ui.px(3); leftMargin=ui.px(4) })
        ui.cardFocus(manage,tile,manageTitle); manage.contentDescription="管理下载"
        manage.setOnClickListener { showDownloads() }
        libraryRow.addView(manage,shelfCell(244))
        focusedId?.let { id -> libraryIds.entries.find { it.value==id }?.key?.requestFocus() }
    }
    /** 播放界面「下载」：把正在看的这一集交给电脑后台处理并保存 */
    private fun downloadCurrent() {
        val a=active ?: return
        playerDownloadButton.isEnabled=false
        backend.request("download-desktop",JSONObject().put("item",a.getJSONObject("item")).put("index",a.optInt("index")).put("decoders",DecoderCapabilities.json)) { event ->
            playerDownloadButton.isEnabled=true
            if(event.optString("event")=="error") toast(event.optString("error"))
            else { playerDownloadButton.text="已加入下载"; toast("已加入电脑下载，完成后在首页「已下载」播放") }
        }
    }
    private fun startSearch(raw: String) {
        if (!ready) return
        val query = raw.trim().take(100)
        if (query.isEmpty()) { toast("请输入片名"); search.requestFocus(); return }
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        backend.cancel(searchId)
        emptyHint.text="没有找到相关影片，试试其他关键词或片源"
        searched = query; if(page!="search") { page="search"; applyPage() }; statusPrefix.text=query; status.text="正在搜索 ${config.optInt("enabled")} 个来源…"; adapter.submitCards(emptyList()); searchButton.text="搜索中…"; searchButton.isEnabled=false
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
        if(active!=null) { TvMotion.visible(controls,false,ui.px(26).toFloat()); TvMotion.visible(top,false,-ui.px(18).toFloat()); TvMotion.visible(buffer,false); TvMotion.visible(seekOverlay,false) }
        p.dialog.setOnDismissListener {
            if (panel === p) {
                backend.cancel(panelId); panelRequests.forEach { backend.cancel(it) }; panelRequests.clear(); panelId=null; panel=null; panelKind=""
                // 选集后主动收起面板时继续加载；用户自己关面板才取消。关闭回调是异步的，标记由这里读取并清掉
                val keep = keepLoading; keepLoading = false
                if (loadingPlayback && !keep) { backend.cancel(openingId); loadingPlayback = false; curtain.lift(260); endSwitch(true) }
                if (loadingPlayback) playback.requestFocus()
                else if (active!=null) { showControls(true); playButton.requestFocus() } else previousFocus?.takeIf { it.isShown }?.requestFocus()
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
    /** 片名比对用：去掉空白和标点，「黑鸽 第二季」与「黑鸽第二季」视为同一部 */
    private fun titleKey(value: String) = value.lowercase().replace(Regex("[\\s·:：,，.。!！?？'\"“”‘’()（）\\-—_\\[\\]【】]"), "")
    /** 设计稿式的小信息标签：描边胶囊 */
    private fun metaChip(label: String, strong: Boolean = false) = ui.text(label, 12, if (strong) Color.WHITE else Color.rgb(200, 200, 206)).apply {
        setPadding(ui.px(9), ui.px(3), ui.px(9), ui.px(3)); isSingleLine = true
        background = if (strong) ui.background(Color.argb(60, 255, 255, 255), radius = 6) else ui.background(Color.TRANSPARENT, Color.argb(90, 255, 255, 255), 6).apply { setStroke(maxOf(1, ui.px(1)), Color.argb(90, 255, 255, 255)) }
    }
    /**
     * 详情页（全屏，按设计稿）：左侧竖版海报；右侧小标签、细体大标题、信息标签、简介、主演/导演/类型，立即播放 / 加入片库 / 下载到电脑；
     * 下面是来源切换、剧集货架和相关推荐。card 可以先只有片名和海报（本周热门），lookup 为真时在后台搜索片源，找到后再填入来源与剧集。
     * related 为相关推荐（同一次搜索的其他结果，或同类热门），确认键打开它们的详情。
     */
    private fun openDetail(card: JSONObject, label: String = "", lookup: Boolean = false, related: List<JSONObject> = emptyList(), rating: String = "") {
        var items = card.optJSONArray("items").objects()
        if (items.isEmpty() && !lookup) return
        val p = newPanel(card.optString("name"))
        p.heading.visibility = View.GONE; p.width = 960; p.height = 540
        p.body.setPadding(ui.px(48), ui.px(34), ui.px(48), ui.px(20))
        val shade = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(250, 8, 8, 9), Color.argb(228, 8, 8, 9), Color.argb(175, 8, 8, 9)))
        val fade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(240, 8, 8, 9)))
        p.body.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(Color.rgb(12, 12, 14)), shade))
        var posterBitmap: android.graphics.Bitmap? = null
        val episodeImages = mutableListOf<ImageView>()
        fun useBackdrop(drawable: android.graphics.drawable.Drawable?) {
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled && it.width > 0 } ?: return
            posterBitmap = bitmap
            val soft = android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createScaledBitmap(bitmap, 24, maxOf(2, 24 * bitmap.height / bitmap.width), true), 120, maxOf(2, 120 * bitmap.height / bitmap.width), true)
            p.body.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.BitmapDrawable(activity.resources, soft).apply { gravity = Gravity.FILL }, shade, fade))
            episodeImages.forEach { it.setImageBitmap(bitmap) }
        }
        // 顶部：海报 + 信息
        val top = ui.row().apply { gravity = Gravity.TOP }
        val posterFrame = FrameLayout(activity).apply { background = ui.posterBackground(14); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; elevation = ui.px(16).toFloat() }
        val posterImage = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        posterFrame.addView(posterImage, FrameLayout.LayoutParams(-1, -1))
        top.addView(posterFrame, ui.margins(168, 252).apply { rightMargin = ui.px(34) })
        val info = ui.column()
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val caption = ui.row()
        caption.addView(View(activity).apply { setBackgroundColor(Color.argb(200, 255, 255, 255)) }, ui.margins(22, 1).apply { rightMargin = ui.px(10) })
        caption.addView(ui.text(label.ifEmpty { "影片详情" }, 11, Color.rgb(190, 190, 196)).apply { letterSpacing = .28f; ui.fontWeight(this, 500) })
        val title = ui.text(card.optString("name"), 38).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; ui.fontWeight(this, 300) }
        val meta = ui.row()
        val desc = ui.text("", 14, Color.rgb(205, 205, 212)).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.3f) }
        val people = ui.column()
        val actions = ui.row()
        val note = ui.text("", 12, ui.muted)
        info.addView(caption, ui.margins(-1, -2).apply { topMargin = ui.px(6) })
        info.addView(title, ui.margins(-1, -2).apply { topMargin = ui.px(10) })
        info.addView(meta, ui.margins(-1, -2).apply { topMargin = ui.px(12) })
        info.addView(desc, ui.margins(620, -2).apply { topMargin = ui.px(14) })
        info.addView(people, ui.margins(-1, -2).apply { topMargin = ui.px(10) })
        info.addView(actions, ui.margins(-1, -2).apply { topMargin = ui.px(20) })
        info.addView(note, ui.margins(-1, -2).apply { topMargin = ui.px(10) })
        p.content.addView(top)
        posters.load(posterImage, card.optString("pic")) { useBackdrop(posterImage.drawable) }
        // 下方：来源、剧集、相关推荐
        fun section(name: String, hint: TextView) = ui.row().apply {
            addView(ui.text(name, 18).apply { ui.fontWeight(this, 500) }, ui.margins(-2, -2).apply { rightMargin = ui.px(12) }); addView(hint)
        }
        fun shelfOf(row: LinearLayout) = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(4)); addView(row) }
        val sourceHint = ui.text("", 12, ui.muted)
        val tabs = ui.row()
        val sourceBlock = ui.column().apply { visibility = View.GONE }
        sourceBlock.addView(section("来源", sourceHint), ui.margins(-1, -2).apply { topMargin = ui.px(22) })
        sourceBlock.addView(shelfOf(tabs), ui.margins(-1, -2).apply { topMargin = ui.px(4); leftMargin = -ui.px(8) })
        val episodeHint = ui.text("", 12, ui.muted)
        val episodeRow = ui.row().apply { gravity = Gravity.TOP }
        val episodeBlock = ui.column().apply { visibility = View.GONE }
        episodeBlock.addView(section("剧集", episodeHint), ui.margins(-1, -2).apply { topMargin = ui.px(16) })
        episodeBlock.addView(shelfOf(episodeRow), ui.margins(-1, -2).apply { topMargin = ui.px(2); leftMargin = -ui.px(8) })
        val relatedRow = ui.row().apply { gravity = Gravity.TOP }
        val relatedBlock = ui.column().apply { visibility = if (related.isEmpty()) View.GONE else View.VISIBLE }
        val relatedHint = ui.text("确认键查看详情", 12, ui.muted)
        relatedBlock.addView(section("相关推荐", relatedHint), ui.margins(-1, -2).apply { topMargin = ui.px(16) })
        relatedBlock.addView(shelfOf(relatedRow), ui.margins(-1, -2).apply { topMargin = ui.px(2); leftMargin = -ui.px(8) })
        p.content.addView(sourceBlock); p.content.addView(episodeBlock); p.content.addView(relatedBlock)
        // 相关推荐：同系列（其他季、电影版）排在最前，其后是同一次搜索的其他结果或同类热门
        var relatedKeys = emptyList<String>()
        fun renderRelated(list: List<JSONObject>, seriesCount: Int) {
            // 搜索中途会多次更新：名单没变就不重建，免得海报重新加载闪一下
            val keys = list.take(20).map { titleKey(it.optString("name")) + "|" + (it.optJSONArray("items")?.length() ?: 0) } + "series:$seriesCount"
            if (keys == relatedKeys) return
            relatedKeys = keys
            val focused = relatedRow.findFocus()?.contentDescription
            relatedRow.removeAllViews()
            relatedBlock.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            relatedHint.text = if (seriesCount > 0) "同系列 $seriesCount 部 · 确认键查看详情" else "确认键查看详情"
            list.take(20).forEachIndexed { index, other ->
                val count = other.optJSONArray("items")?.length() ?: 0
                val sub = listOf(other.optString("year"), other.optString("remarks")).filter { it.isNotEmpty() }.joinToString(" · ")
                val badge = if (index < seriesCount) "同系列" else if (count > 1) "$count 源" else other.optString("badge")
                val view = posterCard(other.optString("pic"), other.optString("name"), sub, badge, 150) {
                    p.dialog.dismiss(); main.post { if (other.optBoolean("hot")) openHotDetail(other) else openDetail(other, "相关推荐", related = list.filter { it !== other } + card) }
                }
                relatedRow.addView(view, LinearLayout.LayoutParams(ui.px(100), ui.px(204)).apply { setMargins(ui.px(6), 0, ui.px(6), ui.px(10)) })
                if (other.optString("name") == focused) view.post { view.requestFocus() }
            }
        }
        renderRelated(related, 0)
        // 后台按系列名搜一次（不记进最近搜索），筛出同系列作品按季排好放到最前；同一系列名的结果缓存，各季之间来回看不再搜索
        val seriesBase = TvSeries.base(card.optString("name"))
        fun applySeries(groups: List<JSONObject>) {
            if (panel !== p) return
            val current = titleKey(card.optString("name"))
            val entries = groups.map { Triple(it.optString("name"), it.optString("remarks"), it.optJSONArray("items")?.length() ?: 0) }
            val series = TvSeries.arrange(entries, seriesBase, card.optString("name")).map { groups[it] }.filter { titleKey(it.optString("name")) != current }
            if (series.isEmpty()) return
            renderRelated(series + related.filter { r -> series.none { titleKey(it.optString("name")) == titleKey(r.optString("name")) } && titleKey(r.optString("name")) != current }, series.size)
        }
        if (seriesBase.length >= 2) {
            val cached = seriesResults[titleKey(seriesBase)]
            if (cached != null) applySeries(cached)
            else {
                // 搜索进行中每 3 秒更新一次，搜完再定稿并缓存
                var shownAt = 0L
                panelRequests.add(backend.request("search", JSONObject().put("query", seriesBase).put("record", false)) { event ->
                    val value = event.optJSONObject("value") ?: return@request
                    if (event.optString("event") !in setOf("result", "search-progress")) return@request
                    val groups = value.optJSONArray("groups").objects()
                    val done = value.optBoolean("done")
                    if (done) { if (seriesResults.size >= 30) seriesResults.remove(seriesResults.keys.first()); seriesResults[titleKey(seriesBase)] = groups }
                    if (done || SystemClock.elapsedRealtime() - shownAt >= 3000) { shownAt = SystemClock.elapsedRealtime(); applySeries(groups) }
                })
            }
        }
        // 看过这部：播放按钮改为接着看
        val history = homeData.optJSONArray("history").objects().firstOrNull { titleKey(it.optString("title")) == titleKey(card.optString("name")) }
        var selected: JSONObject? = null
        var picked = false
        var downloadMode = false
        var episodes = JSONArray()
        val play = ui.button("▶   立即播放") {}.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(15).toFloat()); ui.fontWeight(this, 600); setPadding(ui.px(26), 0, ui.px(26), 0) }
        fun favoriteLabel() = if (favorites.has(card)) "✓   已在片库" else "＋   加入片库"
        val favorite = ui.button(favoriteLabel()) {}.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(15).toFloat()); setPadding(ui.px(22), 0, ui.px(22), 0) }
        favorite.setOnClickListener { if (items.isEmpty()) return@setOnClickListener; toast(if (favorites.toggle(card)) "已加入片库" else "已移出片库"); favorite.text = favoriteLabel(); if (page == "library") renderFavorites() }
        val download = ui.button("下载到电脑") {}.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(15).toFloat()); setPadding(ui.px(22), 0, ui.px(22), 0); visibility = if (config.optBoolean("desktopConfigured")) View.VISIBLE else View.GONE }
        actions.addView(play, ui.margins(-2, 44).apply { rightMargin = ui.px(12) }); actions.addView(favorite, ui.margins(-2, 44).apply { rightMargin = ui.px(12) }); actions.addView(download, ui.margins(-2, 44))
        fun resumeEntry() = history?.takeIf { h -> selected?.optString("sourceKey") == h.optString("sourceKey") && selected?.optString("vodId") == h.optString("vodId") }
        fun refreshPlay() {
            val entry = resumeEntry()
            play.isEnabled = selected != null
            play.text = when {
                selected == null -> "▶   正在查找片源"
                entry != null && entry.optInt("totalEpisodes") > 1 -> "▶   继续播放 · 第 ${entry.optInt("episodeIndex") + 1} 集"
                entry != null -> "▶   继续播放"
                else -> "▶   立即播放"
            }
        }
        fun startDownload(item: JSONObject, n: Int) {
            backend.request("download-desktop", JSONObject().put("item", item).put("index", n).put("decoders", DecoderCapabilities.json)) { event ->
                if (event.optString("event") == "error") toast(event.optString("error")) else toast("已加入电脑下载：${item.optString("name")}${if (n > 0) " 第 ${n + 1} 集" else ""}")
            }
        }
        play.setOnClickListener {
            val item = selected ?: return@setOnClickListener
            val entry = resumeEntry()
            when {
                entry != null -> resumeHistory(entry)
                // 没手动选来源时，多个来源先比较码率再播
                !picked && items.size > 1 -> { play.isEnabled = false; play.text = "▶   正在选择码率最高的来源…"; playBest(JSONObject(card.toString()).put("items", JSONArray(items))) { message -> note.text = message } }
                else -> openPlayback(item, 0)
            }
        }
        fun renderMeta(info: JSONObject) {
            meta.removeAllViews()
            val chips = mutableListOf<View>()
            if (rating.isNotEmpty()) chips.add(metaChip("豆瓣 $rating", true))
            listOf(info.optString("year").ifEmpty { card.optString("year") }).filter { it.isNotEmpty() }.forEach { chips.add(ui.text(it, 13, Color.rgb(200, 200, 206))) }
            info.optString("remarks").ifEmpty { card.optString("remarks") }.takeIf { it.isNotEmpty() }?.let { chips.add(metaChip(it)) }
            listOf(info.optString("typeName").ifEmpty { card.optString("typeName") }, info.optString("area")).filter { it.isNotEmpty() }.forEach { chips.add(ui.text(it, 13, Color.rgb(200, 200, 206))) }
            if (items.size > 1) chips.add(ui.text("${items.size} 个来源", 13, Color.rgb(200, 200, 206)))
            chips.forEach { meta.addView(it, ui.margins(-2, -2).apply { rightMargin = ui.px(14) }) }
            people.removeAllViews()
            fun line(name: String, value: String) { if (value.isBlank()) return
                val row = ui.row(); row.addView(ui.text(name, 12, ui.muted), ui.margins(40, -2)); row.addView(ui.text(value.trim().take(60), 12, Color.rgb(205, 205, 212)).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.margins(560, -2))
                people.addView(row, ui.margins(-1, -2).apply { topMargin = ui.px(5) }) }
            line("主演", info.optString("actor").split(Regex("[,，/、]")).map { it.trim() }.filter { it.isNotEmpty() }.take(5).joinToString("、"))
            line("导演", info.optString("director"))
            line("类型", listOf(info.optString("typeName"), info.optString("area")).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        fun renderEpisodes(item: JSONObject) {
            episodeRow.removeAllViews(); episodeImages.clear()
            val count = episodes.length()
            episodeBlock.visibility = if (count > 1) View.VISIBLE else View.GONE
            if (count <= 1) return
            episodeHint.text = "${item.optString("sourceName")} · 共 $count 集"
            val watched = resumeEntry()?.optInt("episodeIndex", -1) ?: -1
            for (n in 0 until count) {
                val cell = ui.column().apply { id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true }
                val frame = FrameLayout(activity).apply { background = ui.posterBackground(10); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
                val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = .55f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                posterBitmap?.let { image.setImageBitmap(it) }; episodeImages.add(image)
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                frame.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(170, 0, 0, 0))) }, FrameLayout.LayoutParams(-1, -1))
                frame.addView(ui.text("%02d".format(n + 1), 20, Color.WHITE).apply { ui.fontWeight(this, 300) }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(ui.px(10), 0, 0, ui.px(6)) })
                if (n == watched) frame.addView(ui.text("上次看到", 10, Color.WHITE, true).apply { setPadding(ui.px(7), ui.px(2), ui.px(7), ui.px(2)); background = ui.background(Color.argb(150, 0, 0, 0), radius = 20) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.px(8), ui.px(8), 0, 0) })
                cell.addView(frame, ui.margins(-1, 72))
                val name = ui.text("第 ${n + 1} 集", 13).apply { ui.fontWeight(this, 500); isSingleLine = true }
                cell.addView(name, ui.margins(-1, -2).apply { topMargin = ui.px(8); leftMargin = ui.px(2) })
                ui.cardFocus(cell, frame, name); cell.contentDescription = "第 ${n + 1} 集"
                cell.setOnClickListener { if (downloadMode) startDownload(item, n) else openPlayback(item, n, if (n == watched) history?.optDouble("playbackPosition") else null) }
                episodeRow.addView(cell, LinearLayout.LayoutParams(ui.px(128), ui.px(108)).apply { setMargins(ui.px(6), 0, ui.px(6), ui.px(6)) })
            }
        }
        val sourceButtons = mutableListOf<Pair<JSONObject, Button>>()
        fun select(item: JSONObject, byUser: Boolean = false) {
            selected = item; picked = picked || byUser; refreshPlay()
            sourceButtons.forEach { (it, b) -> b.isSelected = it === item }
            backend.cancel(panelId); note.text = "正在读取 ${item.optString("sourceName")} 的剧集…"
            panelId = backend.request("detail", JSONObject().put("item", item)) { event ->
                if (panel !== p) return@request
                if (event.optString("event") == "error") { note.text = "${item.optString("sourceName")}：${event.optString("error")}（可换一个来源）"; return@request }
                val detail = event.optJSONObject("value")?.optJSONObject("detail") ?: return@request
                val videoInfo = detail.optJSONObject("videoInfo") ?: JSONObject()
                episodes = detail.optJSONArray("episodes") ?: JSONArray()
                note.text = ""
                @Suppress("DEPRECATION")
                desc.text = Html.fromHtml(videoInfo.optString("desc")).toString().trim().replace(Regex("\\s+"), " ").take(200)
                desc.visibility = if (desc.text.isEmpty()) View.GONE else View.VISIBLE
                renderMeta(videoInfo)
                download.setOnClickListener {
                    if (episodes.length() <= 1) startDownload(item, 0)
                    else { downloadMode = !downloadMode; download.text = if (downloadMode) "选择要下载的集 · 再按取消" else "下载到电脑"; download.isSelected = downloadMode }
                }
                renderEpisodes(item)
                if (p.dialog.currentFocus == null || p.dialog.currentFocus === p.close) play.requestFocus()
            }
        }
        fun attach(found: List<JSONObject>) {
            val fresh = found.filter { f -> sourceButtons.none { (it, _) -> it.optString("sourceKey") == f.optString("sourceKey") && it.optString("vodId") == f.optString("vodId") } }
            if (fresh.isEmpty()) return
            items = items + fresh.filter { f -> items.none { it.optString("sourceKey") == f.optString("sourceKey") && it.optString("vodId") == f.optString("vodId") } }
            card.put("items", JSONArray(items))
            fresh.forEach { item -> val button = ui.button(item.optString("sourceName")) { select(item, true) }; sourceButtons.add(item to button); tabs.addView(button, ui.margins(-2, 34).apply { rightMargin = ui.px(10) }) }
            sourceBlock.visibility = View.VISIBLE
            sourceHint.text = if (items.size > 1) "${items.size} 个来源 · 默认播放码率最高的" else "1 个来源"
            if (selected == null) select(history?.let { h -> items.firstOrNull { it.optString("sourceKey") == h.optString("sourceKey") && it.optString("vodId") == h.optString("vodId") } } ?: items[0])
        }
        renderMeta(JSONObject()); refreshPlay()
        p.show()
        play.requestFocus()
        if (items.isNotEmpty()) attach(items)
        if (lookup) {
            // 本周热门：在已启用的片源里按片名找这部剧，找到第一个同名结果就先填上，之后到达的来源继续追加
            note.text = "正在 ${config.optInt("enabled")} 个片源里查找《${card.optString("name")}》…"
            val key = titleKey(card.optString("name"))
            val id = backend.request("search", JSONObject().put("query", card.optString("name")).put("record", false)) { event ->
                if (panel !== p) return@request
                val value = event.optJSONObject("value") ?: return@request
                if (event.optString("event") !in setOf("result", "search-progress")) return@request
                val groups = value.optJSONArray("groups").objects()
                val match = groups.firstOrNull { titleKey(it.optString("name")) == key }
                    ?: if (value.optBoolean("done")) groups.filter { g -> titleKey(g.optString("name")).let { it.contains(key) || key.contains(it) } }.minByOrNull { it.optString("name").length } else null
                if (match != null) {
                    listOf("year", "typeName", "remarks").forEach { k -> if (card.optString(k).isEmpty() && match.optString(k).isNotEmpty()) card.put(k, match.optString(k)) }
                    attach(match.optJSONArray("items").objects())
                }
                if (value.optBoolean("done") && items.isEmpty()) {
                    note.text = "没有在已启用的片源里找到《${card.optString("name")}》"
                    play.text = "搜索其他结果"; play.isEnabled = true
                    play.setOnClickListener { p.dialog.dismiss(); main.post { searchFor(card.optString("name")) } }
                }
            }
            panelRequests.add(id)
        }
    }
    /** 系列名搜索结果（按系列名缓存），详情页的同系列推荐用 */
    private val seriesResults = LinkedHashMap<String, List<JSONObject>>()
    /** 本周热门卡片进入详情页：先显示豆瓣的片名、海报和评分，后台查找片源；相关推荐为同类热门 */
    private fun openHotDetail(hot: JSONObject) {
        val tv = hot.optBoolean("tv")
        val related = hotItems.filter { it.tv == tv && it.title != hot.optString("name") }.map { hotCard(it) }
        openDetail(JSONObject().put("name", hot.optString("name")).put("pic", hot.optString("pic")).put("typeName", if (tv) "剧集" else "电影"),
            "本周热门 · ${if (tv) "剧集" else "电影"}", lookup = true, related = related, rating = hot.optString("rate"))
    }
    private fun hotCard(item: TvHotList.Item) = JSONObject().put("name", item.title).put("pic", item.cover).put("rate", item.rate).put("tv", item.tv).put("hot", true)
        .put("remarks", item.note).put("badge", if (item.rate.isNotEmpty()) "豆瓣 ${item.rate}" else "")
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
    private fun playBest(card: JSONObject, onStatus: ((String) -> Unit)? = null) {
        val items=card.optJSONArray("items") ?: return
        if (items.length()<=1) { items.optJSONObject(0)?.let { openPlayback(it,0) }; return }
        if (loadingPlayback || choosingId!=null) return
        val before=status.text.toString()
        status.text="正在比较 ${items.length()} 个来源的码率…"; onStatus?.invoke(status.text.toString())
        choosingId=backend.request("best-source",JSONObject().put("items",items)) { event ->
            choosingId=null; status.text=before; onStatus?.invoke("")
            val value=event.optJSONObject("value")
            val item=value?.optJSONObject("item") ?: items.optJSONObject(0) ?: return@request
            if (event.optString("event")=="result" && value.optString("quality").isNotEmpty()) toast("已选码率最高的来源：${item.optString("sourceName")} · ${value.optString("quality")}")
            openPlayback(item,0)
        }
    }
    /** 换集结束：成功时新一集接管播放器；取消或失败时 resume 为真则让原来那集接着播 */
    private fun endSwitch(resume: Boolean) {
        if (!switching) return
        switching = false
        if (resume && resumeAfterSwitch && active != null) command("play")
        resumeAfterSwitch = false
    }
    private fun openPlayback(item: JSONObject, index: Int, position: Double? = null, fromLibrary: Boolean = false) {
        if (loadingPlayback) return
        loadingPlayback = true; openingStatus=status.text.toString()
        if (active != null) {
            // 播放中换集：先停住当前这集的声音和画面，收起控制栏与选集面板，直接显示加载片头
            switching = true; resumeAfterSwitch = !state.optBoolean("paused")
            command("pause"); main.removeCallbacks(hideControls); seekPreview = null
            TvMotion.visible(controls,false,ui.px(26).toFloat()); TvMotion.visible(top,false,-ui.px(18).toFloat()); TvMotion.visible(buffer,false); TvMotion.visible(seekOverlay,false)
            playback.requestFocus()
        }
        // 选集面板、详情页等收起，露出加载片头；这次关闭不算取消
        if (panel?.dialog?.isShowing == true) { keepLoading = true; panel?.dialog?.dismiss() }
        // 从片库播放的本来就是已下载的版本
        playerDownloadButton.text=if(fromLibrary) "已下载" else "下载"; playerDownloadButton.isEnabled=!fromLibrary
        val opening=if(fromLibrary) "正在从电脑片库读取…" else if(config.optString("playbackMode")=="desktop") "正在连接电脑处理视频…" else "正在连接片源…"
        if(config.optString("playbackMode")=="desktop" || fromLibrary) status.text=opening
        // 灯光暗下，片头浮现；出错时重新亮灯，画面就绪后淡出
        main.removeCallbacks(liftCurtain); curtainSession=""
        curtain.raise("即将放映",item.optString("name"),listOf(item.optString("sourceName"),if(index>0) "第 ${index+1} 集" else "").filter { it.isNotEmpty() }.joinToString("  ·  "),opening)
        val args = JSONObject().put("item", item).put("index", index).put("decoders", DecoderCapabilities.json).put("fromLibrary", fromLibrary)
        if (position != null) args.put("position", position)
        openingId = backend.request("open-playback", args) { event ->
            if(event.optString("event")=="processing-progress") {
                val message=event.optJSONObject("value")?.optString("message").orEmpty()
                if(message.isNotEmpty()) { status.text=message; panel?.status?.text=message; curtain.status(message) }
                return@request
            }
            loadingPlayback = false; status.text=openingStatus
            if (event.optString("event") == "error") {
                // 换集失败：收起黑幕回到原来那一集。电脑串流会弹出失败面板让用户选择，这时保持暂停；直连则接着播原来那集
                curtain.lift(260)
                val desktop = config.optString("playbackMode") == "desktop"
                endSwitch(!desktop)
                if (desktop) showDesktopFailure(event.optString("error"), item, index, position)
                else { toast(event.optString("error")); if (active != null) showControls(true) }
                return@request
            }
            switching = false; resumeAfterSwitch = false
            val descriptor = event.optJSONObject("value") ?: return@request
            savePlayback(); closeDesktopPlayback(); panel?.dialog?.dismiss(); backend.cancel(compareId); compareId=null; compareText=""
            bufferingSince=0L
            active = descriptor; session = "native-${SystemClock.elapsedRealtime()}"; state = JSONObject(); seekPreview = null; qualityHeight = 0; endedSession = ""; desktopFailureSession = ""
            applyConfig(descriptor.optJSONObject("config") ?: config)
            view.background=null; backdrop.visibility=View.GONE; homeScroll.visibility = View.GONE; playback.visibility = View.VISIBLE; playback.requestFocus()
            // 片头最多再等 8 秒；迟迟不出画面就先收起，让缓冲信息露出来
            curtainSession=session; curtain.status("正在缓冲…"); main.postDelayed(liftCurtain,8000)
            val delivery = descriptor.optJSONObject("playback") ?: JSONObject().put("url",descriptor.getJSONObject("detail").getJSONArray("episodes").getString(descriptor.optInt("index")))
            val url = delivery.getString("url")
            player.command(JSONObject().put("version", 1).put("sessionId", session).put("type", "load")
                .put("request", JSONObject().put("url", url).put("headers", delivery.optJSONObject("headers") ?: JSONObject()).put("delivery", delivery.optString("delivery", "direct")).put("processing",delivery.optString("processing")).put("label",delivery.optString("label")).put("position", descriptor.optDouble("position")).put("adFilter", delivery.optString("processing")!="enhance" && config.optBoolean("adFilter", true))
                .put("adSegments", if (delivery.optString("processing")=="enhance") JSONArray() else delivery.optJSONArray("adSegments") ?: JSONArray())
                .put("playlists", if (delivery.optString("delivery")=="desktop") JSONArray() else delivery.optJSONArray("playlists") ?: JSONArray())
                .put("timeMap", if (delivery.optString("processing")=="enhance") delivery.optJSONArray("timeMap") ?: JSONArray() else null)))
            command("rate", JSONObject().put("rate", rate)); command("fit", JSONObject().put("fit", fit))
            startComparison(descriptor.getJSONObject("item"), descriptor.optInt("index"), session)
            showControls(false)
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
        p.status.text=if(Regex("Failed to connect|ECONNREFUSED|Connection refused|timeout",RegexOption.IGNORE_CASE).containsMatchIn(message)) "连不上电脑服务：请确认电脑已开机、电脑端服务正在运行，且与电视在同一网络" else message
        p.content.addView(ui.text(if(sourceFailure) "电脑已连接，但片源拒绝访问或已失效。请换源；切回直连也可能无法播放。" else "可先换源；也可检查电脑服务、地址和网络，或切回独立直连。",15,ui.muted))
        // 三个操作并排的胶囊按钮（整行宽的按钮聚焦放大时会超出面板）
        val actions=ui.row().apply { gravity=Gravity.CENTER }
        val switchSource=ui.button("换源") { showSwitchSources(item,index) }
        val retry=ui.button("重试电脑串流") { openPlayback(item,index,position) }
        val direct=ui.button("切回独立直连") {
            backend.request("settings",JSONObject().put("playbackMode","direct")) { event ->
                event.optJSONObject("value")?.let { applyConfig(it); openPlayback(item,index,position) }
            }
        }
        listOf(retry,switchSource,direct).forEachIndexed { i,button ->
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(15).toFloat()); button.setPadding(ui.px(26),0,ui.px(26),0)
            actions.addView(button,ui.margins(-2,46).apply { if(i>0) leftMargin=ui.px(16) })
        }
        p.content.addView(actions,ui.margins(-1,-2).apply { topMargin=ui.px(34) })
        p.height=330
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
        if(curtainSession==session && (value.optBoolean("rendered") || error.isNotEmpty())) {
            // 第一帧已出：片头至少停留 1.6 秒再淡出，随后带出片名与控制栏
            curtainSession=""; main.removeCallbacks(liftCurtain)
            main.postDelayed({ if(active!=null) { curtain.lift(); showControls(true) } },if(error.isNotEmpty()) 0L else maxOf(0L,1600-(SystemClock.elapsedRealtime()-curtain.since)))
        }
        val buffering=value.optBoolean("buffering") && !value.optBoolean("paused") && error.isEmpty()
        if (buffering && bufferingSince==0L) bufferingSince=SystemClock.elapsedRealtime()
        if (!buffering) bufferingSince=0L
        TvMotion.visible(buffer,panel==null && seekPreview==null && !switching && (error.isNotEmpty() || buffering && SystemClock.elapsedRealtime()-bufferingSince>=350))
        bufferTitle.setLabel(if(error.isNotEmpty()) error else "正在缓冲")
        bufferSpinner.visibility=if(error.isEmpty()) View.VISIBLE else View.GONE
        bufferSpeed.visibility=if(error.isEmpty()) View.VISIBLE else View.GONE
        bufferSpeed.setLabel(TvPlaybackRules.speed(value.optDouble("bytesPerSecond")))
        bufferNote.setLabel(if(error.isNotEmpty()) "按下键打开控制栏换源" else if(value.optDouble("bytesPerSecond")>0) "视频下载速度" else "等待片源响应，暂未收到数据")
        bufferDetail.setLabel(if(error.isNotEmpty()) "" else "已缓冲 ${"%.1f".format(value.optDouble("bufferedDuration"))} 秒 · 下键可打开控制栏换源")
        TvMotion.visible(seekOverlay,seekPreview!=null && panel==null,ui.px(10).toFloat())
        seekTime.setLabel(TvPlaybackRules.time(position))
        if (height > qualityHeight) {
            qualityHeight = height
            backend.request("played-quality", JSONObject().put("sourceKey", item.optString("sourceKey")).put("vodId", item.optString("vodId")).put("height", height))
        }
        if (SystemClock.elapsedRealtime() - lastSaved >= 5000) savePlayback()
        // 换集加载中，原来那集已暂停，它的报错和播完都不再处理，免得弹出面板挡住加载画面
        if (error.isNotEmpty() && !switching) {
            showControls(true)
            if (a.optJSONObject("playback")?.optString("delivery") == "desktop" && desktopFailureSession != session) {
                desktopFailureSession = session
                showDesktopFailure("电脑串流中断：$error", item, a.optInt("index"), originalPosition(value.optDouble("position"),timeMap()))
            }
        }
        if (value.optBoolean("ended") && endedSession != session && !switching) {
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
        val extra=listOfNotNull(summary?.optInt("codecGroups")?.takeIf { it>0 }?.let { "编码比对 $it 段" }, summary?.optInt("learnedGroups")?.takeIf { it>0 }?.let { "已学习 $it 段" }, summary?.optInt("repeatGroups")?.takeIf { it>0 }?.let { "重复插入 $it 段" }, summary?.optInt("timelineGroups")?.takeIf { it>0 }?.let { "时间轴比对 $it 段" })
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
            it.addView(ui.text(label,13,ui.muted),ui.margins(77,-2))
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
        add(flags,flag("autoplayNext")); flags.addView(ui.text("广告过滤",13,ui.muted),ui.margins(77,-2)); add(flags,flag("adFilter"))
        if(config.optBoolean("adFilter",true)) {
            val learning=row("广告学习")
            add(learning,ui.button("标记当前片段为广告") { markAd(p) })
            val clear=ui.button("清除已学习（${config.optInt("learnedAds")}）") {}
            clear.setOnClickListener {
                backend.request("clear-learned-ads") { event -> event.optJSONObject("value")?.let { applyConfig(it); clear.text="清除已学习（0）"; toast("已清除学习的广告") } }
            }
            add(learning,clear)
        }
        if(config.optBoolean("desktopConfigured")) {
            val downloadRow=row("电脑下载")
            add(downloadRow,ui.button("下载本集到电脑") {
                val a=active ?: return@button
                backend.request("download-desktop",JSONObject().put("item",a.getJSONObject("item")).put("index",a.optInt("index")).put("decoders",DecoderCapabilities.json)) { event ->
                    if(event.optString("event")=="error") toast(event.optString("error")) else toast("已加入电脑下载，可在首页「电脑下载」查看进度")
                }
            })
            downloadRow.addView(ui.text("电脑后台处理整集并保存，之后播放不再实时处理",11,ui.muted),ui.margins(-2,-2))
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
    /** 电脑下载：电脑后台把整集处理好保存，完成后播放直接读取 */
    private fun showDownloads() {
        if(!ready) return
        val p=newPanel("电脑下载")
        p.width=883; p.height=486; p.close.text="完成"
        p.status.text="电脑在后台处理整集（去广告、超分、去水印）后保存在电脑上；完成后播放直接读取，不再实时处理。"
        val list=ui.column()
        p.content.addView(list)
        fun size(bytes: Long)=if(bytes>=1L shl 30) "%.1f GB".format(bytes/1073741824.0) else "%.0f MB".format(bytes/1048576.0)
        lateinit var load: () -> Unit
        // 按钮的标识单独记录：View.tag 是方向键焦点搜索用的标记，不能改写
        val keys=mutableMapOf<View,String>()
        var shown=""; var first=true; var confirming=""
        fun render(items: List<JSONObject>) {
            // 内容没变就不重建，避免每 3 秒刷新时打断正在进行的焦点移动
            val signature=items.joinToString("|") { "${it.optString("id")}:${it.optString("state")}:${it.optInt("done")}:${it.optString("error")}" }
            if(signature==shown && list.childCount>0) return
            shown=signature
            val focused=p.dialog.currentFocus?.let { keys[it] }
            list.removeAllViews(); keys.clear()
            if(items.isEmpty()) list.addView(ui.text("还没有下载。在影片详情页（搜索结果长按确认键）或播放设置中选择「下载到电脑」。",15,ui.muted),ui.margins(-1,-2).apply { topMargin=ui.px(20) })
            for(entry in items) {
                val row=ui.row().apply { setPadding(ui.px(16),ui.px(12),ui.px(16),ui.px(12)); background=ui.background(Color.argb(20,255,255,255),radius=16) }
                val info=ui.column()
                info.addView(ui.text(entry.optString("title"),16,bold=true).apply { ui.fontWeight(this,650); isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END })
                val done=entry.optInt("done"); val total=entry.optInt("segments")
                val stateText=when(entry.optString("state")) {
                    "queued" -> "排队中"
                    "preparing" -> "电脑正在读取片源并准备处理"
                    "downloading" -> "处理中 ${if(total>0) "${done*100/total}%（$done / $total 段）" else "$done 段"}"
                    "done" -> "已完成 · ${size(entry.optLong("bytes"))} · ${TvPlaybackRules.time(entry.optDouble("duration"))}"
                    else -> "失败：${entry.optString("error")}"
                }
                info.addView(ui.text("${entry.optString("subtitle")}  ·  $stateText",12,if(entry.optString("state")=="error") Color.rgb(255,140,130) else ui.muted).apply { isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,-2).apply { topMargin=ui.px(4) })
                if(entry.optString("state")=="downloading" && total>0) info.addView(android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=total; progress=done; progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(60,255,255,255))
                },ui.margins(-1,6).apply { topMargin=ui.px(6) })
                row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
                val meta=entry.optJSONObject("meta") ?: JSONObject()
                val item=JSONObject().put("sourceKey",meta.optString("sourceKey")).put("sourceName",meta.optString("sourceName")).put("sourceUrl",meta.optString("sourceUrl"))
                    .put("vodId",meta.optString("vodId")).put("name",meta.optString("name")).put("pic",meta.optString("pic"))
                val id=entry.optString("id")
                if(entry.optString("state")=="done") row.addView(ui.button("播放") { p.dialog.dismiss(); main.post { openPlayback(item,meta.optInt("index"),fromLibrary=true) } }.also { keys[it]="play:$id" },ui.margins(-2,38).apply { leftMargin=ui.px(10) })
                if(entry.optString("state")=="error") row.addView(ui.button("重试") {
                    backend.request("download-desktop",JSONObject().put("item",item).put("index",meta.optInt("index")).put("decoders",DecoderCapabilities.json)) { load() }
                }.also { keys[it]="retry:$id" },ui.margins(-2,38).apply { leftMargin=ui.px(10) })
                // 删除不可恢复：第一次按下变为「确认删除」，再按一次才删除
                lateinit var delete: Button
                delete=ui.button(if(confirming==id) "确认删除" else "删除") {
                    if(confirming==id) { confirming=""; backend.request("delete-download",JSONObject().put("id",id)) { shown=""; load(); refreshLibrary() } }
                    else { confirming=id; delete.text="确认删除" }
                }
                delete.setOnFocusChangeListener { v,has -> v.animate().scaleX(if(has) 1.06f else 1f).scaleY(if(has) 1.06f else 1f).setDuration(140).start(); if(!has && confirming==id) { confirming=""; delete.text="删除" } }
                keys[delete]="delete:$id"
                row.addView(delete,ui.margins(-2,38).apply { leftMargin=ui.px(10) })
                list.addView(row,ui.margins(-1,-2).apply { bottomMargin=ui.px(10) })
            }
            // 焦点回到原来的按钮；那一项被删除时落到同类的第一个按钮
            if(focused!=null) (keys.entries.find { it.value==focused } ?: keys.entries.find { it.value.substringBefore(":")==focused.substringBefore(":") } ?: keys.entries.firstOrNull())?.key?.requestFocus() ?: p.close.requestFocus()
            // 打开面板时焦点直接落在第一部影片的操作按钮上
            else if(first && keys.isNotEmpty()) keys.keys.first().requestFocus()
            first=false
        }
        load = {
            backend.request("desktop-downloads") { event ->
                if(panel!==p) return@request
                if(event.optString("event")=="error") { p.status.text=event.optString("error"); return@request }
                val value=event.optJSONObject("value") ?: JSONObject()
                if(!value.optBoolean("available")) { p.status.text="请先在片源管理中填写电脑地址和配对码"; return@request }
                val items=value.optJSONArray("items").objects()
                render(items)
                // 有未完成的下载时每 3 秒刷新进度
                if(items.any { it.optString("state") !in setOf("done","error") }) main.postDelayed({ if(panel===p) load() },3000)
            }
        }
        p.show(); load()
    }
    private fun formatRate()=if(rate%1.0==0.0) rate.toInt().toString() else rate.toString()
    // 设置：左边是分组导航，焦点移到哪一组右边就显示哪一组；右边是一行行「名称 … 取值」
    private var settingsTab = "playback"
    private fun showSourceSettings(tab: String = settingsTab) {
        if(!ready) return
        val p=newPanel("设置")
        p.width=883; p.height=486; p.close.text="完成"
        p.body.setPadding(ui.px(26),ui.px(16),ui.px(26),ui.px(18))
        p.title.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(24).toFloat())
        p.body.removeView(p.scroll); p.status.minHeight=ui.px(16)
        val nav=ui.column()
        val pane=ui.column().apply { setPadding(ui.px(10),ui.px(6),ui.px(10),ui.px(14)) }
        val paneScroll=ScrollView(activity).apply { isFocusable=false; isVerticalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER; addView(pane) }
        val split=ui.row().apply { gravity=Gravity.TOP }
        split.addView(nav,ui.margins(190,-2).apply { rightMargin=ui.px(14); topMargin=ui.px(6) })
        split.addView(paneScroll,LinearLayout.LayoutParams(0,-1,1f))
        p.body.addView(split,LinearLayout.LayoutParams(-1,0,1f))
        val navButtons=linkedMapOf<String,Button>()
        var first: View?=null
        fun edge(view: View): View { navButtons[settingsTab]?.let { view.nextFocusLeftId=it.id }; if(first==null) first=view; return view }
        fun style(view: View) {
            if(view is Button) { view.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(14).toFloat()); view.setPadding(ui.px(14),ui.px(8),ui.px(14),ui.px(8)) }
            if(view is EditText) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(15).toFloat()); view.setPadding(ui.px(18),0,ui.px(18),0)
                // 选中时底色变白，文字和提示要跟着变深，否则看不清
                view.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.rgb(18,18,20),ui.ink)))
                view.setHintTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.rgb(110,110,115),ui.muted)))
            }
            view.background=ui.focusedBackground(radius=14)
        }
        fun group(label: String) = pane.addView(ui.text(label,12,ui.muted).apply { letterSpacing=.1f; ui.fontWeight(this,600) },ui.margins(-1,-2).apply { topMargin=ui.px(if(pane.childCount==0) 4 else 18); bottomMargin=ui.px(8); leftMargin=ui.px(6) })
        fun note(label: String) = pane.addView(ui.text(label,12,ui.muted).apply { setLineSpacing(ui.px(3).toFloat(),1f) },ui.margins(-1,-2).apply { topMargin=ui.px(8); leftMargin=ui.px(6); rightMargin=ui.px(6) })
        fun row(label: String, value: () -> String, action: (done: () -> Unit) -> Unit) {
            lateinit var item: TvWidgets.SettingRow
            item=ui.settingRow(label,value()) { action { item.value.text=value() } }
            pane.addView(edge(item.view),ui.margins(-1,46).apply { bottomMargin=ui.px(6) })
        }
        fun field(input: EditText) = pane.addView(edge(input.apply { style(this) }),ui.margins(-1,44).apply { bottomMargin=ui.px(8) })
        fun action(button: Button) = pane.addView(edge(button.apply { style(this) }),ui.margins(-2,40).apply { topMargin=ui.px(4) })
        fun onOff(key: String) = if(config.optBoolean(key,true)) "已开启" else "已关闭"
        fun sourcesStatus() = "已启用 ${config.optInt("enabled")} 个片源 · 确认键切换，自动保存"
        fun render(key: String) {
            settingsTab=key; navButtons.forEach { (k,button) -> button.isSelected=k==key }
            if(key!="desktop") hubDiscovery.stop()
            // 从右上角「完成」按下回到当前栏目，而不是跳到第一个栏目把内容换掉
            navButtons[key]?.let { p.close.nextFocusDownId=it.id }
            pane.removeAllViews(); paneScroll.scrollTo(0,0); first=null; p.status.text=""
            when(key) {
                "phones" -> {
                    group("手机连接")
                    note("在同一个 Wi-Fi 下打开手机上的 SuperTV，点右上角的电视图标就能找到这台电视；第一次连接时要在这里用遥控器确认。连上后可以同步观看记录和片库、把手机上的影片投到电视、遥控播放和代替遥控器打字。")
                    val service=companionService
                    if(service==null || service.port==0) note("手机连接服务没有启动（端口被占用），重新打开应用后再试。")
                    else {
                        row("这台电视",{ listOf(service.name,service.address().ifEmpty { "未连接网络" }+if(service.port!=TvCompanion.PORT) ":${service.port}" else "").joinToString(" · ") }) { done -> done() }
                        note("手机上搜不到这台电视时，在手机的电视面板里手动填写上面的地址。")
                        val phones=service.paired()
                        group(if(phones.isEmpty()) "还没有手机连接过" else "已允许的手机（确认键移除）")
                        phones.forEach { (device,label) -> row(label,{ "移除" }) { _ -> service.forget(device); toast("已移除「$label」"); render("phones"); pane.post { (first ?: navButtons["phones"])?.requestFocus() } } }
                    }
                }
                "playback" -> {
                    group("播放方式")
                    row("播放模式",{ if(config.optString("playbackMode")=="desktop") "电脑串流" else "独立直连" }) { done ->
                        backend.request("settings",JSONObject().put("playbackMode",if(config.optString("playbackMode")=="desktop") "direct" else "desktop")) { event -> event.optJSONObject("value")?.let { applyConfig(it); done() } }
                    }
                    note("独立直连：电视自己访问片源，不需要电脑。电脑串流：由电脑去广告、超分后再传给电视，电脑服务要一直开着。")
                    group("画面处理（仅电脑串流）")
                    row("AI 超分",{ if(config.optBoolean("superResolution",true)) "已开启 · 升高一个档位" else "已关闭 · 原始分辨率" }) { done ->
                        backend.request("settings",JSONObject().put("superResolution",!config.optBoolean("superResolution",true))) { event -> event.optJSONObject("value")?.let { applyConfig(it); done() } }
                    }
                    row("去水印",{ if(config.optBoolean("overlayRemoval",true)) "已开启 · 台标与滚动广告字幕" else "已关闭 · 保持原画" }) { done ->
                        backend.request("settings",JSONObject().put("overlayRemoval",!config.optBoolean("overlayRemoval",true))) { event -> event.optJSONObject("value")?.let { applyConfig(it); done() } }
                    }
                    note("处理能力以电脑服务的连接检测结果为准。")
                }
                "desktop" -> {
                    val connected=config.optBoolean("desktopConfigured")
                    val server=ui.input("例如 http://192.168.1.10:8090").apply { setText(config.optString("desktopServerUrl")); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
                    // 已连接时不显示保存着的长配对码（很长，也没必要看）；要换电脑就重新填 6 位配对码
                    val pairing=ui.input(if(connected) "已连接 · 换电脑时填新的 6 位配对码" else "电脑服务窗口里显示的 6 位配对码").apply { inputType=InputType.TYPE_CLASS_NUMBER }
                    var connecting=false
                    var shown=""   // 当前显示的电脑列表；置空可强制重画（连接成功后更新「已连接」标记）
                    fun finish(event: JSONObject, message: String?) {
                        connecting=false
                        if(panel!==p || settingsTab!="desktop") return
                        p.status.text=if(event.optString("event")=="error") event.optString("error") else message.orEmpty()
                    }
                    fun connect() {
                        if(connecting) return
                        val url=server.text.toString().trim().let { if(it.isNotEmpty() && !it.contains("://")) "http://$it" else it }.let { if(Regex("^https?://[^/:]+$").matches(it)) "$it:8090" else it }
                        val code=pairing.text.toString().filter { !it.isWhitespace() }
                        if(url.isEmpty()) { p.status.text="先选一台电脑，或填写电脑服务地址"; return }
                        connecting=true; p.status.text="正在连接电脑服务…"
                        when {
                            Regex("\\d{6}").matches(code) -> backend.request("pair-desktop",JSONObject().put("url",url).put("pin",code)) { event ->
                                event.optJSONObject("value")?.optJSONObject("config")?.let { applyConfig(it) }
                                finish(event,event.optJSONObject("value")?.optString("message"))
                                if(event.optString("event")=="result") { pairing.setText(""); pairing.hint="已连接 · 换电脑时填新的 6 位配对码"; shown="" }
                            }
                            // 没填配对码：沿用已保存的长配对码，只换地址或重新检测
                            code.isEmpty() && config.optString("desktopPairingToken").isNotEmpty() -> {
                                val args=JSONObject().put("desktopServerUrl",url).put("desktopPairingToken",config.optString("desktopPairingToken"))
                                backend.request("settings",args) { saved -> saved.optJSONObject("value")?.let { applyConfig(it) }; backend.request("test-desktop",args) { event -> finish(event,event.optString("value")) } }
                            }
                            // 旧办法仍然可用：直接粘贴 config.json 里的长配对码
                            code.length>=24 -> {
                                val args=JSONObject().put("desktopServerUrl",url).put("desktopPairingToken",code)
                                backend.request("settings",args) { saved -> saved.optJSONObject("value")?.let { applyConfig(it) }; backend.request("test-desktop",args) { event -> finish(event,event.optString("value")) } }
                            }
                            else -> { connecting=false; p.status.text="配对码是电脑服务窗口里显示的 6 位数字" }
                        }
                    }
                    group("局域网里的电脑")
                    val found=ui.column(); pane.addView(found)
                    fun renderFound() {
                        if(panel!==p || settingsTab!="desktop") return
                        val list=runCatching { JSONArray(hubDiscovery.list()).objects() }.getOrDefault(emptyList()).sortedBy { it.optString("name") }
                        val key=list.joinToString("|") { it.optString("host")+":"+it.optInt("port") }
                        if(key!=shown || found.childCount==0) {
                            // 列表变化时焦点若在其中，重建后放回同一位置附近
                            val hadFocus=found.findFocus()!=null
                            shown=key; found.removeAllViews()
                            if(list.isEmpty()) found.addView(ui.text("正在寻找…电脑上要先启动 SuperTV 电脑服务，并和电视连同一个网络。",12,ui.muted),ui.margins(-1,-2).apply { leftMargin=ui.px(6); bottomMargin=ui.px(6) })
                            list.forEach { pc ->
                                val address="http://${pc.optString("host")}:${pc.optInt("port")}"
                                val current=config.optString("desktopServerUrl")==address && config.optBoolean("desktopConfigured")
                                val item=ui.settingRow(pc.optString("name"),if(current) "已连接" else pc.optString("host")) {
                                    server.setText(address); p.status.text="已选中「${pc.optString("name")}」，在下面填它窗口里显示的 6 位配对码"; pairing.requestFocus()
                                }
                                found.addView(edge(item.view),ui.margins(-1,46).apply { bottomMargin=ui.px(6) })
                            }
                            // 从左边导航按右，先到电脑列表（有的话），否则到配对码输入框
                            val entry=(found.getChildAt(0)?.takeIf { it.isFocusable } ?: pairing).apply { if(id==View.NO_ID) id=View.generateViewId() }
                            first=entry; navButtons.values.forEach { it.nextFocusRightId=entry.id }
                            if(hadFocus) found.post { (found.getChildAt(0)?.takeIf { it.isFocusable } ?: navButtons["desktop"])?.requestFocus() }
                        }
                        main.postDelayed({ renderFound() },1500)
                    }
                    hubDiscovery.start(); renderFound()
                    group("配对码")
                    field(pairing)
                    pairing.setOnEditorActionListener { _,_,_ -> connect(); true }
                    action(ui.button(if(connected) "重新连接" else "连接") { connect() })
                    note("配对码显示在电脑服务的窗口里，只在第一次连接时需要。连上后，观看记录、片库和搜索记录会在各设备间同步，电视还能用电脑去广告、超分。")
                    group("手动填写地址（找不到电脑时）")
                    field(server)
                }
                "sources" -> {
                    p.status.text=sourcesStatus()
                    val sources=ui.grid(3)
                    config.optJSONArray("sources").objects().forEachIndexed { index,source ->
                        val id=source.optString("key")
                        fun enabled()=config.optJSONArray("selectedKeys").strings().contains(id)
                        fun label()="${if(enabled()) "✓ " else "○ "}${source.optString("name")}"
                        val button=ui.button(label()) {}.apply { style(this); isSelected=enabled(); gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT; isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END }
                        if(index%3==0) edge(button)
                        button.setOnClickListener {
                            backend.request("toggle-source",JSONObject().put("key",id)) { event -> event.optJSONObject("value")?.let {
                                applyConfig(it); button.text=label(); button.isSelected=enabled(); if(settingsTab=="sources") p.status.text=sourcesStatus()
                            } }
                        }
                        sources.addView(button,ui.gridCell(index,3,38).apply { setMargins(ui.px(4),ui.px(4),ui.px(4),ui.px(4)) })
                    }
                    pane.addView(sources)
                }
                "add" -> {
                    group("名称（可选）")
                    val name=ui.input("给这个片源起个名字"); field(name)
                    group("Apple CMS 接口地址")
                    val url=ui.input("https://example.com/api.php/provide/vod").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }; field(url)
                    action(ui.button("添加片源") {
                        backend.request("add-source",JSONObject().put("name",name.text.toString()).put("url",url.text.toString())) { event ->
                            if(event.optString("event")=="error") p.status.text=event.optString("error")
                            else { event.optJSONObject("value")?.let { applyConfig(it) }; name.setText(""); url.setText(""); p.status.text="片源已添加并启用"; toast("片源已添加并启用") }
                        }
                    })
                }
                "subscriptions" -> {
                    group("订阅地址")
                    val subscription=ui.input("https://example.com/sources.json").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }; field(subscription)
                    var busy=false
                    val syncButtons=mutableListOf<Button>()
                    fun sync(value: String) {
                        if(busy) return
                        busy=true; syncButtons.forEach { it.isEnabled=false }; p.status.text="正在从设备同步订阅…"
                        backend.request("sync-subscription",JSONObject().put("url",value.trim())) { event ->
                            busy=false; syncButtons.forEach { it.isEnabled=true }
                            if(event.optString("event")=="error") p.status.text=event.optString("error")
                            else backend.request("config") { e -> e.optJSONObject("value")?.let { applyConfig(it); toast("订阅已同步"); if(panel===p && settingsTab=="subscriptions") { render("subscriptions"); p.status.text="订阅已同步"; pane.post { (first ?: navButtons["subscriptions"])?.requestFocus() } } } }
                        }
                    }
                    action(ui.button("同步订阅") { sync(subscription.text.toString()) }.also { syncButtons.add(it) })
                    note("支持 LibreTV SourceList 和 TVBOX 的直连接口；Spider 类订阅暂不支持。")
                    val saved=config.optJSONArray("subscriptions").objects()
                    if(saved.isNotEmpty()) group("已保存的订阅")
                    saved.forEach { sub -> row(sub.optString("name").ifEmpty { sub.optString("url") },{ "更新" }) { _ -> sync(sub.optString("url")) } }
                }
            }
            navButtons.values.forEach { it.nextFocusRightId=first?.id ?: View.NO_ID }
        }
        listOf("playback" to "播放","desktop" to "电脑服务","phones" to "手机","sources" to "片源","add" to "添加片源","subscriptions" to "订阅").forEach { (key,label) ->
            val button=ui.button(label) { render(key) }.apply {
                background=ui.focusedBackground(Color.TRANSPARENT,14); gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT
                setTextSize(TypedValue.COMPLEX_UNIT_PX,ui.px(15).toFloat()); setPadding(ui.px(18),0,ui.px(18),0); nextFocusLeftId=id
            }
            val scale=button.onFocusChangeListener
            button.setOnFocusChangeListener { v,focused -> scale?.onFocusChange(v,focused); if(focused && settingsTab!=key) render(key) }
            navButtons[key]=button; nav.addView(button,ui.margins(-1,44).apply { bottomMargin=ui.px(4) })
        }
        render(if(navButtons.containsKey(tab)) tab else "playback")
        p.close.nextFocusDownId=View.NO_ID
        p.show(); navButtons[settingsTab]?.requestFocus()
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
        // 控制栏自下滑入、片名自上滑入；收起时反向滑出
        TvMotion.visible(controls,show && panel==null,ui.px(26).toFloat())
        TvMotion.visible(top,show && panel==null,-ui.px(18).toFloat())
        if (!show) playback.requestFocus()
        scheduleControls()
    }
    private fun scheduleControls() { main.removeCallbacks(hideControls); if (active != null && TvMotion.isVisible(controls)) main.postDelayed(hideControls, 5000) }
    private fun exitPlayback() {
        savePlayback(); closeDesktopPlayback(); backend.cancel(openingId); loadingPlayback = false; active = null; session = ""; seekPreview = null
        main.removeCallbacks(liftCurtain); curtainSession=""; curtain.lift(240)
        TvMotion.visible(controls,false); TvMotion.visible(top,false); TvMotion.visible(buffer,false); TvMotion.visible(seekOverlay,false)
        player.stop(); playback.visibility=View.GONE; view.background=TvHomeBackground(); backdrop.visibility=View.VISIBLE; homeScroll.visibility=View.VISIBLE
        // 散场亮灯：首页从略微放大的状态收拢并淡入
        homeScroll.alpha=0f; homeScroll.scaleX=1.04f; homeScroll.scaleY=1.04f
        homeScroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(520).setInterpolator(TvMotion.out).start()
        when(page) { "home" -> adapter.focusHistory(historyFocusKey); "search" -> grid.getChildAt(0)?.requestFocus(); else -> tabs[page]?.requestFocus() }
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
        val nav=TvPlaybackNavigation.State(TvMotion.isVisible(controls), current!=null && (0 until controlRow.childCount).any { controlRow.getChildAt(it)===current },seekPreview!=null,panel!=null,panelKind=="settings")
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
        if(active!=null && switching) {
            // 换集加载中：返回键取消并回到原来那集，其余按键不落到黑幕后面的播放器
            if (code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && loadingPlayback) {
                backend.cancel(openingId); loadingPlayback = false; status.text = openingStatus
                curtain.lift(260); endSwitch(true); showControls(true); playButton.requestFocus(); toast("已取消换集")
            }
            return playbackKey(code) != null || code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
        }
        if(active!=null) {
            if(playbackKey(code)==null) return false
            if(event.action==KeyEvent.ACTION_DOWN && !(event.repeatCount>0 && code !in setOf(19,20,21,22))) handlePlaybackKey(code)
            return true
        }
        // 片头期间只响应返回键（取消），其余按键不落到黑幕后面的首页
        if (curtain.raised && code !in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) return true
        if (code in setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_F2)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showSourceSettings()
            return true
        }
        if (code in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                if(loadingPlayback) { backend.cancel(openingId); loadingPlayback=false; status.text=openingStatus; curtain.lift(260); toast("已取消播放") }
                else if(page!="home") { showPage("home"); if(hero.visibility==View.VISIBLE) hero.play.requestFocus() else tabs["home"]?.requestFocus() }
                else activity.finish()
            }
            return true
        }
        if (code in setOf(19,20,21,22)) { if(event.action==KeyEvent.ACTION_DOWN) ui.moveFocus(home,code); return true }
        return false
    }
    // —— 手机伴侣：同步、接力播放、遥控、代输入（见 TvCompanion）——
    /** 在局域网里寻找电脑服务（设置里的「电脑服务」一栏打开时才开始找） */
    private val hubDiscovery = TvDiscovery(activity.applicationContext, "_supertv-hub._tcp")
    var companionService: TvCompanion? = null
    private fun focusedInput(): EditText? = (panel?.dialog?.takeIf { it.isShowing }?.currentFocus ?: activity.currentFocus) as? EditText
    val companion = object : TvCompanion.Handler {
        override fun confirmPairing(name: String, decide: (Boolean) -> Unit) {
            if (disposed) { decide(false); return }
            var decided = false
            fun settle(allowed: Boolean) { if (!decided) { decided = true; decide(allowed); if (allowed) toast("已允许「$name」连接") } }
            // 自绘的确认框（黑白灰、与界面一致）：厂商自带的对话框样式各不相同，还会截断说明文字
            val dialog = android.app.Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
            val allow = ui.button("允许") { settle(true); dialog.dismiss() }
            val deny = ui.button("拒绝") { settle(false); dialog.dismiss() }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; background = ui.background(Color.rgb(28, 28, 30), Color.argb(40, 255, 255, 255), 22)
                setPadding(ui.px(34), ui.px(30), ui.px(34), ui.px(28))
                addView(ui.text("允许这台手机连接？", 22, bold = true))
                addView(ui.text("「$name」想连接这台电视。\n允许后，它可以同步观看记录和片库、把影片投到电视上播放、遥控播放，并代替遥控器打字。", 15, ui.muted).apply { setLineSpacing(ui.px(6).toFloat(), 1f) }, ui.margins(-1, -2).apply { topMargin = ui.px(14) })
                addView(ui.row().apply {
                    gravity = Gravity.END
                    listOf(allow, deny).forEachIndexed { i, b -> b.setPadding(ui.px(30), 0, ui.px(30), 0); addView(b, ui.margins(-2, 46).apply { if (i > 0) leftMargin = ui.px(14) }) }
                }, ui.margins(-1, -2).apply { topMargin = ui.px(26) })
            }
            dialog.setContentView(box, ViewGroup.LayoutParams(ui.px(520), ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setDimAmount(.7f) }
            dialog.setOnCancelListener { settle(false) }
            dialog.setOnDismissListener { settle(false) }
            // 默认停在「拒绝」上：误按确认键不会放行
            runCatching { dialog.show(); deny.requestFocus() }.onFailure { settle(false) }
            main.postDelayed({ if (!decided && dialog.isShowing) dialog.dismiss() }, 60_000)
        }
        override fun sync(body: JSONObject, done: (JSONObject?) -> Unit) {
            if (disposed) { done(null); return }
            backend.request("peer-sync", body) { event -> when (event.optString("event")) { "result" -> done(event.optJSONObject("value")); "error" -> done(null) } }
        }
        override fun state(): JSONObject {
            val input = focusedInput(); val a = active
            val value = JSONObject().put("page", page).put("playing", a != null).put("loading", loadingPlayback)
                .put("input", input != null).put("inputText", input?.text?.toString().orEmpty().take(500)).put("inputHint", input?.hint?.toString().orEmpty())
            if (a != null) value.put("title", a.getJSONObject("item").optString("name")).put("index", a.optInt("index"))
                .put("episodes", a.optJSONObject("detail")?.optJSONArray("episodes")?.length() ?: 0)
                .put("position", state.optDouble("position", 0.0).takeIf { it.isFinite() } ?: 0.0).put("duration", state.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0)
                .put("paused", state.optBoolean("paused"))
            return value
        }
        override fun cast(args: JSONObject): String? {
            val key = args.optString("sourceKey"); val vod = args.optString("vodId")
            if (key.isEmpty() || vod.isEmpty() || key.length > 200 || vod.length > 200) return "投屏内容无效"
            if (disposed || config.length() == 0) return "电视还没有准备好"
            if (loadingPlayback) return "电视正在打开另一部影片，请稍后再试"
            val item = JSONObject().put("sourceKey", key).put("sourceUrl", args.optString("sourceUrl").take(2000)).put("sourceName", args.optString("sourceName").take(80).ifEmpty { sourceName(key) })
                .put("vodId", vod).put("name", args.optString("title").take(200)).put("pic", args.optString("pic").take(2000))
            val position = args.optDouble("position", 0.0).takeIf { it.isFinite() && it > 5 }
            toast("来自手机：${item.optString("name")}")
            openPlayback(item, args.optInt("index").coerceAtLeast(0), position)
            return null
        }
        override fun control(action: String, value: Double): String? {
            if (active == null || switching || loadingPlayback) return "电视没有在播放"
            val length = state.optDouble("duration", 0.0); val now = state.optDouble("position", 0.0)
            when (action) {
                "toggle" -> command(if (state.optBoolean("paused")) "play" else "pause")
                "play", "pause" -> command(action)
                "seek", "seekTo" -> {
                    if (!TvPlaybackRules.canSeek(length)) return "这部影片还不能快进"
                    command("seek", JSONObject().put("position", (if (action == "seek") now + value else value).coerceIn(0.0, (length - 2).coerceAtLeast(0.0))))
                }
                "next" -> episode(1)
                "prev" -> episode(-1)
                "stop" -> { panel?.dialog?.dismiss(); exitPlayback(); return null }
                else -> return "不支持的操作"
            }
            if (panel?.dialog?.isShowing != true && !loadingPlayback) showControls(true)
            return null
        }
        override fun text(value: String, submit: Boolean): String? {
            val input = focusedInput() ?: return "电视上没有选中输入框"
            input.setText(value.filter { !it.isISOControl() }.take(500)); input.setSelection(input.text.length)
            if (submit) input.onEditorAction((input.imeOptions and EditorInfo.IME_MASK_ACTION).takeIf { it != EditorInfo.IME_ACTION_UNSPECIFIED } ?: EditorInfo.IME_ACTION_DONE)
            return null
        }
        override fun search(query: String): String? {
            val text = query.filter { !it.isISOControl() }.trim().take(100)
            if (text.isEmpty()) return "请输入要搜索的片名"
            if (active != null || loadingPlayback) return "电视正在播放，先停止播放再搜索"
            if (disposed || config.length() == 0) return "电视还没有准备好"
            // 焦点落在搜索框上：原先聚焦的首页内容已经隐藏，而且手机可以接着改字
            panel?.dialog?.dismiss(); searchFor(text); search.requestFocus()
            return null
        }
    }
    fun release() { hubDiscovery.stop(); hot.close(); backdrop.close(); backend.cancel(compareId); closeDesktopPlayback(); disposed=true; posters.close(); panel?.dialog?.dismiss(); main.removeCallbacksAndMessages(null) }
    /** 搜索结果进入详情页，相关推荐为同一次搜索的其他结果 */
    private fun openSearchDetail(card: JSONObject) = openDetail(card, "搜索结果", related = adapter.cards.filter { it !== card && !it.has("history") })
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
        fun focusHistory(id:String?) { (rows["history:$id"] ?: rows.values.firstOrNull())?.let { card -> card.requestFocus(); card.post { ui.reveal(card) } } }
        fun submitCards(value:List<JSONObject>) { if (cards.isEmpty()) visible=72; cards=value; render() }
        private fun render() {
            val shown=cards.take(visible)
            val keys=shown.map { key(it) }
            val focused=rows.entries.find { it.value.hasFocus() }?.key
            val reorder=keys!=rows.keys.toList()
            if (reorder) {
                val previous=rows.toMap(); grid.removeAllViews(); watchRow.removeAllViews(); rows.clear()
                shown.forEachIndexed { index,card ->
                    val k=key(card); val root=previous[k] ?: createCard().also { TvMotion.rise(it,minOf(index,12)*32L,ui.px(18).toFloat(),480) }
                    rows[k]=root
                    // 历史观看排成一行横向货架；搜索结果仍是网格
                    if(card.has("history")) watchRow.addView(root,shelfCell(262))
                    else grid.addView(root,ui.gridCell(index,5,308).apply { setMargins(ui.px(6),0,ui.px(6),ui.px(21)) })
                }
                if(focused==null) watchShelf.scrollTo(0,0)
            }
            shown.forEach { card -> bind(rows.getValue(key(card)),card) }
            if (reorder && focused!=null) rows[focused]?.requestFocus()
            empty.visibility=if (cards.isEmpty() && searched.isNotEmpty()) View.VISIBLE else View.GONE
            watchSection.visibility=if (cards.isNotEmpty() && cards.first().has("history")) View.VISIBLE else View.GONE
            val history=cards.firstOrNull()?.has("history")==true
            grid.visibility=if (cards.isEmpty() || history) View.GONE else View.VISIBLE
            watchShelf.visibility=if (history) View.VISIBLE else View.GONE
            more.visibility=if (!history && cards.size>visible) View.VISIBLE else View.GONE
            more.text="显示更多（还有 ${maxOf(0,cards.size-visible)} 部）"
        }
        private fun createCard():LinearLayout=ui.column().apply {
            id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true
            // 海报圆角卡片：焦点时整体放大浮起，海报下方是片名与信息
            val poster=FrameLayout(activity).apply { background=ui.posterBackground(); clipToOutline=true; outlineProvider=android.view.ViewOutlineProvider.BACKGROUND }
            poster.addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
            poster.addView(ui.text("",10,Color.WHITE,true).apply { setPadding(ui.px(9),ui.px(4),ui.px(9),ui.px(4)); background=ui.background(Color.argb(150,0,0,0),radius=20) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply { setMargins(ui.px(10),ui.px(10),0,0) })
            poster.addView(android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=1000; progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(90,255,255,255)); visibility=View.GONE
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,ui.px(4),Gravity.BOTTOM).apply { setMargins(ui.px(10),0,ui.px(10),ui.px(10)) })
            addView(poster,ui.margins(-1,246))
            addView(ui.text("",15,bold=true).apply { ui.fontWeight(this,600); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,24).apply { topMargin=ui.px(12); leftMargin=ui.px(4); rightMargin=ui.px(4) })
            addView(ui.text("",12,ui.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END },ui.margins(-1,18).apply { topMargin=ui.px(3); leftMargin=ui.px(4); rightMargin=ui.px(4) })
            addView(ui.text("",12,ui.muted).apply { isSingleLine=true; ellipsize=android.text.TextUtils.TruncateAt.END; visibility=View.GONE },ui.margins(-1,18).apply { leftMargin=ui.px(4); rightMargin=ui.px(4) })
            ui.cardFocus(this,poster,getChildAt(1) as TextView)
        }
        private fun bind(root:LinearLayout,card:JSONObject) {
            val poster=root.getChildAt(0) as FrameLayout
            val image=poster.getChildAt(0) as ImageView
            if (image.tag!=card.optString("pic")) posters.load(image,card.optString("pic"))
            val entry=card.optJSONObject("history")
            poster.layoutParams=ui.margins(-1,if(entry!=null) 186 else 246)
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
            if(entry!=null) featureOf[root]=historyFeature(entry) else featureOf.remove(root)
            root.setOnClickListener { if(entry!=null) resumeHistory(entry) else openSearchDetail(card) }
            // 确认键：短按直接播放（原逻辑）；搜索结果长按进入详情页，可选来源、选集、下载到电脑
            var longPressed=false
            root.setOnKeyListener { _,code,event ->
                if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER) {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) longPressed=false
                    // 搜索结果：短按进入详情页，长按直接播放码率最高的来源
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount>0 && !longPressed && entry==null) { longPressed=true; playBest(card) }
                    if(event.action==KeyEvent.ACTION_UP && !longPressed) { if(entry!=null) resumeHistory(entry) else openSearchDetail(card) }
                    true
                } else false
            }
        }
    }
}
