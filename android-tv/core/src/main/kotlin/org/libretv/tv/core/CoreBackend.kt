package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据层的总入口：界面用 request(操作名, 参数) 发起操作，结果和进度以事件（JSON）回到主线程。
 * 事件：result / error，以及各操作自己的进度事件（search-progress、candidates 等）。
 * 没有请求号的事件（ready、fatal、library-changed）交给 onBoot。
 */
/** post：把回调送到界面线程（安卓是主线程 Handler，电脑端是界面事件线程） */
class CoreBackend(dir: File, bundledSources: JSONArray, private val post: (Runnable) -> Unit, private val onBoot: (JSONObject) -> Unit) {
    val settings = Settings(File(dir, "settings.json"), bundledSources)
    val library = Library(File(dir, "library.json"))
    private val sync = LibrarySync(library, File(dir, "sync.json"))
    private val cache = SourceCache(File(dir, "cache.json"))
    private val catalog = Catalog(cache)
    private val learned = Ads.LearnedStore(File(dir, "learned-ads.json"))
    private val compareStore = JsonFile(File(dir, "compare.json"), 500)
    private val comparisons: JSONObject = compareStore.read() ?: JSONObject()
    private val sequence = AtomicInteger()
    private val callbacks = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val cancels = ConcurrentHashMap<String, Cancel>()
    @Volatile private var disposed = false
    /** 手机端：已连接的电视；资料直接与它同步，不经过电脑 */
    @Volatile private var peer: Triple<String, String, String>? = null
    private var runner: LibrarySync.Runner? = null
    private var unsubscribe: () -> Unit = {}

