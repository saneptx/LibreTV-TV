package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import org.libretv.tv.PlaylistFilter
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 去广告分析：把播放列表按「不连续点 / 目录」切成段组，读每组第一个分片的开头，得到编码指纹和起始时间戳，
 * 再综合四种依据判断哪些段组是插播广告：编码参数与正片不同、用户标记学习过、同目录同长度重复出现、时间轴上凭空多出来。
 */
object Ads {
    class Group(val start: Double, var duration: Double, val lines: MutableList<String>, val firstUrl: String, val dir: String,
                val keyUri: String?, val keyIv: ByteArray?, val initUrl: String?) {
        var sig: String? = null; var hash: String? = null; var probed = false; @Volatile var pts: Double? = null
    }
    class Media(val url: String, val text: String, val total: Double, val groups: List<Group>, val heuristicLines: List<String>) { var contentDir: String? = null }
    class Decision(val extraLines: List<String>, val heuristicLines: List<String>, val codecGroups: Int, val learnedGroups: Int, val repeatGroups: Int, val timelineGroups: Int)
    class Learned(val hash: String, val sig: String?, val dir: String?, val duration: Double, val title: String?, val at: Long) {
        fun json(): JSONObject = JSONObject().put("hash", hash).put("duration", duration).put("at", at).apply { sig?.let { put("sig", it) }; dir?.let { put("dir", it) }; title?.let { put("title", it) } }
    }
    const val MAX_SHORT_GROUP = 180.0
    private const val CODEC_AD_MAX = 90.0
    private const val REPEAT_AD_MAX = 60.0
    private const val MAX_PROBED = 24
    private const val TIMELINE_SAMPLES = 16
    private const val MAX_TIMED_READS = 200
    private const val TIMELINE_AD_MAX = 90.0
    private const val PTS_TOLERANCE = .5
    private const val PTS_WRAP = 8589934592.0 / 90000

    private fun directory(url: String): String = try { val u = URL(url); val path = u.path; originOf(url) + path.substring(0, path.lastIndexOf('/') + 1) } catch (_: Exception) { "" }
    private fun hexIv(value: String): ByteArray? {
        val hex = value.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (!Regex("^[0-9a-fA-F]{32}$").matches(hex)) return null
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    /** 分片开头的内容指纹（两路 32 位滚动哈希，与旧版一致，已学习的广告继续有效） */
    fun prefixHash(buf: ByteArray): String {
        var a = 0x811c9dc5.toInt(); var b = 0x01000193 xor 0x5bd1e995
        for (byte in buf) { val v = byte.toInt() and 0xff; a = (a xor v) * 0x01000193; b = ((b xor v) * 0x01000193) xor (b ushr 13) }
        return "%08x%08x".format(a.toLong() and 0xFFFFFFFFL, b.toLong() and 0xFFFFFFFFL)
    }
    private fun segmentLines(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    /** 启发式规则（片头短组、夹在长段之间的短组、广告地址）会删掉的分片行 */
    fun heuristicRemovedLines(text: String): List<String> {
        val before = segmentLines(text); val after = segmentLines(PlaylistFilter.strip(text)); val removed = ArrayList<String>(); var j = 0
        for (line in before) if (j < after.size && after[j] == line) j++ else removed.add(line)
        return removed
    }
    private fun jsFloat(text: String) = Regex("^\\s*[-+]?(\\d+\\.?\\d*|\\.\\d+)").find(text)?.value?.trim()?.toDoubleOrNull() ?: 0.0
    fun playlistGroups(text: String, base: String): Pair<List<Group>, Double> {
        fun build(splitByDir: Boolean): Pair<List<Group>, Double> {
            val groups = ArrayList<Group>(); var sequence = 0L; var duration = 0.0; var total = 0.0; var boundary = true
            var keyUri: String? = null; var keyIv: ByteArray? = null; var initUrl: String? = null; var current: Group? = null
            for (raw in text.split('\n')) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substring(22).trim().toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-KEY:") -> {
                        val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1); val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val resolved = uri?.let { resolveUrl(base, it) }
                        if (method == "AES-128" && resolved != null) { keyUri = resolved; keyIv = Regex("IV=(0x[0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hexIv(it) } } else { keyUri = null; keyIv = null }
                    }
                    line.startsWith("#EXT-X-MAP:") -> initUrl = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { resolveUrl(base, it) }
                    line == "#EXT-X-DISCONTINUITY" -> boundary = true
                    line.startsWith("#EXTINF:") -> duration = jsFloat(line.substring(8))
                    line.isNotEmpty() && !line.startsWith("#") -> {
                        val url = resolveUrl(base, line) ?: line; val dir = directory(url)
                        var g = current
                        if (g == null || boundary || splitByDir && dir != g.dir) {
                            g = Group(total, 0.0, ArrayList(), url, dir, keyUri, if (keyUri != null) keyIv ?: QualityProbe.sequenceIv(sequence) else null, initUrl)
                            groups.add(g); current = g
                        }
                        g.lines.add(line); g.duration += duration; total += duration; sequence++; duration = 0.0; boundary = false
                    }
                }
            }
            return groups to total
        }
        val split = build(true)
        return if (split.first.count { it.duration <= MAX_SHORT_GROUP } > MAX_PROBED) build(false) else split
    }

