package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 画质记录的有效期与合并规则 */
object Quality {
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    const val FAIL_TTL_MS = 2L * 60 * 1000
    const val MAX_ENTRIES = 400
    fun key(sourceKey: String, vodId: String) = "$sourceKey:$vodId"
    fun fresh(entry: JSONObject, now: Long = System.currentTimeMillis()) = now - entry.optLong("timestamp") < (if (entry.str("error").isNullOrEmpty()) TTL_MS else FAIL_TTL_MS)
    private val tiers = listOf(2160 to "4K", 1440 to "2K", 1080 to "1080P", 720 to "720P", 540 to "540P", 480 to "480P", 360 to "360P")
    fun label(height: Int?): String? {
        if (height == null || height <= 0) return null
        var best: Pair<Int, String>? = null
        for (tier in tiers) {
            if (Math.abs(tier.first - height) > tier.first * 0.15) continue
            if (best == null || Math.abs(tier.first - height) < Math.abs(best.first - height)) best = tier
        }
        return best?.second ?: "${height}P"
    }
    fun text(height: Int?, bitrate: Int?): String = listOfNotNull(label(height), bitrate?.takeIf { it > 0 }?.let { "%.1fM".format(java.util.Locale.US, it / 1000.0) }).joinToString(" · ")
    /** 播放实测优先于探测；探测到过高度的不被"未知"覆盖；超出上限时丢最旧的 */
    fun merge(current: JSONObject, incoming: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val next = LinkedHashMap<String, JSONObject>()
        for (key in current.keys()) current.optJSONObject(key)?.let { if (fresh(it, now)) next[key] = it }
        for (key in incoming.keys()) {
            val value = incoming.optJSONObject(key) ?: continue; val prev = next[key]
            if (prev?.optString("from") == "play") {
                if (value.optString("from") != "play") continue
                val best = maxOf(prev.optInt("height"), value.optInt("height"))
                next[key] = JSONObject().put("from", "play").put("timestamp", now).apply { if (best > 0) put("height", best) }
                continue
            }
            if (prev?.optString("from") == "probe" && value.optString("from") == "probe" && prev.has("height") && !value.has("height")) continue
            next[key] = value
        }
        val overflow = next.size - MAX_ENTRIES
        if (overflow > 0) next.entries.sortedBy { it.value.optLong("timestamp") }.take(overflow).map { it.key }.forEach { next.remove(it) }
        return JSONObject().apply { next.forEach { (k, v) -> put(k, v) } }
    }
}

/**
 * 设置与片源（原网页版的全局设置）。内存里是一份 JSON，字段名与旧版一致，旧数据可以原样导入。
 * 内置片源（APK 自带的 source.json）不落盘，启动时注入。
 */
class Settings(file: File, bundled: JSONArray) {
    private val store = JsonFile(file, 800)
    private var state: JSONObject = defaults()
    /** APK 内置片源：key 为 env_<序号> */
    val envSources: List<JSONObject> = bundled.objects().mapIndexed { i, s -> JSONObject().put("key", "env_$i").put("name", s.optString("name")).put("url", s.optString("url").trim().trimEnd('/')).apply { if (s.optBoolean("isAdult")) put("isAdult", true); s.str("detail")?.let { put("detail", it) } } }

    init { store.read()?.let { load(it) }; applyEnvSources() }

    private fun defaults() = JSONObject().put("hiddenSources", JSONArray()).put("customAPIs", JSONArray()).put("selectedKeys", JSONArray()).put("envKeysSeen", JSONArray()).put("subscriptions", JSONArray())
        .put("sourceHealth", JSONObject()).put("qualityMap", JSONObject()).put("yellowFilter", true).put("adFilter", true).put("superResolution", true).put("overlayRemoval", true)
        .put("playbackMode", "direct").put("desktopServerUrl", "").put("desktopPairingToken", "").put("autoplayNext", true)
    /** 载入已保存或迁移来的设置；缺的字段用默认值，不认识的字段原样保留 */
    @Synchronized fun load(saved: JSONObject) {
        val next = defaults()
        for (key in saved.keys()) next.put(key, saved.get(key))
        for (key in listOf("customAPIs", "selectedKeys", "envKeysSeen", "subscriptions", "hiddenSources")) if (next.optJSONArray(key) == null) next.put(key, JSONArray())
        for (key in listOf("sourceHealth", "qualityMap")) if (next.optJSONObject(key) == null) next.put(key, JSONObject())
        state = next
    }
    @Synchronized fun importSaved(saved: JSONObject) { load(saved); applyEnvSources(); persist() }
    private fun persist() { store.schedule { synchronized(this) { state.toString() } } }
    fun flush() = store.flush()

