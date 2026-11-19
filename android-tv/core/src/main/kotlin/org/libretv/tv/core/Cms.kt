package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.Collator
import java.util.Locale

/** 采集站（苹果 CMS 接口）返回内容的解析，以及搜索结果的相关性过滤。纯函数。 */
object CmsParser {
    val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36", "Accept" to "application/json")

    fun parseSearchList(data: Any?, source: JSONObject): List<JSONObject> {
        val root = data as? JSONObject ?: throw IllegalStateException("API返回的数据格式无效")
        val raw = root.opt("list")
        if (raw != null && raw != JSONObject.NULL && raw !is JSONArray) throw IllegalStateException("API返回的数据格式无效")
        return (raw as? JSONArray).objects().map { vod ->
            JSONObject().put("sourceKey", source.optString("key")).put("sourceName", source.optString("name")).put("vodId", jsString(vod.opt("vod_id"))).put("name", jsString(vod.opt("vod_name")))
                .put("sourceUrl", source.optString("url")).apply {
                    vod.str("vod_pic")?.let { put("pic", it) }; vod.str("type_name")?.let { put("typeName", it) }; vod.str("vod_year")?.let { put("year", it) }
                    vod.str("vod_area")?.let { put("area", it) }; vod.str("vod_remarks")?.let { put("remarks", it) }; if (source.has("isAdult")) put("isAdult", source.optBoolean("isAdult"))
                }
        }
    }
    /** 播放地址串：线路之间用 $$$ 分隔，集与集之间用 #，每集是「名称$地址」；优先取含 m3u8 的线路 */
    fun extractEpisodes(playUrl: String): List<String> {
        if (playUrl.isEmpty()) return emptyList()
        val groups = playUrl.split("$$$").map { group -> group.split('#').map { ep -> ep.split('$').let { if (it.size > 1) it[1] else "" } }.filter { it.startsWith("http://") || it.startsWith("https://") } }.filter { it.isNotEmpty() }
        if (groups.isEmpty()) return emptyList()
        return groups.firstOrNull { eps -> eps.any { it.contains(".m3u8") } } ?: groups[0]
    }
    private val m3u8InText = Regex("\\$(https?://[^\"'\\s]+?\\.m3u8)")
    fun parseDetail(data: Any?, source: JSONObject): JSONObject {
        val vod = (data as? JSONObject)?.optJSONArray("list")?.optJSONObject(0) ?: throw IllegalStateException("获取到的详情内容无效")
        var episodes = extractEpisodes(jsString(vod.opt("vod_play_url")))
        if (episodes.isEmpty()) episodes = m3u8InText.findAll(jsString(vod.opt("vod_content"))).map { it.groupValues[1] }.toList()
        val info = JSONObject().put("sourceKey", source.optString("key")).put("sourceName", source.optString("name")).put("sourceUrl", source.optString("url"))
        for ((field, key) in listOf("title" to "vod_name", "cover" to "vod_pic", "desc" to "vod_content", "typeName" to "type_name", "year" to "vod_year", "area" to "vod_area", "director" to "vod_director", "actor" to "vod_actor", "remarks" to "vod_remarks"))
            vod.str(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { info.put(field, it) }
        return JSONObject().put("episodes", JSONArray(episodes)).put("videoInfo", info)
    }
    fun parseDetailPage(html: String, source: JSONObject): JSONObject {
        var matches = m3u8InText.findAll(html).map { it.value }.toList()
        if (matches.isEmpty()) matches = Regex("\\$(https?://[^\"'\\s]+?/\\d{8}/\\d+_[a-f0-9]+/index\\.m3u8)").findAll(html).map { it.value }.toList()
        val episodes = matches.distinct().map { link -> link.substring(1).let { u -> u.indexOf('(').let { if (it > 0) u.substring(0, it) else u } } }
        val info = JSONObject().put("sourceKey", source.optString("key")).put("sourceName", source.optString("name")).put("sourceUrl", source.optString("url"))
        Regex("<h1[^>]*>([^<]+)</h1>").find(html)?.let { info.put("title", it.groupValues[1].trim()) }
        Regex("<div[^>]*class=[\"']sketch[\"'][^>]*>([\\s\\S]*?)</div>").find(html)?.let { info.put("desc", it.groupValues[1].replace(Regex("<[^>]+>"), " ").trim()) }
        return JSONObject().put("episodes", JSONArray(episodes)).put("videoInfo", info)
    }

    private val adultKeywords = listOf("伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "日本福利片", "日本伦理片", "擦边短剧", "擦边", "韩国伦理", "里番动漫", "门事件", "萝莉少女", "制服诱惑", "国产传媒",
        "cosplay", "黑丝诱惑", "无码", "日本无码", "有码", "日本有码", "SWAG", "网红主播", "色情片", "同性片", "福利视频", "无码高清", "有码高清", "91视频", "自拍偷拍", "mini传媒")
    fun isAdult(typeName: String?) = !typeName.isNullOrEmpty() && adultKeywords.any { typeName.contains(it) }

    /** JS 正则里 \\s 涵盖的空白（含全角空格），Java 的 \\s 只认 ASCII */
    private const val WS = "\\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
    private val punctuation = Regex("[$WS\\p{P}\\p{S}]+")
    fun normalizeTitle(s: String) = s.lowercase(Locale.ROOT).replace(punctuation, "")

    private class Season(val base: String, val season: Int, val explicit: Boolean)
    private fun parseSeason(title: String, allowAnnotations: Boolean = false, bareNeedsSpace: Boolean = true): Season? {
        val text = title.trim()
        val explicit = Regex(if (allowAnnotations) "^(.*?)[$WS]*第[$WS]*([1-9]\\d?|[一二两三四五六七八九十]{1,3})[$WS]*季.*$" else "^(.*?)[$WS]*第[$WS]*([1-9]\\d?|[一二两三四五六七八九十]{1,3})[$WS]*季$").find(text)
        val bare = Regex(if (bareNeedsSpace) "^(.*?)[$WS]+([1-9]\\d?)$" else "^(.*?)[$WS]*([1-9]\\d?)$").find(text)
        val match = explicit ?: bare ?: return null
        if (normalizeTitle(match.groupValues[1]).isEmpty()) return null
        val value = match.groupValues[2]; val digits = "零一二三四五六七八九"
        val season = value.toIntOrNull() ?: value.replace('两', '二').let { chinese ->
            when {
                Regex("^[一二三四五六七八九]$").matches(chinese) -> digits.indexOf(chinese)
                Regex("^[一二三四五六七八九]?十[一二三四五六七八九]?$").matches(chinese) -> { val (tens, units) = chinese.split('十'); (if (tens.isNotEmpty()) digits.indexOf(tens) else 1) * 10 + (if (units.isNotEmpty()) digits.indexOf(units) else 0) }
                else -> return null
            }
        }
        return Season(match.groupValues[1], season, explicit != null)
    }
    /** 关键词出现在片名里，且不是更长数字的一部分（搜「1」不该命中「2012」） */
    private fun includesKeyword(title: String, keyword: String): Boolean {
        if (title.isEmpty() || keyword.isEmpty()) return false
        var index = title.indexOf(keyword)
        while (index >= 0) {
            val before = title.getOrNull(index - 1); val after = title.getOrNull(index + keyword.length)
            if ((!keyword.first().isAsciiDigit() || before?.isAsciiDigit() != true) && (!keyword.last().isAsciiDigit() || after?.isAsciiDigit() != true)) return true
            index = title.indexOf(keyword, index + 1)
        }
        return false
    }
    private fun Char.isAsciiDigit() = this in '0'..'9'
    private class Query(val normalized: String, val season: Season?, val tokens: List<String>)
    private fun prepare(wd: String): Query? {
        val normalized = normalizeTitle(wd); if (normalized.isEmpty()) return null
        return Query(normalized, parseSeason(wd, false, false), wd.trim().split(Regex("[$WS]+")).map { normalizeTitle(it) }.filter { it.isNotEmpty() })
    }
    private fun matches(name: String, query: Query) = includesKeyword(name, query.normalized) || query.tokens.size > 1 && query.tokens.all { includesKeyword(name, it) }
    private fun relevant(name: String, query: Query): Boolean {
        val normalized = normalizeTitle(name); if (normalized.isEmpty()) return false
        val titleSeason = parseSeason(name, true)
        if (query.season != null && titleSeason != null) {
            if (query.season.season != titleSeason.season) return false
            if (query.season.explicit || titleSeason.explicit) prepare(query.season.base)?.let { return matches(normalizeTitle(titleSeason.base), it) }
        }
        return matches(normalized, query)
    }
    fun isRelevant(name: String, wd: String): Boolean = prepare(wd)?.let { relevant(name, it) } ?: true
    fun filterRelevant(items: List<JSONObject>, wd: String): List<JSONObject> { val query = prepare(wd) ?: return items; return items.filter { relevant(it.optString("name"), query) } }
}

/** 各片源对集数 / 完结状态的写法不一，统一成「全 N 集 / 更新至 N 集 / 已完结」 */
object Remarks {
    private val count = Regex("(\\d+)\\s*集")
    private fun finished(text: String) = Regex("完结|大结局|全集|剧终").containsMatchIn(text) || Regex("(?:全|共)\\s*\\d+\\s*集").containsMatchIn(text) || Regex("\\d+\\s*集\\s*全").containsMatchIn(text)
    fun format(raw: String?): String? {
        val text = raw?.trim().orEmpty(); if (text.isEmpty()) return null
        val done = finished(text); val n = count.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return if (n > 0) (if (done) "全${n}集" else "更新至${n}集") else if (done) "已完结" else text
    }
    fun inferGroup(raws: List<String?>): String? {
        var total = 0L; var progress = 0L; var done = false; var fallback: String? = null
        for (raw in raws) {
            val text = format(raw) ?: continue
            if (fallback == null) fallback = text
            Regex("^全(\\d+)集$").find(text)?.let { total = maxOf(total, it.groupValues[1].toLong()) } ?: Regex("^更新至(\\d+)集$").find(text)?.let { progress = maxOf(progress, it.groupValues[1].toLong()) } ?: run { if (text == "已完结") done = true }
        }
        return if (total > 0) "全${total}集" else if (done) "已完结" else if (progress > 0) "更新至${progress}集" else fallback
    }
}

/** 搜索、详情、同名聚合、换源发现。网络请求在调用线程上阻塞进行。 */
class Catalog(private val cache: SourceCache) {
    private val collator: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    private val details = LinkedHashMap<String, Pair<JSONObject, Long>>()

