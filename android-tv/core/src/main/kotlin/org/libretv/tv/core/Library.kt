package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本机资料库：观看历史、各集进度、搜索记录、片库收藏，以及用来同步删除的删除记录。
 * 每条记录带自己的修改时间；多设备之间按修改时间合并（新的覆盖旧的）。数据量小，整份放在内存里并落盘为一个 JSON 文件。
 */
class Library(file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    enum class Table(val key: String, val clock: String) {
        history("id", "timestamp"), progress("key", "updatedAt"), searchHistory("text", "timestamp"), favorites("key", "updatedAt")
    }
    private val store = JsonFile(file, 500)
    private val tables = Table.values().associateWith { LinkedHashMap<String, JSONObject>() }
    /** 删除记录：键为「表:主键」，值为删除时间 */
    private val tombstones = LinkedHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    init { store.read()?.let { load(it) } }

    @Synchronized private fun load(root: JSONObject) {
        for (table in Table.values()) {
            val rows = tables.getValue(table); rows.clear()
            root.optJSONArray(table.name).objects().forEach { row -> row.optString(table.key).takeIf { it.isNotEmpty() }?.let { rows[it] = row } }
        }
        tombstones.clear()
        root.optJSONArray("tombstones").objects().forEach { t -> tableOf(t.optString("table"))?.let { tombstones["${it.name}:${t.optString("key")}"] = t.optLong("at") } }
    }
    /** 从旧版（网页数据库）迁移：按修改时间并入现有数据（本机较新的记录不会被覆盖） */
    fun importAll(root: JSONObject) {
        val changes = ArrayList<JSONObject>()
        for (table in Table.values()) root.optJSONArray(table.name).objects().forEach { row ->
            val key = jsString(row.opt(table.key)); if (key.isNotEmpty() && row.optLong(table.clock) > 0) changes.add(JSONObject().put("table", table.name).put("key", key).put("at", row.optLong(table.clock)).put("value", row))
        }
        root.optJSONArray("tombstones").objects().forEach { t -> if (t.optLong("at") > 0) changes.add(JSONObject().put("table", t.optString("table")).put("key", t.optString("key")).put("at", t.optLong("at")).put("deleted", true)) }
        applyChanges(changes); persist()
    }
    @Synchronized fun isEmpty() = tables.values.all { it.isEmpty() } && tombstones.isEmpty()
    private fun snapshot(): String = synchronized(this) {
        val root = JSONObject()
        for (table in Table.values()) root.put(table.name, JSONArray(tables.getValue(table).values))
        root.put("tombstones", JSONArray(tombstones.map { (id, at) -> JSONObject().put("table", id.substringBefore(':')).put("key", id.substringAfter(':')).put("at", at) }))
        root.toString()
    }
    private fun persist() = store.schedule { snapshot() }
    fun flush() = store.flush()
    /** origin：local（本机操作）或 sync（其他设备同步进来） */
    fun onChange(listener: (String) -> Unit): () -> Unit { listeners.add(listener); return { listeners.remove(listener) } }
    fun notifyChange(origin: String = "local") { persist(); listeners.forEach { runCatching { it(origin) } } }
    private fun tableOf(name: String) = Table.values().firstOrNull { it.name == name }
    private fun clock(table: Table, row: JSONObject) = row.optLong(table.clock)
    private fun bury(table: Table, keys: Collection<String>) { val at = now(); keys.forEach { tombstones["${table.name}:$it"] = at } }
    private fun trim(table: Table, cap: Int) {
        val rows = tables.getValue(table)
        if (rows.size > cap) rows.values.sortedBy { it.optLong("timestamp") }.take(rows.size - cap).forEach { rows.remove(it.optString(table.key)) }
    }

    // ---- 观看历史与进度 ----
    @Synchronized fun history(limit: Int = MAX_HISTORY): List<JSONObject> = tables.getValue(Table.history).values.sortedByDescending { it.optLong("timestamp") }.take(limit).map { it.copy() }
    @Synchronized fun historyEntry(sourceKey: String, vodId: String): JSONObject? = tables.getValue(Table.history)["${sourceKey}_$vodId"]?.copy()
    @Synchronized fun progress(sourceKey: String, vodId: String, index: Int): JSONObject? = tables.getValue(Table.progress)["${sourceKey}_${vodId}_$index"]?.copy()
    fun upsertHistory(entry: JSONObject) {
        synchronized(this) {
            val id = "${entry.optString("sourceKey")}_${entry.optString("vodId")}"; val rows = tables.getValue(Table.history); val existing = rows[id]
            val merged = existing?.copy() ?: JSONObject()
            for (key in entry.keys()) merged.put(key, entry.get(key))
            merged.put("id", id).put("playbackPosition", if (entry.optDouble("playbackPosition", 0.0) > 10) entry.optDouble("playbackPosition") else existing?.optDouble("playbackPosition", 0.0) ?: 0.0)
                .put("duration", entry.optDouble("duration", 0.0).takeIf { it > 0 } ?: existing?.optDouble("duration", 0.0) ?: 0.0).put("timestamp", now())
            rows[id] = merged; tombstones.remove("history:$id"); trim(Table.history, MAX_HISTORY)
        }
        notifyChange()
    }
    fun updateHistoryProgress(sourceKey: String, vodId: String, position: Double, duration: Double) {
        synchronized(this) {
            val existing = tables.getValue(Table.history)["${sourceKey}_$vodId"] ?: return
            if (Math.abs(existing.optDouble("playbackPosition", 0.0) - position) < 2 && Math.abs(existing.optDouble("duration", 0.0) - duration) < 2) return
            existing.put("playbackPosition", position).put("duration", duration).put("timestamp", now())
        }
        notifyChange()
    }
    fun saveProgress(sourceKey: String, vodId: String, index: Int, position: Double, duration: Double) {
        if (duration <= 0 || position < 1) return
        synchronized(this) {
            val key = "${sourceKey}_${vodId}_$index"
            tables.getValue(Table.progress)[key] = JSONObject().put("key", key).put("position", position).put("duration", duration).put("updatedAt", now())
            tombstones.remove("progress:$key")
        }
        notifyChange()
    }
    /** 删除观看记录及其各集进度；删除会同步到其他设备 */
    fun removeHistory(sourceKey: String, vodId: String) = removeHistories(listOf(sourceKey to vodId))
    fun removeHistories(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        synchronized(this) {
            for ((sourceKey, vodId) in entries) {
                val id = "${sourceKey}_$vodId"
                if (tables.getValue(Table.history).remove(id) != null) bury(Table.history, listOf(id))
                val progress = tables.getValue(Table.progress); val prefix = "${id}_"
                val keys = progress.keys.filter { it.startsWith(prefix) && it.substring(prefix.length).toIntOrNull() != null }
                keys.forEach { progress.remove(it) }; bury(Table.progress, keys)
            }
        }
        notifyChange()
    }
    /** 清空全部观看记录和进度 */
    fun clearHistory() {
        synchronized(this) {
            for (table in listOf(Table.history, Table.progress)) { val rows = tables.getValue(table); bury(table, rows.keys.toList()); rows.clear() }
        }
        notifyChange()
    }

    // ---- 搜索记录 ----
    @Synchronized fun searches(): List<JSONObject> = tables.getValue(Table.searchHistory).values.sortedByDescending { it.optLong("timestamp") }.take(MAX_SEARCH).map { it.copy() }
    fun addSearch(text: String) {
        val value = text.trim().take(50); if (value.isEmpty()) return
        synchronized(this) { tables.getValue(Table.searchHistory)[value] = JSONObject().put("text", value).put("timestamp", now()); tombstones.remove("searchHistory:$value"); trim(Table.searchHistory, MAX_SEARCH) }
        notifyChange()
    }
    fun clearSearches() {
        synchronized(this) { val rows = tables.getValue(Table.searchHistory); bury(Table.searchHistory, rows.keys.toList()); rows.clear() }
        notifyChange()
    }

    // ---- 片库收藏 ----
    @Synchronized fun favorites(): List<JSONObject> = tables.getValue(Table.favorites).values.sortedByDescending { it.optLong("addedAt") }.map { it.copy() }
    /** 返回切换后是否在片库里 */
    fun toggleFavorite(card: JSONObject): Boolean {
        val key = favoriteKey(card); require(key.substringBefore('|').isNotEmpty()) { "影片缺少片名" }
        val added = synchronized(this) {
            val rows = tables.getValue(Table.favorites)
            if (rows.remove(key) != null) { bury(Table.favorites, listOf(key)); false }
            else { val at = now(); rows[key] = JSONObject().put("key", key).put("card", card).put("addedAt", at).put("updatedAt", at); tombstones.remove("favorites:$key"); true }
        }
        notifyChange(); return added
    }
    /** 导入旧版片库（只补本机没有、也没删过的） */
    fun importFavorites(cards: List<JSONObject>): Int {
        var added = 0
        synchronized(this) {
            val rows = tables.getValue(Table.favorites)
            for (card in cards.take(2000)) {
                val key = favoriteKey(card)
                if (key.substringBefore('|').isEmpty() || rows.containsKey(key) || tombstones.containsKey("favorites:$key")) continue
                val at = card.optLong("addedAt").takeIf { it > 0 } ?: now()
                rows[key] = JSONObject().put("key", key).put("card", card.copy().apply { remove("addedAt") }).put("addedAt", at).put("updatedAt", at); added++
            }
        }
        if (added > 0) notifyChange()
        return added
    }

    // ---- 同步 ----
    /** 本机在 since 之后（不含）修改或删除的记录 */
    @Synchronized fun collectChanges(since: Long): List<JSONObject> {
        val changes = ArrayList<JSONObject>()
        for (table in Table.values()) for ((key, row) in tables.getValue(table)) if (clock(table, row) > since)
            changes.add(JSONObject().put("table", table.name).put("key", key).put("at", clock(table, row)).put("value", row.copy()))
        for ((id, at) in tombstones) if (at > since) changes.add(JSONObject().put("table", id.substringBefore(':')).put("key", id.substringAfter(':')).put("at", at).put("deleted", true))
        return changes
    }
    /** 合并其他设备的修改，返回实际改动的条数（不发通知，由调用方决定） */
    @Synchronized fun applyChanges(changes: List<JSONObject>): Int {
        var applied = 0
        for (c in changes) {
            val table = tableOf(c.optString("table")) ?: continue
            val key = c.str("key") ?: continue; val at = c.optLong("at"); val id = "${table.name}:$key"
            val rows = tables.getValue(table); val local = rows[key]; val tomb = tombstones[id]
            if (c.optBoolean("deleted")) {
                if (local != null && clock(table, local) > at) continue
                if (local != null) { rows.remove(key); applied++ }
                if (tomb == null || tomb < at) tombstones[id] = at
                continue
            }
            val value = c.optJSONObject("value") ?: continue
            if (local != null && clock(table, local) >= at || tomb != null && tomb >= at) continue
            rows[key] = value.copy().put(table.key, key); tombstones.remove(id); applied++
        }
        // 本机只保留最近的若干条（只删本机，不记删除）
        trim(Table.history, MAX_HISTORY); trim(Table.searchHistory, MAX_SEARCH)
        if (applied > 0) persist()
        return applied
    }

    companion object {
        const val MAX_HISTORY = 100
        const val MAX_SEARCH = 10
        fun favoriteKey(card: JSONObject) = "${jsString(card.opt("name")).trim()}|${jsString(card.opt("year"))}"
    }
}