    // ---- 读取 ----
    @Synchronized fun bool(key: String, fallback: Boolean) = state.optBoolean(key, fallback)
    @Synchronized fun string(key: String) = state.optString(key)
    val yellowFilter get() = bool("yellowFilter", true)
    val adFilter get() = bool("adFilter", true)
    @Synchronized fun customSources(): List<JSONObject> = state.optJSONArray("customAPIs").objects()
    @Synchronized fun selectedKeys(): List<String> = state.optJSONArray("selectedKeys").strings()
    @Synchronized fun subscriptions(): List<JSONObject> = state.optJSONArray("subscriptions").objects()
    @Synchronized fun quality(key: String): JSONObject? = state.optJSONObject("qualityMap")?.optJSONObject(key)
    /** 用户删掉的片源地址：内置片源和订阅带来的片源无法真正移除，记在这里不再显示、不再参与搜索 */
    @Synchronized fun hiddenUrls(): Set<String> = state.optJSONArray("hiddenSources").strings().toSet()
    /** 界面上显示、可参与搜索的片源（不含已删除的） */
    fun allSources(): List<JSONObject> { val hidden = hiddenUrls(); return (envSources + customSources()).filter { it.optString("url").trimEnd('/') !in hidden } }

    fun resolveSource(key: String, fallbackUrl: String? = null, fallbackName: String? = null): JSONObject? =
        customSources().firstOrNull { it.optString("key") == key } ?: envSources.firstOrNull { it.optString("key") == key }
            ?: fallbackUrl?.takeIf { it.isNotEmpty() }?.let { JSONObject().put("key", key).put("name", fallbackName?.takeIf { n -> n.isNotEmpty() } ?: "自定义源").put("url", it) }

    /** 参与搜索的片源：已勾选、地址不重复、未被成人内容过滤挡掉 */
    fun activeSources(): List<JSONObject> {
        val seen = HashSet<String>(); val filter = yellowFilter
        val hidden = hiddenUrls()
        return selectedKeys().mapNotNull { resolveSource(it) }.filter { source -> source.optString("url").trimEnd('/') !in hidden && !(filter && source.optBoolean("isAdult")) && seen.add(source.optString("url")) }
    }

    // ---- 修改 ----
    private fun put(key: String, value: Any) { state.put(key, value); persist() }
    private fun applyEnvSources() = synchronized(this) {
        val seen = state.optJSONArray("envKeysSeen").strings().toMutableList()
        val fresh = envSources.map { it.optString("key") }.filter { it !in seen }
        if (fresh.isEmpty()) return@synchronized
        val filter = state.optBoolean("yellowFilter", true)
        val select = fresh.filter { key -> envSources.first { it.optString("key") == key }.let { !it.optBoolean("isAdult") || !filter } }
        state.put("envKeysSeen", JSONArray(seen + fresh)); state.put("selectedKeys", JSONArray(state.optJSONArray("selectedKeys").strings() + select)); persist()
    }
    @Synchronized fun toggleSource(key: String) {
        val current = selectedKeys()
        if (key in current) { put("selectedKeys", JSONArray(current - key)); return }
        val source = allSources().firstOrNull { it.optString("key") == key }
        if (source?.optBoolean("isAdult") == true && state.optBoolean("yellowFilter", true)) return
        put("selectedKeys", JSONArray(current + key))
    }
    /** 添加自定义片源；同一地址（内置或已添加）已存在时不重复添加，返回是否真的加了 */
    @Synchronized fun addCustomSource(name: String, url: String, isAdult: Boolean = false, detail: String? = null): Boolean {
        val bare = url.trimEnd('/')
        if (allSources().any { it.optString("url").trimEnd('/') == bare }) return false
        // 之前删掉的内置 / 订阅片源：重新添加等于恢复
        (envSources + customSources()).firstOrNull { it.optString("url").trimEnd('/') == bare }?.let { existing ->
            state.put("hiddenSources", JSONArray(hiddenUrls() - bare))
            if (existing.optString("key") !in selectedKeys()) state.put("selectedKeys", JSONArray(selectedKeys() + existing.optString("key")))
            persist(); return true
        }
        val list = customSources(); val used = list.map { it.optString("key") }.toSet()
        var i = 0; while ("custom_$i" in used) i++
        val entry = JSONObject().put("key", "custom_$i").put("name", name).put("url", bare).apply { if (isAdult) put("isAdult", true); detail?.takeIf { it.isNotEmpty() }?.let { put("detail", it) } }
        put("customAPIs", JSONArray(list + entry))
        if (!(isAdult && state.optBoolean("yellowFilter", true))) put("selectedKeys", JSONArray(selectedKeys() + "custom_$i"))
        return true
    }
    /** 删除一个片源。手动添加的直接移除；内置的和订阅带来的记为已删除（订阅再次同步也不会回来）。返回是否有变化 */
    @Synchronized fun removeSource(key: String): Boolean {
        val source = allSources().firstOrNull { it.optString("key") == key } ?: return false
        val custom = customSources(); val owned = custom.any { it.optString("key") == key }
        val fromSubscription = owned && subscriptions().any { belongs(key, subKeyPrefix(it.optString("url"))) }
        if (owned) state.put("customAPIs", JSONArray(custom.filter { it.optString("key") != key }))
        if (!owned || fromSubscription) state.put("hiddenSources", JSONArray(hiddenUrls() + source.optString("url").trimEnd('/')))
        state.put("selectedKeys", JSONArray(selectedKeys() - key)); persist()
        return true
    }
    /** 恢复所有删掉的内置片源（并重新启用）；订阅带来的要等该订阅下次同步 */
    @Synchronized fun restoreHiddenSources(): Int {
        val hidden = hiddenUrls(); if (hidden.isEmpty()) return 0
        val back = envSources.filter { it.optString("url").trimEnd('/') in hidden }
        val filter = state.optBoolean("yellowFilter", true)
        state.put("hiddenSources", JSONArray())
        state.put("selectedKeys", JSONArray((selectedKeys() + back.filter { !it.optBoolean("isAdult") || !filter }.map { it.optString("key") }).distinct())); persist()
        return back.size
    }
    /** 删除一个订阅及它带来的全部片源 */
    @Synchronized fun removeSubscription(url: String): Boolean {
        if (subscriptions().none { it.optString("url") == url }) return false
        val prefix = subKeyPrefix(url)
        state.put("subscriptions", JSONArray(subscriptions().filter { it.optString("url") != url }))
        state.put("customAPIs", JSONArray(customSources().filter { !belongs(it.optString("key"), prefix) }))
        state.put("selectedKeys", JSONArray(selectedKeys().filter { !belongs(it, prefix) })); persist()
        return true
    }
    @Synchronized fun setQuality(entries: JSONObject) { put("qualityMap", Quality.merge(state.optJSONObject("qualityMap") ?: JSONObject(), entries)) }
    /** 只接受已知的设置项；打开成人内容过滤时顺带取消勾选成人片源 */
    @Synchronized fun update(patch: JSONObject) {
        for (key in patch.keys()) state.put(key, patch.get(key))
        if (patch.optBoolean("yellowFilter")) {
            val adult = allSources().filter { it.optBoolean("isAdult") }.map { it.optString("key") }.toSet()
            state.put("selectedKeys", JSONArray(selectedKeys().filter { it !in adult }))
        }
        persist()
    }