    private fun cmsUrl(source: JSONObject, params: Map<String, String>): String {
        val base = source.optString("url"); val uri = URI(base)
        require(uri.scheme in setOf("http", "https")) { "无效的源地址" }
        // 覆盖同名参数，其余查询参数保留
        val existing = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() && it.substringBefore('=') !in params.keys }.orEmpty()
        val query = (existing + params.map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }).joinToString("&")
        return base.substringBefore('#').substringBefore('?') + "?" + query
    }
    private fun json(url: String, cancel: Cancel?): Any {
        val res = Net.fetch(url, headers = CmsParser.headers, timeoutMs = 8000, cancel = cancel)
        if (!res.ok) throw IllegalStateException("HTTP ${res.status}")
        return res.json()
    }
    private fun aggregate(lists: List<List<JSONObject>>, wd: String, filterAdult: Boolean): List<JSONObject> {
        val seen = HashSet<String>(); val exact = CmsParser.normalizeTitle(wd)
        return CmsParser.filterRelevant(lists.flatten().filter { !filterAdult || !CmsParser.isAdult(it.str("typeName")) }, wd)
            .filter { seen.add("${it.optString("sourceKey")}:${it.optString("vodId")}") }
            .sortedWith(compareBy<JSONObject> { if (CmsParser.normalizeTitle(it.optString("name")) == exact) 0 else 1 }.thenComparator { a, b -> collator.compare(a.optString("name"), b.optString("name")) }.thenComparator { a, b -> collator.compare(a.optString("sourceName"), b.optString("sourceName")) })
    }

    class SearchResult(val list: List<JSONObject>, val failures: Int)
    /**
     * 并发搜索各片源（最多 50 个，6 路并发，每个片源总时限 10 秒、最多 5 页）。
     * 每个片源的结果（outcome：sourceKey / ok / list / ms / error / timedOut）经 onSource 逐个回报。
     */
    fun search(wd: String, sources: List<JSONObject>, filterAdult: Boolean, cancel: Cancel, forceRefresh: Boolean = false, onSource: ((JSONObject) -> Unit)? = null): SearchResult {
        require(wd.isNotBlank() && wd.length <= 100) { "搜索关键词无效" }
        require(sources.isNotEmpty()) { "请至少选择一个点播源" }
        val outcomes = Pool.mapConcurrent(sources.take(50), 6, cancel) { source ->
            cancel.check()
            val cacheKey = SourceCache.searchKey(wd, source, filterAdult)
            val cached = if (forceRefresh) null else cache.get(cacheKey)
            if (cached != null && !cached.stale) return@mapConcurrent JSONObject(cached.json).also { onSource?.invoke(it) }
            val start = System.currentTimeMillis(); val limit = cancel.child(10_000)
            val outcome = JSONObject().put("sourceKey", source.optString("key"))
            try {
                fun page(pg: Int) = json(cmsUrl(source, mapOf("ac" to "videolist", "wd" to wd, "pg" to pg.toString())), limit)
                val first = page(1)
                val pages = ((first as? JSONObject)?.optDouble("pagecount", 1.0) ?: 1.0).let { if (it.isFinite()) Math.floor(it).toInt().coerceIn(1, 5) else 1 }
                val list = CmsParser.parseSearchList(first, source).toMutableList()
                for (pg in 2..pages) try { list.addAll(CmsParser.parseSearchList(page(pg), source)) } catch (_: Exception) { if (limit.cancelled) break }
                outcome.put("ok", true).put("list", JSONArray(aggregate(listOf(list), wd, filterAdult)))
            } catch (error: Exception) {
                if (cancel.cancelled) throw CancelledException()
                outcome.put("ok", false).put("list", JSONArray()).put("timedOut", limit.timedOut).put("error", if (limit.timedOut) "请求超时" else error.message ?: "请求失败")
            } finally { limit.close() }
            cancel.check()
            outcome.put("ms", System.currentTimeMillis() - start)
            cache.set(cacheKey, outcome.toString(), if (outcome.optBoolean("ok")) 10 * 60_000L else 30_000L)
            onSource?.invoke(outcome)
            outcome
        }
        return SearchResult(aggregate(outcomes.map { it.optJSONArray("list").objects() }, wd, filterAdult), outcomes.count { !it.optBoolean("ok") })
    }

    /** 影片详情（剧集地址 + 简介）；接口拿不到剧集时退回解析详情网页。一分钟内重复请求用缓存 */
    fun detail(id: String, source: JSONObject, cancel: Cancel?, forceRefresh: Boolean = false): JSONObject {
        require(Regex("^[\\w-]+$").matches(id)) { "无效的视频 ID" }
        cancel?.check()
        val key = listOf(source.optString("key"), source.optString("name"), source.optString("url"), source.optString("detail"), id).joinToString("|")
        synchronized(details) { details[key]?.takeIf { !forceRefresh && System.currentTimeMillis() - it.second < 60_000 }?.let { return it.first.copy() } }
        var resolved: JSONObject? = null
        try { resolved = CmsParser.parseDetail(json(cmsUrl(source, mapOf("ac" to "videolist", "ids" to id)), cancel), source) }
        catch (error: Exception) { if (cancel?.cancelled == true) throw CancelledException() }
        if ((resolved?.optJSONArray("episodes")?.length() ?: 0) == 0) {
            val root = source.str("detail")?.takeIf { it.isNotEmpty() } ?: URI(source.optString("url")).let { "${it.scheme}://${it.rawAuthority}" }
            val res = Net.fetch("${root.trimEnd('/')}/index.php/vod/detail/id/$id.html", headers = CmsParser.headers, cancel = cancel)
            if (res.ok) resolved = CmsParser.parseDetailPage(res.text(), source)
        }
        if ((resolved?.optJSONArray("episodes")?.length() ?: 0) == 0) throw IllegalStateException("未找到播放资源")
        cancel?.check()
        synchronized(details) { if (details.size >= 100) details.remove(details.keys.first()); details[key] = resolved!! to System.currentTimeMillis() }
        return resolved!!.copy()
    }

    /** 把跨片源的搜索结果按「同名影片」聚合；同名但年份不同（翻拍）时按年份拆开 */
    fun groups(list: List<JSONObject>): List<JSONObject> {
        val byName = LinkedHashMap<String, MutableList<JSONObject>>()
        for (item in list) { val name = item.optString("name").trim(); if (name.isNotEmpty()) byName.getOrPut(name) { ArrayList() }.add(item) }
        fun build(name: String, year: String?, items: List<JSONObject>) = JSONObject().put("key", "$name|${year.orEmpty()}|${items.joinToString(",") { "${it.optString("sourceKey")}_${it.optString("vodId")}" }}").put("name", name).put("items", JSONArray(items)).apply {
            year?.let { put("year", it) }; items.firstNotNullOfOrNull { it.str("typeName")?.takeIf { t -> t.isNotEmpty() } }?.let { put("typeName", it) }
            items.firstNotNullOfOrNull { it.str("pic")?.takeIf { p -> p.isNotEmpty() } }?.let { put("pic", it) }; Remarks.inferGroup(items.map { it.str("remarks") })?.let { put("remarks", it) }
        }
        val out = ArrayList<JSONObject>()
        for ((name, items) in byName) {
            val years = items.mapNotNull { it.str("year")?.takeIf { y -> y.isNotEmpty() } }.distinct()
            if (years.size > 1) {
                years.forEach { y -> out.add(build(name, y, items.filter { it.str("year") == y })) }
                items.filter { it.str("year").isNullOrEmpty() }.takeIf { it.isNotEmpty() }?.let { out.add(build(name, null, it)) }
            } else out.add(build(name, years.firstOrNull(), items))
        }
        return out
    }

    /**
     * 换源：在各片源里找同一部影片并逐个检查能否打开（4 路并发）。有缓存时先回报缓存，过期或强制刷新再重新查。
     * 行（row）：{ source, result, ok?, ms?, episodes?, error? }
     */
    fun discover(title: String, sources: List<JSONObject>, filterAdult: Boolean, cancel: Cancel, forceRefresh: Boolean,
                 onSnapshot: (rows: List<JSONObject>, cached: Boolean, refreshing: Boolean) -> Unit, onWarning: (String) -> Unit): List<JSONObject> {
        cancel.check()
        val key = JSONArray().put("discovery").put(title.trim()).put(JSONArray(sources.map { SourceCache.identity(it).toString() }.sorted())).put(filterAdult).toString()
        val cached = cache.get(key); val cachedRows = cached?.let { JSONObject(it.json).optJSONArray("rows").objects() }
        if (cached != null && cachedRows != null) {
            onSnapshot(cachedRows, true, cached.stale || forceRefresh)
            if (!cached.stale && !forceRefresh) return cachedRows
        }
        try {
            val found = search(title, sources, filterAdult, cancel, forceRefresh)
            cancel.check()
            if (found.list.isEmpty() && found.failures == sources.size && sources.isNotEmpty()) throw IllegalStateException("来源请求全部失败，请检查网络或刷新来源")
            val wanted = title.trim()
            val rows = sources.mapNotNull { source ->
                val hits = found.list.filter { it.optString("sourceKey") == source.optString("key") }
                (hits.firstOrNull { it.optString("name").trim() == wanted } ?: hits.firstOrNull { it.optString("name").trim().startsWith(wanted) })?.let { JSONObject().put("source", source).put("result", it) }
            }
            Pool.mapConcurrent(rows, 4, cancel) { row ->
                cancel.check()
                try {
                    val started = System.currentTimeMillis()
                    val detail = detail(row.getJSONObject("result").optString("vodId"), row.getJSONObject("source"), cancel, forceRefresh)
                    row.put("ok", true).put("ms", System.currentTimeMillis() - started).put("episodes", detail.optJSONArray("episodes")?.length() ?: 0)
                } catch (error: Exception) { cancel.check(); row.put("ok", false).put("error", error.message ?: "片源暂不可用") }
            }
            cancel.check()
            if (cachedRows?.any { it.optBoolean("ok") } == true && rows.none { it.optBoolean("ok") } && found.failures > 0) throw IllegalStateException("刷新未获取到可用来源，暂时保留缓存")
            val usable = rows.any { it.optBoolean("ok") }
            cache.set(key, JSONObject().put("rows", JSONArray(rows)).toString(), if (usable) 5 * 60_000L else 30_000L, if (usable) 24 * 3600_000L else 30_000L)
            onSnapshot(rows, false, false)
            return rows
        } catch (error: Exception) {
            cancel.check()
            if (cachedRows == null) throw error
            onSnapshot(cachedRows, true, false); onWarning(error.message ?: "刷新失败，暂时使用缓存")
            return cachedRows
        }
    }
}
