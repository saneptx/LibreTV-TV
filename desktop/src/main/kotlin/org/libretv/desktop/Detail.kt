package org.libretv.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.libretv.tv.core.objects

private fun same(a: JSONObject?, b: JSONObject?) = a != null && b != null && a.optString("sourceKey") == b.optString("sourceKey") && a.optString("vodId") == b.optString("vodId")

/** 影片详情：片源、剧集、简介；从热门榜进来只有片名时，先在已启用的片源里找。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(card: JSONObject) {
    val m = LocalModel.current
    var current by remember(card) { mutableStateOf(card) }
    var selected by remember(card) { mutableStateOf<JSONObject?>(null) }
    var picked by remember(card) { mutableStateOf(false) }
    var detail by remember(card) { mutableStateOf<JSONObject?>(null) }
    var note by remember(card) { mutableStateOf("") }
    var busy by remember(card) { mutableStateOf(false) }
    val requests = remember(card) { mutableListOf<String>() }
    val history = m.historyFor(current.optString("name"))
    fun select(item: JSONObject, byUser: Boolean = false) {
        selected = item; picked = picked || byUser; detail = null; note = "正在读取 ${item.optString("sourceName")} 的剧集…"
        requests.forEach { m.backend.cancel(it) }; requests.clear()
        requests.add(m.request("detail", JSONObject().put("item", item)) { event ->
            if (!same(selected, item)) return@request
            if (event.optString("event") == "error") { note = "${item.optString("sourceName")}：${event.optString("error")}（可以换一个片源）"; return@request }
            detail = event.optJSONObject("value")?.optJSONObject("detail")
            note = if ((detail?.optJSONArray("episodes")?.length() ?: 0) == 0) "这个片源没有可播放的剧集，换一个试试" else ""
        })
    }
    fun chooseInitial() {
        val items = current.optJSONArray("items").objects(); val h = m.historyFor(current.optString("name"))
        (items.firstOrNull { same(it, h) } ?: items.firstOrNull())?.let { select(it) }
    }
    DisposableEffect(card) {
        if ((card.optJSONArray("items")?.length() ?: 0) > 0) chooseInitial()
        else {
            note = "正在片源里查找「${card.optString("name")}」…"
            requests.add(m.request("search", JSONObject().put("query", card.optString("name")).put("record", false)) { event ->
                if (event.optString("event") == "error") { note = event.optString("error"); return@request }
                val value = event.optJSONObject("value") ?: return@request
                val groups = value.optJSONArray("groups").objects()
                val match = groups.firstOrNull { m.titleKey(it.optString("name")) == m.titleKey(card.optString("name")) } ?: groups.firstOrNull { it.optString("name").contains(card.optString("name")) }
                if (match != null) {
                    val grew = (match.optJSONArray("items")?.length() ?: 0) > (current.optJSONArray("items")?.length() ?: 0)
                    // 海报沿用榜单的，片源给的常常打不开
                    if (grew) current = JSONObject(match.toString()).apply { if (card.optString("pic").isNotBlank()) put("pic", card.optString("pic")) }
                    if (selected == null) chooseInitial()
                } else if (value.optBoolean("done")) note = "已启用的片源里没有找到这部影片"
            })
        }
        onDispose { requests.forEach { m.backend.cancel(it) } }
    }
    val episodes = detail?.optJSONArray("episodes")?.length() ?: 0
    val video = detail?.optJSONObject("videoInfo") ?: JSONObject()
    val watched = history?.takeIf { same(it, selected) }?.optInt("episodeIndex") ?: -1
    fun resumeIndex(): Int { val h = history ?: return 0; return if (same(h, selected) || !picked) h.optInt("episodeIndex").coerceAtMost(maxOf(0, episodes - 1)) else 0 }
    fun play(index: Int) { selected?.let { m.open(Screen.Player(it, index)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Poster(current.optString("pic").ifBlank { video.optString("cover") }, Modifier.size(220.dp, 330.dp), 16.dp)
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).widthIn(max = 760.dp)) {
                T(current.optString("name"), 32.sp, weight = FontWeight.Bold, lines = 2)
                Spacer(Modifier.height(10.dp))
                T(listOf(video.optString("year").ifBlank { current.optString("year") }, video.optString("area"), video.optString("typeName").ifBlank { current.optString("typeName") }, video.optString("remarks").ifBlank { current.optString("remarks") }).filter { it.isNotBlank() }.joinToString(" · "), 14.sp, C.muted)
                listOf("导演" to video.optString("director"), "主演" to video.optString("actor")).filter { it.second.isNotBlank() }.forEach { (k, v) -> Spacer(Modifier.height(6.dp)); T("$k：$v", 13.sp, C.muted, lines = 2) }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = if (busy) "正在比较片源…" else if (history == null) "播放" else if ((history.optInt("totalEpisodes")) > 1) "继续播放 · 第 ${history.optInt("episodeIndex") + 1} 集" else "继续播放"
                    Pill(label, primary = true, enabled = selected != null && !busy, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp, vertical = 13.dp)) {
                        val items = current.optJSONArray("items") ?: JSONArray()
                        // 没看过、也没手动选片源：先比较各片源的实测码率，播最清晰的
                        if (history == null && !picked && items.length() > 1) {
                            busy = true
                            m.request("best-source", JSONObject().put("items", items)) { event ->
                                busy = false
                                val value = event.optJSONObject("value"); val best = value?.optJSONObject("item") ?: selected ?: return@request
                                if (value?.optString("quality").orEmpty().isNotEmpty()) m.toast("已选最清晰的片源：${best.optString("sourceName")} · ${value?.optString("quality")}")
                                m.open(Screen.Player(best, 0))
                            }
                        } else play(resumeIndex())
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(if (m.isFavorite(current)) Icon.HEART_ON else Icon.HEART, 46.dp, filled = true) {
                        m.request("toggle-favorite", JSONObject().put("card", current)) { event -> event.optJSONObject("value")?.let { m.toast(if (it.optBoolean("favorite")) "已加入片库" else "已从片库移除") } }
                    }
                }
                if (note.isNotBlank()) { Spacer(Modifier.height(12.dp)); T(note, 13.sp, C.muted) }
                val desc = video.optString("desc").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
                if (desc.isNotEmpty()) { Spacer(Modifier.height(18.dp)); T(desc, 14.sp, C.muted, lines = 5, lineHeight = 22.sp) }
            }
        }
        val items = current.optJSONArray("items").objects()
        if (items.size > 1) {
            Spacer(Modifier.height(32.dp)); T("片源 · ${items.size} 个", 17.sp, weight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item -> Chip(listOf(item.optString("sourceName"), item.optString("remarks")).filter { it.isNotBlank() }.joinToString(" · "), same(item, selected)) { select(item, true) } }
            }
        }
        if (episodes > 1) {
            Spacer(Modifier.height(28.dp)); T("选集 · 共 $episodes 集", 17.sp, weight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 0 until episodes) Chip("${n + 1}", n == watched, minWidth = 64.dp) { play(n) }
            }
        }
    }
}
