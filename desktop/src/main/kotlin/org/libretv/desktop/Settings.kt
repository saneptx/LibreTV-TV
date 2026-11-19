package org.libretv.desktop

import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.libretv.tv.core.Pool
import org.libretv.tv.core.objects
import org.libretv.tv.core.strings
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

var settingsTab by mutableStateOf("playback")

@Composable
fun SettingsScreen() {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(start = 24.dp, top = 24.dp, end = 12.dp)) {
            T("设置", 28.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 18.dp))
            listOf("playback" to "播放", "sources" to "片源", "subscriptions" to "订阅", "service" to "串流服务", "about" to "关于").forEach { (key, label) ->
                val on = settingsTab == key
                val navColor by androidx.compose.animation.animateColorAsState(if (on) C.raised else Color.Transparent, androidx.compose.animation.core.tween(180))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(navColor).press { settingsTab = key }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    T(label, 15.sp, if (on) C.ink else C.muted, FontWeight.Medium)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 40.dp, top = 76.dp, bottom = 40.dp).widthIn(max = 820.dp)) {
            androidx.compose.animation.Crossfade(settingsTab, animationSpec = androidx.compose.animation.core.tween(180)) { tab ->
                Column {
                    when (tab) {
                        "playback" -> PlaybackPane()
                        "sources" -> SourcesPane()
                        "subscriptions" -> SubscriptionsPane()
                        "service" -> ServicePane()
                        else -> AboutPane()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackPane() {
    val m = LocalModel.current; val c = m.config
    fun set(key: String, value: Boolean) = m.request("settings", JSONObject().put(key, value))
    Group("播放") {
        SettingRow("去除插播广告", "自动识别并跳过片源里夹带的广告片段") { Toggle(c.optBoolean("adFilter", true)) { set("adFilter", it) } }
        Divider()
        SettingRow("自动播放下一集") { Toggle(c.optBoolean("autoplayNext", true)) { set("autoplayNext", it) } }
        if (c.optInt("learnedAds") > 0) { Divider(); SettingRow("清除已学习的广告", "已记住 ${c.optInt("learnedAds")} 段", onClick = { m.request("clear-learned-ads") { m.toast("已清除") } }) }
    }
    Group("电视的画面处理（电视走电脑串流时生效）") {
        SettingRow("AI 超分", "电视播放时由这台电脑的显卡把画面升高一档") { Toggle(c.optBoolean("superResolution", true)) { set("superResolution", it) } }
        Divider()
        SettingRow("去水印", "识别并抹掉固定的台标和滚动广告字幕") { Toggle(c.optBoolean("overlayRemoval", true)) { set("overlayRemoval", it) } }
    }
}

@Composable
private fun SourcesPane() {
    val m = LocalModel.current; val dialog = LocalDialog.current; val c = m.config
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    val selected = c.optJSONArray("selectedKeys").strings().toSet()
    val sources = c.optJSONArray("sources").objects()
    Group("添加片源") {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Input(name, { name = it }, "名称（可不填）", Modifier.width(180.dp)); Spacer(Modifier.width(10.dp))
                Input(url, { url = it }, "采集接口地址，以 http:// 或 https:// 开头", Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
                Pill("添加", primary = true) { m.request("add-source", JSONObject().put("name", name).put("url", url)) { e -> if (e.optString("event") == "error") m.toast(e.optString("error")) else { name = ""; url = ""; m.toast("已添加") } } }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("从剪贴板批量导入") { importClipboard(m, dialog) }; Spacer(Modifier.width(12.dp))
                T("支持多行接口地址、订阅地址，或整段片源列表 / TVBox 配置", 12.sp, C.muted)
            }
        }
    }
    Group("片源 · 已启用 ${c.optInt("enabled")} / ${sources.size}") {
        sources.forEachIndexed { i, s ->
            if (i > 0) Divider()
            val key = s.optString("key")
            val origin = if (key.startsWith("env_")) "" else if (key.startsWith("sub_")) "订阅 · " else "自己添加 · "
            SettingRow(s.optString("name"), origin + runCatching { java.net.URI(s.optString("url")).host }.getOrNull().orEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Icon.CLOSE, 32.dp, C.faint) {
                        val noteText = if (key.startsWith("env_")) "这是内置片源，删除后不再显示和搜索，可以在下方恢复。" else if (key.startsWith("sub_")) "它来自订阅，删除后该订阅再次同步也不会把它加回来。" else "删除后需要重新添加才能找回。"
                        dialog.confirm("删除片源", "删除「${s.optString("name")}」。$noteText 已有的观看记录不受影响。", "删除") { m.request("remove-source", JSONObject().put("key", key)) { m.toast("已删除") } }
                    }
                    Spacer(Modifier.width(10.dp)); Toggle(key in selected) { m.request("toggle-source", JSONObject().put("key", key)) }
                }
            }
        }
        if (c.optInt("hiddenSources") > 0) { Divider(); SettingRow("恢复删除的内置片源", "已删除 ${c.optInt("hiddenSources")} 个", onClick = { m.request("restore-sources") { e -> e.optJSONObject("value")?.let { m.toast("已恢复 ${it.optInt("restored")} 个片源") } } }) }
    }
}

fun importClipboard(m: AppModel, dialog: DialogState) {
    val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull().orEmpty()
    if (text.isBlank()) { m.toast("剪贴板里没有文字，先复制片源或订阅地址"); return }
    m.request("preview-paste", JSONObject().put("text", text)) { event ->
        if (event.optString("event") == "error") { dialog.confirm("没有可导入的内容", event.optString("error") + "\n\n可以粘贴：每行一个采集接口地址（前面可带名称）、每行一个订阅地址，或整段片源列表 / TVBox 配置。", "知道了") {}; return@request }
        val v = event.optJSONObject("value") ?: return@request
        val sources = v.optJSONArray("sources").objects(); val subs = v.optJSONArray("subscriptions").strings(); val fresh = v.optInt("newSources")
        dialog.show {
            var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
            Column(Modifier.width(560.dp).padding(24.dp)) {
                T("从剪贴板导入", 18.sp, weight = FontWeight.Bold); Spacer(Modifier.height(10.dp))
                T("识别到 " + listOf(if (sources.isNotEmpty()) "${sources.size} 个片源接口" + (if (fresh < sources.size) "（${sources.size - fresh} 个已存在，会跳过）" else "") else "", if (subs.isNotEmpty()) "${subs.size} 个订阅地址" else "").filter { it.isNotEmpty() }.joinToString("、"), 14.sp)
                v.optString("note").takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); T(it, 12.sp, C.muted) }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    (sources.map { it.optString("name") to it.optString("url") } + subs.map { "订阅" to it }).take(80).forEach { (n, u) -> Column(Modifier.padding(vertical = 5.dp)) { T(n, 13.sp, lines = 1); T(u, 11.sp, C.faint, lines = 1) } }
                }
                if (status.isNotEmpty()) { Spacer(Modifier.height(10.dp)); T(status, 13.sp, C.muted) }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Pill("取消") { dialog.close() }; Spacer(Modifier.width(10.dp))
                    Pill(if (busy) "正在导入…" else "导入", primary = true, enabled = !busy) {
                        busy = true; status = if (subs.isNotEmpty()) "正在导入并同步订阅…" else ""
                        m.request("import-paste", JSONObject().put("text", text)) { done ->
                            busy = false
                            if (done.optString("event") == "error") { status = done.optString("error"); return@request }
                            val r = done.optJSONObject("value") ?: return@request; val failed = r.optJSONArray("failed").objects()
                            val parts = listOf(if (r.optInt("added") > 0) "新增 ${r.optInt("added")} 个片源" else "", if (r.optInt("skipped") > 0) "跳过 ${r.optInt("skipped")} 个已存在的" else "", if (r.optInt("subscriptions") > 0) "同步 ${r.optInt("subscriptions")} 个订阅（${r.optInt("subscriptionSources")} 个片源）" else "").filter { it.isNotEmpty() }
                            if (failed.isEmpty()) { dialog.close(); m.toast(parts.joinToString("，").ifEmpty { "没有新内容" }) }
                            else status = (parts + "${failed.size} 个订阅失败：" + failed.map { "${it.optString("url").take(60)}：${it.optString("error")}" }).joinToString("\n")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionsPane() {
    val m = LocalModel.current; val dialog = LocalDialog.current
    var url by remember { mutableStateOf("") }; var busy by remember { mutableStateOf("") }
    Group("添加订阅") {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Input(url, { url = it }, "订阅地址：片源列表或 TVBox 配置的网址", Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
            Pill(if (busy == "new") "正在同步…" else "订阅", primary = true, enabled = busy.isEmpty()) {
                busy = "new"; m.request("sync-subscription", JSONObject().put("url", url)) { e -> busy = ""; if (e.optString("event") == "error") m.toast(e.optString("error")) else { url = ""; m.refreshHome(); m.toast("订阅已同步") } }
            }
        }
    }
    val subs = m.config.optJSONArray("subscriptions").objects()
    Group("我的订阅") {
        if (subs.isEmpty()) SettingRow("还没有订阅", "订阅地址指向一份片源清单，同步后一次导入其中所有能直接搜索的片源")
        subs.forEachIndexed { i, s ->
            if (i > 0) Divider()
            val u = s.optString("url")
            SettingRow(s.optString("name").ifBlank { runCatching { java.net.URI(u).host }.getOrNull() ?: "订阅" },
                if (busy == u) "正在同步…" else if (s.optString("lastStatus") == "error") "上次同步失败：${s.optString("lastError")}" else "${s.optJSONObject("lastCounts")?.optInt("vod") ?: 0} 个片源 · $u") {
                Row {
                    Pill("重新同步", enabled = busy.isEmpty(), padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) { busy = u; m.request("sync-subscription", JSONObject().put("url", u)) { e -> busy = ""; m.refreshHome(); m.toast(if (e.optString("event") == "error") e.optString("error") else "已同步") } }
                    Spacer(Modifier.width(8.dp))
                    Pill("删除", padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) { dialog.confirm("删除订阅", "删除这个订阅和它带来的全部片源。", "删除") { m.request("remove-subscription", JSONObject().put("url", u)) { m.toast("已删除") } } }
                }
            }
        }
    }
}

@Composable
private fun ServicePane() {
    val m = LocalModel.current; val s = m.service; val dialog = LocalDialog.current
    LaunchedEffect(Unit) { while (true) { Pool.ops.execute { s.refresh() }; delay(4000) } }
    val running = s.state == LocalService.State.RUNNING || s.state == LocalService.State.EXTERNAL
    Group("") {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(when (s.state) { LocalService.State.RUNNING -> "串流服务运行中"; LocalService.State.EXTERNAL -> "串流服务运行中"; LocalService.State.STARTING -> "正在启动…"; LocalService.State.FAILED -> "没有启动"; else -> "已停止" }, 20.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    T(if (running) listOf(if (s.processing == "enhance") "去广告 + AI 超分${if (s.model.isNotBlank()) "（${s.model}）" else ""}" else "只转发原画", s.addresses().joinToString("、") { "$it:${LocalService.PORT}" }).filter { it.isNotBlank() }.joinToString(" · ")
                        else "电视、投影仪通过它用这台电脑的显卡去广告、超分，并在各设备间同步观看记录", 13.sp, C.muted)
                    if (s.message.isNotBlank()) { Spacer(Modifier.height(8.dp)); T(s.message, 13.sp, C.ink) }
                }
                if (s.owned || s.state == LocalService.State.STARTING) { Pill("重启") { s.restart() }; Spacer(Modifier.width(8.dp)); Pill("停止") { s.stop() } }
                else if (!running) Pill("启动", primary = true, enabled = s.available()) { s.start() }
            }
            if (running) {
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.raised).padding(18.dp)) {
                    Column {
                        T("电视配对码", 13.sp, C.muted); Spacer(Modifier.height(6.dp))
                        T(s.pin?.let { "${it.take(3)} ${it.drop(3)}" } ?: "——", 40.sp, weight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        T("在电视「设置 → 电脑服务」里选中这台电脑，填这 6 位数字。只在第一次连接时需要，每次启动服务会更换。", 12.sp, C.muted)
                    }
                }
            }
        }
    }
    Group("设置") {
        SettingRow("随 SuperTV 自动启动") { Toggle(s.autoStart) { s.autoStart = it } }
        Divider()
        SettingRow("关闭窗口后在后台继续运行", "电视要用串流服务时保持开着；从任务栏右下角的图标打开或退出") { Toggle(s.keepRunning) { s.keepRunning = it } }
        Divider()
        SettingRow("处理方式", if (s.mode == "enhance") "去广告 + AI 超分（需要 NVIDIA 显卡和超分工具）" else "只转发原画（不需要显卡）", onClick = { s.mode = if (s.mode == "enhance") "relay" else "enhance"; if (s.owned) s.restart() }) { T(if (s.mode == "enhance") "超分" else "仅转发", 14.sp, C.muted) }
        Divider()
        SettingRow("下载位置", s.libraryDir.absolutePath) {
            Pill(if (s.movingLibrary) "正在搬…" else "更改", enabled = !s.movingLibrary, padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) { chooseLibrary(m, dialog) }
        }
        Divider()
        SettingRow("超分工具", if (s.toolsReady()) "已就绪（FFmpeg 与超分模型）" else "首次使用超分需要下载 FFmpeg 和模型，约几百 MB") {
            Pill(if (s.settingUp) "正在下载…" else if (s.toolsReady()) "重新检查" else "下载超分工具", enabled = !s.settingUp, padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) { s.setupTools { if (s.owned) s.restart() } }
        }
    }
    if (s.log.isNotEmpty()) Group("日志") {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(16.dp)) {
                s.log.takeLast(120).forEach { androidx.compose.foundation.text.BasicText(it, style = TextStyle(color = C.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)) }
            }
        }
    }
}

