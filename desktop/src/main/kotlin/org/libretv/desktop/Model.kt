package org.libretv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.libretv.tv.core.CoreBackend
import org.libretv.tv.core.HotList
import org.libretv.tv.core.Pool
import org.libretv.tv.core.objects
import java.io.File
import javax.swing.SwingUtilities

sealed class Screen {
    data object Home : Screen()
    data object Library : Screen()
    data object Downloads : Screen()
    data object Settings : Screen()
    data class Search(val query: String) : Screen()
    /** card：同名影片（name、pic、items 各片源） */
    class Detail(val card: JSONObject) : Screen()
    class Player(val item: JSONObject, val index: Int, val position: Double? = null, val fromLibrary: Boolean = false) : Screen()
}

/** 界面状态；数据都来自共用的 Kotlin 数据层（CoreBackend）。 */
class AppModel(val home: File, val resources: File?) {
    var data by mutableStateOf(JSONObject()); private set
    val config: JSONObject get() = data.optJSONObject("config") ?: JSONObject()
    var ready by mutableStateOf(false); private set
    var bootError by mutableStateOf<String?>(null); private set
    var hotTv by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var hotMovie by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var toast by mutableStateOf<String?>(null); private set
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val service: LocalService
    val backend: CoreBackend
    private val hot = HotList(File(home, "app/hot.json"))

    init {
        val sources = runCatching { JSONArray((resources?.resolve("sources.json")?.takeIf { it.isFile } ?: File("../source.json")).readText()) }.getOrDefault(JSONArray())
        backend = CoreBackend(File(home, "app/core"), sources, { SwingUtilities.invokeLater(it) }) { event ->
            when (event.optString("event")) {
                "fatal" -> bootError = event.optString("error")
                else -> { data = event.optJSONObject("value") ?: data; ready = true }
            }
        }
        val scripts = resources?.resolve("service/desktop-server")?.takeIf { it.isDirectory } ?: File("../desktop-server").takeIf { it.isDirectory }?.absoluteFile
        service = LocalService(home, scripts)
        service.onReady = { url, token -> SwingUtilities.invokeLater { linkHub(url, token) } }
        backend.start()
        hot.cached()?.let { (tv, movie) -> hotTv = tv; hotMovie = movie }
        if (!hot.fresh()) Pool.ops.execute { hot.refresh()?.let { (tv, movie) -> SwingUtilities.invokeLater { hotTv = tv; hotMovie = movie } } }
        if (service.autoStart) service.start() else Pool.ops.execute { service.refresh() }
    }
    /** 本机资料与本机串流服务同步（电视、手机经它汇合），并能看电脑片库 */
    private fun linkHub(url: String, token: String) {
        if (config.optString("desktopServerUrl") == url && config.optString("desktopPairingToken") == token) return
        request("settings", JSONObject().put("desktopServerUrl", url).put("desktopPairingToken", token).put("playbackMode", "direct"))
    }

    private val debug = System.getenv("SUPERTV_DEBUG") != null
    private val started = System.currentTimeMillis()
    fun request(op: String, args: JSONObject = JSONObject(), callback: (JSONObject) -> Unit = {}) = backend.request(op, args) { event ->
        if (debug) System.err.println("[%6.1fs] %s → %s %s".format((System.currentTimeMillis() - started) / 1000.0, op, event.optString("event"), event.optString("error").take(200)))
        if (event.optString("event") == "result") (event.opt("value") as? JSONObject)?.let { v ->
            if (v.has("history") && v.has("config")) data = v
            else if (v.has("sources") && v.has("selectedKeys")) data = JSONObject(data.toString()).put("config", v)
            else v.optJSONObject("home")?.let { data = it } ?: v.optJSONObject("config")?.let { c -> if (c.has("selectedKeys")) data = JSONObject(data.toString()).put("config", c) }
        }
        callback(event)
    }
    fun refreshHome() = request("home")

    fun open(screen: Screen) { if (screen is Screen.Player) stack.removeAll { it is Screen.Player }; stack.add(screen) }
    fun tab(screen: Screen) { stack.clear(); stack.add(screen) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    private var toastSeq = 0
    fun toast(message: String) {
        if (message.isBlank()) return
        toast = message; val mine = ++toastSeq
        Pool.timer.schedule({ SwingUtilities.invokeLater { if (toastSeq == mine) toast = null } }, 2600, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // ---- 常用数据 ----
    fun sourceName(key: String) = config.optJSONArray("sources").objects().find { it.optString("key") == key }?.optString("name") ?: "片源"
    fun historyItem(entry: JSONObject) = JSONObject().put("sourceKey", entry.optString("sourceKey")).put("sourceUrl", entry.optString("sourceUrl")).put("sourceName", sourceName(entry.optString("sourceKey")))
        .put("vodId", entry.optString("vodId")).put("name", entry.optString("title")).put("pic", entry.optString("pic"))
    fun titleKey(name: String) = name.trim().lowercase()
    /** 观看记录按片名去重：同一部片换过片源时只留最近的一条 */
    fun history() = data.optJSONArray("history").objects().distinctBy { titleKey(it.optString("title")) }
    fun historyFor(name: String) = data.optJSONArray("history").objects().firstOrNull { titleKey(it.optString("title")) == titleKey(name) }
    fun isFavorite(card: JSONObject) = data.optJSONArray("favorites").objects().any { it.optString("name").trim() == card.optString("name").trim() && it.optString("year") == card.optString("year") }

    fun release() { backend.release(); if (service.owned) service.stop() }
}
