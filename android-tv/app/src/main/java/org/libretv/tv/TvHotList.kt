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
    class Item(val id: String, val title: String, val cover: String, val rate: String, val note: String, val tv: Boolean)
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
    // 横版剧照：榜单只给竖版海报，铺不满电视屏幕；向豆瓣移动端接口要这部作品的剧照，取第一张够大的横图。
    // 找到的地址长期有效，记在本机；没找到的只在本次运行里记住，下次启动再问。
    private val stills = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun still(id: String, tv: Boolean, done: (String?) -> Unit) {
        if (!id.matches(Regex("\\d{1,12}"))) { done(null); return }
        (stills[id] ?: prefs.getString("still:$id", null))?.let { done(it.ifEmpty { null }); return }
        Thread {
            val found = try { fetchStill(id, tv) } catch (_: Exception) { null }
            stills[id] = found.orEmpty()
            if (found != null) prefs.edit().putString("still:$id", found).apply()
            main.post { if (!closed) done(found) }
        }.start()
    }
    private fun fetchStill(id: String, tv: Boolean): String? {
        val connection = (URL("https://m.douban.com/rexxar/api/v2/${if (tv) "tv" else "movie"}/$id/photos?start=0&count=30").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
            setRequestProperty("Referer", "https://m.douban.com/"); setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val photos = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("photos").objects()
            return photos.mapNotNull { it.optJSONObject("image")?.optJSONObject("large") }.firstOrNull { large ->
                val width = large.optInt("width"); val height = large.optInt("height"); val url = large.optString("url")
                height > 0 && width >= 1200 && width.toDouble() / height in 1.4..2.5 && Regex("https://img\\d+\\.doubanio\\.com/.+").matches(url)
            }?.optString("url")
        } finally { connection.disconnect() }
    }
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
            .map { Item(it.optString("id"), it.optString("title").trim(), it.optString("cover"), it.optString("rate"), it.optString("episodes_info"), tv) }
        list("tv", true) to list("movie", false)
    } catch (_: Exception) { null }
}
