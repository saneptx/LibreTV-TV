package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * 本周热门：向豆瓣取「热门剧集 / 热门电影」两份榜单（免登录的公开接口），在本机缓存 6 小时；取不到时沿用旧缓存。
 * 每项：id、title、cover、rate、note（集数说明）、tv（是否剧集）。
 */
class HotList(file: File) {
    private val store = JsonFile(file, 0)
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    /** 已缓存的榜单（可能过期）；没有缓存时为 null */
    fun cached(): Pair<List<JSONObject>, List<JSONObject>>? = store.read()?.let { parse(it) }
    fun fresh() = (store.read()?.optLong("at") ?: 0) > System.currentTimeMillis() - 6 * 3600_000L
    /** 联网刷新；失败时返回缓存 */
    fun refresh(cancel: Cancel? = null): Pair<List<JSONObject>, List<JSONObject>>? = try {
        val root = JSONObject().put("tv", fetch("tv", cancel)).put("movie", fetch("movie", cancel)).put("at", System.currentTimeMillis())
        val parsed = parse(root)
        if (parsed.first.isEmpty() && parsed.second.isEmpty()) cached() else { store.schedule { root.toString() }; store.flush(); parsed }
    } catch (_: Exception) { cached() }
    private fun fetch(type: String, cancel: Cancel?): JSONArray {
        val url = "https://movie.douban.com/j/search_subjects?type=$type&tag=${URLEncoder.encode("热门", "UTF-8")}&sort=recommend&page_limit=24&page_start=0"
        val res = Net.fetch(url, headers = mapOf("User-Agent" to ua, "Referer" to "https://movie.douban.com/", "Accept" to "application/json"), timeoutMs = 8000, cancel = cancel)
        return if (res.ok) (res.json() as? JSONObject)?.optJSONArray("subjects") ?: JSONArray() else JSONArray()
    }
    private fun parse(root: JSONObject): Pair<List<JSONObject>, List<JSONObject>> {
        fun list(key: String, tv: Boolean) = root.optJSONArray(key).objects().filter { it.optString("title").isNotBlank() && it.optString("cover").startsWith("http") }
            .map { JSONObject().put("id", it.optString("id")).put("title", it.optString("title").trim()).put("cover", it.optString("cover")).put("rate", it.optString("rate")).put("note", it.optString("episodes_info")).put("tv", tv) }
        return list("tv", true) to list("movie", false)
    }
    companion object {
        /** 豆瓣图床校验来路，请求海报时要带上 */
        fun imageHeaders(url: String): Map<String, String> = if (Regex("doubanio\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url)) mapOf("Referer" to "https://movie.douban.com/") else emptyMap()
    }
}