/** 选文件夹作为下载位置；原位置里已有影片时问要不要一起搬过去 */
private fun chooseLibrary(m: AppModel, dialog: DialogState) {
    val s = m.service
    val chooser = runCatching {
        val old = javax.swing.UIManager.getLookAndFeel()
        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        javax.swing.JFileChooser(s.libraryDir.takeIf { it.isDirectory } ?: s.libraryDir.parentFile).also { javax.swing.UIManager.setLookAndFeel(old) }
    }.getOrElse { javax.swing.JFileChooser() }
    chooser.dialogTitle = "选择下载影片的位置"
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    if (chooser.showDialog(null, "选这个文件夹") != javax.swing.JFileChooser.APPROVE_OPTION) return
    val picked = chooser.selectedFile ?: return
    // 选了个盘符或已有很多东西的文件夹时，单独建一个子文件夹放影片
    val target = if (picked.parentFile == null || (picked.listFiles()?.any { !(it.isDirectory && File(it, "meta.json").isFile) } == true)) File(picked, "SuperTV 下载") else picked
    val existing = s.libraryDir.listFiles()?.count { it.isDirectory && File(it, "meta.json").isFile } ?: 0
    val apply = { move: Boolean -> s.changeLibrary(target, move) { m.toast(it) } }
    if (existing == 0) apply(false)
    else dialog.show {
        Column(Modifier.width(440.dp).padding(24.dp)) {
            T("更改下载位置", 18.sp, weight = FontWeight.Bold); Spacer(Modifier.height(10.dp))
            T("新位置：${target.path}\n原位置有 $existing 部已下载的影片，要一起搬过去吗？搬的时候串流服务会暂停一下，正在下载的会在重启后继续。", 14.sp, C.muted, lineHeight = 21.sp)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill("取消") { dialog.close() }; Spacer(Modifier.width(10.dp))
                Pill("只改位置") { dialog.close(); apply(false) }; Spacer(Modifier.width(10.dp))
                Pill("一起搬过去", primary = true) { dialog.close(); apply(true) }
            }
        }
    }
}

@Composable
private fun AboutPane() {
    val m = LocalModel.current
    Group("关于") {
        SettingRow("SuperTV 电脑端", "版本 " + (System.getProperty("jpackage.app-version") ?: "开发版"))
        Divider()
        SettingRow("数据目录", m.home.path)
        Divider()
        SettingRow("资料同步", if (m.config.optBoolean("desktopConfigured")) "已接上本机串流服务，观看记录、片库与电视、手机同步" else "串流服务启动后自动接上")
    }
}


