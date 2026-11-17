package org.libretv.tv

import android.app.Activity
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** APK 内置页面 + 有界的原生 HTTP 桥。虚拟 origin 沿用旧安装，保留 WebView 历史和设置。
 * 没有本地监听端口；该 origin 的所有资源请求都在这里处理，绝不回落到旧电脑服务。
 */
internal class StandaloneHost(private val activity: Activity, private val web: WebView, private val origin: String, private val phone: Boolean = false) {
    private class Job {
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var future: Future<*>? = null
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; connection?.disconnect(); future?.cancel(true) }
    }
    // 8 个线程：广告分析按段组并发读取分片开头的时间戳
    private val executor = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128))
    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var disposed = false

    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "LibreTvNativeHttp", setOf(origin)) { _, message, source, mainFrame, reply ->
                if (!mainFrame || !sameOrigin(source) || disposed) return@addWebMessageListener
                val request = try { JSONObject(message.data.orEmpty()) } catch (_: Exception) { return@addWebMessageListener }
                val id = request.optString("id")
                if (id.isEmpty() || id.length > 100) return@addWebMessageListener
                if (request.optString("type") == "cancel") { jobs.remove(id)?.cancel(); return@addWebMessageListener }
                val job = Job()
                if (jobs.putIfAbsent(id, job) != null) return@addWebMessageListener
                fun respond(result: JSONObject) {
                    jobs.remove(id, job)
                    if (!disposed && !job.cancelled) activity.runOnUiThread {
                        if (!disposed && !job.cancelled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply.postMessage(result.put("id", id).toString())
                    }
                }
                try {
                    job.future = executor.submit {
                        try {
                            val result = when (request.optString("type")) {
                                "fetch" -> fetch(request, job)
                                "decrypt" -> decrypt(request)
                                else -> throw IllegalArgumentException("未知的本地请求")
                            }
                            respond(result)
                        } catch (error: Exception) {
                            respond(JSONObject().put("error", if (error is java.net.SocketTimeoutException) "网络请求超时" else error.message ?: "网络请求失败"))
                        } finally { job.connection?.disconnect() }
                    }
                    if (job.cancelled) job.future?.cancel(true)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    respond(JSONObject().put("error", "请求过多，请稍后重试"))
                }
            }
        } else { throw IllegalStateException("请更新 Android System WebView") }
    }

    fun sameOrigin(uri: Uri): Boolean = Uri.parse(origin).let { uri.scheme == it.scheme && uri.host == it.host && uri.port == it.port }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!sameOrigin(request.url)) return null
        val path = request.url.path.orEmpty()
        if (request.method != "GET") return response(405, "Method Not Allowed", "")
        if (path == "/api/proxy") {
            // 封面走设备原生网络，避免旧 WebView 的跨域和混合内容限制。
            return try {
                val target = request.url.getQueryParameter("url").orEmpty()
                // 豆瓣图床校验来路
                val headers = JSONObject().apply { if (Uri.parse(target).host.orEmpty().endsWith("doubanio.com")) put("Referer", "https://movie.douban.com/") }
                val data = fetch(JSONObject().put("url", target).put("headers", headers).put("maxBytes", 4 * 1024 * 1024), Job())
                val status = data.getInt("status")
                WebResourceResponse(data.getJSONObject("headers").optString("Content-Type", "image/jpeg").substringBefore(';'), null,
                    status, if (status in 200..299) "OK" else "Upstream Error", mapOf("Cache-Control" to "max-age=3600"),
                    ByteArrayInputStream(Base64.decode(data.getString("body"), Base64.DEFAULT)))
            } catch (_: Exception) { response(502, "Bad Gateway", "") }
        }
        if (path.startsWith("/api/")) return response(404, "Not Found", "本地接口不可用")
        val name = when (path) {
            "", "/" -> if (phone) "index.html" else "tv.html"
            "/tv", "/tv/" -> "tv.html"
            "/watch", "/watch/" -> "watch.html"
            "/native-tv", "/native-tv/" -> "native-tv.html"
            // 手机界面的其余页面（/library 等）：静态导出的同名 html
            else -> path.removePrefix("/").trimEnd('/').let { if (phone && !it.substringAfterLast('/').contains('.')) "$it.html" else it }
        }
        if (name.split('/').any { it == ".." || it == "." } || name.contains('\\')) return response(403, "Forbidden", "")
        return try {
            val mime = when {
                name.endsWith(".js") -> "application/javascript"
                name.endsWith(".css") -> "text/css"
                name.endsWith(".html") -> "text/html"
                name.endsWith(".txt") -> "text/x-component"
                name.endsWith(".json") -> "application/json"
                name.endsWith(".svg") -> "image/svg+xml"
                else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
            }
            WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), activity.assets.open("web/$name"))
        } catch (_: Exception) { response(404, "Not Found", "页面资源不存在，请重新安装完整 APK") }
    }

    private fun response(status: Int, reason: String, text: String) = WebResourceResponse("text/plain", "UTF-8", status, reason,
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(text.toByteArray()))

    private fun http(value: String): URL {
        val url = URL(value)
        require(url.protocol in setOf("http", "https") && url.host.isNotBlank() && url.userInfo == null) { "仅支持 HTTP/HTTPS 地址" }
        require(!sameOrigin(Uri.parse(value))) { "独立模式不请求旧电脑服务器" }
        return url
    }

    private fun fetch(request: JSONObject, job: Job): JSONObject {
        var target = http(request.getString("url"))
        val limit = request.optInt("maxBytes", 4 * 1024 * 1024).coerceIn(1, 4 * 1024 * 1024)
        val timeout = request.optInt("timeoutMs", 10000).coerceIn(1000, 20000)
        val method = request.optString("method", "GET")
        require(method in setOf("GET", "POST", "DELETE")) { "不支持的 HTTP 方法" }
        val body = request.optString("body").toByteArray(Charsets.UTF_8)
        // 电脑串流会附带设备分析过的媒体列表原文
        require(body.size <= 2 * 1024 * 1024 && (body.isEmpty() || method == "POST")) { "请求体无效或过大" }
        val originalOrigin = java.net.URI(target.toString()).let { "${it.scheme}://${it.rawAuthority}" }
        for (hop in 0..5) {
            check(!job.cancelled && !Thread.currentThread().isInterrupted) { "请求已取消" }
            val connection = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = timeout; readTimeout = timeout
                setRequestProperty("User-Agent", "Mozilla/5.0 LibreTV-AndroidTV/0.3")
                setRequestProperty("Accept-Encoding", "identity")
                val headers = request.optJSONObject("headers")
                for (key in listOf("User-Agent", "Accept", "Referer", "Origin", "Range", "Authorization", "Content-Type")) {
                    headers?.optString(key)?.takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { setRequestProperty(key, it) }
                }
            }
            job.connection = connection
            var reusable = false
            try {
                if (method == "POST" && body.isNotEmpty()) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    target = http(URL(target, connection.getHeaderField("Location") ?: error("缺少跳转地址")).toString())
                    val nextOrigin = java.net.URI(target.toString()).let { "${it.scheme}://${it.rawAuthority}" }
                    require(method == "GET" && (request.optJSONObject("headers")?.optString("Authorization").isNullOrEmpty() || nextOrigin == originalOrigin)) { "拒绝转发带凭证或非 GET 的重定向请求" }
                    continue
                }
                val output = ByteArrayOutputStream()
                val input = if (status >= 400) connection.errorStream else connection.inputStream
                var complete = input == null
                input?.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(!job.cancelled && !Thread.currentThread().isInterrupted) { "请求已取消" }
                        val count = it.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                        if (count < 0) { complete = true; break }
                        if (output.size() + count > limit) {
                            if (!request.optBoolean("prefix")) error("响应内容过大")
                            output.write(buffer, 0, limit - output.size()); break
                        }
                        output.write(buffer, 0, count)
                    }
                }
                val headers = JSONObject().put("Content-Type", connection.contentType ?: "application/octet-stream")
                // 码率探测用 Range bytes=0-0 读分片总大小
                connection.getHeaderField("Content-Range")?.let { headers.put("Content-Range", it) }
                connection.getHeaderField("Content-Length")?.let { headers.put("Content-Length", it) }
                // 响应体完整读完时不断开：连接回到复用池，同一 CDN 的下一个请求省掉 TLS 握手
                // （投影仪上一次握手约 1 秒，广告分析要连续读几十个分片开头）
                if (complete) reusable = true
                return JSONObject().put("status", status).put("url", target.toString()).put("headers", headers)
                    .put("body", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            } finally { if (!reusable) connection.disconnect(); job.connection = null }
        }
        error("重定向次数过多")
    }

    private fun decrypt(request: JSONObject): JSONObject {
        val input = Base64.decode(request.getString("body"), Base64.DEFAULT)
        require(input.size <= 96 * 1024)
        val key = Base64.decode(request.getString("key"), Base64.DEFAULT)
        val iv = Base64.decode(request.getString("iv"), Base64.DEFAULT)
        require(key.size >= 16 && iv.size >= 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv.copyOf(16)))
        return JSONObject().put("body", Base64.encodeToString(cipher.doFinal(input.copyOf(input.size / 16 * 16)), Base64.NO_WRAP))
    }

    fun cancelRequests() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    fun release() {
        disposed = true; cancelRequests(); executor.shutdownNow()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "LibreTvNativeHttp")
    }
}
