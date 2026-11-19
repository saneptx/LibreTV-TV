package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** 电脑串流服务（desktop-server）的客户端：建立播放任务、片库下载、多来源比对、资料同步。仅电视端使用。 */
object Desktop {
    private const val UNREACHABLE = "无法连接电脑服务，请检查地址、服务进程和网络；也可切回独立直连"

    /** 把原片时间换算回含广告的源时间 / 时长（去掉的区间加回去） */
    fun sourcePosition(position: Double, removals: List<Pair<Double, Double>>): Double {
        var result = position; var removed = 0.0
        for ((start, duration) in removals) { if (position + .00001 >= start - removed) result += duration; removed += duration }
        return result
    }
    fun sourceDuration(duration: Double, removals: List<Pair<Double, Double>>) = if (duration > 0) duration + removals.sumOf { it.second } else 0.0
    fun intervals(list: JSONArray?) = list.objects().map { it.optDouble("start") to it.optDouble("duration") }

    fun address(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { null }
        if (uri?.scheme == null || uri.host == null) throw IllegalArgumentException("请填写电脑服务地址，例如 http://192.168.1.10:8090")
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.rawPath.orEmpty() !in setOf("", "/") || uri.rawQuery != null || uri.rawFragment != null) throw IllegalArgumentException("电脑服务地址只能包含协议、主机和端口")
        val port = uri.port.takeUnless { it == -1 || uri.scheme == "http" && it == 80 || uri.scheme == "https" && it == 443 }
        return "${uri.scheme}://${uri.host.lowercase(java.util.Locale.ROOT)}${port?.let { ":$it" }.orEmpty()}"
    }
    class Server(val url: String, val token: String)
    private fun api(server: Server, path: String, method: String, body: JSONObject? = null, cancel: Cancel? = null, timeoutMs: Long = 8000, maxBytes: Int = 65536): JSONObject {
        val base = address(server.url); val token = server.token.trim()
        if (token.length < 24 || token.length > 256 || token.contains('\r') || token.contains('\n')) throw IllegalArgumentException("请填写电脑服务生成的配对码（独立直连无需配对）")
        val limit = (cancel ?: Cancel()).child(timeoutMs)
        try {
            val response = Net.fetch(base + path, method, mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), body?.toString(), (timeoutMs - 1000).toInt(), maxBytes, cancel = limit)
            val result = try { response.json() as? JSONObject } catch (_: Exception) { null } ?: throw java.io.IOException("电脑服务返回了无法识别的内容")
            if (!response.ok) throw IllegalStateException(result.str("error")?.takeIf { it.isNotEmpty() } ?: "电脑服务返回 ${response.status}")
            return result
        } catch (error: Exception) {
            if (cancel?.cancelled == true) throw CancelledException()
            // 服务端明确给出的错误原样上报；连不上、超时统一成一句说明
            if (error is IllegalStateException || error is IllegalArgumentException) throw error
            throw IllegalStateException(UNREACHABLE)
        } finally { limit.close() }
    }
    private fun pause(ms: Long, cancel: Cancel?) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { cancel?.check(); Thread.sleep(50) } }

    /** 首次连接：用电脑窗口里显示的 6 位配对码换取长配对码（之后保存在本机，不用再输） */
    fun pair(url: String, pin: String, cancel: Cancel?): String {
        val base = address(url)
        val response = try { Net.fetch("$base/v1/pair", "POST", mapOf("Content-Type" to "application/json"), JSONObject().put("pin", pin.filter { it.isDigit() }).toString(), 6000, 4096, cancel = cancel) }
            catch (error: CancelledException) { throw error } catch (_: Exception) { throw IllegalStateException(UNREACHABLE) }
        val body = runCatching { response.json() as? JSONObject }.getOrNull()
        if (response.status == 404 && body?.str("error").isNullOrEmpty()) throw IllegalStateException("电脑服务版本较旧，不支持 6 位配对码，请更新电脑服务")
        if (!response.ok) throw IllegalStateException(body?.str("error")?.takeIf { it.isNotEmpty() } ?: "电脑服务返回 ${response.status}")
        return body?.str("token")?.takeIf { it.length in 24..256 } ?: throw IllegalStateException("电脑服务返回的数据无效")
    }
    fun test(server: Server, cancel: Cancel?): String {
        val result = api(server, "/v1/status", "GET", cancel = cancel)
        if (result.optInt("protocol") != 1 || result.optString("service") != "LibreTV Desktop" || result.optString("processing") !in setOf("relay", "enhance")) throw IllegalStateException("电脑服务版本不兼容，请更新服务")
        return if (result.optString("processing") == "enhance") "已连接电脑 · 广告分片过滤 / ${result.optJSONObject("capabilities")?.str("model")?.takeIf { it.isNotEmpty() } ?: "AI"} 超分 / 编码已启用" else "已连接电脑 · 当前仅串流，去广告和超分尚未启用"
    }
    fun exchangeLibrary(server: Server, body: JSONObject, cancel: Cancel?): JSONObject = api(server, "/v1/sync", "POST", body, cancel, 20000, 4 * 1024 * 1024)

    /** 设备上报的硬解能力，只保留认识的字段 */
    fun sanitizeDecoders(value: JSONObject?): JSONObject? {
        if (value == null) return null
        val result = JSONObject()
        for (codec in listOf("hevc", "h264", "av1")) {
            val d = value.optJSONObject(codec) ?: continue
            fun n(k: String) = d.optDouble(k, 0.0).let { if (it.isFinite() && it >= 0 && it <= 8192) Math.round(it) else 0 }
            result.put(codec, JSONObject().put("maxWidth", n("maxWidth")).put("maxHeight", n("maxHeight")).put("fps1080", n("fps1080")).put("fps2160", n("fps2160")))
        }
        return result.takeIf { it.length() > 0 }
    }

    /** 多来源音频比对：电脑找出只在这个来源里出现的段落（夹带广告）。返回 (区间, 参考来源数) */
    fun compare(server: Server, request: JSONObject, cancel: Cancel?, onProgress: (String) -> Unit): Pair<JSONArray, Int> {
        var status = api(server, "/v1/compare", "POST", request, cancel)
        val id = status.optString("id")
        if (status.optInt("protocol") != 1 || !Regex("^[a-f0-9]{32}$").matches(id)) throw IllegalStateException("电脑服务尚不支持多来源比对，请更新电脑服务")
        try {
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (status.optString("state") == "running") {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("多来源比对超时")
                onProgress(status.str("message")?.takeIf { it.isNotEmpty() } ?: "电脑正在多来源比对")
                pause(2000, cancel)
                status = api(server, "/v1/compare/$id", "GET", cancel = cancel)
            }
        } catch (error: Exception) { if (cancel?.cancelled == true) Pool.ops.execute { runCatching { api(server, "/v1/compare/$id", "DELETE") } }; throw error }
        if (status.optString("state") != "done") throw IllegalStateException(status.str("error")?.takeIf { it.isNotEmpty() } ?: status.str("message")?.takeIf { it.isNotEmpty() } ?: "多来源比对失败")
        val intervals = JSONArray(status.optJSONArray("intervals").objects().filter { val s = it.optDouble("start"); val d = it.optDouble("duration"); s.isFinite() && d.isFinite() && s >= 0 && d > 0 && d <= 600 }.take(100))
        return intervals to status.optInt("references")
    }

    fun downloads(server: Server, cancel: Cancel?): JSONArray = api(server, "/v1/downloads", "GET", cancel = cancel).optJSONArray("items") ?: JSONArray()
    fun addDownload(server: Server, body: JSONObject, cancel: Cancel?): JSONObject = api(server, "/v1/downloads", "POST", body, cancel)
    fun removeDownload(server: Server, id: String) { require(Regex("^[a-f0-9]{32}$").matches(id)) { "无效的下载" }; api(server, "/v1/downloads/$id", "DELETE") }
    /** 结束播放任务；失败无妨，服务端会按超时自行清理 */
    fun closeSession(session: JSONObject?) {
        val id = session?.optString("id").orEmpty()
        if (!Regex("^[a-f0-9]{32}$").matches(id)) return
        Pool.ops.execute { runCatching { api(Server(session!!.optString("server"), session.optString("token")), "/v1/sessions/$id", "DELETE") } }
    }

    internal fun validProcessingInfo(info: JSONObject?): JSONObject? {
        if (info == null) return null
        fun int(k: String) = (info.opt(k) as? Number)?.toDouble()?.takeIf { it == Math.floor(it) }?.toInt()
        val sw = int("sourceWidth"); val sh = int("sourceHeight"); val ow = int("outputWidth"); val oh = int("outputHeight")
        if (listOf(sw, sh, ow, oh).any { it == null || it <= 0 || it > 8192 }) return null
        val sr = info.opt("superResolution") as? Boolean ?: return null
        val srEnabled = info.opt("superResolutionEnabled"); if (srEnabled != null && srEnabled !is Boolean) return null
        if (srEnabled == false && sr) return null
        val adOn = info.opt("adRemovalEnabled") as? Boolean ?: return null; val adOk = info.opt("adRemovalSupported") as? Boolean ?: return null
        val segments = int("removedSegments") ?: return null; val seconds = (info.opt("removedSeconds") as? Number)?.toDouble() ?: return null
        if (segments < 0 || !seconds.isFinite() || seconds < 0) return null
        val model = info.opt("model")
        if (model != null && model != JSONObject.NULL && (model !is String || model.length > 64)) return null
        if (sr && ((model as? String).isNullOrEmpty() || ow!! <= sw!! || oh!! <= sh!!)) return null
        if (segments > 0 && (!adOn || !adOk)) return null
        val overlays = info.opt("overlays")
        if (overlays != null) {
            if (overlays !is JSONArray || overlays.length() > 8) return null
            if ((0 until overlays.length()).any { i -> val r = overlays.optJSONObject(i); r == null || r.optString("kind") !in setOf("logo", "marquee") || listOf("x", "y", "w", "h").any { k -> (r.opt(k) as? Number)?.toDouble()?.let { it == Math.floor(it) && it >= 0 && it <= 8192 } != true } }) return null
            if (overlays.length() > 0 && info.opt("overlayRemovalEnabled") == false) return null
        }
        if (info.has("codec") && info.optString("codec") !in setOf("h264", "hevc")) return null
        return info
    }
    /** 把处理信息整理成三行给界面显示的文字 */
    internal fun presentation(info: JSONObject): JSONObject {
        val model = info.str("model")?.let { if (it == "fsrcnnx") "FSRCNNX" else it }
        val ow = info.optInt("outputWidth"); val oh = info.optInt("outputHeight"); val sr = info.optBoolean("superResolution")
        val output = "$ow×$oh" + if (ow >= 3840) (if (oh >= 2160) "（4K）" else "（4K 宽屏）") else ""
        val video = "原始 ${info.optInt("sourceWidth")}×${info.optInt("sourceHeight")} → 当前 $output · " + (if (sr) "已超分（$model）" else if (info.opt("superResolutionEnabled") == false) "超分已关闭（原始分辨率）" else "未超分（仅转码）") + if (info.optString("codec") == "hevc") " · HEVC" else ""
        val seconds = Math.round(info.optDouble("removedSeconds") * 10) / 10.0
        val ads = if (!info.optBoolean("adRemovalEnabled")) "去广告：已关闭" else if (!info.optBoolean("adRemovalSupported")) "去广告：此片源不支持分片过滤"
            else if (info.optInt("removedSegments") > 0) "已去广告：${info.optInt("removedSegments")} 个分片 / ${jsString(seconds)} 秒（仅分片广告）" else "去广告：未发现可识别广告分片"
        val overlays = if (!info.has("overlayRemovalEnabled")) "" else if (!info.optBoolean("overlayRemovalEnabled")) "去水印：已关闭" else {
            val list = info.optJSONArray("overlays").objects(); val logos = list.count { it.optString("kind") == "logo" }; val bands = list.size - logos
            if (list.isEmpty()) "去水印：未发现固定水印或滚动字幕" else "去水印：已处理 " + listOf(if (logos > 0) "台标/水印 $logos 处" else "", if (bands > 0) "滚动字幕 $bands 条" else "").filter { it.isNotEmpty() }.joinToString("、")
        }
        return JSONObject().put("video", video).put("ads", ads).put("overlays", overlays)
    }

    class Options(val position: Double? = null, val adFilter: Boolean? = null, val adSegments: List<String> = emptyList(), val playlists: JSONArray = JSONArray(), val decoders: JSONObject? = null, val onProgress: (JSONObject) -> Unit = {})
    /**
     * 决定这一集怎么送到播放器：独立直连时就是片源地址；电脑串流时在电脑上建立播放任务，
     * 等它准备好（超分需要预缓冲），返回电脑上的播放地址、广告时间轴和处理说明。
     */
    fun resolveDelivery(url: String, desktop: Boolean, server: Server, superResolution: Boolean, overlayRemoval: Boolean, cancel: Cancel?, options: Options): JSONObject {
        if (!desktop) return JSONObject().put("url", url).put("headers", JSONObject()).put("delivery", "direct")
        cancel?.check()
        fun create(extras: Boolean): JSONObject = api(server, "/v1/sessions", "POST", JSONObject().put("url", url).put("superResolution", superResolution).put("overlayRemoval", overlayRemoval).apply {
            options.decoders?.let { put("decoders", it) }; options.position?.let { put("position", it) }; options.adFilter?.let { put("adFilter", it) }
            if (extras && options.adSegments.isNotEmpty()) put("adSegments", JSONArray(options.adSegments)); if (extras && options.playlists.length() > 0) put("playlists", options.playlists)
        }, cancel, maxBytes = 65536)
        val result = try { create(true) } catch (error: Exception) { if (error.message == "请求过大" && (options.playlists.length() > 0 || options.adSegments.isNotEmpty())) create(false) else throw error }
        val id = result.str("id").orEmpty(); val path = result.str("path").orEmpty(); val processing = result.optString("processing")
        if (result.optInt("protocol") != 1 || !Regex("^[a-f0-9]{32}$").matches(id) || processing !in setOf("relay", "enhance") || !path.startsWith("/v1/sessions/$id/")) throw IllegalStateException("电脑服务返回了无效播放任务")
        val base = address(server.url)
        val session = JSONObject().put("id", id).put("server", base).put("token", server.token.trim())
        val valid = !path.contains('?') && !path.contains('#') && !path.contains("..") && if (processing == "enhance") path == "/v1/sessions/$id/processed/index.m3u8" else Regex("^/v1/sessions/[a-f0-9]{32}/media/[a-f0-9]{32}(?:\\.[a-zA-Z0-9]{1,8})?$").matches(path)
        if (!valid) { closeSession(session); throw IllegalStateException("电脑服务返回的播放地址无效") }
        if (cancel?.cancelled == true) { closeSession(session); throw CancelledException() }
        var status: JSONObject? = null
        if (processing == "enhance") try {
            val deadline = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < deadline) {
                status = api(server, "/v1/sessions/$id", "GET", cancel = cancel)
                options.onProgress(status)
                if (status.optString("state") == "error") throw IllegalStateException(status.str("error")?.takeIf { it.isNotEmpty() } ?: status.str("message")?.takeIf { it.isNotEmpty() } ?: "电脑处理视频失败")
                if (status.optString("state") == "ready") break
                pause(700, cancel)
            }
            if (!superResolution && status?.optJSONObject("processingInfo")?.opt("superResolutionEnabled") != false) throw IllegalStateException("电脑服务尚不支持关闭超分，请更新并重启电脑服务")
            if (status?.optString("state") != "ready") throw IllegalStateException("电脑处理等待超时，请检查电脑处理速度")
            if (status.has("timeMap")) {
                val map = status.optJSONArray("timeMap")
                if (map == null || map.length() > 12000 || map.objects().size != map.length() || map.objects().any { val s = it.optDouble("start"); val d = it.optDouble("duration"); !s.isFinite() || !d.isFinite() || s < 0 || d <= 0 }) throw IllegalStateException("电脑返回的广告时间轴无效")
            }
        } catch (error: Exception) { closeSession(session); throw error }
        val info = validProcessingInfo(status?.optJSONObject("processingInfo"))
        return JSONObject().put("url", base + path).put("headers", JSONObject().put("Authorization", "Bearer ${server.token.trim()}")).put("delivery", "desktop").put("desktopSession", session).put("processing", processing)
            .put("label", status?.str("label") ?: "电脑串流 · 原画转发").apply {
                info?.let { put("processingInfo", it); put("presentation", presentation(it)) }
                (status?.optJSONObject("input")?.opt("height") as? Number)?.let { put("sourceHeight", it) }
                ((status?.opt("position") as? Number)?.toDouble() ?: options.position)?.let { put("position", it) }
                status?.optJSONArray("timeMap")?.let { put("timeMap", it) }
            }
    }
}