    /** 开始工作：启动后台同步并向界面发出 ready。旧数据迁移（如有）要在这之前完成 */
    fun start() {
        if (runner != null || disposed) return
        runner = sync.Runner({ peer?.let { "tv:${it.first}" } ?: desktop()?.url.orEmpty() }, LibrarySync.Transport { body, cancel ->
            val tv = peer
            if (tv != null) {
                val res = Net.fetch("${tv.second}/v1/sync", "POST", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${tv.third}"), body.toString(), 20000, 4 * 1024 * 1024, cancel = cancel)
                val reply = runCatching { res.json() as? JSONObject }.getOrNull()
                if (!res.ok || reply == null) throw IllegalStateException(reply?.str("error")?.takeIf { it.isNotEmpty() } ?: "电视返回 ${res.status}")
                reply
            } else Desktop.exchangeLibrary(desktop() ?: throw IllegalStateException("没有同步对象"), body, cancel)
        })
        // 其他设备的修改同步进来后通知界面刷新首页、片库
        unsubscribe = library.onChange { origin -> if (origin == "sync") emit(JSONObject().put("event", "library-changed").put("value", home())) }
        emit(JSONObject().put("event", "ready").put("value", home()))
    }

    private fun emit(event: JSONObject) {
        if (disposed) return
        event.put("version", 1)
        post {
            if (disposed) return@post
            val id = event.optString("id"); val type = event.optString("event")
            if (id.isEmpty()) { onBoot(event); return@post }
            val callback = callbacks[id]
            if (type == "result" || type == "error") callbacks.remove(id)
            callback?.invoke(event)
        }
    }
    fun request(op: String, args: JSONObject = JSONObject(), callback: ((JSONObject) -> Unit)? = null): String {
        val id = "ui-${sequence.incrementAndGet()}"
        if (disposed) return id
        if (callback != null) callbacks[id] = callback
        val cancel = Cancel(); cancels[id] = cancel
        Pool.ops.execute {
            try {
                val value = handle(op, args, cancel) { type, payload -> if (!cancel.cancelled) emit(JSONObject().put("id", id).put("event", type).put("value", payload)) }
                if (!cancel.cancelled) emit(JSONObject().put("id", id).put("event", "result").put("value", value ?: JSONObject.NULL))
            } catch (error: Throwable) {
                if (!cancel.cancelled) emit(JSONObject().put("id", id).put("event", "error").put("error", (error as? Exception)?.message?.takeIf { it.isNotBlank() } ?: "操作失败"))
            } finally { cancels.remove(id); if (cancel.cancelled) callbacks.remove(id) }
        }
        return id
    }
    fun cancel(id: String?) { if (id == null) return; callbacks.remove(id); cancels.remove(id)?.cancel() }
    fun release() {
        disposed = true; unsubscribe(); runner?.stop(); cancels.values.forEach { it.cancel() }; cancels.clear(); callbacks.clear()
        flush()
    }
    /** 把内存里的修改立即落盘（应用转入后台时调用） */
    fun flush() { settings.flush(); library.flush(); cache.flush(); compareStore.flush() }

    /** 从旧版（网页数据页）迁移：设置、资料库、已学习的广告 */
    fun importLegacy(data: JSONObject) {
        data.optJSONObject("settings")?.let { settings.importSaved(it) }
        data.optJSONObject("library")?.let { library.importAll(it) }
        data.optJSONArray("learnedAds")?.let { learned.importAll(it) }
        flush()
    }

    // ---- 公共数据 ----
    private fun desktop(): Desktop.Server? { val url = settings.string("desktopServerUrl"); val token = settings.string("desktopPairingToken"); return if (url.isNotEmpty() && token.isNotEmpty()) Desktop.Server(url, token) else null }
    private fun desktopOrEmpty() = Desktop.Server(settings.string("desktopServerUrl"), settings.string("desktopPairingToken"))
    private fun config(): JSONObject {
        val filter = settings.yellowFilter
        return JSONObject().put("sources", JSONArray(settings.allSources().filter { !filter || !it.optBoolean("isAdult") })).put("selectedKeys", JSONArray(settings.selectedKeys()))
            .put("enabled", settings.activeSources().size).put("hiddenSources", settings.envSources.count { it.optString("url").trimEnd('/') in settings.hiddenUrls() }).put("subscriptions", JSONArray(settings.subscriptions())).put("adFilter", settings.adFilter).put("learnedAds", learned.all().size)
            .put("desktopConfigured", desktop() != null).put("superResolution", settings.bool("superResolution", true)).put("overlayRemoval", settings.bool("overlayRemoval", true)).put("autoplayNext", settings.bool("autoplayNext", true))
            .put("playbackMode", settings.string("playbackMode").ifEmpty { "direct" }).put("desktopServerUrl", settings.string("desktopServerUrl")).put("desktopPairingToken", settings.string("desktopPairingToken"))
    }
    private fun home(): JSONObject = JSONObject().put("config", config()).put("history", JSONArray(library.history())).put("searches", JSONArray(library.searches()))
        .put("favorites", JSONArray(library.favorites().map { f -> (f.optJSONObject("card") ?: JSONObject()).copy().put("addedAt", f.optLong("addedAt")) })).put("sync", runner?.status() ?: JSONObject())
    private fun sourceFor(item: JSONObject): JSONObject = settings.resolveSource(item.optString("sourceKey"), item.str("sourceUrl"), item.str("sourceName")) ?: throw IllegalStateException("找不到对应片源，请在片源管理中检查配置")

    // ---- 画质与码率 ----
    private fun bitrateKey(sourceKey: String, vodId: String) = "bitrate|${Quality.key(sourceKey, vodId)}"
    private fun bitrateProbed(sourceKey: String, vodId: String) = cache.get(bitrateKey(sourceKey, vodId)) != null
    /** 0 表示探测过但测不出码率，同样是有效结论 */
    private fun cachedBitrate(sourceKey: String, vodId: String): Int? = cache.get(bitrateKey(sourceKey, vodId))?.json?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    private class Probed(val height: Int?, val bitrate: Int?, val error: String?, val cached: Boolean)
    private fun probeSource(item: JSONObject, cancel: Cancel?, force: Boolean = false): Probed {
        val source = sourceFor(item); val key = Quality.key(source.optString("key"), item.optString("vodId"))
        val known = settings.quality(key); val bitrate = cachedBitrate(source.optString("key"), item.optString("vodId"))
        fun height(entry: JSONObject?) = entry?.optInt("height")?.takeIf { it > 0 }
        if (!force && known != null && Quality.fresh(known) && (bitrateProbed(source.optString("key"), item.optString("vodId")) || !known.str("error").isNullOrEmpty())) return Probed(height(known), bitrate, known.str("error")?.takeIf { it.isNotEmpty() }, true)
        val result = QualityProbe.probe(source, item.optString("vodId"), cancel)
        settings.setQuality(JSONObject().put(key, JSONObject().put("from", "probe").put("timestamp", System.currentTimeMillis()).apply { result.height?.let { put("height", it) }; result.error?.let { put("error", it) } }))
        if (result.error == null) cache.set(bitrateKey(source.optString("key"), item.optString("vodId")), (result.bitrate ?: 0).toString(), 6 * 3600_000L, 24 * 3600_000L)
        return Probed(height(settings.quality(key)) ?: result.height, result.bitrate, result.error, false)
    }
    /** 同一来源的探测只跑一次：选片预取与确认选源共用；不随界面取消，跑完写缓存供下次使用 */
    private val probing = ConcurrentHashMap<String, Future<Probed>>()
    private fun probeShared(item: JSONObject): Future<Probed> {
        val key = Quality.key(item.optString("sourceKey"), item.optString("vodId"))
        return probing.computeIfAbsent(key) { Pool.io.submit<Probed> { try { probeSource(item, null) } finally { probing.remove(key) } } }
    }

    // ---- 去广告 ----
    private val analyses = LinkedHashMap<String, Pair<List<Ads.Media>, Long>>()
    private fun analyzeAds(url: String, cancel: Cancel): List<Ads.Media> {
        // 电脑串流时插播广告的时间轴检测交给电脑（网络快得多），设备只做编码比对和学习库
        val timeline = settings.string("playbackMode") != "desktop"; val key = "${if (timeline) "timeline" else "codec"}|$url"
        // 列表里的签名地址和插入的广告都会变，缓存只覆盖本次观看（含标记后重开）
        synchronized(analyses) { analyses[key]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000 }?.let { return it.first } }
        val media = Ads.analyzePlaylist(url, Ads.NetTransport, cancel, 5000, timeline)
        if (cancel.cancelled) return media
        synchronized(analyses) { analyses.remove(key); if (analyses.size >= 20) analyses.remove(analyses.keys.first()); analyses[key] = media to System.currentTimeMillis() }
        return media
    }
    /** 交给播放器的分析原文；总量过大时让播放器自行下载 */
    private fun analyzedPlaylists(media: List<Ads.Media>): JSONArray = if (media.sumOf { it.text.length } <= 1536 * 1024) JSONArray(media.map { JSONObject().put("url", it.url).put("text", it.text) }) else JSONArray()
    /** 电脑会话请求体有上限；超出时只少删，不影响播放 */
    private fun bounded(lines: List<String>): List<String> {
        val result = ArrayList<String>(); var bytes = 0
        for (line in lines) { bytes += line.length + 4; if (result.size >= 400 || line.length > 2048 || bytes > 48 * 1024) break; result.add(line) }
        return result
    }

