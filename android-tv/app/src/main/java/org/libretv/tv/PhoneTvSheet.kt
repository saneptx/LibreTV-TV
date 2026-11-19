package org.libretv.tv

import android.app.Dialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject

/** 电视伴侣面板：寻找并连接电视；连接后可遥控播放、替电视打字 / 搜索；投屏也从这里发出。 */
@UnstableApi
internal class PhoneTvSheet(private val ui: PhoneUi) {
    private val kit get() = ui.kit
    private val link get() = ui.link
    private var dialog: Dialog? = null
    private var body: LinearLayout? = null
    private var generation = 0
    private var afterPair: (() -> Unit)? = null
    private val density get() = ui.activity.resources.displayMetrics.density

    fun show() {
        dialog?.dismiss()
        val (d, b) = kit.sheet("电视")
        dialog = d; body = b
        d.setOnDismissListener { if (dialog === d) { dialog = null; body = null; generation++; link.discovery.stop(); afterPair = null } }
        render(); d.show()
    }
    private fun render() { if (link.selected() == null) picker() else panel() }

    /** 把这一集交给电视接着放 */
    fun cast(item: JSONObject, index: Int, position: Double, onSent: () -> Unit = {}) {
        val send = send@{
            val tv = link.selected() ?: return@send
            kit.toast("正在投到 ${tv.name}…")
            link.call(tv, "/v1/cast", JSONObject().put("sourceKey", item.optString("sourceKey")).put("sourceUrl", item.optString("sourceUrl")).put("sourceName", item.optString("sourceName"))
                .put("vodId", item.optString("vodId")).put("title", item.optString("name")).put("pic", item.optString("pic")).put("index", index).put("position", position)) { reply ->
                if (reply.ok) { onSent(); kit.toast("已投到 ${tv.name}"); show() } else kit.toast(reply.error.orEmpty())
            }
        }
        if (link.selected() != null) send() else { show(); afterPair = send }
    }