    // ---- 片源健康度：连续失败的片源记录下来（仅记录，是否停用由界面决定） ----
    @Synchronized fun recordSourceHealth(outcomes: List<JSONObject>) {
        if (outcomes.isEmpty()) return
        val now = System.currentTimeMillis(); val old = state.optJSONObject("sourceHealth") ?: JSONObject(); val health = JSONObject()
        for (key in old.keys()) old.optJSONObject(key)?.let { if (now - it.optLong("timestamp") < 7L * 24 * 3600 * 1000) health.put(key, it) }
        val ladder = longArrayOf(30L * 60 * 1000, 24L * 3600 * 1000)
        for (o in outcomes) {
            val prev = health.optJSONObject(o.optString("sourceKey")); val ok = o.optBoolean("ok")
            val interrupted = prev != null && prev.has("disabledUntil") && prev.optLong("disabledUntil") <= now
            val streak = if (ok) 0 else if (interrupted) 1 else (prev?.optInt("failStreak") ?: 0) + 1
            val count = if (ok) maxOf(0, (prev?.optInt("disableCount") ?: 0) - 1) else prev?.optInt("disableCount") ?: 0
            val entry = JSONObject().put("ok", ok).put("failStreak", streak).put("disableCount", count).put("timestamp", now)
            if (o.has("ms")) entry.put("ms", o.optLong("ms")); o.str("error")?.let { entry.put("error", it) }; if (o.optBoolean("timedOut")) entry.put("timedOut", true)
            if (!ok && streak >= 2) {
                val already = prev?.optBoolean("permanent") == true || (prev?.optLong("disabledUntil") ?: 0) > now
                if (already) { entry.put("disableCount", prev!!.optInt("disableCount", count)); if (prev.has("disabledUntil")) entry.put("disabledUntil", prev.optLong("disabledUntil")); if (prev.optBoolean("permanent")) entry.put("permanent", true) }
                else { val level = count + 1; entry.put("disableCount", level); if (level - 1 < ladder.size) entry.put("disabledUntil", now + ladder[level - 1]) else entry.put("permanent", true) }
            }
            health.put(o.optString("sourceKey"), entry)
        }
        put("sourceHealth", health)
    }

