package org.libretv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.libretv.tv.core.JsonFile
import org.libretv.tv.core.Net
import org.libretv.tv.core.Pool
import java.io.File
import java.net.NetworkInterface
import javax.swing.SwingUtilities

/**
 * 本机串流服务（desktop-server）的管理：随 SuperTV 启动、显示电视配对码、查看状态和日志、下载超分工具。
 * 服务本身仍是 Node 脚本（调用显卡和 FFmpeg 做去广告、超分），由本程序作为子进程启动；
 * 数据（配对码、同步资料、片库、超分工具）放在 SUPERTV_HOME 下的 .desktop-server。
 */
class LocalService(private val home: File, private val scripts: File?) {
    enum class State { STOPPED, STARTING, RUNNING, EXTERNAL, FAILED }
    var state by mutableStateOf(State.STOPPED); private set
    var pin by mutableStateOf<String?>(null); private set
    var message by mutableStateOf(""); private set
    var processing by mutableStateOf("relay"); private set
    var model by mutableStateOf(""); private set
    var settingUp by mutableStateOf(false); private set
    val log = mutableStateListOf<String>()
    private val prefsFile = JsonFile(File(home, "app/service.json"), 0)
    private val prefs: JSONObject = prefsFile.read() ?: JSONObject()
    var autoStart: Boolean get() = prefs.optBoolean("autoStart", true); set(v) { prefs.put("autoStart", v); save() }
    /** enhance：去广告 + AI 超分（需要显卡和超分工具）；relay：只转发原画 */
    var mode: String get() = prefs.optString("mode", "enhance"); set(v) { prefs.put("mode", v); save() }
    var keepRunning: Boolean get() = prefs.optBoolean("keepRunning", true); set(v) { prefs.put("keepRunning", v); save() }
    /** 下载的影片存放位置；没选过时在数据目录里 */
    val defaultLibrary get() = File(home, ".desktop-server/library")
    var libraryDir by mutableStateOf(prefs.optString("libraryDir").takeIf { it.isNotBlank() }?.let(::File) ?: defaultLibrary); private set
    var movingLibrary by mutableStateOf(false); private set

    /**
     * 换下载位置。move=true 时先停服务，把已下载的影片（每部一个文件夹）搬到新位置，再按原状态重启服务；
     * 搬不动的（比如正被占用）留在原处并在日志里说明。
     */
    fun changeLibrary(target: File, move: Boolean, done: (String) -> Unit) {
        val from = libraryDir
        if (target.absoluteFile == from.absoluteFile || movingLibrary) return
        val wasOwned = owned
        movingLibrary = true
        if (wasOwned) stop()
        Pool.ops.execute {
            if (wasOwned) Thread.sleep(1500)
            var moved = 0; var failed = 0
            runCatching { target.mkdirs() }
            if (move) from.listFiles()?.filter { it.isDirectory && File(it, "meta.json").isFile }?.forEach { folder ->
                val dest = File(target, folder.name)
                val ok = !dest.exists() && runCatching {
                    // 同一磁盘直接改名；跨磁盘时复制完再删
                    try { java.nio.file.Files.move(folder.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: Exception) { folder.copyRecursively(dest, overwrite = false); folder.deleteRecursively() }
                    true
                }.getOrDefault(false)
                if (ok) moved++ else { failed++; append("没能搬走 ${folder.path}") }
            }
            ui {
                prefs.put("libraryDir", if (target.absoluteFile == defaultLibrary.absoluteFile) "" else target.absolutePath); save()
                libraryDir = target; movingLibrary = false
                if (wasOwned) start()
                done(when {
                    failed > 0 -> "已改下载位置，搬了 $moved 部，$failed 部没搬动（见日志）"
                    moved > 0 -> "已改下载位置，搬了 $moved 部影片"
                    !wasOwned && state == State.EXTERNAL -> "已改下载位置，串流服务下次启动时生效"
                    else -> "已改下载位置"
                })
            }
        }
    }
    private fun save() { val text = prefs.toString(); prefsFile.schedule { text }; prefsFile.flush() }
    private var process: Process? = null
    /** 服务就绪且拿到长配对码时回调（用于让本机资料和电脑片库接上服务） */
    var onReady: (url: String, token: String) -> Unit = { _, _ -> }

    val url = "http://127.0.0.1:$PORT"
    val owned get() = process?.isAlive == true
    fun token(): String? = runCatching { JSONObject(File(home, ".desktop-server/config.json").readText()).optString("token").takeIf { it.length >= 24 } }.getOrNull()
    fun toolsReady() = File(home, ".desktop-server/tools").listFiles()?.isNotEmpty() == true
    fun available() = scripts?.resolve("start.mjs")?.isFile == true

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)
    private fun append(line: String) = ui { log.add(line.replace(Regex("[0-9a-f]{40,}"), "<配对码>")); while (log.size > 300) log.removeAt(0) }

