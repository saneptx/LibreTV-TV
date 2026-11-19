package org.libretv.desktop

import org.libretv.tv.core.objects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.WindowState
import org.json.JSONObject
import java.io.File
import javax.swing.SwingUtilities

/**
 * 开发用：不开窗口，把各个页面渲染成 PNG（用来检查界面）。
 * 参数：输出目录，之后是一串步骤：home / library / downloads / settings:<栏目> / search:<关键词> / detail:<片名> / wait:<秒>
 */
object Snapshot {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args[0]).apply { mkdirs() }
        val resources = System.getProperty("compose.application.resources.dir")?.let { File(it) }?.takeIf { it.isDirectory }
        lateinit var model: AppModel
        SwingUtilities.invokeAndWait { model = AppModel(dataHome().apply { mkdirs() }, resources) }
        val dialog = DialogState()
        val scene = ImageComposeScene(1360, 860, Density(1f)) {
            val window = remember { WindowState() }
            val chrome = remember { Chrome(java.awt.Frame(), System.getenv("SUPERTV_CHROME") != null) }
            CompositionLocalProvider(LocalModel provides model, LocalDialog provides dialog, LocalWindow provides window, LocalChrome provides chrome) { App(model, dialog) }
        }
        var shot = 0
        fun render(name: String) {
            var image: org.jetbrains.skia.Image? = null
            // 多渲染几帧让动画和异步加载落定
            repeat(4) { SwingUtilities.invokeAndWait { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); image = scene.render(System.nanoTime()) }; Thread.sleep(150) }
            File(out, "%02d-%s.png".format(++shot, name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_"))).writeBytes(image!!.encodeToData()!!.bytes)
        }
        Thread.sleep(2500)
        for (step in args.drop(1)) {
            val (kind, arg) = step.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            SwingUtilities.invokeAndWait {
                when (kind) {
                    "home" -> model.tab(Screen.Home)
                    "library" -> model.tab(Screen.Library)
                    "downloads" -> model.tab(Screen.Downloads)
                    "settings" -> { model.tab(Screen.Settings); if (arg.isNotEmpty()) settingsTab = arg }
                    "search" -> model.open(Screen.Search(arg))
                    "detail" -> model.open(Screen.Detail(JSONObject().put("name", arg).put("items", org.json.JSONArray())))
                    "back" -> model.back()
                    // playsrc:片名|片源名|第几集
                    "playsrc" -> { val (q, src, ep) = arg.split("|"); model.request("search", JSONObject().put("query", q).put("record", false)) { e ->
                        if (e.optString("event") == "result") e.optJSONObject("value")?.optJSONArray("groups")?.objects()?.flatMap { it.optJSONArray("items").objects() }
                            ?.firstOrNull { it.optString("sourceName").contains(src) && it.optString("name").contains(q) }?.let { System.err.println("播放：" + it.optString("sourceName")); model.open(Screen.Player(it, ep.toInt() - 1)) }
                    } }
                    // 搜片名，播第一个结果的第一集
                    "play" -> model.request("search", JSONObject().put("query", arg).put("record", false)) { e ->
                        if (e.optString("event") == "result") e.optJSONObject("value")?.optJSONArray("groups")?.optJSONObject(0)?.optJSONArray("items")?.optJSONObject(0)?.let { model.open(Screen.Player(it, 0)) }
                    }
                }
            }
            if (kind == "shot") { render("shot-$arg"); continue }
            // 跳到最后一个广告段之前几秒（片尾那段离结尾最近，最容易出现误跳下一集）
            if (kind == "before-ad") { SwingUtilities.invokeAndWait { debugPlayer?.let { p -> p.adStart(arg == "first")?.let { System.err.println("跳到广告前：" + (it - 6)); p.seekSeconds(it - 6) } } }; continue }
            if (kind == "seek") { SwingUtilities.invokeAndWait { debugPlayer?.seekSeconds(arg.toDouble()) }; continue }
            // 鼠标移到画面上（唤出播放控制栏）或点击某个位置：move:x,y / click:x,y
            if (kind == "move" || kind == "click") {
                val (x, y) = arg.split(",").map { it.trim().toFloat() }
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(x, y))
                    if (kind == "click") { scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.geometry.Offset(x, y)); scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.geometry.Offset(x, y)) }
                }
                Thread.sleep(600); render("$kind-$arg"); continue
            }
            if (kind == "wait") { Thread.sleep((arg.toDoubleOrNull() ?: 1.0).times(1000).toLong()); continue }
            Thread.sleep(1500)
            render("$kind-$arg")
        }
        SwingUtilities.invokeAndWait { scene.close(); model.release() }
        System.exit(0)
    }
}
