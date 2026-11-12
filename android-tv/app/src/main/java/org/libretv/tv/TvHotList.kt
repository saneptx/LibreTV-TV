package org.libretv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 本周热门：直接向豆瓣取「热门剧集 / 热门电影」两份榜单（免登录的公开接口），结果在本机缓存 6 小时；
 * 取不到时沿用上一次的缓存，两者都没有就不显示这一栏。只取片名、海报和评分，点击后用片名在已启用的片源里搜索。 */
internal class TvHotList(context: Context) {
    class Item(val title: String, val cover: String, val rate: String, val note: String, val tv: Boolean)
    private val prefs = context.getSharedPreferences("supertv-hot", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    fun load(done: (tv: List<Item>, movie: List<Item>) -> Unit) {
        val cached = prefs.getString("lists", null)
        val fresh = System.currentTimeMillis() - prefs.getLong("at", 0) < 6 * 3600_000L
        if (cached != null) parse(cached)?.let { done(it.first, it.second) }
        if (cached != null && fresh) return
        Thread {
            try {
                val lists = JSONObject().put("tv", fetch("tv")).put("movie", fetch("movie")).toString()
                val parsed = parse(lists) ?: return@Thread
                if (parsed.first.isEmpty() && parsed.second.isEmpty()) return@Thread
                prefs.edit().putString("lists", lists).putLong("at", System.currentTimeMillis()).apply()
                main.post { if (!closed) done(parsed.first, parsed.second) }
            } catch (_: Exception) { /* 网络不通或接口变动：保留旧缓存 */ }
        }.start()
    }
    fun close() { closed = true }
    private fun fetch(type: String): JSONArray {
        val url = URL("https://movie.douban.com/j/search_subjects?type=$type&tag=${URLEncoder.encode("热门", "UTF-8")}&sort=recommend&page_limit=20&page_start=0")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            setRequestProperty("Referer", "https://movie.douban.com/"); setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return JSONArray()
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("subjects") ?: JSONArray()
        } finally { connection.disconnect() }
    }
    private fun parse(text: String): Pair<List<Item>, List<Item>>? = try {
        val root = JSONObject(text)
        fun list(key: String, tv: Boolean) = root.optJSONArray(key).objects().filter { it.optString("title").isNotBlank() && it.optString("cover").startsWith("http") }
            .map { Item(it.optString("title").trim(), it.optString("cover"), it.optString("rate"), it.optString("episodes_info"), tv) }
        list("tv", true) to list("movie", false)
    } catch (_: Exception) { null }
}
