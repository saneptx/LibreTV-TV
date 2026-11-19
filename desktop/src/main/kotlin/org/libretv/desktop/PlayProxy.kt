package org.libretv.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.libretv.tv.PlaylistFilter
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import java.io.BufferedInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 播放用的本机代理（只监听 127.0.0.1）：
 * - 播放器（VLC）不能为每个请求带上自定义请求头（电脑片库要 Authorization，部分片源要 Referer），由代理代为请求；
 * - 去广告：媒体播放列表在这里按数据层的判定删掉广告分片，记下删掉的时间段，用于进度换算。
 */
object PlayProxy {
    class Session(val id: String, val headers: Map<String, String>, val adFilter: Boolean, val flagged: Set<String>, val playlists: Map<String, String>) {
        /** 已从片源收到的字节数（算缓冲时的下载速度） */
        val received = java.util.concurrent.atomic.AtomicLong()
        /** 广告所在的区间（原片时间），按实际交给播放器的那份播放列表算 */
        @Volatile var removals: List<PlaylistFilter.Removal> = emptyList()
        /** 已经取过的播放列表：点播列表不会变，但盗链站每次请求插广告的位置可能不同，同一个地址始终给播放器同一份 */
        val served = ConcurrentHashMap<String, String>()
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64).apply {
            executor = Executors.newCachedThreadPool { Thread(it, "supertv-proxy").apply { isDaemon = true } }
            createContext("/p/") { exchange -> runCatching { handle(exchange) }.onFailure { runCatching { exchange.sendResponseHeaders(502, -1) } }; exchange.close() }
            start()
        }
    }
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun open(headers: Map<String, String>, adFilter: Boolean, flagged: Set<String>, playlists: Map<String, String>): Session {
        val id = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return Session(id, headers, adFilter, flagged, playlists).also { sessions[id] = it }
    }
    fun close(session: Session?) { if (session != null) sessions.remove(session.id) }
    fun url(session: Session, upstream: String): String {
        val name = runCatching { URI(upstream).path.substringAfterLast('/') }.getOrNull().orEmpty().replace(Regex("[^\\w.-]"), "").take(60).ifEmpty { "media" }
        return "http://127.0.0.1:${server.address.port}/p/${session.id}/${b64.encodeToString(upstream.toByteArray())}/$name"
    }

    private fun request(session: Session, url: String, range: String?): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", session.headers["User-Agent"] ?: "Mozilla/5.0 SuperTV-Desktop")
        session.headers.forEach { (k, v) -> if (k in setOf("Referer", "Origin", "Authorization", "Accept", "Cookie")) builder.header(k, v) }
        if (range != null) builder.header("Range", range)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }
    /** 先取一次播放列表，算出去掉的广告区间（播放器开播前就能换算续播位置）。多码率时按码率最高的一档 */
    fun prepare(session: Session, url: String): List<PlaylistFilter.Removal> = runCatching {
        var target = url; var text = playlistText(session, target) ?: return emptyList()
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines().map { it.trim() }; var best = -1L; var pick: String? = null
            lines.forEachIndexed { i, line -> if (line.startsWith("#EXT-X-STREAM-INF")) { val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0; val ref = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }; if (ref != null && bw > best) { best = bw; pick = ref } } }
            target = pick?.let { URI(target).resolve(it).toString() } ?: return emptyList(); text = playlistText(session, target) ?: return emptyList()
        }
        if (session.adFilter) PlaylistFilter.filter(text, session.flagged).removals.also { session.removals = it } else emptyList()
    }.getOrDefault(emptyList())
    private fun playlistText(session: Session, url: String): String? = session.playlists[url] ?: session.served[url] ?: request(session, url, null).let { r ->
        if (r.statusCode() in 200..299) r.body().use { String(it.readNBytes(8 * 1024 * 1024), Charsets.UTF_8) }.also { session.served[url] = it } else { r.body().close(); null }
    }

    private fun handle(exchange: HttpExchange) {
        val parts = exchange.requestURI.rawPath.split('/')   // ["", "p", sid, token, name]
        val session = sessions[parts.getOrNull(2)] ?: run { exchange.sendResponseHeaders(404, -1); return }
        val upstream = String(Base64.getUrlDecoder().decode(parts.getOrNull(3) ?: ""))
        if (!upstream.startsWith("http://") && !upstream.startsWith("https://")) { exchange.sendResponseHeaders(400, -1); return }
        // 设备分析时读到的播放列表原文：盗链站按请求插入广告，必须用同一份才对得上
        (session.playlists[upstream] ?: session.served[upstream])?.let { sendPlaylist(exchange, session, it, upstream); return }
        val response = request(session, upstream, exchange.requestHeaders.getFirst("Range"))
        val input = BufferedInputStream(response.body(), 65536)
        input.mark(16); val head = ByteArray(7); val n = input.readNBytes(head, 0, 7); input.reset()
        if (response.statusCode() in 200..299 && n == 7 && String(head, Charsets.US_ASCII) == "#EXTM3U") {
            val text = input.use { String(it.readNBytes(8 * 1024 * 1024), Charsets.UTF_8) }
            session.served[upstream] = text
            sendPlaylist(exchange, session, text, response.uri().toString()); return
        }
        response.headers().firstValue("content-type").ifPresent { exchange.responseHeaders.add("Content-Type", it) }
        // 有的片源对普通请求也回 206（Content-Range 是整个文件）。播放器没要分段却收到 206 会当成出错停播，
        // 这种情况按 200 转给播放器
        val range = response.headers().firstValue("content-range").orElse(null)
        val whole = range?.let { Regex("""bytes 0-(\d+)/(\d+)""").find(it) }?.let { it.groupValues[1].toLong() + 1 == it.groupValues[2].toLong() } == true
        val status = if (response.statusCode() == 206 && exchange.requestHeaders.getFirst("Range") == null && (range == null || whole)) 200 else response.statusCode()
        if (range != null && status == 206) exchange.responseHeaders.add("Content-Range", range)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        val length = response.headers().firstValueAsLong("content-length").orElse(-1)
        exchange.sendResponseHeaders(status, if (length >= 0) (if (length == 0L) -1 else length) else 0)
        input.use { src -> exchange.responseBody.use { dst ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = src.read(buffer); if (n < 0) break; session.received.addAndGet(n.toLong()); dst.write(buffer, 0, n) }
        } }
    }
    private fun sendPlaylist(exchange: HttpExchange, session: Session, original: String, base: String) {
        var text = original
        if (session.adFilter && !text.contains("#EXT-X-STREAM-INF")) {
            val result = PlaylistFilter.filter(text, session.flagged)
            session.removals = result.removals; text = result.text
            // 插播广告是硬插在正片里的，删掉后前后两段正片的时间戳本来就接得上。
            // VLC 遇到「时间轴断开」标记会按新片段的时间戳重新计时（进度跳回 0、提前「播完」误跳下一集），所以一并去掉
            if (result.removals.isNotEmpty()) text = text.lines().filterNot { it.trim() == "#EXT-X-DISCONTINUITY" }.joinToString("\n")
        }
        val root = URI(base)
        fun proxied(ref: String) = runCatching { url(session, root.resolve(ref.trim()).toString()) }.getOrDefault(ref)
        val out = text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> line.replace(Regex("URI=\"([^\"]+)\"")) { "URI=\"${proxied(it.groupValues[1])}\"" }
                else -> proxied(t)
            }
        }
        val bytes = out.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
    }
}

/** 告诉 vlcj 去哪里找 libVLC：安装后的程序目录里的 vlc，开发时 Mac 上项目里的 desktop/.vlc */
class VlcDirectory : DiscoveryDirectoryProvider {
    override fun priority() = 1000
    override fun supported() = true
    override fun directories(): Array<String> {
        val resources = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        return listOfNotNull(resources?.resolve("vlc"), File("desktop/.vlc/VLC.app/Contents/MacOS/lib"), File(".vlc/VLC.app/Contents/MacOS/lib"), File("../desktop/.vlc/VLC.app/Contents/MacOS/lib"))
            .filter { it.isDirectory }.map { it.absolutePath }.toTypedArray()
    }
}
