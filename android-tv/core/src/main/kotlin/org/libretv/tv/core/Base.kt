package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 数据层（片源、资料库、同步、去广告、电脑串流）的公共部分：取消、网络、落盘。全部是普通 Kotlin，不依赖界面。 */
class CancelledException : RuntimeException("已取消")

/** 取消信号：界面取消请求、超时、上层取消都走它；正在进行的网络连接登记后可被立即断开 */
class Cancel(private val parent: Cancel? = null) {
    @Volatile private var flag = false
    @Volatile var timedOut = false; private set
    private val hooks = CopyOnWriteArrayList<() -> Unit>()
    private var detach: (() -> Unit)? = null
    private var timer: ScheduledFuture<*>? = null
    init { if (parent != null) { detach = parent.onCancel { cancel() }; if (parent.cancelled) flag = true } }
    val cancelled: Boolean get() = flag
    fun cancel() { if (flag) return; flag = true; hooks.forEach { runCatching(it) }; hooks.clear() }
    /** 返回注销函数 */
    fun onCancel(hook: () -> Unit): () -> Unit { if (flag) { runCatching(hook); return {} }; hooks.add(hook); if (flag) { hooks.remove(hook); runCatching(hook) }; return { hooks.remove(hook) } }
    fun check() { if (flag) throw CancelledException() }
    /** 派生一个带总时限的信号；用完调用 close() */
    fun child(timeoutMs: Long = 0): Cancel = Cancel(this).also { c -> if (timeoutMs > 0) c.timer = Pool.timer.schedule({ c.timedOut = true; c.cancel() }, timeoutMs, TimeUnit.MILLISECONDS) }
    fun close() { timer?.cancel(false); detach?.invoke(); hooks.clear() }
    companion object { fun timeout(ms: Long) = Cancel().also { c -> c.timer = Pool.timer.schedule({ c.timedOut = true; c.cancel() }, ms, TimeUnit.MILLISECONDS) } }
}

object Pool {
    private val count = AtomicInteger()
    /** 界面发来的操作（各自可能阻塞在网络上） */
    val ops: ExecutorService = Executors.newCachedThreadPool { Thread(it, "supertv-op-${count.incrementAndGet()}").apply { isDaemon = true } }
    /** 并发的网络小任务（搜索各片源、读分片开头等） */
    val io: ExecutorService = Executors.newCachedThreadPool { Thread(it, "supertv-io-${count.incrementAndGet()}").apply { isDaemon = true } }
    val timer = Executors.newScheduledThreadPool(1) { Thread(it, "supertv-timer").apply { isDaemon = true } }

    /** 以给定并发度依次处理；任一任务抛出取消时整体取消。保持输入顺序返回结果 */
    fun <T, R> mapConcurrent(items: List<T>, concurrency: Int, cancel: Cancel?, task: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val results = arrayOfNulls<Any?>(items.size); val next = AtomicInteger()
        val workers: List<Future<*>> = (0 until minOf(concurrency, items.size)).map {
            io.submit {
                while (true) {
                    cancel?.check()
                    val index = next.getAndIncrement(); if (index >= items.size) break
                    results[index] = task(items[index])
                }
            }
        }
        var failure: Throwable? = null
        for (worker in workers) try { worker.get() } catch (e: java.util.concurrent.ExecutionException) { if (failure == null) failure = e.cause ?: e } catch (e: InterruptedException) { workers.forEach { it.cancel(true) }; throw CancelledException() }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return results.toList() as List<R>
    }
}

class HttpResponse(val status: Int, val url: String, val headers: Map<String, String>, val body: ByteArray) {
    val ok get() = status in 200..299
    fun header(name: String) = headers[name.lowercase()]
    fun text(): String {
        val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(header("content-type").orEmpty())?.groupValues?.get(1)
        val set = runCatching { java.nio.charset.Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return String(body, set).removePrefix("﻿")
    }
    /** 解析为 JSONObject / JSONArray；不是 JSON 时抛出 */
    fun json(): Any = JSONTokener(text()).nextValue().also { if (it !is JSONObject && it !is JSONArray) throw IllegalStateException("返回的不是 JSON") }
}

object Net {
    const val DEFAULT_UA = "Mozilla/5.0 LibreTV-AndroidTV/0.3"
    private val forwarded = listOf("User-Agent", "Accept", "Referer", "Origin", "Range", "Authorization", "Content-Type")
    private fun http(value: String): URL {
        val url = URL(value)
        require(url.protocol in setOf("http", "https") && url.host.isNotBlank() && url.userInfo == null) { "仅支持 HTTP/HTTPS 地址" }
        return url
    }
    private fun originOf(url: URL) = URI(url.toString()).let { "${it.scheme}://${it.rawAuthority}" }

