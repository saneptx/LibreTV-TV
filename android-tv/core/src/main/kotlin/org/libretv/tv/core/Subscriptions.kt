package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** 片源订阅：远程的片源列表（本应用的列表格式，或 TVBox 配置），解析成点播源并整体替换上次导入的那批。 */
object Subscriptions {
    private const val MAX_SOURCES = 100
    fun normalizeUrl(raw: String) = raw.trim().trimEnd('/')
    private fun httpUrl(raw: Any?, trimSlash: Boolean): String? {
        val text = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
        if (runCatching { URI(text).host }.getOrNull().isNullOrEmpty() && runCatching { java.net.URL(text).host }.getOrNull().isNullOrEmpty()) return null
        return if (trimSlash) text.trimEnd('/') else text
    }
    private fun text(raw: Any?) = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun host(url: String) = runCatching { java.net.URL(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url

    /** 宽松解析：去掉注释、字符串里的裸换行、对象 / 数组末尾多余的逗号（TVBox 配置里很常见） */
    fun parseJson(input: String): Any {
        val source = input.removePrefix("﻿"); val out = StringBuilder(source.length)
        var inString = false; var line = false; var block = false; var i = 0
        while (i < source.length) {
            val ch = source[i]; val next = source.getOrNull(i + 1)
            when {
                line -> if (ch == '\n' || ch == '\r') { line = false; out.append(ch) }
                block -> if (ch == '*' && next == '/') { block = false; i++ }
                inString -> when {
                    ch.code < 0x20 -> out.append(when (ch) { '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> "\\u%04x".format(ch.code) })
                    ch == '\\' -> { out.append(ch); if (next != null) out.append(next); i++ }
                    else -> { out.append(ch); if (ch == '"') inString = false }
                }
                ch == '"' -> { inString = true; out.append(ch) }
                ch == '/' && next == '/' -> { line = true; i++ }
                ch == '/' && next == '*' -> { block = true; i++ }
                else -> out.append(ch)
            }
            i++
        }
        val cleaned = StringBuilder(out.length); inString = false; i = 0
        while (i < out.length) {
            val ch = out[i]
            if (inString) { cleaned.append(ch); if (ch == '\\') { out.getOrNull(i + 1)?.let { cleaned.append(it) }; i++ } else if (ch == '"') inString = false }
            else if (ch == '"') { inString = true; cleaned.append(ch) }
            else if (ch == ',') { var j = i + 1; while (j < out.length && out[j].isWhitespace()) j++; if (out.getOrNull(j) != '}' && out.getOrNull(j) != ']') cleaned.append(ch) }
            else cleaned.append(ch)
            i++
        }
        return try { JSONTokener(cleaned.toString()).nextValue().also { require(it is JSONObject || it is JSONArray) } } catch (_: Exception) { throw IllegalStateException("订阅内容不是合法的 JSON") }
    }

    class Parsed(val name: String?, val sources: List<JSONObject>, val liveCount: Int, val note: String)
    private val spider = Regex("^csp_|\\.(?:jar|js|py)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    private val cmsApi = Regex("/api\\.php/provide/vod(?![^?#]*/at/xml)(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    fun parse(json: Any): Parsed {
        val root = json as? JSONObject
        if (root != null && (root.optJSONArray("sites") != null || root.optJSONArray("lives") != null)) return parseTvbox(root)
        val raw = (json as? JSONArray ?: root?.optJSONArray("sources")).objects(); val live = root?.optJSONArray("liveSources")?.length() ?: 0
        if (raw.isEmpty() && live == 0) throw IllegalStateException("订阅内容格式不正确（缺少 sources / liveSources 数组）")
        val seen = HashSet<String>(); val sources = ArrayList<JSONObject>()
        for (s in raw) {
            if (sources.size >= MAX_SOURCES) break
            val url = httpUrl(s.opt("url"), true) ?: continue
            if (!seen.add(url)) continue
            sources.add(JSONObject().put("name", text(s.opt("name")) ?: host(url)).put("url", url).apply { text(s.opt("detail"))?.let { put("detail", it) }; if (s.opt("isAdult") == true) put("isAdult", true) })
        }
        return Parsed(root?.let { text(it.opt("name")) }, sources, live, "")
    }
    /** TVBox 配置：只导入能直接搜索的采集接口；需要 Spider 引擎、XML 接口、不支持搜索的站点跳过并说明 */
    private fun parseTvbox(root: JSONObject): Parsed {
        val reasons = LinkedHashMap<String, Int>(); val seen = HashSet<String>(); val sources = ArrayList<JSONObject>(); var truncated = 0
        fun skip(reason: String) { reasons[reason] = (reasons[reason] ?: 0) + 1 }
        fun number(raw: Any?): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        for (site in root.optJSONArray("sites").objects()) {
            val name = text(site.opt("name")); val api = text(site.opt("api")); val type = number(site.opt("type"))?.toInt()
            if (api == null) { skip("地址不可用"); continue }
            if (type == 3 || spider.containsMatchIn(api)) { skip("Spider 引擎"); continue }
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(api)) { skip(if (Regex("^\\.{0,2}/").containsMatchIn(api)) "Spider 引擎" else "地址不可用"); continue }
            if (site.opt("searchable") == 0 || site.opt("searchable") == "0") { skip("不支持搜索"); continue }
            if (!(type == 1 || (type == null || type == 0 || type == 4) && cmsApi.containsMatchIn(api))) { skip(if (type == 4) "Spider 引擎" else "XML 接口"); continue }
            val url = httpUrl(api, true); if (url == null) { skip("地址不可用"); continue }
            if (url in seen) continue
            if (sources.size >= MAX_SOURCES) { truncated++; continue }
            seen.add(url); sources.add(JSONObject().put("name", name ?: host(url)).put("url", url))
        }
        val live = root.optJSONArray("lives")?.length() ?: 0
        val skipped = reasons.values.sum(); val detail = reasons.entries.joinToString("、") { "${it.key} ${it.value}" }
        if (sources.isEmpty()) throw IllegalStateException(if (skipped > 0) "配置中没有可直接导入的源：已跳过 $skipped 个条目（$detail）" else "配置中没有可用的点播站点")
        val note = listOf(if (skipped > 0) "跳过 $skipped 个不可用条目（$detail）" else "", if (truncated > 0) "超出上限截断 $truncated 条" else "").filter { it.isNotEmpty() }.joinToString("，")
        return Parsed(text(root.opt("name")), sources, live, note)
    }

    /** 从一段粘贴的文字里认出来的内容 */
    class Pasted(val sources: List<JSONObject>, val subscriptions: List<String>, val note: String)
    private val urlPattern = Regex("https?://[^\\s\"'<>，。；、）)\\]】]+", RegexOption.IGNORE_CASE)
    /** 采集接口地址的常见样子：能直接搜索，按片源添加；其余网址按订阅地址处理 */
    private fun looksLikeApi(url: String) = cmsApi.containsMatchIn(url) || Regex("/provide/vod|/api\\.php|[?&]ac=", RegexOption.IGNORE_CASE).containsMatchIn(url)
    /**
     * 解析粘贴的文字，支持三种写法混用：
     * - 整段是片源列表或 TVBox 配置的 JSON：取出里面的点播源；
     * - 每行一个采集接口地址，前面可以带名称（「名称 地址」「名称,地址」「名称$地址」）；
     * - 每行一个订阅地址（指向上面那种 JSON 的网址）。
     */
    fun parsePasted(text: String): Pasted {
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) throw IllegalStateException("剪贴板里没有文字")
        if (trimmed.length > 512 * 1024) throw IllegalStateException("内容太长，无法导入")
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = parse(parseJson(trimmed))
            if (parsed.sources.isEmpty()) throw IllegalStateException("这段内容里没有点播源")
            return Pasted(parsed.sources, emptyList(), parsed.note)
        }
        val sources = ArrayList<JSONObject>(); val subscriptions = ArrayList<String>(); val seen = HashSet<String>()
        for (line in trimmed.lines()) {
            val match = urlPattern.find(line) ?: continue
            val url = httpUrl(match.value.trimEnd('.', ',', ';'), true) ?: continue
            if (!seen.add(url)) continue
            if (looksLikeApi(url)) {
                val name = line.substring(0, match.range.first).trim().trim(',', '，', '$', ':', '：', '|', '-', '=', '\t', ' ').take(40)
                if (sources.size < MAX_SOURCES) sources.add(JSONObject().put("name", name.ifEmpty { host(url) }).put("url", url))
            } else if (subscriptions.size < 20) subscriptions.add(url)
        }
        if (sources.isEmpty() && subscriptions.isEmpty()) throw IllegalStateException("没有认出片源接口或订阅地址：每行写一个 http(s) 开头的地址")
        return Pasted(sources, subscriptions, "")
    }
    /** 导入粘贴内容：片源逐个加入（地址已存在的跳过），订阅逐个同步；单个订阅失败不影响其余 */
    fun importPasted(settings: Settings, pasted: Pasted, cancel: Cancel?): JSONObject {
        var added = 0; var skipped = 0; var vod = 0; val ok = ArrayList<String>(); val failed = org.json.JSONArray()
        for (source in pasted.sources) if (settings.addCustomSource(source.optString("name"), source.optString("url"), source.optBoolean("isAdult"), source.str("detail"))) added++ else skipped++
        for (url in pasted.subscriptions) {
            cancel?.check()
            try { vod += sync(settings, url, cancel).optInt("vodCount"); ok.add(url) }
            catch (error: CancelledException) { throw error }
            catch (error: Exception) { failed.put(JSONObject().put("url", url).put("error", error.message ?: "同步失败")) }
        }
        return JSONObject().put("added", added).put("skipped", skipped).put("subscriptions", ok.size).put("subscriptionSources", vod).put("failed", failed)
    }

    /** 同步一个订阅并写入设置；返回导入的点播源数量和说明。失败时记下原因再抛出 */
    fun sync(settings: Settings, rawUrl: String, cancel: Cancel?): JSONObject {
        val url = normalizeUrl(rawUrl)
        try {
            val res = Net.fetch(url, cancel = cancel)
            if (!res.ok) throw IllegalStateException("订阅地址返回 HTTP ${res.status}")
            val parsed = parse(parseJson(res.text()))
            if (parsed.sources.isEmpty()) throw IllegalStateException(if (parsed.liveCount > 0) "这个订阅里只有直播源，没有点播源" else "订阅内容为空")
            val count = settings.applySubscriptionSources(url, parsed.sources)
            settings.addSubscription(url)
            settings.markSubscription(url) { it.put("lastSync", System.currentTimeMillis()).put("lastStatus", "ok").put("lastCounts", JSONObject().put("vod", count).put("live", 0)); it.remove("lastError"); parsed.name?.let { n -> it.put("name", n) } }
            return JSONObject().put("vodCount", count).put("liveCount", 0).put("note", parsed.note).apply { parsed.name?.let { put("name", it) } }
        } catch (error: Exception) {
            if (error !is CancelledException) settings.markSubscription(url) { it.put("lastStatus", "error").put("lastError", error.message ?: "订阅同步失败") }
            throw error
        }
    }
}