    // ---- 订阅 ----
    @Synchronized fun addSubscription(url: String) { if (subscriptions().none { it.optString("url") == url }) put("subscriptions", JSONArray(subscriptions() + JSONObject().put("url", url))) }
    @Synchronized fun markSubscription(url: String, change: (JSONObject) -> Unit) { put("subscriptions", JSONArray(subscriptions().onEach { if (it.optString("url") == url) change(it) })) }
    /** 用订阅的最新列表整体替换它之前导入的片源；仍在列表里的保留勾选状态，新出现的默认勾选 */
    @Synchronized fun applySubscriptionSources(subUrl: String, list: List<JSONObject>): Int {
        val prefix = subKeyPrefix(subUrl)
        fun bare(url: String) = url.trimEnd('/')
        val incomingUrls = list.map { bare(it.optString("url")) }.toSet()
        val custom = customSources(); val selected = selectedKeys()
        val kept = custom.filter { !belongs(it.optString("key"), prefix) && bare(it.optString("url")) !in incomingUrls }
        val owned = custom.filter { belongs(it.optString("key"), prefix) }
        val ownedUrls = owned.map { bare(it.optString("url")) }.toSet()
        val ownedSelected = owned.filter { it.optString("key") in selected }.map { bare(it.optString("url")) }.toSet()
        // 序号按订阅里的原始位置编，用户删掉的那几个跳过
        val hidden = hiddenUrls()
        val incoming = list.mapIndexed { i, s -> s.copy().put("key", "${prefix}_$i") }.filter { bare(it.optString("url")) !in hidden }
        val filter = state.optBoolean("yellowFilter", true)
        val select = incoming.filter { s -> val u = bare(s.optString("url")); if (u in ownedUrls) u in ownedSelected else !s.optBoolean("isAdult") || !filter }.map { it.optString("key") }
        state.put("customAPIs", JSONArray(kept + incoming)); state.put("selectedKeys", JSONArray(selected.filter { !belongs(it, prefix) } + select)); persist()
        return incoming.size
    }

    companion object {
        /** 订阅导入的片源 key 前缀：sub_<地址的哈希> */
        fun subKeyPrefix(url: String): String { var h = 5381L; for (c in url) h = ((h shl 5) + h + c.code) and 0xFFFFFFFFL; return "sub_" + h.toString(36) }
        fun belongs(key: String, prefix: String) = key == prefix || key.startsWith("${prefix}_")
    }
}

/** 搜索结果、换源探测等的短期缓存（落盘，上限约 1 MB / 250 条）。 */
class SourceCache(file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    class Hit(val json: String, val stale: Boolean, val ageMs: Long)
    private class Entry(val key: String, val json: String, val at: Long, val freshUntil: Long, val expires: Long) { val size get() = (key.length + json.length + 80) * 2 }
    private val store = JsonFile(file, 250)
    private val entries = LinkedHashMap<String, Entry>(64, .75f, true)
    init {
        store.read()?.optJSONArray("entries").objects().forEach {
            val e = Entry(it.optString("key"), it.optString("json"), it.optLong("at"), it.optLong("freshUntil"), it.optLong("expires"))
            if (e.key.isNotEmpty() && e.json.isNotEmpty() && e.expires > now()) entries[e.key] = e
        }
    }
    private fun prune() {
        entries.values.removeAll { it.expires <= now() }
        var size = entries.values.sumOf { it.size }
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && (size > 1024 * 1024 || entries.size > 250)) { size -= iterator.next().size; iterator.remove() }
    }
    @Synchronized fun get(key: String): Hit? {
        val entry = entries[key] ?: return null
        if (entry.expires <= now()) { entries.remove(key); return null }
        return Hit(entry.json, entry.freshUntil <= now(), maxOf(0, now() - entry.at))
    }
    @Synchronized fun set(key: String, json: String, ttlMs: Long, retainMs: Long = ttlMs) {
        if (json.isEmpty() || json.length * 2 > 256 * 1024) return
        entries.remove(key); entries[key] = Entry(key, json, now(), now() + ttlMs, now() + maxOf(ttlMs, retainMs))
        prune()
        store.schedule { synchronized(this) { JSONObject().put("entries", JSONArray(entries.values.map { JSONObject().put("key", it.key).put("json", it.json).put("at", it.at).put("freshUntil", it.freshUntil).put("expires", it.expires) })).toString() } }
    }
    fun flush() = store.flush()
    companion object {
        fun identity(source: JSONObject) = JSONArray().put(source.optString("key")).put(source.optString("name")).put(source.optString("url")).put(source.optString("detail")).put(source.optBoolean("isAdult"))
        fun searchKey(query: String, source: JSONObject, filterAdult: Boolean) = JSONArray().put("search").put(query.trim()).put(identity(source)).put(filterAdult).toString()
    }
}