    /**
     * 有界的 HTTP 请求：最多 6 次跳转；带凭证或非 GET 的请求不跟随跨站跳转；
     * 响应超过 maxBytes 时报错，prefix 为真时改为截断。timeoutMs 是连接 / 读取的单次超时，总时限用 cancel 控制。
     */
    fun fetch(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null,
              timeoutMs: Int = 10000, maxBytes: Int = 4 * 1024 * 1024, prefix: Boolean = false, cancel: Cancel? = null): HttpResponse {
        var target = http(url)
        val limit = maxBytes.coerceIn(1, 8 * 1024 * 1024); val timeout = timeoutMs.coerceIn(1000, 30000)
        require(method in setOf("GET", "POST", "DELETE")) { "不支持的 HTTP 方法" }
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(bytes.size <= 3 * 1024 * 1024 && (bytes.isEmpty() || method == "POST")) { "请求体无效或过大" }
        val first = originOf(target)
        for (hop in 0..5) {
            cancel?.check()
            val connection = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; requestMethod = method; connectTimeout = timeout; readTimeout = timeout; useCaches = false
                setRequestProperty("User-Agent", DEFAULT_UA); setRequestProperty("Accept-Encoding", "identity")
                for (key in forwarded) headers[key]?.takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { setRequestProperty(key, it) }
            }
            val unhook = cancel?.onCancel { connection.disconnect() }
            var reusable = false
            try {
                if (method == "POST" && bytes.isNotEmpty()) { connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size); connection.outputStream.use { it.write(bytes) } }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    target = http(URL(target, connection.getHeaderField("Location") ?: error("缺少跳转地址")).toString())
                    require(method == "GET" && (headers["Authorization"].isNullOrEmpty() || originOf(target) == first)) { "拒绝转发带凭证或非 GET 的重定向请求" }
                    continue
                }
                val output = ByteArrayOutputStream()
                val input = if (status >= 400) connection.errorStream else connection.inputStream
                var complete = input == null
                input?.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        cancel?.check()
                        val count = it.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                        if (count < 0) { complete = true; break }
                        if (output.size() + count > limit) { if (!prefix) error("响应内容过大"); output.write(buffer, 0, limit - output.size()); break }
                        output.write(buffer, 0, count)
                    }
                }
                val out = HashMap<String, String>()
                out["content-type"] = connection.contentType ?: "application/octet-stream"
                connection.getHeaderField("Content-Range")?.let { out["content-range"] = it }
                connection.getHeaderField("Content-Length")?.let { out["content-length"] = it }
                // 读完整的响应不主动断开：连接回到复用池，同一 CDN 的下一个请求省掉握手
                if (complete) reusable = true
                return HttpResponse(status, target.toString(), out, output.toByteArray())
            } catch (error: Exception) {
                if (cancel?.cancelled == true) throw CancelledException()
                if (error is java.net.SocketTimeoutException) throw java.io.IOException("网络请求超时")
                throw error
            } finally { unhook?.invoke(); if (!reusable) connection.disconnect() }
        }
        error("重定向次数过多")
    }
}

/** 一个 JSON 文件：读入内存，修改后延迟、原子地写回（先写临时文件再改名）。 */
class JsonFile(private val file: File, private val delayMs: Long = 400) {
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var source: (() -> String)? = null
    fun read(): JSONObject? = try { if (file.exists()) JSONObject(file.readText()) else null } catch (_: Exception) { null }
    /** 登记一次写入；content 在真正写盘时取值，短时间内多次修改只写一次 */
    @Synchronized fun schedule(content: () -> String) {
        source = content
        if (pending?.isDone == false) return
        pending = Pool.timer.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun flush() {
        val content = source ?: return; source = null; pending?.cancel(false); pending = null
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp"); temp.writeText(content())
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        } catch (_: Exception) { /* 写不进时内存里的数据仍然有效，下次修改再试 */ }
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
/** 仅当值是字符串时返回（JSON 里的数字、null 不算） */
fun JSONObject.str(key: String): String? = (opt(key) as? String)
fun JSONObject.copy() = JSONObject(toString())
/** JS 的 String(value ?? '')：数字不带多余的小数 */
fun jsString(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is Double -> if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    else -> value.toString()
}