    // ---- 多来源比对（电脑上做，结果按剧集地址记 7 天） ----
    private fun savedComparison(url: String): JSONObject? = synchronized(comparisons) { comparisons.optJSONObject(url)?.takeIf { System.currentTimeMillis() - it.optLong("at") < 7L * 24 * 3600_000 && it.optJSONArray("intervals") != null } }
    private fun saveComparison(url: String, entry: JSONObject) {
        synchronized(comparisons) {
            comparisons.put(url, entry)
            if (comparisons.length() > 300) comparisons.keys().asSequence().toList().sortedBy { comparisons.optJSONObject(it)?.optLong("at") ?: 0 }.take(comparisons.length() - 300).forEach { comparisons.remove(it) }
        }
        compareStore.schedule { synchronized(comparisons) { comparisons.toString() } }
    }
    /** 同一集的其他来源：集数一致，优先选不同 CDN 主机；多给几个候选，电脑依次尝试 */
    private fun referenceEpisodes(item: JSONObject, episodes: List<String>, index: Int, cancel: Cancel): List<String> {
        val primary = episodes[index]; fun host(u: String) = runCatching { java.net.URL(u).let { "${it.host}:${it.port}" } }.getOrNull()
        val found = catalog.search(item.optString("name"), settings.activeSources(), settings.yellowFilter, cancel)
        val urls = ArrayList<Pair<String, Boolean>>()
        for (candidate in found.list.filter { it.optString("name").trim() == item.optString("name").trim() && it.optString("sourceKey") != item.optString("sourceKey") }.take(16)) {
            if (urls.count { !it.second } >= 4) break
            try {
                val list = catalog.detail(candidate.optString("vodId"), sourceFor(candidate), cancel).optJSONArray("episodes").strings()
                val url = if (list.size == episodes.size) list[index] else continue
                if (url != primary && Regex("\\.m3u8([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url) && urls.none { it.first == url }) urls.add(url to (host(url)?.let { it == host(primary) } ?: true))
            } catch (error: Exception) { if (cancel.cancelled) throw error }
        }
        return urls.sortedBy { it.second }.take(4).map { it.first }
    }
    private fun compareEpisode(item: JSONObject, index: Int, cancel: Cancel, onProgress: (String) -> Unit): JSONObject {
        val episodes = catalog.detail(item.optString("vodId"), sourceFor(item), cancel).optJSONArray("episodes").strings()
        val url = episodes.getOrNull(index) ?: throw IllegalStateException("找不到这一集")
        savedComparison(url)?.let { return it.copy().put("cached", true) }
        onProgress("正在寻找同一集的其他来源")
        val references = referenceEpisodes(item, episodes, index, cancel)
        if (references.isEmpty()) throw IllegalStateException("没有找到同一集的其他来源")
        val analyzed = analyzeAds(url, cancel); val ads = Ads.decide(analyzed, learned.all())
        val (intervals, count) = Desktop.compare(desktopOrEmpty(), JSONObject().put("primary", JSONObject().put("url", url).put("playlists", analyzedPlaylists(analyzed))).put("references", JSONArray(references))
            .put("adFilter", settings.adFilter).put("adSegments", JSONArray(bounded(ads.extraLines + ads.heuristicLines))), cancel, onProgress)
        return JSONObject().put("intervals", intervals).put("references", count).put("at", System.currentTimeMillis()).also { saveComparison(url, it) }
    }
    /** 看当前集时在后台比对下一集；同一时间只跑一个 */
    @Volatile private var prefetching: Cancel? = null
    private fun prefetchComparison(item: JSONObject, index: Int) {
        if (prefetching != null) return
        val cancel = Cancel(); prefetching = cancel
        Pool.ops.execute { try { compareEpisode(item, index, cancel) {} } catch (_: Exception) {} finally { if (prefetching === cancel) prefetching = null } }
    }

    // ---- 操作 ----
    private fun handle(op: String, args: JSONObject, cancel: Cancel, progress: (String, Any) -> Unit): Any? {
        when (op) {
            "home" -> return home()
            "config" -> return config()
            "remove-history" -> {
                // items：[{sourceKey, vodId}]；同一部片在多个片源各有一条记录时一起删
                library.removeHistories(args.optJSONArray("items").objects().map { it.optString("sourceKey") to it.optString("vodId") }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() })
                return home()
            }
            "clear-history" -> { library.clearHistory(); return home() }
            "clear-search-history" -> { library.clearSearches(); return home() }
            "toggle-favorite" -> { val card = args.optJSONObject("card") ?: throw IllegalArgumentException("影片信息无效"); return JSONObject().put("favorite", library.toggleFavorite(card)).put("home", home()) }
            "import-favorites" -> return JSONObject().put("imported", library.importFavorites(args.optJSONArray("items").objects())).put("home", home())
            "peer-sync" -> return sync.servePeer(args) { runner?.kick() }
            "peer-link" -> {
                val id = args.optString("id"); val url = args.optString("url"); val token = args.optString("token")
                peer = if (Regex("^[0-9a-f]{8,64}$").matches(id) && Regex("^http://[0-9.]{7,15}:\\d{1,5}$").matches(url) && Regex("^[0-9a-f]{32,128}$").matches(token)) Triple(id, url, token) else null
                runner?.kick(); return home()
            }
            "sync-now" -> { runner?.kick(); return home() }
            "search" -> {
                val wd = args.optString("query").trim()
                // 详情页按片名查找片源（本周热门）时 record 为 false，不记进最近搜索
                if (args.opt("record") != false) { library.addSearch(wd); progress("search-history", home()) }
                val sources = settings.activeSources(); val outcomes = ArrayList<JSONObject>(); var completed = 0; var failures = 0; var lastEmit = 0L
                val lock = Any()
                val result = catalog.search(wd, sources, settings.yellowFilter, cancel) { outcome ->
                    synchronized(lock) {
                        completed++; if (!outcome.optBoolean("ok")) failures++
                        outcomes.addAll(outcome.optJSONArray("list").objects())
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250 || completed == sources.size) { lastEmit = now; progress("search-progress", JSONObject().put("groups", JSONArray(catalog.groups(outcomes).take(300))).put("completed", completed).put("total", sources.size).put("failures", failures)) }
                    }
                    settings.recordSourceHealth(listOf(outcome))
                }
                cache.flush(); settings.flush()
                return JSONObject().put("groups", JSONArray(catalog.groups(result.list).take(300))).put("completed", completed).put("total", sources.size).put("failures", failures).put("done", true)
            }
            "detail" -> { val item = args.getJSONObject("item"); return JSONObject().put("item", item).put("detail", catalog.detail(item.optString("vodId"), sourceFor(item), cancel)) }
            "open-playback" -> {
                val item = args.getJSONObject("item"); val source = sourceFor(item); val key = source.optString("key"); val vodId = item.optString("vodId")
                val detail = catalog.detail(vodId, source, cancel); val episodes = detail.optJSONArray("episodes").strings()
                val index = args.optInt("index").coerceIn(0, episodes.size - 1)
                val saved = library.progress(key, vodId, index)
                val history = if (saved == null) library.historyEntry(key, vodId) else null
                val resume: Pair<Double, Double>? = saved?.let { it.optDouble("position", 0.0) to it.optDouble("duration", 0.0) } ?: history?.takeIf { it.optInt("episodeIndex") == index }?.let { it.optDouble("playbackPosition", 0.0) to it.optDouble("duration", 0.0) }
                val position = (args.opt("position") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { maxOf(0.0, it) }
                    ?: if (resume != null && (resume.second <= 0 || resume.first < resume.second - 15)) maxOf(0.0, resume.first) else 0.0
                if (cancel.cancelled) return null
                val adFilter = settings.adFilter
                val analyzed = if (adFilter) analyzeAds(episodes[index], cancel) else emptyList()
                val ads = if (adFilter) Ads.decide(analyzed, learned.all()) else null
                if (cancel.cancelled) return null
                // 电脑片库里的影片在直连模式下也走电脑，直接读取已处理好的整集
                val useDesktop = args.optBoolean("fromLibrary") || settings.string("playbackMode") == "desktop"
                val playback = Desktop.resolveDelivery(episodes[index], useDesktop, desktopOrEmpty(), settings.bool("superResolution", true), settings.bool("overlayRemoval", true), cancel,
                    Desktop.Options(position, adFilter, ads?.let { bounded(it.extraLines + it.heuristicLines) } ?: emptyList(), analyzedPlaylists(analyzed), Desktop.sanitizeDecoders(args.optJSONObject("decoders"))) { progress("processing-progress", it) })
                if (ads != null) playback.put("adSegments", JSONArray(bounded(ads.extraLines))).put("playlists", if (playback.optString("delivery") == "direct") analyzedPlaylists(analyzed) else JSONArray())
                    .put("adSummary", JSONObject().put("codecGroups", ads.codecGroups).put("learnedGroups", ads.learnedGroups).put("repeatGroups", ads.repeatGroups).put("timelineGroups", ads.timelineGroups))
                val session = playback.optJSONObject("desktopSession")
                if (cancel.cancelled) { Desktop.closeSession(session); return null }
                try {
                    library.upsertHistory(JSONObject().put("sourceKey", key).put("sourceUrl", source.optString("url")).put("vodId", vodId).put("title", item.optString("name"))
                        .put("episodeIndex", index).put("totalEpisodes", episodes.size).put("playbackPosition", position).put("duration", resume?.second ?: 0.0)
                        .apply { (item.str("pic")?.takeIf { it.isNotEmpty() } ?: detail.optJSONObject("videoInfo")?.str("cover"))?.let { put("pic", it) } })
                } catch (error: Exception) { Desktop.closeSession(session); throw error }
                if (cancel.cancelled) Desktop.closeSession(session)
                return JSONObject().put("item", item).put("source", source).put("detail", detail).put("index", index).put("position", (playback.opt("position") as? Number)?.toDouble() ?: position).put("playback", playback).put("config", config())
            }
            "save-progress" -> {
                val index = args.optDouble("index", Double.NaN); val position = args.optDouble("position", Double.NaN); val duration = args.optDouble("duration", Double.NaN)
                if (listOf(index, position, duration).any { !it.isFinite() } || index < 0 || position < 0 || duration <= 0) return null
                val removals = Desktop.intervals(args.optJSONArray("timeMap"))
                val p = Desktop.sourcePosition(position, removals); val d = Desktop.sourceDuration(duration, removals)
                library.saveProgress(args.optString("sourceKey"), args.optString("vodId"), index.toInt(), p, d); library.updateHistoryProgress(args.optString("sourceKey"), args.optString("vodId"), p, d)
                return null
            }
            "played-quality" -> {
                val height = args.optDouble("height", 0.0)
                if (height.isFinite() && height > 0) settings.setQuality(JSONObject().put(Quality.key(args.optString("sourceKey"), args.optString("vodId")), JSONObject().put("height", height.toInt()).put("from", "play").put("timestamp", System.currentTimeMillis())))
                return null
            }
            "switch-sources" -> {
                val title = args.optString("title").trim(); val sources = settings.activeSources(); val force = args.optBoolean("refresh"); val filter = settings.yellowFilter
                val rows = catalog.discover(title, sources, filter, cancel, force, { rows, cached, refreshing ->
                    progress("candidates", JSONArray(rows.map { JSONObject().put("item", it.getJSONObject("result")) }))
                    for (row in rows) {
                        val result = row.getJSONObject("result"); val key = row.getJSONObject("source").optString("key")
                        val quality = settings.quality(Quality.key(key, result.optString("vodId")))?.takeIf { Quality.fresh(it) }
                        val height = quality?.optInt("height")?.takeIf { it > 0 }; val bitrate = cachedBitrate(key, result.optString("vodId"))
                        progress("source-result", JSONObject().put("item", result).apply {
                            if (row.has("ok")) put("ok", row.optBoolean("ok")); if (row.has("ms")) put("ms", row.optLong("ms")); if (row.has("episodes")) put("episodes", row.optInt("episodes")); row.str("error")?.let { put("error", it) }
                            height?.let { put("height", it) }; bitrate?.let { put("bitrate", it) }; Quality.text(height, bitrate).takeIf { it.isNotEmpty() }?.let { put("quality", it) }
                        })
                    }
                    progress("sources-tested", JSONObject().put("count", rows.size).put("cached", cached).put("refreshing", refreshing))
                }, { message -> progress("cache-warning", JSONObject().put("message", message)) })
                Pool.mapConcurrent(rows.filter { it.optBoolean("ok") }.map { it.getJSONObject("result") }, 3, cancel) { item ->
                    val quality = probeSource(item, cancel, force)
                    if (!quality.cached) progress("quality-result", JSONObject().put("item", item).put("quality", Quality.text(quality.height, quality.bitrate).ifEmpty { "未知" }).apply { quality.height?.let { put("height", it) }; quality.bitrate?.let { put("bitrate", it) }; quality.error?.let { put("error", it) } })
                }
                cache.flush(); settings.flush()
                return JSONObject().put("count", rows.size)
            }
            "retest-source" -> {
                val item = args.getJSONObject("item"); val started = System.currentTimeMillis()
                val detail = catalog.detail(item.optString("vodId"), sourceFor(item), cancel, true)
                val height = settings.quality(Quality.key(item.optString("sourceKey"), item.optString("vodId")))?.takeIf { Quality.fresh(it) }?.optInt("height")?.takeIf { it > 0 }
                return JSONObject().put("item", item).put("ok", true).put("ms", System.currentTimeMillis() - started).put("episodes", detail.optJSONArray("episodes")?.length() ?: 0).apply { height?.let { put("height", it) }; Quality.label(height)?.let { put("quality", it) } }
            }
            "mark-ad" -> {
                val url = args.optString("url"); val position = args.optDouble("position", Double.NaN)
                if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url) || !position.isFinite() || position < 0) throw IllegalArgumentException("无效的播放位置")
                val (group, entry, _) = Ads.learnAt(analyzeAds(url, cancel), position, Ads.NetTransport, cancel)
                learned.save(Ads.Learned(entry.hash, entry.sig, entry.dir, entry.duration, args.optString("title").take(80).ifEmpty { null }, entry.at))
                return JSONObject().put("start", group.start).put("end", group.start + group.duration).put("learnedAds", learned.all().size).put("config", config())
            }
            "clear-learned-ads" -> { learned.clear(); return config() }
            "desktop-downloads" -> { val server = desktop() ?: return JSONObject().put("available", false).put("items", JSONArray()); return JSONObject().put("available", true).put("items", Desktop.downloads(server, cancel)) }
            "download-desktop" -> {
                // 电脑后台按当前处理设置把整集处理好保存到电脑片库
                val server = desktop() ?: throw IllegalStateException("请先在片源管理中填写电脑地址和配对码")
                val item = args.getJSONObject("item"); val source = sourceFor(item)
                val detail = catalog.detail(item.optString("vodId"), source, cancel); val episodes = detail.optJSONArray("episodes").strings()
                val index = args.optInt("index").coerceIn(0, episodes.size - 1); val sourceName = item.str("sourceName")?.takeIf { it.isNotEmpty() } ?: source.optString("name")
                return Desktop.addDownload(server, JSONObject().put("url", episodes[index]).put("superResolution", settings.bool("superResolution", true)).put("adFilter", settings.adFilter).put("overlayRemoval", settings.bool("overlayRemoval", true))
                    .put("title", item.optString("name")).put("subtitle", "${if (episodes.size > 1) "第 ${index + 1} 集" else "电影"} · $sourceName").apply { Desktop.sanitizeDecoders(args.optJSONObject("decoders"))?.let { put("decoders", it) } }
                    .put("meta", JSONObject().put("sourceKey", item.optString("sourceKey")).put("sourceName", sourceName).put("vodId", item.optString("vodId")).put("index", index).put("name", item.optString("name"))
                        .apply { item.str("sourceUrl")?.let { put("sourceUrl", it) }; (item.str("pic")?.takeIf { it.isNotEmpty() } ?: detail.optJSONObject("videoInfo")?.str("cover"))?.let { put("pic", it) } }), cancel)
            }
            "delete-download" -> { Desktop.removeDownload(desktopOrEmpty(), args.optString("id")); return JSONObject().put("removed", true) }
            "prefetch-bitrates" -> { args.optJSONArray("items").objects().take(12).forEach { runCatching { probeShared(it) } }; return null }
            "best-source" -> {
                // 比较各来源的实测码率，选码率最高、可用的来源；最多等 6 秒，未测完的按未知处理
                val items = args.optJSONArray("items").objects().take(12)
                if (items.isEmpty()) throw IllegalStateException("没有可播放的来源")
                val tasks = items.map { it to runCatching { probeShared(it) }.getOrNull() }; val deadline = System.currentTimeMillis() + 6000
                val scores = ArrayList<Pair<JSONObject, Probed>>()
                for ((item, task) in tasks) {
                    if (task == null) continue
                    while (!task.isDone && System.currentTimeMillis() < deadline && !cancel.cancelled) try { task.get(50, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) {} catch (_: Exception) { break }
                    if (task.isDone) runCatching { task.get() }.getOrNull()?.let { scores.add(item to it) }
                }
                if (cancel.cancelled) return null
                val best = scores.filter { it.second.error == null && ((it.second.bitrate ?: 0) > 0 || (it.second.height ?: 0) > 0) }.sortedWith(compareByDescending<Pair<JSONObject, Probed>> { it.second.bitrate ?: 0 }.thenByDescending { it.second.height ?: 0 }).firstOrNull()
                return JSONObject().put("item", best?.first ?: items[0]).put("quality", Quality.text(best?.second?.height, best?.second?.bitrate)).put("compared", scores.size).put("total", items.size).apply { best?.second?.bitrate?.let { put("bitrate", it) }; best?.second?.height?.let { put("height", it) } }
            }
            "compare-ads" -> {
                if (!settings.adFilter || desktop() == null) return JSONObject().put("available", false)
                val item = args.getJSONObject("item"); val index = maxOf(0, args.optInt("index"))
                val result = compareEpisode(item, index, cancel) { message -> progress("compare-progress", JSONObject().put("message", message)) }
                if (cancel.cancelled) return null
                val count = catalog.detail(item.optString("vodId"), sourceFor(item), cancel).optJSONArray("episodes")?.length() ?: 0
                if (index + 1 < count) prefetchComparison(item, index + 1)
                return result.copy().put("available", true)
            }
            "remove-source" -> { if (!settings.removeSource(args.optString("key"))) throw IllegalStateException("找不到这个片源"); settings.flush(); return config() }
            "restore-sources" -> { val count = settings.restoreHiddenSources(); settings.flush(); return config().put("restored", count) }
            "remove-subscription" -> { if (!settings.removeSubscription(args.optString("url"))) throw IllegalStateException("找不到这个订阅"); settings.flush(); return config() }
            "toggle-source" -> { settings.toggleSource(args.optString("key")); settings.flush(); return config() }
            "add-source" -> {
                val raw = args.optString("url").trim()
                val uri = runCatching { java.net.URL(raw) }.getOrNull()
                if (uri == null || uri.protocol !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) throw IllegalArgumentException("请输入有效的 HTTP/HTTPS 地址")
                if (!settings.addCustomSource(args.optString("name").trim().ifEmpty { uri.host }, raw.trimEnd('/'))) throw IllegalStateException("这个片源已经在列表里了")
                settings.flush(); return config()
            }
            "preview-paste" -> {
                // 只解析、不导入：让界面先告诉用户认出了什么
                val pasted = Subscriptions.parsePasted(args.optString("text"))
                val existing = settings.allSources().map { it.optString("url").trimEnd('/') }.toSet()
                return JSONObject().put("sources", JSONArray(pasted.sources)).put("newSources", pasted.sources.count { it.optString("url") !in existing })
                    .put("subscriptions", JSONArray(pasted.subscriptions)).put("note", pasted.note)
            }
            "import-paste" -> {
                val result = Subscriptions.importPasted(settings, Subscriptions.parsePasted(args.optString("text")), cancel)
                settings.flush(); return result.put("config", config())
            }
            "sync-subscription" -> return Subscriptions.sync(settings, args.optString("url"), cancel).also { settings.flush() }
            "pair-desktop" -> {
                // 6 位配对码 → 长配对码 → 保存并检测连接
                val url = Desktop.address(args.optString("url"))
                val token = Desktop.pair(url, args.optString("pin"), cancel)
                settings.update(JSONObject().put("desktopServerUrl", url).put("desktopPairingToken", token)); settings.flush(); runner?.kick()
                return JSONObject().put("message", Desktop.test(Desktop.Server(url, token), cancel)).put("config", config())
            }
            "test-desktop" -> return Desktop.test(Desktop.Server(args.optString("desktopServerUrl"), args.optString("desktopPairingToken")), cancel)
            "close-desktop" -> { Desktop.closeSession(args.optJSONObject("session")); return null }
            "settings" -> {
                val patch = JSONObject()
                args.str("playbackMode")?.takeIf { it == "direct" || it == "desktop" }?.let { patch.put("playbackMode", it) }
                for (key in listOf("desktopServerUrl", "desktopPairingToken")) args.str(key)?.let { patch.put(key, it.trim()) }
                for (key in listOf("superResolution", "overlayRemoval", "adFilter", "autoplayNext")) (args.opt(key) as? Boolean)?.let { patch.put(key, it) }
                settings.update(patch); settings.flush()
                if (patch.has("desktopServerUrl") || patch.has("desktopPairingToken")) runner?.kick()
                return config()
            }
            else -> throw IllegalArgumentException("未知的操作")
        }
    }
}
