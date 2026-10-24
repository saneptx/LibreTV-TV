package org.libretv.tv

import android.graphics.Color
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** 沿用旧版的双列来源列表和右侧详情，焦点浏览不触发换源。 */
internal class TvSourcePicker(private val ui: TvWidgets, private val panel: TvWidgets.Panel, private val posters: TvPosterLoader,
    private val current: JSONObject, private val choose: (JSONObject)->Unit, private val retest: (JSONObject)->Unit) {
    private val results=linkedMapOf<String,JSONObject>()
    private val buttons=linkedMapOf<String,Button>()
    private val row=ui.row().apply { gravity=Gravity.TOP; visibility=View.GONE }
    private val grid=ui.grid(2).apply { setPadding(ui.px(4),ui.px(6),ui.px(4),ui.px(6)) }
    private val detail=ui.column().apply { setPadding(ui.px(15),ui.px(10),ui.px(15),ui.px(10)); background=ui.background(Color.argb(112,8,15,25),Color.argb(20,255,255,255),8) }
    private val cover=ImageView(ui.activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val name=ui.text("",17,bold=true)
    private val note=ui.text("",11,ui.muted)
    private val quality=ui.text("",11,ui.muted)
    private val speed=ui.text("",16)
    private val count=ui.text("",16)
    private val action=ui.button("切换到此源") { selected()?.let { choose(it) } }
    private val retry=ui.button("重新检测此来源") { selected()?.let { startRetest(it) } }
    private val error=ui.text("",10,Color.rgb(229,184,134))
    private val loading=ui.text("正在搜索各来源…",15).apply { gravity=Gravity.CENTER }
    private var selectedKey=""
    private var tested=false
    private var probes=0
    private var retesting=""
    private var displayedOrder=emptyList<String>()
    private var reordering=false
    private var receiving=false
    private var cached=false
    private var refreshing=false
    private var warning=""
    init {
        panel.content.addView(loading,LinearLayout.LayoutParams(-1,0,1f))
        panel.content.addView(row,LinearLayout.LayoutParams(-1,0,1f))
        val list=ui.column()
        list.addView(ui.text("原始分辨率从高到低 · 确认键切换",12,ui.muted),ui.margins(-1,25))
        list.addView(object: ScrollView(ui.activity) {
            override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
                // 保留焦点框的空间，滚动到最后一项时也要完整显示卡片底边。
                val expanded=Rect(rectangle).apply { inset(-ui.px(6),-ui.px(6)) }
                return super.requestChildRectangleOnScreen(child,expanded,immediate)
            }
        }.apply {
            isFocusable=false
            // 卡片必须裁切在列表视口内；否则几十个来源会绘制到页脚和面板外。
            clipChildren=true; clipToPadding=true
            isVerticalScrollBarEnabled=true
            addView(grid)
        },LinearLayout.LayoutParams(-1,0,1f))
        row.addView(list,LinearLayout.LayoutParams(0,-1,1.15f).apply { rightMargin=ui.px(23) })
        row.addView(ScrollView(ui.activity).apply { isFocusable=false; addView(detail) },LinearLayout.LayoutParams(0,-1,1f))
        val header=ui.row().apply { gravity=Gravity.TOP }
        header.addView(cover,ui.margins(61,92))
        val title=ui.column()
        title.addView(name); title.addView(note,ui.margins(-1,-2).apply { topMargin=ui.px(7) }); title.addView(quality,ui.margins(-1,-2).apply { topMargin=ui.px(9) })
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=ui.px(13) }); detail.addView(header)
        val metrics=ui.row()
        listOf("响应速度" to speed,"可用集数" to count).forEach { (label,value) ->
            val cell=ui.column().apply { background=ui.background(Color.argb(7,255,255,255),radius=5); setPadding(ui.px(10),ui.px(8),ui.px(10),ui.px(8)) }
            cell.addView(ui.text(label,10,ui.muted),ui.margins(-1,-2).apply { bottomMargin=ui.px(4) }); cell.addView(value)
            metrics.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=ui.px(8) })
        }
        detail.addView(metrics,ui.margins(-1,-2).apply { topMargin=ui.px(12) })
        detail.addView(action,ui.margins(-1,39).apply { topMargin=ui.px(14) })
        detail.addView(retry,ui.margins(-1,37).apply { topMargin=ui.px(7) })
        detail.addView(error,ui.margins(-1,-2).apply { topMargin=ui.px(8) })
        panel.footer.text="方向键选择 · 确认换源 · 返回继续观看                       列表可上下滚动"
    }
    private fun key(item: JSONObject)="${item.optString("sourceKey")}:${item.optString("vodId")}"
    private fun selected()=results[selectedKey]
    private fun current(result: JSONObject)=key(result.getJSONObject("item"))==key(current)
    fun candidates(items: List<JSONObject>) {
        receiving=true
        val ids=items.map { key(it.getJSONObject("item")) }.toSet()
        results.keys.retainAll(ids)
        items.forEach { val id=key(it.getJSONObject("item")); if(!results.containsKey(id)) results[id]=it }
        if(!tested) { panel.status.text="正在检查来源可用性…"; loading.text="正在检查播放资源…" }
    }
    fun refreshing() { refreshing=true; warning=""; if(tested) summary() else panel.status.text="正在搜索同名影片…" }
    fun warning(message: String) { warning=message; refreshing=false; summary() }
    fun update(result: JSONObject) {
        val id=key(result.getJSONObject("item")); results[id]=result
        retesting=if(retesting==id) "" else retesting
        if(tested && !receiving) { updateButton(id); reorder(); if(id==selectedKey) updateDetail(); summary() }
    }
    fun quality(result: JSONObject) {
        val id=key(result.getJSONObject("item")); results[id]?.put("quality",result.optString("quality"))
        if(result.optInt("height")>0) results[id]?.put("height",result.optInt("height"))
        probes++
        if(tested && !receiving) { updateButton(id); reorder(); if(id==selectedKey) updateDetail(); summary() }
    }
    fun tested(fromCache: Boolean=false, backgroundRefresh: Boolean=false) {
        receiving=false
        val first=!tested
        val focused=buttons.values.firstOrNull { it.hasFocus() }?.let { b -> buttons.entries.firstOrNull { it.value===b }?.key }
        cached=fromCache; refreshing=backgroundRefresh; warning=""
        tested=true; loading.visibility=if(results.isEmpty()) View.VISIBLE else View.GONE; loading.text="其他点播源未找到同名资源，请返回继续播放"
        row.visibility=if(results.isEmpty()) View.GONE else View.VISIBLE
        buttons.keys.filter { !results.containsKey(it) }.forEach { id -> buttons.remove(id)?.let { grid.removeView(it) } }
        results.values.forEach { result ->
            val id=key(result.getJSONObject("item"))
            if(buttons.containsKey(id)) { updateButton(id); return@forEach }
            val button=ui.button("") {
                val known=results[id] ?: return@button
                if(current(known)) panel.dialog.dismiss()
                else if(known.optInt("episodes")>0) choose(known)
                else startRetest(known)
            }.apply {
                gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT; setPadding(ui.px(12),ui.px(7),ui.px(12),ui.px(7)); maxLines=3
                setOnFocusChangeListener { _,focused -> if(!reordering) { if(focused) { selectedKey=id; buttons.forEach { (k,b) -> b.isSelected=k==id }; updateDetail() }; updateButton(id) } }
            }
            buttons[id]=button; updateButton(id)
        }
        if(!results.containsKey(selectedKey)) selectedKey=results.values.firstOrNull { current(it) }?.let { key(it.getJSONObject("item")) } ?: sortedKeys().firstOrNull().orEmpty()
        reorder()
        if(first || focused!=null && !buttons.containsKey(focused)) buttons[selectedKey]?.requestFocus()
        updateDetail(); summary()
    }
    private fun sortedKeys()=TvSourceOrder.sorted(results.map { (id,result) -> TvSourceOrder.Value(id,
        result.optInt("height").takeIf { it>0 } ?: TvSourceOrder.heightFromLabel(result.optString("quality")),
        result.optBoolean("ok"),result.optInt("ms",Int.MAX_VALUE)) })
    private fun reorder() {
        val order=sortedKeys()
        order.forEachIndexed { index,id ->
            val r=results[id] ?: return@forEachIndexed
            buttons[id]?.nextFocusRightId=if(index%2==0 && index+1<order.size) buttons[order[index+1]]?.id ?: View.NO_ID
                else if(!current(r) && r.optInt("episodes")>0) action.id
                else if(!r.optBoolean("ok")) retry.id
                else buttons[id]?.id ?: View.NO_ID
        }
        if(order==displayedOrder && grid.childCount==order.size) return
        val focused=buttons.values.firstOrNull { it.hasFocus() }
        reordering=true
        try {
            grid.removeAllViews()
            order.forEachIndexed { index,id -> buttons[id]?.let { button ->
                grid.addView(button,ui.gridCell(index,2,64).apply { setMargins(ui.px(4),ui.px(4),ui.px(5),ui.px(5)) })
            } }
            focused?.requestFocus()
        } finally { reordering=false }
        displayedOrder=order
        buttons.forEach { (id,b) -> b.isSelected=id==selectedKey; updateButton(id) }
        focused?.post { if(focused.hasFocus()) focused.requestRectangleOnScreen(android.graphics.Rect(0,0,focused.width,focused.height),true) }
    }
    private fun startRetest(result: JSONObject) {
        if(retesting.isNotEmpty()) return
        retesting=key(result.getJSONObject("item")); updateButton(retesting); updateDetail(); retest(result)
    }
    private fun updateButton(id: String) {
        val result=results[id] ?: return
        val meta=if(retesting==id) "重新检测中…" else if(result.optBoolean("ok")) "● ${if(current(result)) "当前播放" else "可用"}  ${result.optString("quality").ifEmpty { "${result.optInt("ms")} ms" }}" else "不可用 · 确认重测"
        val label="${result.getJSONObject("item").optString("sourceName")}\n$meta"
        buttons[id]?.let { button ->
            button.text=SpannableString(label).apply {
                val start=label.indexOf('\n')+1
                setSpan(RelativeSizeSpan(.75f),start,label.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(if(button.hasFocus()) Color.rgb(62,86,103) else ui.muted),start,label.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(result.optBoolean("ok")) setSpan(ForegroundColorSpan(if(result.optInt("ms")>2000) Color.rgb(237,190,101) else Color.rgb(70,220,162)),start,start+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else setSpan(ForegroundColorSpan(if(button.hasFocus()) Color.rgb(133,69,0) else Color.rgb(241,184,121)),start,label.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
    fun complete() { probes=results.size; refreshing=false; summary() }
    private fun summary() { panel.status.text="${if(warning.isNotEmpty()) "$warning · " else if(refreshing) "后台刷新中 · " else if(cached) "已使用缓存 · " else ""}共 ${results.size} 个来源 · ${results.values.count { it.optBoolean("ok") }} 个可用 · ${if(probes>=results.size) "画质探测完成" else "画质探测 $probes/${results.size}"}" }
    private fun updateDetail() {
        val r=selected() ?: return
        val item=r.getJSONObject("item"); if(cover.tag!=item.optString("pic")) posters.load(cover,item.optString("pic"))
        name.setLabel(item.optString("sourceName")); note.setLabel(if(current(r)) "当前正在播放" else if(r.optInt("episodes")>0) "可切换至此来源" else "暂时无法获取资源")
        quality.setLabel("画质  ${r.optString("quality").ifEmpty { "待检测" }}")
        speed.setLabel(if(r.optBoolean("ok")) "${r.optInt("ms")} ms" else "暂不可用"); count.setLabel(if(r.optInt("episodes")>0) "${r.optInt("episodes")} 集" else "暂无")
        action.setLabel(if(current(r)) "当前正在使用" else "切换到此源"); action.isEnabled=!current(r) && r.optInt("episodes")>0; action.alpha=if(action.isEnabled) 1f else .45f
        retry.visibility=if(r.optBoolean("ok")) View.GONE else View.VISIBLE; retry.isEnabled=retesting.isEmpty()
        retry.setLabel(if(retesting==selectedKey) "重新检测中…" else "重新检测此来源")
        action.nextFocusLeftId=buttons[selectedKey]?.id ?: View.NO_ID
        retry.nextFocusLeftId=buttons[selectedKey]?.id ?: View.NO_ID
        error.setLabel(r.optString("error")); error.visibility=if(r.optBoolean("ok") || r.optString("error").isEmpty()) View.GONE else View.VISIBLE
    }
}
