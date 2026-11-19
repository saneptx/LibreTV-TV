package org.libretv.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.libretv.tv.core.objects

/** 海报卡片：悬停时放大并出现白色描边；可带进度条和右上角的删除按钮 */
@Composable
fun MovieCard(title: String, pic: String, note: String = "", badge: String = "", progress: Float? = null, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f)
    Column(Modifier.hoverable(source).press(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).scale(scale)) {
            Poster(pic, Modifier.fillMaxSize().then(if (hovered) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)) {
                if (badge.isNotBlank()) Badge(badge, Modifier.align(Alignment.BottomStart).padding(8.dp))
                if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
                }
            }
            if (onRemove != null && hovered) IconButton(Icon.CLOSE, 30.dp, filled = true, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) { onRemove() }
        }
        Spacer(Modifier.height(8.dp))
        T(title, 14.sp, weight = FontWeight.Medium, lines = 1)
        if (note.isNotBlank()) { Spacer(Modifier.height(3.dp)); T(note, 12.sp, C.muted, lines = 1) }
    }
}

fun LazyGridScope.header(title: String, trailing: (@Composable () -> Unit)? = null) = item(span = { GridItemSpan(maxLineSpan) }) {
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, 22.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f)); trailing?.invoke()
    }
}
fun LazyGridScope.line(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun PosterGrid(content: LazyGridScope.() -> Unit) = LazyVerticalGrid(GridCells.Adaptive(158.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 40.dp),
    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)

@Composable
fun HomeScreen() {
    val m = LocalModel.current; val dialog = LocalDialog.current
    val history = m.history().take(12)
    PosterGrid {
        if (history.isNotEmpty()) {
            header("继续观看")
            items(history, key = { "h" + it.optString("id") }) { entry ->
                val duration = entry.optDouble("duration", 0.0); val position = entry.optDouble("playbackPosition", 0.0); val total = entry.optInt("totalEpisodes")
                MovieCard(entry.optString("title"), entry.optString("pic"), if (total > 1) "第 ${entry.optInt("episodeIndex") + 1} 集 · 共 $total 集" else m.sourceName(entry.optString("sourceKey")),
                    progress = if (duration > 0) (position / duration).toFloat() else null, onRemove = { confirmRemoveHistory(m, dialog, entry) }) {
                    m.open(Screen.Player(m.historyItem(entry), entry.optInt("episodeIndex")))
                }
            }
        }
        fun hot(title: String, list: List<JSONObject>) {
            if (list.isEmpty()) return
            header(title)
            items(list.take(18), key = { title + it.optString("id") }) { item ->
                MovieCard(item.optString("title"), item.optString("cover"), listOf(item.optString("rate").takeIf { it.isNotBlank() && it != "0" }?.let { "豆瓣 $it" }.orEmpty(), item.optString("note")).filter { it.isNotBlank() }.joinToString(" · ")) {
                    m.open(Screen.Detail(JSONObject().put("name", item.optString("title")).put("pic", item.optString("cover")).put("items", JSONArray())))
                }
            }
        }
        hot("热门剧集", m.hotTv); hot("热门电影", m.hotMovie)
        if (history.isEmpty() && m.hotTv.isEmpty() && m.hotMovie.isEmpty()) line { Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) { T("在右上角搜索片名开始观看", 16.sp, C.muted) } }
    }
}

fun confirmRemoveHistory(m: AppModel, dialog: DialogState, entry: JSONObject) {
    val same = m.data.optJSONArray("history").objects().filter { m.titleKey(it.optString("title")) == m.titleKey(entry.optString("title")) }
    dialog.confirm("删除观看记录", "删除「${entry.optString("title")}」的观看记录和播放进度。连接的电视、手机上也会同步删除。", "删除") {
        m.request("remove-history", JSONObject().put("items", JSONArray(same.map { JSONObject().put("sourceKey", it.optString("sourceKey")).put("vodId", it.optString("vodId")) }))) { if (it.optString("event") == "result") m.toast("已删除") }
    }
}

@Composable
fun SearchScreen(query: String) {
    val m = LocalModel.current
    val cards = remember(query) { mutableStateListOf<JSONObject>() }
    var status by remember(query) { mutableStateOf("正在搜索…") }
    DisposableEffect(query) {
        val id = m.request("search", JSONObject().put("query", query)) { event ->
            when (event.optString("event")) {
                "result", "search-progress" -> {
                    val value = event.optJSONObject("value") ?: return@request
                    cards.clear(); cards.addAll(value.optJSONArray("groups").objects())
                    val done = value.optBoolean("done")
                    status = if (!done) "正在搜索 ${value.optInt("total")} 个片源 · 已找到 ${cards.size} 部" else if (cards.isEmpty()) "没有找到相关影片，换个关键词试试" else "找到 ${cards.size} 部影片" + (value.optInt("failures").takeIf { it > 0 }?.let { " · $it 个片源没有响应" } ?: "")
                }
                "error" -> status = event.optString("error")
            }
        }
        onDispose { m.backend.cancel(id) }
    }
    PosterGrid {
        line { Column(Modifier.padding(top = 24.dp)) { T("「$query」", 24.sp, weight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); T(status, 13.sp, C.muted) } }
        items(cards, key = { it.optString("key") }) { card ->
            MovieCard(card.optString("name"), card.optString("pic"), listOf(card.optString("year"), card.optString("typeName"), "${card.optJSONArray("items")?.length() ?: 0} 个片源").filter { it.isNotBlank() }.joinToString(" · "), card.optString("remarks")) {
                m.open(Screen.Detail(card))
            }
        }
    }
}

