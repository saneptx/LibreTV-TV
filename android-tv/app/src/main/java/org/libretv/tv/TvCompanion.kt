package org.libretv.tv

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 手机伴侣服务：电视在局域网里广播自己（_supertv._tcp），手机上的 SuperTV 发现后直接连接，
 * 用来同步观看资料、把手机正在看的影片接力到电视、遥控播放、代替遥控器打字。
 * 首次连接要在电视上用遥控器确认，之后凭电视发给这台手机的口令访问；除打招呼和申请配对外，所有请求都要口令。
 */
internal class TvCompanion(private val activity: Activity, private val handler: Handler) {
    /** 全部在主线程调用 */
    interface Handler {
        fun confirmPairing(name: String, decide: (Boolean) -> Unit)
        fun sync(body: JSONObject, done: (JSONObject?) -> Unit)
        fun state(): JSONObject
        fun cast(args: JSONObject): String?
        fun control(action: String, value: Double): String?
        fun text(value: String, submit: Boolean): String?
        fun search(query: String): String?
    }
    private class Failure(val status: Int, message: String) : Exception(message)
    private class Pairing(val device: String, val name: String, val createdAt: Long) { @Volatile var state = "pending"; @Volatile var token = "" }

    private val prefs = activity.getSharedPreferences("supertv_companion", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val executor = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val pairings = HashMap<String, Pairing>()
    @Volatile private var deniedAt = 0L
    @Volatile private var closed = false
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    val id: String = prefs.getString("id", null) ?: hex(16).also { prefs.edit().putString("id", it).apply() }
    val name: String = (runCatching { Settings.Global.getString(activity.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "电视").take(40)
    var port = 0; private set

    init {
        for (candidate in PORT until PORT + 5) {
            try { server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(candidate)) }; port = candidate; break } catch (_: Exception) { /* 端口被占用，换下一个 */ }
        }
        server?.let { socket ->
            Thread({
                while (!closed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    try { executor.execute { serve(client) } } catch (_: Exception) { runCatching { client.close() } }
                }
            }, "supertv-companion").apply { isDaemon = true }.start()
            runCatching {
                val info = NsdServiceInfo().apply { serviceName = "SuperTV $name"; serviceType = SERVICE_TYPE; port = this@TvCompanion.port; setAttribute("id", id) }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {}
                    override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
                }
                (activity.getSystemService(Context.NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                registration = listener
            }
        }
    }

    /** 这台电视在局域网里的地址，手机搜不到时照着手动填 */
    fun address(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull().orEmpty()
    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun devices() = runCatching { JSONObject(prefs.getString("devices", "{}") ?: "{}") }.getOrDefault(JSONObject())
    /** 已配对的手机：设备标识 → 名称 */
    fun paired(): List<Pair<String, String>> = devices().let { all -> all.keys().asSequence().map { it to (all.optJSONObject(it)?.optString("name") ?: "手机") }.toList() }
    fun forget(device: String) { prefs.edit().putString("devices", devices().apply { remove(device) }.toString()).apply() }
    private fun authorized(header: String?): Boolean {
        val token = header?.removePrefix("Bearer ")?.takeIf { it.length == 64 && header.startsWith("Bearer ") } ?: return false
        val all = devices()
        return all.keys().asSequence().any { MessageDigest.isEqual(all.optJSONObject(it)?.optString("token").orEmpty().toByteArray(), token.toByteArray()) }
    }
    /** 在主线程执行并等待结果；电视忙或界面已销毁时超时 */
    private fun <T> onMain(seconds: Long = 8, block: (done: (T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); var result: T? = null; var failed: Throwable? = null
        activity.runOnUiThread { try { block { result = it; latch.countDown() } } catch (error: Throwable) { failed = error; latch.countDown() } }
        if (!latch.await(seconds, TimeUnit.SECONDS)) throw Failure(504, "电视没有响应")
        failed?.let { throw Failure(500, "电视处理失败") }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun done(error: String?) = if (error == null) JSONObject().put("ok", true) else throw Failure(409, error)

    private fun route(method: String, path: String, auth: String?, body: JSONObject): JSONObject {
        if (method == "GET" && path == "/v1/hello") return JSONObject().put("service", "SuperTV").put("protocol", 1).put("id", id).put("name", name)
        if (method == "POST" && path == "/v1/pair") {
            val device = body.optString("id"); val label = body.optString("name").filter { !it.isISOControl() }.trim().take(40).ifEmpty { "手机" }
            if (!Regex("[a-f0-9]{32}").matches(device)) throw Failure(400, "配对请求无效")
            val now = System.currentTimeMillis()
            val request = synchronized(pairings) {
                pairings.values.removeAll { now - it.createdAt > 90_000 }
                if (now - deniedAt < 10_000) throw Failure(429, "刚刚在电视上拒绝过，请稍后再试")
                if (pairings.values.any { it.state == "pending" }) throw Failure(429, "电视正在等待确认另一个连接")
                hex(16).also { pairings[it] = Pairing(device, label, now) }
            }
            val pairing = synchronized(pairings) { pairings[request] }!!
            activity.runOnUiThread {
                handler.confirmPairing(label) { allowed ->
                    if (allowed) {
                        val token = hex(32)
                        prefs.edit().putString("devices", devices().put(device, JSONObject().put("name", label).put("token", token).put("at", System.currentTimeMillis())).toString()).apply()
                        pairing.token = token; pairing.state = "approved"
                    } else { pairing.state = "denied"; deniedAt = System.currentTimeMillis() }
                }
            }
            return JSONObject().put("request", request)
        }
        Regex("/v1/pair/([a-f0-9]{32})").matchEntire(path)?.takeIf { method == "GET" }?.let { match ->
            val pairing = synchronized(pairings) { pairings[match.groupValues[1]] } ?: throw Failure(404, "配对请求已过期")
            val reply = JSONObject().put("state", pairing.state)
            // 口令只交付一次
            if (pairing.state == "approved") { reply.put("token", pairing.token).put("id", id).put("name", name); synchronized(pairings) { pairings.remove(match.groupValues[1]) } }
            return reply
        }
        if (!authorized(auth)) throw Failure(401, "这台手机还没有在电视上确认连接")
        return when {
            method == "GET" && path == "/v1/state" -> onMain { it(handler.state()) }
            method == "POST" && path == "/v1/sync" -> onMain<JSONObject?>(15) { handler.sync(body, it) } ?: throw Failure(502, "电视同步失败")
            method == "POST" && path == "/v1/cast" -> done(onMain { it(handler.cast(body)) })
            method == "POST" && path == "/v1/control" -> done(onMain { it(handler.control(body.optString("action"), body.optDouble("value", 0.0).takeIf { v -> v.isFinite() } ?: 0.0)) })
            method == "POST" && path == "/v1/text" -> done(onMain { it(handler.text(body.optString("text"), body.optBoolean("submit"))) })
            method == "POST" && path == "/v1/search" -> done(onMain { it(handler.search(body.optString("query"))) })
            else -> throw Failure(404, "不支持的请求")
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            var status = 200
            val reply = try {
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0 || head.length > 8192) throw Failure(400, "请求无效")
                    head.append(byte.toChar())
                }
                val lines = head.toString().trimEnd().split("\r\n")
                val start = lines[0].split(" ")
                if (start.size < 2) throw Failure(400, "请求无效")
                val headers = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
                // 只接受应用自己的请求：浏览器网页发起的跨站请求一律带 Origin
                if (headers.containsKey("origin")) throw Failure(403, "不支持的来源")
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length < 0 || length > MAX_BODY) throw Failure(413, "请求过大")
                val bytes = ByteArray(length); var read = 0
                while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) throw Failure(400, "请求不完整"); read += n }
                val body = if (length == 0) JSONObject() else try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw Failure(400, "请求格式无效") }
                route(start[0], start[1].substringBefore('?'), headers["authorization"], body)
            } catch (error: Failure) { status = error.status; JSONObject().put("error", error.message) }
            catch (_: Exception) { status = 500; JSONObject().put("error", "电视处理失败") }
            runCatching {
                val bytes = reply.toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        }
    }

    fun release() {
        closed = true
        runCatching { registration?.let { (activity.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(it) } }
        runCatching { server?.close() }
        executor.shutdownNow()
    }

    companion object {
        const val PORT = 8091
        const val SERVICE_TYPE = "_supertv._tcp"
        private const val MAX_BODY = 3 * 1024 * 1024
    }
}