    private fun picker() {
        val b = body ?: return; val mine = ++generation
        b.removeAllViews()
        b.addView(kit.text("电视 / 投影仪打开 SuperTV 并和手机连同一个 Wi-Fi，就会出现在这里。第一次连接需要在电视上用遥控器确认。", 13, kit.muted).apply { setLineSpacing(kit.dp(3f), 1f) })
        val status = kit.text("", 14, kit.ink).apply { visibility = View.GONE }
        val list = kit.column()
        b.addView(list, kit.lp(top = 12)); b.addView(status, kit.lp(top = 12))
        var busy = false
        fun connect(host: String, port: Int) {
            if (busy) return
            busy = true; status.visibility = View.VISIBLE; status.text = "正在连接…"
            link.pair(host, port, onWaiting = { if (mine == generation) status.text = "请在电视上用遥控器选择「允许」" }) { error ->
                if (mine != generation) return@pair
                busy = false
                if (error != null) status.text = error else { val next = afterPair; afterPair = null; render(); next?.invoke() }
            }
        }
        fun row(name: String, detail: String, onClick: () -> Unit) = kit.row().apply {
            background = kit.pressable(kit.raised, 12); setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12)); isClickable = true; setOnClickListener { onClick() }
            addView(ImageView(ui.activity).apply { setImageDrawable(PhoneIcon(PhoneIcon.TV, kit.ink, density)) }, kit.lp(24, 24))
            val labels = kit.column(); labels.addView(kit.text(name, 15, kit.ink, 600, 1)); labels.addView(kit.text(detail, 12, kit.muted, lines = 1), kit.lp(top = 4))
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = kit.dp(12) })
        }
        var shownKey = ""
        fun refresh() {
            if (mine != generation) return
            val found = link.found(); val paired = link.paired()
            val key = found.joinToString { it.toString() } + "|" + paired.joinToString { it.id + it.host }
            if (key != shownKey) {
                shownKey = key; list.removeAllViews()
                paired.forEach { tv -> list.addView(row(tv.name, "已连接过 · ${tv.host}") { link.select(tv.id); render() }, kit.lp(top = 8)) }
                found.filter { f -> paired.none { it.id.isNotEmpty() && it.id == f.optString("id") } }.forEach { f ->
                    list.addView(row(f.optString("name"), "${f.optString("host")} · 点一下连接") { connect(f.optString("host"), f.optInt("port")) }, kit.lp(top = 8))
                }
                if (list.childCount == 0) list.addView(kit.row().apply {
                    addView(ProgressBar(ui.activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(kit.muted) }, kit.lp(18, 18))
                    addView(kit.text("正在寻找电视…", 14, kit.muted), kit.lp(-2, -2, left = 10))
                }, kit.lp(top = 12, bottom = 4))
            }
            ui.main.postDelayed({ refresh() }, 1000)
        }
        link.discovery.start(); refresh()
        b.addView(kit.text("找不到？填写电视「设置 → 手机」里显示的地址", 13, kit.muted), kit.lp(top = 20))
        val manual = kit.row()
        val address = ui.input("例如 192.168.1.77").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        fun go() { val parsed = TvLink.parseAddress(address.text.toString()); if (parsed == null) { status.visibility = View.VISIBLE; status.text = "地址格式不对，应类似 192.168.1.77" } else { ui.hideKeyboard(); connect(parsed.first, parsed.second) } }
        address.setOnEditorActionListener { _, _, _ -> go(); true }
        manual.addView(address, LinearLayout.LayoutParams(0, kit.dp(46), 1f)); manual.addView(kit.button("连接", true) { go() }, kit.lp(-2, 46, left = 10))
        b.addView(manual, kit.lp(top = 10))
    }

    private fun panel() {
        val b = body ?: return; val tv = link.selected() ?: return; val mine = ++generation
        b.removeAllViews()
        val head = kit.row()
        val labels = kit.column(); labels.addView(kit.text(tv.name, 17, kit.ink, 600, 1))
        val now = kit.text("正在连接…", 13, kit.muted, lines = 2); labels.addView(now, kit.lp(top = 5))
        head.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(kit.text("换一台", 13, kit.muted).apply { setPadding(kit.dp(10), kit.dp(10), 0, kit.dp(10)); setOnClickListener { link.select(""); render() } })
        b.addView(head)
        fun control(action: String, value: Double = 0.0) { link.call(tv, "/v1/control", JSONObject().put("action", action).put("value", value)) { reply -> if (!reply.ok && mine == generation) kit.toast(reply.error.orEmpty()) } }
        fun round(kind: Int, size: Int, onClick: () -> Unit) = kit.icon(kind, size) { onClick() }.apply { background = kit.pressable(kit.raised, size / 2) }
        val playButton = round(PhoneIcon.PAUSE, 64) { control("toggle") }
        val first = kit.row().apply { gravity = Gravity.CENTER }
        first.addView(round(PhoneIcon.REWIND, 52) { control("seek", -10.0) }); first.addView(playButton, kit.lp(64, 64, left = 22, right = 22)); first.addView(round(PhoneIcon.FORWARD30, 52) { control("seek", 30.0) })
        val second = kit.row().apply { gravity = Gravity.CENTER }
        second.addView(round(PhoneIcon.PREV, 46) { control("prev") }); second.addView(round(PhoneIcon.STOP, 46) { control("stop") }, kit.lp(46, 46, left = 26, right = 26)); second.addView(round(PhoneIcon.NEXT, 46) { control("next") })
        val controls = kit.column(); controls.addView(first, kit.lp(top = 20)); controls.addView(second, kit.lp(top = 14))
        controls.addView(kit.text("后退 10 秒 · 暂停 / 继续 · 快进 30 秒\n上一集 · 停止 · 下一集", 11, kit.faint).apply { gravity = Gravity.CENTER; setLineSpacing(kit.dp(3f), 1f) }, kit.lp(top = 10))
        controls.visibility = View.GONE
        b.addView(controls)

        val inputLabel = kit.text("在电视上搜索", 13, kit.muted); b.addView(inputLabel, kit.lp(top = 22))
        val field = ui.input("输入片名，电视直接搜索", EditorInfo.IME_ACTION_SEARCH); b.addView(field, kit.lp(-1, 46, top = 8))
        var typing = false; var applying = false
        val sendText = Runnable { link.call(tv, "/v1/text", JSONObject().put("text", field.text.toString()).put("submit", false)) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (typing && !applying) { ui.main.removeCallbacks(sendText); ui.main.postDelayed(sendText, 120) } }
        })
        field.setOnEditorActionListener { _, _, _ ->
            val value = field.text.toString().trim()
            if (typing) { ui.main.removeCallbacks(sendText); link.call(tv, "/v1/text", JSONObject().put("text", field.text.toString()).put("submit", true)) { r -> if (!r.ok) kit.toast(r.error.orEmpty()) } }
            else if (value.isNotEmpty()) link.call(tv, "/v1/search", JSONObject().put("query", value)) { r -> if (r.ok) field.setText(""); kit.toast(if (r.ok) "电视正在搜索「$value」" else r.error.orEmpty()) }
            ui.hideKeyboard(); true
        }
        b.addView(kit.text("断开并忘记这台电视", 13, kit.muted).apply { gravity = Gravity.CENTER; setPadding(0, kit.dp(14), 0, kit.dp(4)); setOnClickListener { link.forget(tv.id); render() } }, kit.lp(top = 14))

        fun poll() {
            if (mine != generation) return
            link.call(tv, "/v1/state", null, 4000) { reply ->
                if (mine != generation) return@call
                if (reply.status == 401) { render(); return@call }
                val s = reply.body
                if (!reply.ok || s == null) { now.text = reply.error; controls.visibility = View.GONE }
                else {
                    val playing = s.optBoolean("playing")
                    controls.visibility = if (playing) View.VISIBLE else View.GONE
                    now.text = if (s.optBoolean("loading")) "电视正在打开影片…" else if (playing) listOf(s.optString("title"), if (s.optInt("episodes") > 1) "第 ${s.optInt("index") + 1} 集" else "", "${kit.time(s.optDouble("position"))} / ${kit.time(s.optDouble("duration"))}").filter { it.isNotBlank() }.joinToString(" · ") else "已连接 · 电视空闲"
                    playButton.setImageDrawable(PhoneIcon(if (s.optBoolean("paused")) PhoneIcon.PLAY else PhoneIcon.PAUSE, kit.ink, density))
                    val input = s.optBoolean("input")
                    if (input != typing) {
                        typing = input
                        inputLabel.text = if (input) "替电视打字：${s.optString("inputHint").ifBlank { "电视上选中的输入框" }}" else "在电视上搜索"
                        field.hint = if (input) "这里输入的字会出现在电视上" else "输入片名，电视直接搜索"
                        field.imeOptions = if (input) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_SEARCH
                        // 切到打字：接着电视输入框里已有的内容写；切回搜索：清空
                        applying = true; field.setText(if (input) s.optString("inputText") else ""); field.setSelection(field.text.length); applying = false
                        if (field.hasFocus()) (ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).restartInput(field)
                    }
                }
                ui.main.postDelayed({ poll() }, 1500)
            }
        }
        poll()
    }
    fun release() { generation++; dialog?.dismiss(); dialog = null }
}