@Composable
fun LibraryScreen() {
    val m = LocalModel.current; val dialog = LocalDialog.current
    val favorites = m.data.optJSONArray("favorites").objects(); val history = m.history()
    PosterGrid {
        line { T("片库", 28.sp, weight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp)) }
        if (favorites.isEmpty() && history.isEmpty()) line { Box(Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) { T("收藏的影片和看过的记录会出现在这里", 15.sp, C.muted) } }
        if (favorites.isNotEmpty()) {
            header("我的收藏")
            items(favorites, key = { "f" + it.optString("name") + it.optString("year") }) { card ->
                MovieCard(card.optString("name"), card.optString("pic"), listOf(card.optString("year"), card.optString("remarks")).filter { it.isNotBlank() }.joinToString(" · ")) { m.open(Screen.Detail(card)) }
            }
        }
        if (history.isNotEmpty()) {
            header("观看记录") { Pill("清空", padding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)) {
                dialog.confirm("清空观看记录", "删除全部观看记录和播放进度，片库收藏不受影响。连接的电视、手机上也会同步清空，无法撤销。", "全部清空") { m.request("clear-history") { if (it.optString("event") == "result") m.toast("已清空") } }
            } }
            items(history, key = { "r" + it.optString("id") }) { entry ->
                val duration = entry.optDouble("duration", 0.0); val position = entry.optDouble("playbackPosition", 0.0); val total = entry.optInt("totalEpisodes")
                MovieCard(entry.optString("title"), entry.optString("pic"), listOf(if (total > 1) "第 ${entry.optInt("episodeIndex") + 1} 集" else "", if (duration > 0) (if (position >= duration - 15) "已看完" else "看到 ${clock(position)}") else "").filter { it.isNotBlank() }.joinToString(" · "),
                    progress = if (duration > 0) (position / duration).toFloat() else null, onRemove = { confirmRemoveHistory(m, dialog, entry) }) {
                    m.open(Screen.Player(m.historyItem(entry), entry.optInt("episodeIndex")))
                }
            }
        }
    }
}

fun clock(seconds: Double): String {
    val t = seconds.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 0
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}
fun bytes(n: Long): String = when { n >= 1L shl 30 -> "%.1f GB".format(n / 1073741824.0); n >= 1L shl 20 -> "%.0f MB".format(n / 1048576.0); else -> "${n / 1024} KB" }

/** 电脑片库：串流服务替电视下载、处理好的影片 */
@Composable
fun DownloadsScreen() {
    val m = LocalModel.current; val dialog = LocalDialog.current
    val items = remember { mutableStateListOf<JSONObject>() }
    var status by remember { mutableStateOf("正在读取…") }
    LaunchedEffect(Unit) {
        while (true) {
            m.request("desktop-downloads") { event ->
                val value = event.optJSONObject("value")
                if (event.optString("event") == "error") status = event.optString("error")
                else if (value?.optBoolean("available") == false) status = "串流服务还没有运行，启动后这里会显示电视下载到电脑的影片"
                else { val list = value?.optJSONArray("items").objects(); items.clear(); items.addAll(list.sortedByDescending { it.optLong("createdAt") }); status = if (list.isEmpty()) "还没有下载。在电视上播放时选择「下载」，影片会处理好后存到这台电脑" else "" }
            }
            delay(3000)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { T("电脑片库", 28.sp, weight = FontWeight.Bold) }
        if (status.isNotEmpty()) item { T(status, 14.sp, C.muted, modifier = Modifier.padding(top = 20.dp)) }
        items(items, key = { it.optString("id") }) { d ->
            val meta = d.optJSONObject("meta") ?: JSONObject(); val state = d.optString("state")
            val ready = state == "done"
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(meta.optString("pic"), Modifier.size(54.dp, 80.dp), 8.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        T(d.optString("title"), 16.sp, weight = FontWeight.SemiBold, lines = 1); Spacer(Modifier.height(4.dp)); T(d.optString("subtitle"), 13.sp, C.muted, lines = 1); Spacer(Modifier.height(6.dp))
                        T(when (state) {
                            "done" -> listOf(d.optString("label"), bytes(d.optLong("bytes")), d.optDouble("duration", 0.0).takeIf { it > 0 }?.let { clock(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                            "error" -> "失败：${d.optString("error")}"
                            "queued" -> "排队中"
                            else -> "处理中 ${d.optInt("done")} / ${d.optInt("segments")} 段 · ${bytes(d.optLong("bytes"))}"
                        }, 12.sp, if (state == "error") C.ink else C.faint, lines = 2)
                    }
                    if (ready) { Pill("播放", primary = true) { m.open(Screen.Player(JSONObject().put("sourceKey", meta.optString("sourceKey")).put("sourceName", meta.optString("sourceName")).put("sourceUrl", meta.optString("sourceUrl")).put("vodId", meta.optString("vodId")).put("name", meta.optString("name")).put("pic", meta.optString("pic")), meta.optInt("index"), fromLibrary = true)) }; Spacer(Modifier.width(8.dp)) }
                    IconButton(Icon.CLOSE) { dialog.confirm("删除下载", "从这台电脑上删除「${d.optString("title")} ${d.optString("subtitle")}」的文件。", "删除") { m.request("delete-download", JSONObject().put("id", d.optString("id"))) { if (it.optString("event") == "result") m.toast("已删除") else m.toast(it.optString("error")) } } }
                }
            }
        }
    }
}