    /** 读取方式由调用方提供，便于测试 */
    interface Transport {
        fun fetchText(url: String, cancel: Cancel): Pair<String, String>?          // (正文, 最终地址)
        fun fetchPrefix(url: String, maxBytes: Int, cancel: Cancel, range: Boolean = false): ByteArray?
    }
    object NetTransport : Transport {
        override fun fetchText(url: String, cancel: Cancel) = Net.fetch(url, timeoutMs = 5000, cancel = cancel).takeIf { it.ok }?.let { it.text() to it.url }
        // 不支持 Range 的服务器会返回整个文件，照样只读开头
        override fun fetchPrefix(url: String, maxBytes: Int, cancel: Cancel, range: Boolean) =
            Net.fetch(url, headers = if (range) mapOf("Range" to "bytes=0-${maxBytes - 1}") else emptyMap(), timeoutMs = 4000, maxBytes = maxBytes, prefix = true, cancel = cancel).takeIf { it.ok }?.body
    }
    private class Keys(val transport: Transport) { private val cache = ConcurrentHashMap<String, Lazy<ByteArray?>>()
        fun get(uri: String, cancel: Cancel): ByteArray? = cache.getOrPut(uri) { lazy { runCatching { transport.fetchPrefix(uri, 32, cancel) }.getOrNull() } }.value }
    private fun readPrefix(group: Group, bytes: Int, keys: Keys, cancel: Cancel, range: Boolean = false): ByteArray? {
        val buf = keys.transport.fetchPrefix(group.firstUrl, bytes, cancel, range) ?: return null
        if (buf.size < 188) return null
        val keyUri = group.keyUri ?: return buf
        val key = keys.get(keyUri, cancel)?.takeIf { it.size >= 16 } ?: return null
        return Codec.decryptPrefix(buf, key, group.keyIv ?: return null)
    }
    private fun fingerprint(group: Group, keys: Keys, cancel: Cancel) {
        if (group.probed) return
        val buf = readPrefix(group, 64 * 1024, keys, cancel) ?: return
        group.hash = prefixHash(buf)
        val mp4 = Codec.looksLikeMp4(buf)
        if (group.initUrl == null && !mp4) group.pts = Codec.parseTsFirstPts(buf)
        group.sig = if (group.initUrl != null || mp4) (if (group.initUrl != null) keys.transport.fetchPrefix(group.initUrl, 64 * 1024, cancel) else buf)?.let { Codec.parseMp4VideoResolution(it) }?.let { "mp4|${it.width}x${it.height}" }
        else Codec.parseTsSignature(buf)
        group.probed = true
    }
    /** 并发处理，单个失败不影响其他；取消后不再开始新的 */
    private fun <T> pool(items: List<T>, concurrency: Int, cancel: Cancel, task: (T) -> Unit) {
        if (items.isEmpty()) return
        val next = AtomicInteger()
        (0 until minOf(concurrency, items.size)).map { Pool.io.submit { while (!cancel.cancelled) { val i = next.getAndIncrement(); if (i >= items.size) break; runCatching { task(items[i]) } } } }.forEach { runCatching { it.get() } }
    }
    private fun ptsDelta(a: Double, b: Double): Double { val d = (a - b) % PTS_WRAP; return if (d > PTS_WRAP / 2) d - PTS_WRAP else if (d < -PTS_WRAP / 2) d + PTS_WRAP else d }
    /** 抽样读各段组的起始时间戳：时间轴连续的区间不再细读，只在出现跳变的区间里继续二分 */
    private fun probeTimeline(groups: List<Group>, cancel: Cancel, read: (Group) -> Unit) {
        val tried = HashSet<Group>(); var reads = 0
        fun offset(g: Group) = g.pts?.let { it - g.start }
        fun same(a: Group, b: Group): Boolean { val x = offset(a); val y = offset(b); return x != null && y != null && Math.abs(ptsDelta(x, y)) <= PTS_TOLERANCE }
        var intervals = if (groups.size > 1) listOf(0 to groups.size - 1) else emptyList()
        while (intervals.isNotEmpty() && !cancel.cancelled && reads < MAX_TIMED_READS) {
            val points = LinkedHashSet<Int>(); val next = ArrayList<Pair<Int, Int>>(); val sampled = ArrayList<List<Int>>()
            for ((a, b) in intervals) {
                val count = b - a + 1
                val picks = if (count <= TIMELINE_SAMPLES) (a..b).toList() else (0 until TIMELINE_SAMPLES).map { a + Math.round(it * (b - a).toDouble() / (TIMELINE_SAMPLES - 1)).toInt() }
                points.addAll(picks); sampled.add(if (count <= TIMELINE_SAMPLES) emptyList() else picks)
            }
            val todo = points.map { groups[it] }.filter { it.pts == null && it !in tried }.take(MAX_TIMED_READS - reads)
            tried.addAll(todo); reads += todo.size
            pool(todo, 8, cancel, read)
            for (picks in sampled) for (k in 1 until picks.size) { val a = picks[k - 1]; val b = picks[k]; if (b - a > 1 && !same(groups[a], groups[b])) next.add(a to b) }
            intervals = next
        }
    }
    private fun analyzeMedia(text: String, url: String, transport: Transport, budget: Cancel, parent: Cancel?, timeline: Boolean): Media {
        val (groups, total) = playlistGroups(text, url)
        val media = Media(url, text, total, groups, heuristicRemovedLines(text))
        if (groups.size < 2) return media
        val longest = groups.sortedByDescending { it.duration }
        media.contentDir = longest[0].dir
        val shorts = groups.filter { it.duration <= MAX_SHORT_GROUP }
        val keys = Keys(transport)
        if (shorts.size <= MAX_PROBED) pool((longest.filter { it.duration > MAX_SHORT_GROUP }.take(3) + shorts).distinct(), 4, budget) { fingerprint(it, keys, budget) }
        if (timeline && groups.all { it.initUrl == null } && parent?.cancelled != true) {
            val limit = (parent ?: Cancel()).child(4000)
            try { probeTimeline(groups, limit) { g -> readPrefix(g, 4 * 1024, keys, limit, true)?.takeIf { !Codec.looksLikeMp4(it) }?.let { g.pts = Codec.parseTsFirstPts(it) } } } finally { limit.close() }
        }
        return media
    }
    /** 分析一集的播放列表（多码率时最多看前三档）；任何失败都返回空结果，不影响播放 */
    fun analyzePlaylist(url: String, transport: Transport, cancel: Cancel?, budgetMs: Long = 5000, timeline: Boolean = true): List<Media> {
        if (!Regex("\\.m3u8([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) return emptyList()
        val budget = (cancel ?: Cancel()).child(budgetMs)
        try {
            val first = transport.fetchText(url, budget) ?: return emptyList()
            if (!first.first.trimStart().startsWith("#EXTM3U")) return emptyList()
            if (!first.first.contains("#EXT-X-STREAM-INF")) return listOf(analyzeMedia(first.first, first.second, transport, budget, cancel, timeline))
            val lines = first.first.split('\n').map { it.trim() }
            val variants = lines.mapIndexedNotNull { i, line -> if (line.startsWith("#EXT-X-STREAM-INF")) lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } else null }.mapNotNull { resolveUrl(first.second, it) }.take(3)
            val result = ArrayList<Media>()
            for (variant in variants) {
                val media = runCatching { transport.fetchText(variant, budget) }.getOrNull() ?: continue
                if (media.first.trimStart().startsWith("#EXTM3U")) result.add(analyzeMedia(media.first, media.second, transport, budget, cancel, timeline))
            }
            return result
        } catch (_: Exception) { return emptyList() } finally { budget.close() }
    }

    /** 时间轴上凭空插进来的段组：前后正片的时间戳能接上，中间这段对不上 */
    fun timelineAdGroups(groups: List<Group>, total: Double): List<List<Group>> {
        val spans = ArrayList<List<Group>>(); var anchor: Group? = null; var origin = 0.0; var i = 0
        while (i < groups.size) {
            val g = groups[i]; val pts = g.pts
            if (pts == null) { i++; continue }
            val a = anchor
            if (a == null) { anchor = g; origin = g.start; i++; continue }
            val resume = a.pts!! + g.start - a.start
            if (Math.abs(ptsDelta(pts, resume)) <= PTS_TOLERANCE) { anchor = g; i++; continue }
            var j = i + 1; var inserted = g.duration
            while (j < groups.size && inserted <= TIMELINE_AD_MAX && groups[j].pts != null && Math.abs(ptsDelta(groups[j].pts!!, resume)) > PTS_TOLERANCE) inserted += groups[j++].duration
            val back = groups.getOrNull(j); val backPts = back?.pts
            if (back != null && backPts != null && inserted <= TIMELINE_AD_MAX && inserted <= total * .15 && g.start - origin > TIMELINE_AD_MAX && Math.abs(ptsDelta(backPts, resume)) <= PTS_TOLERANCE) { spans.add(groups.subList(i, j)); anchor = back; i = j + 1 }
            else { anchor = g; origin = g.start; i++ }
        }
        return spans
    }
    private fun fixed2(value: Double) = "%.2f".format(java.util.Locale.US, value)
    fun decide(media: List<Media>, learned: List<Learned>): Decision {
        val hashes = learned.map { it.hash }.toSet(); val extra = LinkedHashSet<String>(); val heuristic = LinkedHashSet<String>()
        var codecGroups = 0; var learnedGroups = 0; var repeatGroups = 0; var timelineGroups = 0
        for (m in media) {
            heuristic.addAll(m.heuristicLines)
            val probed = m.groups.filter { it.sig != null }; val long = probed.filter { it.duration > MAX_SHORT_GROUP }
            val bySig = LinkedHashMap<String, Double>(); var probedLong = 0.0
            for (g in long.ifEmpty { probed }) { bySig[g.sig!!] = (bySig[g.sig!!] ?: 0.0) + g.duration; probedLong += g.duration }
            val top = bySig.entries.sortedByDescending { it.value }.firstOrNull()
            val main = top?.key; val mainSeconds = top?.value ?: 0.0
            var longest: Group? = null; for (g in m.groups) if (longest == null || g.duration > longest.duration) longest = g
            val reliable = main != null && mainSeconds >= probedLong * .7 && longest?.sig == main
            val repeats = HashMap<String, Int>()
            for (g in m.groups) if (g.duration > 0 && g.duration <= REPEAT_AD_MAX && g.dir != m.contentDir) { val key = "${g.dir}|${fixed2(g.duration)}"; repeats[key] = (repeats[key] ?: 0) + 1 }
            for (g in m.groups) {
                if (g.duration > MAX_SHORT_GROUP || g.duration <= 0) continue
                val byCodec = reliable && g.sig != null && g.sig != main && g.duration <= CODEC_AD_MAX && g.duration <= m.total * .15
                val byLearning = g.hash != null && g.hash in hashes || learned.any { l -> l.dir != null && l.dir == g.dir && g.dir != m.contentDir && l.sig != null && l.sig == g.sig && Math.abs(l.duration - g.duration) <= 1.5 }
                val byRepeat = g.dir != m.contentDir && g.duration <= REPEAT_AD_MAX && (repeats["${g.dir}|${fixed2(g.duration)}"] ?: 0) >= 2
                if (!byCodec && !byLearning && !byRepeat) continue
                if (byLearning) learnedGroups++ else if (byCodec) codecGroups++ else repeatGroups++
                extra.addAll(g.lines)
            }
            for (span in timelineAdGroups(m.groups, m.total)) {
                if (span.all { g -> g.lines.all { it in extra } }) continue
                timelineGroups += span.size; span.forEach { extra.addAll(it.lines) }
            }
        }
        return Decision(extra.toList(), heuristic.filter { it !in extra }, codecGroups, learnedGroups, repeatGroups, timelineGroups)
    }
    /** 用户在播放中标记「这是广告」：找到当前位置所在的段组并记下它的指纹 */
    fun learnAt(media: List<Media>, position: Double, transport: Transport, cancel: Cancel?): Triple<Group, Learned, String?> {
        val m = media.firstOrNull()?.takeIf { it.groups.size >= 2 } ?: throw IllegalStateException("这段广告与正片在同一个编码段里，暂时无法按分片识别")
        val group = m.groups.firstOrNull { position >= it.start - .5 && position < it.start + it.duration + .5 } ?: throw IllegalStateException("没有找到当前播放位置对应的片段")
        if (group.duration > MAX_SHORT_GROUP || group.dir == m.contentDir && group.duration >= m.groups.maxOf { it.duration }) throw IllegalStateException("当前片段较长，更像正片，未做标记")
        val budget = (cancel ?: Cancel()).child(8000)
        try { fingerprint(group, Keys(transport), budget) } finally { budget.close() }
        val hash = group.hash ?: throw IllegalStateException("读取当前片段失败，请稍后重试")
        return Triple(group, Learned(hash, group.sig, group.dir.takeIf { it != m.contentDir }, Math.round(group.duration * 10) / 10.0, null, System.currentTimeMillis()), m.contentDir)
    }

    /** 已学习的广告指纹（最多 300 条，落盘） */
    class LearnedStore(file: File) {
        private val store = JsonFile(file, 0)
        private var list: List<Learned> = parse(store.read()?.optJSONArray("items"))
        @Synchronized fun all() = list
        @Synchronized fun save(entry: Learned) { list = (listOf(entry) + list.filter { it.hash != entry.hash }).take(300); write() }
        @Synchronized fun clear() { list = emptyList(); write() }
        @Synchronized fun importAll(items: JSONArray?) { list = parse(items).take(300); write() }
        private fun write() { val text = JSONObject().put("items", JSONArray(list.map { it.json() })).toString(); store.schedule { text }; store.flush() }
        private fun parse(items: JSONArray?) = items.objects().filter { it.str("hash") != null && it.optDouble("duration").isFinite() }.map { Learned(it.optString("hash"), it.str("sig"), it.str("dir"), it.optDouble("duration"), it.str("title"), it.optLong("at")) }
    }
}
