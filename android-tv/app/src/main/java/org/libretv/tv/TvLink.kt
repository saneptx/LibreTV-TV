package org.libretv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 手机端与电视（TvCompanion）的连接：打招呼、配对、带口令的调用。
 * 已配对的电视和口令存在应用自己的偏好设置里；口令只发给配对时的那台电视。
 */
internal class TvLink(context: Context) {
    class Tv(val id: String, val name: String, var host: String, var port: Int, val token: String) {
        val url get() = "http://$host:$port"
    }
    class Reply(val status: Int, val body: JSONObject?, val error: String?) { val ok get() = error == null }

    val discovery = TvDiscovery(context)
    private val prefs = context.getSharedPreferences("supertv_tv_link", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    @Volatile private var closed = false
    private val tvs = mutableListOf<Tv>()
    private var selectedId = ""
    var onChange: (() -> Unit)? = null
    private val device: String = prefs.getString("device", null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("device", it).commit() }

    init {
        runCatching {
            val root = JSONObject(prefs.getString("native", "{}") ?: "{}")
            root.optJSONArray("tvs").objects().forEach {
                val host = it.optString("host"); val port = it.optInt("port"); val token = it.optString("token")
                if (validHost(host) && port in 1..65535 && Regex("[0-9a-f]{32,128}").matches(token)) tvs.add(Tv(it.optString("id"), it.optString("name").ifBlank { "电视" }, host, port, token))
            }
            selectedId = root.optString("selected")
        }
    }
    private fun save() {
        prefs.edit().putString("native", JSONObject().put("selected", selectedId).put("tvs", JSONArray().apply {
            tvs.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("token", it.token)) }
        }).toString()).commit()
        onChange?.invoke()
    }
    fun paired(): List<Tv> = tvs.toList()
    fun selected(): Tv? = tvs.firstOrNull { it.id == selectedId }
    fun select(id: String) { selectedId = id; save() }
    fun forget(id: String) { tvs.removeAll { it.id == id }; if (selectedId == id) selectedId = ""; save() }

    /** 局域网里找到的电视；已配对的电视换了地址时顺手更新 */
    fun found(): List<JSONObject> {
        val list = runCatching { JSONArray(discovery.list()).objects() }.getOrDefault(emptyList())
        var moved = false
        for (entry in list) tvs.firstOrNull { it.id.isNotEmpty() && it.id == entry.optString("id") }?.let {
            val host = entry.optString("host"); val port = entry.optInt("port")
            if (validHost(host) && port in 1..65535 && (it.host != host || it.port != port)) { it.host = host; it.port = port; moved = true }
        }
        if (moved) save()
        return list
    }

    private fun request(base: String, path: String, token: String?, body: JSONObject?, timeoutMs: Int): Reply {
        val connection = try { URL(base + path).openConnection() as HttpURLConnection } catch (_: Exception) { return Reply(0, null, "电视地址无效") }
        return try {
            connection.connectTimeout = minOf(timeoutMs, 5000); connection.readTimeout = timeoutMs; connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) { val n = stream.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > 4 * 1024 * 1024) throw IllegalStateException() }
                out.toString("UTF-8")
            }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status in 200..299 && json != null) Reply(status, json, null) else Reply(status, json, json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "电视返回 $status")
        } catch (_: Exception) { Reply(0, null, "连不上电视：确认电视开着、SuperTV 在前台，并且和手机在同一个 Wi-Fi") }
        finally { connection.disconnect() }
    }
    private fun deliver(reply: Reply, done: (Reply) -> Unit) { main.post { if (!closed) done(reply) } }

    /** 带口令调用已配对的电视；电视上移除了这台手机时自动忘掉它 */
    fun call(tv: Tv, path: String, body: JSONObject? = null, timeoutMs: Int = 8000, done: (Reply) -> Unit = {}) {
        if (closed) return
        executor.execute {
            val reply = request(tv.url, path, tv.token, body, timeoutMs)
            main.post {
                if (closed) return@post
                if (reply.status == 401) { forget(tv.id); done(Reply(401, null, "电视上已移除这台手机，请重新连接")) } else done(reply)
            }
        }
    }

    /** 请求连接：电视上会弹出确认；onWaiting 在请求发出后调用，结果（成功为 null）交给 done */
    fun pair(host: String, port: Int, onWaiting: () -> Unit, done: (error: String?) -> Unit) {
        if (!validHost(host) || port !in 1..65535) { done("电视地址无效"); return }
        val base = "http://$host:$port"
        executor.execute {
            val hello = request(base, "/v1/hello", null, null, 5000)
            if (!hello.ok || hello.body?.optString("service") != "SuperTV") { deliver(hello) { done(if (hello.ok) "这个地址上不是 SuperTV" else hello.error) }; return@execute }
            val asked = request(base, "/v1/pair", null, JSONObject().put("id", device).put("name", discovery.deviceName()), 5000)
            val id = asked.body?.optString("request").orEmpty()
            if (!asked.ok || !Regex("[a-f0-9]{32}").matches(id)) { deliver(asked) { done(asked.error ?: "电视没有接受连接请求") }; return@execute }
            main.post { if (!closed) onWaiting() }
            val deadline = System.currentTimeMillis() + 70_000
            while (!closed && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@execute }
                val state = request(base, "/v1/pair/$id", null, null, 5000)
                if (state.status == 404) { deliver(state) { done("连接请求已过期，请重试") }; return@execute }
                when (state.body?.optString("state")) {
                    "approved" -> {
                        val body = state.body; val token = body.optString("token")
                        if (!Regex("[0-9a-f]{32,128}").matches(token)) { deliver(state) { done("电视返回的数据无效") }; return@execute }
                        val tv = Tv(body.optString("id").ifEmpty { hello.body.optString("id") }, body.optString("name").ifBlank { hello.body.optString("name").ifBlank { "电视" } }, host, port, token)
                        main.post { if (!closed) { tvs.removeAll { it.id == tv.id }; tvs.add(0, tv); selectedId = tv.id; save(); done(null) } }
                        return@execute
                    }
                    "denied" -> { deliver(state) { done("电视上选择了拒绝") }; return@execute }
                }
            }
            main.post { if (!closed) done("电视上没有确认，请重试") }
        }
    }
    fun close() { closed = true; onChange = null; discovery.stop(); executor.shutdownNow() }

    companion object {
        /** 只连接局域网里的 IPv4 地址 */
        fun validHost(host: String): Boolean {
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31
        }
        /** 「192.168.1.77」或「192.168.1.77:8091」 */
        fun parseAddress(text: String): Pair<String, Int>? {
            val value = text.trim().removePrefix("http://").trimEnd('/')
            val host = value.substringBefore(':'); val port = if (':' in value) value.substringAfter(':').toIntOrNull() ?: return null else TvCompanion.PORT
            return if (validHost(host) && port in 1..65535) host to port else null
        }
    }
}
