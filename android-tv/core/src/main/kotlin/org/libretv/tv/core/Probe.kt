package org.libretv.tv.core

import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal fun resolveUrl(base: String, ref: String): String? = try { URL(URL(base), ref).toString() } catch (_: Exception) { null }
internal fun originOf(url: String): String = try { URI(url).let { "${it.scheme}://${it.rawAuthority}" } } catch (_: Exception) { "" }

/** 画质与码率探测：读播放列表和分片开头，不下载整段视频。结果：height / bitrate(kbps) / error。 */
object QualityProbe {
    class Result(val height: Int? = null, val bitrate: Int? = null, val error: String? = null)
    private const val TIMEOUT_MS = 12000L
    private fun heightFromBitrate(bandwidth: Long): Int = listOf(20_000_000L to 4320, 10_000_000L to 2160, 5_000_000L to 1080, 3_000_000L to 720, 1_500_000L to 540, 800_000L to 480, 400_000L to 360).firstOrNull { bandwidth >= it.first }?.second ?: 240

    /** 多码率列表里最高的一档（没写分辨率的按码率估） */
    fun parseBestHeight(text: String): Int? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        var best: Int? = null; var pending: Pair<Long, Int?>? = null
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                pending = bandwidth to resolution?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                continue
            }
            val p = pending ?: continue
            if (line.isEmpty() || line.startsWith("#")) continue
            val height = p.second ?: if (p.first > 0) heightFromBitrate(p.first) else null
            if (height != null && height > 0 && (best == null || height > best)) best = height
            pending = null
        }
        return best
    }
    class MediaInfo(var initUri: String? = null, var firstSegment: String? = null, var keyMethod: String? = null, var keyUri: String? = null, var keyIv: ByteArray? = null, var sequence: Long = 0)
    fun parseMediaPlaylist(text: String): MediaInfo {
        val info = MediaInfo()
        for (raw in text.split('\n')) {
            val line = raw.trim(); if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MAP:") -> info.initUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                line.startsWith("#EXT-X-KEY:") -> {
                    info.keyMethod = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1)?.trim(); info.keyUri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    info.keyIv = Regex("IV=0x([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.takeIf { it.length % 2 == 0 }?.let { hex -> ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> info.sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                !line.startsWith("#") && info.firstSegment == null -> info.firstSegment = line
            }
        }
        return info
    }
    fun sequenceIv(sequence: Long) = ByteArray(16).also { iv -> val s = sequence and 0xFFFFFFFFL; iv[12] = (s shr 24).toByte(); iv[13] = (s shr 16).toByte(); iv[14] = (s shr 8).toByte(); iv[15] = s.toByte() }
    /** 探测剧集时挑靠后的集：第一集常是预告或花絮 */
    fun episodeOrder(episodes: List<String>): List<String> {
        if (episodes.size < 2) return episodes.take(1)
        val mid = episodes[episodes.size / 2]; val last = episodes.last()
        return if (mid == last) listOf(mid) else listOf(mid, last)
    }

    private fun headers(source: JSONObject) = mapOf("User-Agent" to CmsParser.headers.getValue("User-Agent"), "Referer" to "${originOf(source.optString("url"))}/", "Accept" to "*/*")
    private fun prefix(url: String, maxBytes: Int, source: JSONObject, cancel: Cancel): ByteArray? {
        if (!url.startsWith("http", true)) return null
        val res = Net.fetch(url, headers = headers(source), timeoutMs = 6000, maxBytes = maxBytes, prefix = true, cancel = cancel)
        return if (res.ok) res.body else null
    }
    private fun segmentHeight(text: String, playlistUrl: String, source: JSONObject, cancel: Cancel): Int? {
        val list = parseMediaPlaylist(text); val first = list.firstSegment ?: return null
        list.initUri?.let { resolveUrl(playlistUrl, it) }?.let { init -> prefix(init, 64 * 1024, source, cancel)?.let { Codec.parseMp4VideoResolution(it) }?.let { return it.height } }
        var buf = prefix(resolveUrl(playlistUrl, first) ?: return null, 96 * 1024, source, cancel)?.takeIf { it.isNotEmpty() } ?: return null
        if (list.keyMethod != null && list.keyMethod != "NONE") {
            if (list.keyMethod != "AES-128") return null
            val key = prefix(resolveUrl(playlistUrl, list.keyUri ?: return null) ?: return null, 32, source, cancel)?.takeIf { it.size >= 16 } ?: return null
            buf = Codec.decryptPrefix(buf, key, list.keyIv?.takeIf { it.size >= 16 } ?: sequenceIv(list.sequence))
        }
        return (if (Codec.looksLikeMp4(buf)) Codec.parseMp4VideoResolution(buf) else Codec.parseTsVideoResolution(buf))?.height
    }
    /** 抽三个分片的大小估算码率（kbps）；只发 Range 0-0 请求读总大小 */
    private fun bitrate(text: String, playlistUrl: String, source: JSONObject, cancel: Cancel): Int? {
        var media = text; var base = playlistUrl
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.split('\n').map { it.trim() }
            var best: Pair<Long, String>? = null
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (best == null || bandwidth > best!!.first) best = bandwidth to uri
            }
            val url = best?.let { resolveUrl(playlistUrl, it.second) } ?: return null
            val res = Net.fetch(url, headers = headers(source), timeoutMs = 4000, maxBytes = 4 * 1024 * 1024, prefix = true, cancel = cancel)
            if (!res.ok) return null
            media = res.text(); base = res.url
        }
        class Segment(val uri: String, val duration: Double, val length: Long?)
        val segments = ArrayList<Segment>(); var duration = 0.0; var length: Long? = null
        for (raw in media.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> duration = Regex("^[\\d.]+").find(line.substring(8).trim())?.value?.toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> length = line.substring(17).substringBefore('@').trim().toLongOrNull()?.takeIf { it > 0 }
                line.isNotEmpty() && !line.startsWith("#") -> { if (duration > 0) segments.add(Segment(line, duration, length)); duration = 0.0; length = null }
            }
        }
        if (segments.size < 3) return null
        var bytes = 0L; var seconds = 0.0
        for (segment in listOf(.3, .5, .7).map { segments[(segments.size * it).toInt()] }) {
            var size = segment.length
            if (size == null) {
                val url = resolveUrl(base, segment.uri) ?: continue
                val res = Net.fetch(url, headers = headers(source) + ("Range" to "bytes=0-0"), timeoutMs = 4000, maxBytes = 1024, prefix = true, cancel = cancel)
                size = res.header("content-range")?.substringAfter('/', "")?.toLongOrNull()?.takeIf { it > 0 } ?: (if (res.status == 200) res.header("content-length")?.toLongOrNull() else null)?.takeIf { it > 0 }
            }
            if (size != null && size > 1024) { bytes += size; seconds += segment.duration }
        }
        return if (seconds > 0) Math.round(bytes * 8 / seconds / 1000).toInt() else null
    }
    private fun <T> budget(parent: Cancel, client: Cancel?, ms: Long, task: (Cancel) -> T?): T? {
        val limit = parent.child(ms)
        return try { task(limit) } catch (_: Exception) { if (client?.cancelled == true) throw CancelledException(); null } finally { limit.close() }
    }
    private fun episodes(source: JSONObject, id: String, cancel: Cancel): List<String> {
        val res = Net.fetch("${source.optString("url").trimEnd('/')}?ac=videolist&ids=${URLEncoder.encode(id, "UTF-8")}", headers = CmsParser.headers, timeoutMs = 6000, cancel = cancel)
        if (res.ok) try { CmsParser.parseDetail(res.json(), source).optJSONArray("episodes").strings().takeIf { it.isNotEmpty() }?.let { return it } } catch (_: CancelledException) { throw CancelledException() } catch (_: Exception) {}
        val root = (source.str("detail")?.takeIf { it.isNotEmpty() } ?: originOf(source.optString("url"))).trimEnd('/')
        if (!Regex("^https?://").containsMatchIn(root)) return emptyList()
        val page = Net.fetch("$root/index.php/vod/detail/id/$id.html", headers = mapOf("User-Agent" to CmsParser.headers.getValue("User-Agent")), timeoutMs = 6000, cancel = cancel)
        return if (page.ok) CmsParser.parseDetailPage(page.text(), source).optJSONArray("episodes").strings() else emptyList()
    }
    private fun episode(target: String, source: JSONObject, merged: Cancel, client: Cancel?): Result {
        if (!Regex("\\.m3u8([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target)) {
            if (!Regex("\\.mp4([?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(target)) return Result()
            return Result(height = prefix(target, 96 * 1024, source, merged)?.let { Codec.parseMp4VideoResolution(it) }?.height)
        }
        val res = Net.fetch(target, headers = headers(source), timeoutMs = 6000, maxBytes = 64 * 1024, prefix = true, cancel = merged)
        if (!res.ok) return Result(error = "HTTP ${res.status}")
        val text = res.text(); val playlistUrl = res.url
        // 清晰度和码率并行探测
        val rate = Pool.io.submit<Int?> { budget(merged, client, 5000) { bitrate(text, playlistUrl, source, it) } }
        val height = try { parseBestHeight(text) ?: budget(merged, client, 4000) { segmentHeight(text, playlistUrl, source, it) } } catch (e: CancelledException) { rate.cancel(true); throw e }
        val bitrate = try { rate.get() } catch (e: java.util.concurrent.ExecutionException) { if (e.cause is CancelledException) throw CancelledException(); null }
        return Result(height, bitrate)
    }

    /** 探测一部影片在某个片源的画质；总时限 12 秒。界面取消时抛出，其余失败写进 error */
    fun probe(source: JSONObject, id: String, client: Cancel?): Result {
        if (!Regex("^https?://").containsMatchIn(source.optString("url")) || !Regex("^[\\w-]+$").matches(id)) return Result(error = "无效的源或影片 ID")
        val merged = (client ?: Cancel()).child(TIMEOUT_MS)
        try {
            val list = episodes(source, id, merged)
            if (list.isEmpty()) return Result(error = "未找到播放资源")
            val started = System.currentTimeMillis(); var lastError: String? = null
            for (target in episodeOrder(list)) {
                val r = episode(target, source, merged, client)
                if (r.height != null) return Result(r.height, r.bitrate?.takeIf { it > 0 })
                if (r.error != null) lastError = r.error
                if (lastError != null && Regex("^HTTP (401|403)$").matches(lastError)) break
                if (System.currentTimeMillis() - started > TIMEOUT_MS / 2) break
            }
            return Result(error = lastError)
        } catch (error: Exception) {
            if (client?.cancelled == true) throw CancelledException()
            return Result(error = if (merged.timedOut) "画质探测超时" else error.message ?: "探测失败")
        } finally { merged.close() }
    }
}