/**
 * 资料同步协议（与电脑服务 desktop-server/sync.mjs 一致）：设备记住对方给的版本号，
 * 每次只推送本机上次同步之后的修改、只拉取对方更新的部分。电视同时可以充当手机的同步对象。
 */
class LibrarySync(private val library: Library, stateFile: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    /** 发出一次同步请求并返回应答；失败时抛出 */
    fun interface Transport { fun exchange(body: JSONObject, cancel: Cancel): JSONObject }
    private val stateStore = JsonFile(stateFile, 0)
    private var saved: JSONObject? = stateStore.read()
    /** 从手机收到的记录，修改时间可能早于本机上次推送给电脑的时间；把推送起点回拨到这些记录之前 */
    @Volatile private var rewind = Long.MAX_VALUE

    private fun loadState(server: String): JSONObject = saved?.takeIf { it.optString("server") == server && it.has("rev") }?.copy() ?: JSONObject().put("server", server).put("rev", 0).put("pushedAt", 0)
    private fun saveState(state: JSONObject) { saved = state.copy(); stateStore.schedule { state.toString() }; stateStore.flush() }
    @Synchronized private fun rewindPush(at: Long) {
        rewind = minOf(rewind, at)
        saved?.let { if (it.optLong("pushedAt") > at) saveState(it.copy().put("pushedAt", at)) }
    }
    private fun batches(changes: List<JSONObject>): ArrayDeque<List<JSONObject>> {
        val out = ArrayDeque<List<JSONObject>>(); var current = ArrayList<JSONObject>(); var bytes = 0
        for (c in changes) {
            val size = c.toString().length
            if (size > 16000) continue   // 对方会拒收的超大记录不推送
            if (current.size >= PUSH_COUNT || current.isNotEmpty() && bytes + size > PUSH_BYTES) { out.add(current); current = ArrayList(); bytes = 0 }
            current.add(c); bytes += size
        }
        out.add(current); return out
    }

    /** 与对方同步一次：先推送本机修改，再把对方的更新拉完。返回 (推送条数, 拉取条数) */
    fun syncOnce(server: String, transport: Transport, cancel: Cancel): Pair<Int, Int> {
        var state = loadState(server)
        // 本机资料库是空的、同步进度却显示同步过：资料库被清掉了，从头再拉一遍
        if (state.optLong("rev") != 0L && library.isEmpty()) state = JSONObject().put("server", server).put("rev", 0).put("pushedAt", 0)
        val startedAt = now()
        val from = synchronized(this) { minOf(state.optLong("pushedAt"), rewind).also { rewind = Long.MAX_VALUE } }
        var queue = batches(library.collectChanges(from))
        var pushed = 0; var pulled = 0; var resent = false
        while (true) {
            cancel.check()
            val changes = queue.removeFirstOrNull() ?: emptyList()
            val body = JSONObject().put("since", state.optLong("rev")).put("changes", JSONArray(changes))
            state.str("epoch")?.let { body.put("epoch", it) }
            val reply = transport.exchange(body, cancel)
            pushed += changes.size
            // 对方的资料是新的（重置过或换了设备）：本机全部重新推送一遍
            if (reply.optBoolean("reset") && state.optLong("pushedAt") > 0 && !resent) { resent = true; queue = batches(library.collectChanges(0)) }
            pulled += library.applyChanges(reply.optJSONArray("changes").objects())
            state.put("epoch", reply.optString("epoch")).put("rev", reply.optLong("rev"))
            if (queue.isEmpty() && !reply.optBoolean("more")) break
        }
        state.put("pushedAt", synchronized(this) { minOf(startedAt - 1, rewind) })
        saveState(state)
        if (pulled > 0) library.notifyChange("sync")
        return pushed to pulled
    }

    // ---- 充当手机的同步对象 ----
    private val peerEpoch = "tv-${java.lang.Long.toString(now(), 36)}-${java.lang.Long.toString((Math.random() * 1e12).toLong(), 36)}"
    @Volatile private var peerRev = 1L
    private var peerWatching = false
    fun servePeer(body: JSONObject, onApplied: () -> Unit = {}): JSONObject {
        synchronized(this) { if (!peerWatching) { peerWatching = true; library.onChange { peerRev++ } } }
        val limit = now() + 86_400_000
        val incoming = body.optJSONArray("changes").objects().filter { c -> Library.Table.values().any { it.name == c.optString("table") } && c.str("key") != null && c.optLong("at") > 0 && c.optLong("at") < limit }.take(PUSH_COUNT)
        val reset = body.str("epoch") != peerEpoch; val since = body.optLong("since"); val before = peerRev
        if (incoming.isNotEmpty() && library.applyChanges(incoming) > 0) { rewindPush(incoming.minOf { it.optLong("at") } - 1); library.notifyChange("sync"); onApplied() }
        // 手机已是最新、这次的变化只来自它自己：不用回传
        if (!reset && since == before) return JSONObject().put("epoch", peerEpoch).put("reset", false).put("rev", peerRev).put("more", false).put("changes", JSONArray())
        val all = library.collectChanges(0); val start = if (!reset && since < 0) minOf(all.size.toLong(), -since).toInt() else 0
        val changes = JSONArray(); var bytes = 0; var next = start
        while (next < all.size) {
            val size = all[next].toString().length
            if (changes.length() > 0 && bytes + size > PEER_PAGE_BYTES) break
            changes.put(all[next]); bytes += size; next++
        }
        val more = next < all.size
        return JSONObject().put("epoch", peerEpoch).put("reset", reset).put("rev", if (more) -next.toLong() else peerRev).put("more", more).put("changes", changes)
    }

    /** 后台同步：启动时一次，本机有修改 3 秒后一次，之后每分钟一次。server() 返回空表示没有同步对象 */
    inner class Runner(private val server: () -> String, private val transport: Transport, private val intervalMs: Long = 60_000) {
        @Volatile var syncing = false; private set
        @Volatile var lastSyncAt = 0L; private set
        @Volatile var lastError: String? = null; private set
        val enabled get() = server().isNotEmpty()
        private val cancel = Cancel()
        private var timer: java.util.concurrent.ScheduledFuture<*>? = null
        private var again = false
        private val unsubscribe = library.onChange { if (it == "local") schedule(3000) }
        init { schedule(0) }
        @Synchronized private fun schedule(delay: Long) { timer?.cancel(false); if (!cancel.cancelled) timer = Pool.timer.schedule({ Pool.ops.execute { run() } }, delay, java.util.concurrent.TimeUnit.MILLISECONDS) }
        private fun run() {
            synchronized(this) { if (syncing) { again = true; return }; if (cancel.cancelled) return; syncing = true }
            val address = server()
            try {
                if (address.isNotEmpty()) { syncOnce(address, transport, cancel); lastSyncAt = now(); lastError = null }
            } catch (_: CancelledException) {
            } catch (error: Exception) { lastError = error.message ?: "同步失败" }
            val soon = synchronized(this) { syncing = false; again.also { again = false } }
            schedule(if (soon) 1000 else intervalMs)
        }
        /** 立即同步（例如刚改了同步对象） */
        fun kick() { Pool.ops.execute { run() } }
        fun status(): JSONObject = JSONObject().put("enabled", enabled).put("syncing", syncing).apply { if (lastSyncAt > 0) put("lastSyncAt", lastSyncAt); lastError?.let { put("lastError", it) } }
        fun stop() { cancel.cancel(); unsubscribe(); synchronized(this) { timer?.cancel(false) } }
    }

    companion object { const val PUSH_BYTES = 1536 * 1024; const val PUSH_COUNT = 1000; const val PEER_PAGE_BYTES = 1024 * 1024 }
}