    fun node(): String? {
        val names = if (windows) listOf("node.exe") else listOf("node")
        val bundled = scripts?.parentFile?.resolve(if (windows) "node/node.exe" else "node/bin/node")
        if (bundled?.canExecute() == true) return bundled.path
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) + if (windows) listOf("C:\\Program Files\\nodejs") else listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        return dirs.flatMap { d -> names.map { File(d, it) } }.firstOrNull { it.canExecute() }?.path
    }
    /** 可供电视连接的局域网地址 */
    fun addresses(): List<String> = runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .mapNotNull { it.hostAddress }.filter { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) && !it.startsWith("198.18.") && !it.startsWith("198.19.") && !it.startsWith("169.254.") } }.getOrDefault(emptyList())
    private fun fakeIp() = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.any { it.hostAddress.orEmpty().let { a -> a.startsWith("198.18.") || a.startsWith("198.19.") } } }.getOrDefault(false)

    /** 查询正在运行的服务；返回是否在运行 */
    fun refresh(): Boolean {
        val hello = runCatching { Net.fetch("$url/v1/hello", timeoutMs = 1500).takeIf { it.ok }?.json() as? JSONObject }.getOrNull()
        if (hello == null) { if (state == State.RUNNING || state == State.EXTERNAL) ui { state = if (owned) State.STARTING else State.STOPPED; pin = null }; return false }
        val token = token()
        val status = token?.let { t -> runCatching { Net.fetch("$url/v1/status", headers = mapOf("Authorization" to "Bearer $t"), timeoutMs = 2000) }.getOrNull() }
        val pairing = token?.let { t -> runCatching { Net.fetch("$url/v1/pairing", headers = mapOf("Authorization" to "Bearer $t"), timeoutMs = 2000).takeIf { it.ok }?.json() as? JSONObject }.getOrNull() }
        val info = status?.takeIf { it.ok }?.let { runCatching { it.json() as JSONObject }.getOrNull() }
        ui {
            state = if (owned) State.RUNNING else State.EXTERNAL
            pin = pairing?.optString("pin")?.takeIf { it.length == 6 }
            processing = info?.optString("processing") ?: processing
            model = info?.optJSONObject("capabilities")?.optString("model").orEmpty()
            message = when {
                message.startsWith("超分工具还没下载") && owned && processing == "relay" -> message
                status == null || !status.ok -> "端口 $PORT 上运行着另一份串流服务（不是本程序启动的），配对码请看它自己的窗口"
                !owned -> "串流服务已在运行（由其他程序启动）"
                else -> ""
            }
        }
        if (token != null && status?.ok == true) onReady(url, token)
        return true
    }

    fun start() {
        if (owned || state == State.STARTING) return
        Pool.ops.execute {
            if (refresh()) return@execute
            val script = scripts?.resolve("start.mjs")
            val node = node()
            if (script == null || !script.isFile) { ui { state = State.FAILED; message = "找不到串流服务的脚本，请重新安装 SuperTV" }; return@execute }
            if (node == null) { ui { state = State.FAILED; message = "没有找到 Node.js。串流服务需要 Node.js 18 以上版本，请先安装（nodejs.org），再点「启动」" }; return@execute }
            home.mkdirs()
            // 选了超分但工具还没下载：先只转发原画跑起来，不让电视连不上
            val effective = if (mode == "enhance" && !toolsReady()) "relay" else mode
            val args = mutableListOf(node, script.path, "--host", "0.0.0.0", "--port", "$PORT", "--processing", effective)
            if (fakeIp()) args.add("--allow-fake-ip")
            args += listOf("--library", libraryDir.absolutePath)
            ui { state = State.STARTING; message = if (effective != mode) "超分工具还没下载，先以「只转发原画」运行；下载完成后会自动切换" else "正在启动串流服务…"; log.clear(); pin = null }
            try {
                val p = ProcessBuilder(args).directory(home).redirectErrorStream(true).apply { environment()["SUPERTV_HOME"] = home.path }.start()
                process = p
                Thread({
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        append(line)
                        Regex("电视配对码：(\\d{3}) ?(\\d{3})").find(line)?.let { m -> ui { pin = m.groupValues[1] + m.groupValues[2] } }
                        if (line.startsWith("LibreTV 电脑串流服务")) Pool.ops.execute { refresh() }
                    }
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    ui {
                        if (process === p) {
                            process = null; pin = null
                            val tail = log.takeLast(6).joinToString("\n")
                            state = if (code == 0 || stopping) State.STOPPED else State.FAILED
                            message = when {
                                stopping -> ""
                                tail.contains("电脑增强环境未就绪") -> "超分工具还没准备好：先点「下载超分工具」，或把处理方式改成「只转发原画」"
                                tail.contains("EADDRINUSE") -> "端口 $PORT 被其他程序占用"
                                else -> "串流服务已退出（代码 $code），详情见下方日志"
                            }
                            stopping = false
                        }
                    }
                }, "supertv-service-log").apply { isDaemon = true }.start()
            } catch (error: Exception) { ui { state = State.FAILED; message = "启动失败：${error.message}" } }
        }
    }
    @Volatile private var stopping = false
    fun stop() {
        val p = process ?: return
        stopping = true
        // 先结束它启动的 FFmpeg 等子进程，再结束服务本身
        runCatching { p.toHandle().descendants().forEach { it.destroy() } }
        p.destroy()
        Pool.ops.execute { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { p.toHandle().descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() } }
        ui { state = State.STOPPED; pin = null; message = "" }
    }
    fun restart() { stop(); Pool.ops.execute { Thread.sleep(1500); ui { start() } } }

    /** 下载 FFmpeg 和超分模型（首次使用超分前需要，约几百 MB） */
    fun setupTools(done: () -> Unit) {
        val script = scripts?.resolve("setup-tools.mjs"); val node = node()
        if (script == null || node == null) { message = "需要先安装 Node.js"; return }
        settingUp = true; append("—— 开始下载超分工具 ——")
        Pool.ops.execute {
            val code = try {
                val p = ProcessBuilder(node, script.path).directory(home).redirectErrorStream(true).apply { environment()["SUPERTV_HOME"] = home.path }.start()
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { append(it) }
                p.waitFor()
            } catch (error: Exception) { append("下载失败：${error.message}"); -1 }
            ui { settingUp = false; message = if (code == 0) "超分工具已就绪" else "超分工具下载失败，详情见日志"; done() }
        }
    }

    companion object {
        const val PORT = 8090
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
