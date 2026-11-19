package org.libretv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.delay
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.json.JSONArray
import org.json.JSONObject
import org.libretv.tv.AdTimeMap
import org.libretv.tv.PlaylistFilter
import org.libretv.tv.core.objects
import org.libretv.tv.core.strings
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer
import javax.swing.SwingUtilities

/** 开发用：离屏渲染脚本控制当前播放器 */
var debugPlayer: PlayerSession? = null

/** libVLC：整个程序只建一次 */
object Vlc {
    val factory: MediaPlayerFactory? by lazy {
        runCatching { if (!NativeDiscovery().discover()) null else MediaPlayerFactory("--no-video-title-show", "--quiet", "--network-caching=2000", "--avcodec-hw=any", "--no-snapshot-preview") }.getOrNull()
    }

    /**
     * 启动后在后台准备好 VLC，免得第一次播放时界面卡住。
     * 随包的 VLC 没有插件缓存（plugins.dat），没有它 VLC 每次都要逐个加载几百个插件 DLL：
     * 安装或更新后第一次运行时用 vlc-cache-gen 生成一次。
     */
    fun warmUp() = Thread({
        val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
        if (dir != null && LocalService.windows) {
            val cache = File(dir, "plugins/plugins.dat"); val gen = File(dir, "vlc-cache-gen.exe"); val core = File(dir, "libvlccore.dll")
            if (gen.isFile && (!cache.isFile || cache.lastModified() < core.lastModified())) runCatching {
                val p = ProcessBuilder(gen.path, File(dir, "plugins").path).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                Startup.mark("VLC 插件缓存${if (cache.isFile) "已生成" else "生成失败"}")
            }
        }
        factory
        Startup.mark("VLC 已就绪")
    }, "supertv-vlc-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
}

/** 一次播放：VLC 解码出的画面逐帧交给界面绘制（控制栏可以叠在画面上）；进度、换集、去广告的换算都在这里。 */
class PlayerSession(private val m: AppModel) {
    var frame by mutableStateOf<ImageBitmap?>(null); private set
    var status by mutableStateOf("正在连接片源…"); private set
    var error by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    /** 最近约 2 秒的平均下载速度（字节/秒），没有数据时为 -1 */
    var speed by mutableStateOf(-1L); private set
    private val samples = ArrayDeque<Pair<Long, Long>>()
    fun sampleSpeed() {
        val session = proxy ?: run { speed = -1; return }
        val now = System.currentTimeMillis(); val bytes = session.received.get()
        if (samples.isNotEmpty() && samples.last().second > bytes) samples.clear()  // 换了一集
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first().first > 2000) samples.removeFirst()
        val (t0, b0) = samples.first()
        speed = if (now - t0 < 400) -1 else (bytes - b0) * 1000 / (now - t0)
    }
    var time by mutableStateOf(0L); private set
    var length by mutableStateOf(0L); private set
    var active by mutableStateOf<JSONObject?>(null); private set
    var item by mutableStateOf(JSONObject()); private set
    var index by mutableStateOf(0); private set
    var height by mutableStateOf(0); private set
    var rate by mutableStateOf(1f); private set
    var adText by mutableStateOf(""); private set
    private var proxy: PlayProxy.Session? = null
    private var openingId: String? = null
    private var lastSaved = 0L
    private var pendingSeek: Long? = null
    private var recoveries = 0
    private val debug = System.getenv("SUPERTV_DEBUG") != null
    private var qualitySent = false
    private var endedFor = ""
    private val player: EmbeddedMediaPlayer? = Vlc.factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
    // 画面缓冲：VLC 线程写入，界面线程取走；来不及画的帧直接丢掉
    private var width = 0; private var heightPx = 0; private var bytes = ByteArray(0)
    private val lock = Any(); @Volatile private var pendingFrame = false
    private var previous: org.jetbrains.skia.Image? = null; private var current: org.jetbrains.skia.Image? = null

    init {
        val p = player
        if (p == null) { status = "没有找到播放内核（VLC），请重新安装 SuperTV"; error = true }
        else {
            p.videoSurface().set(Vlc.factory!!.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    // 超过 1920 宽的画面由 VLC 先缩小，减少逐帧拷贝的数据量
                    val scale = minOf(1.0, 1920.0 / sourceWidth.coerceAtLeast(1)); val w = (sourceWidth * scale).toInt().coerceAtLeast(2) and 1.inv(); val h = (sourceHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
                    synchronized(lock) { width = w; heightPx = h; bytes = ByteArray(w * h * 4) }
                    SwingUtilities.invokeLater { height = sourceHeight }
                    return RV32BufferFormat(w, h)
                }
                override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
            }, RenderCallback { _: MediaPlayer, buffers: Array<ByteBuffer>, _: BufferFormat ->
                synchronized(lock) { val b = buffers[0]; b.rewind(); if (b.remaining() >= bytes.size) b.get(bytes, 0, bytes.size) }
                if (!pendingFrame) { pendingFrame = true; SwingUtilities.invokeLater { present() } }
            }, true))
            p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = ui { playing = true; buffering = false; status = ""; pendingSeek?.let { mediaPlayer.controls().setTime(it); pendingSeek = null } }
                override fun paused(mediaPlayer: MediaPlayer) = ui { playing = false }
                override fun stopped(mediaPlayer: MediaPlayer) = ui { playing = false }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { buffering = newCache < 100f }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ui { if (debug && newTime / 5000 != time / 5000) System.err.println("时间 %.1f".format(newTime / 1000.0)); time = newTime; tick() }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { length = newLength }
                override fun finished(mediaPlayer: MediaPlayer) = ui { finished() }
                override fun error(mediaPlayer: MediaPlayer) = ui { status = "无法播放这个片源，换一个试试"; error = true; playing = false }
            })
        }
    }
    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)
    private fun present() {
        val image = synchronized(lock) { pendingFrame = false; if (width == 0) return; org.jetbrains.skia.Image.makeRaster(ImageInfo.makeN32(width, heightPx, ColorAlphaType.OPAQUE), bytes, width * 4) }
        // 上上一帧已经不在画面上了，立即释放原生内存
        previous?.close(); previous = current; current = image
        frame = image.toComposeImageBitmap()
    }

    fun open(target: JSONObject, episode: Int, position: Double? = null, fromLibrary: Boolean = false) {
        val p = player ?: return
        // 换了一集或换了片源：断流重连的次数重新计算
        if (target.optString("vodId") != item.optString("vodId") || episode != index) recoveries = 0
        save(); m.backend.cancel(openingId)
        item = target; index = episode; status = "正在连接${target.optString("sourceName").ifBlank { "片源" }}…"; error = false; qualitySent = false; adText = ""
        val args = JSONObject().put("item", target).put("index", episode).put("fromLibrary", fromLibrary)
        if (position != null) args.put("position", position)
        openingId = m.request("open-playback", args) { event ->
            when (event.optString("event")) {
                "processing-progress" -> event.optJSONObject("value")?.optString("message")?.takeIf { it.isNotBlank() }?.let { status = it }
                "error" -> { status = event.optString("error").ifBlank { "打不开这个片源" }; error = true }
                "result" -> {
                    val d = event.optJSONObject("value") ?: return@request
                    active = d; item = d.optJSONObject("item") ?: target; index = d.optInt("index"); time = 0; length = 0; endedFor = ""
                    val playback = d.optJSONObject("playback") ?: JSONObject().put("url", d.getJSONObject("detail").getJSONArray("episodes").getString(index))
                    val headers = playback.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } } ?: emptyMap()
                    val enhanced = playback.optString("processing") == "enhance"
                    val adFilter = m.config.optBoolean("adFilter", true) && !enhanced
                    val playlists = playback.optJSONArray("playlists").objects().associate { it.optString("url") to it.optString("text") }
                    PlayProxy.close(proxy)
                    val session = PlayProxy.open(headers, adFilter, playback.optJSONArray("adSegments").strings().toSet(), playlists); proxy = session
                    val url = playback.getString("url"); val original = d.optDouble("position", 0.0)
                    status = "正在缓冲…"
                    org.libretv.tv.core.Pool.ops.execute {
                        val removals = PlayProxy.prepare(session, url)
                        if (System.getenv("SUPERTV_DEBUG") != null) System.err.println("地址：$url\n广告段：" + removals.joinToString { "%.1f+%.1f".format(it.start, it.duration) })
                        val start = (AdTimeMap.clean(original, removals) * 1000).toLong()
                        ui {
                            if (proxy !== session) return@ui
                            pendingSeek = if (start > 3000) start else null
                            p.media().play(PlayProxy.url(session, url), ":network-caching=2000")
                            p.controls().setRate(rate)
                            adText = if (!adFilter) "" else if (removals.isEmpty()) "未发现插播广告" else "已去掉 ${removals.size} 段插播广告 · ${Math.round(removals.sumOf { it.duration })} 秒"
                        }
                    }
                }
            }
        }
    }
    private fun removals(): List<PlaylistFilter.Removal> = proxy?.removals ?: emptyList()
    /** 原片时间（播放器里的时间去掉了广告） */
    fun originalSeconds() = AdTimeMap.original(time / 1000.0, removals())
    private fun tick() {
        val a = active ?: return
        if (time > 0 && length > 0 && System.currentTimeMillis() - lastSaved > 10_000) save()
        if (!qualitySent && height > 0) {
            qualitySent = true; val it = a.getJSONObject("item")
            m.request("played-quality", JSONObject().put("sourceKey", it.optString("sourceKey")).put("vodId", it.optString("vodId")).put("height", height))
        }
    }
    fun save() {
        val a = active ?: return
        if (length <= 0 || time <= 0) return
        val it = a.getJSONObject("item")
        m.request("save-progress", JSONObject().put("sourceKey", it.optString("sourceKey")).put("vodId", it.optString("vodId")).put("index", a.optInt("index"))
            .put("position", time / 1000.0).put("duration", length / 1000.0).put("timeMap", JSONArray(removals().map { r -> JSONObject().put("start", r.start).put("duration", r.duration) })))
        lastSaved = System.currentTimeMillis()
    }
    val episodes get() = active?.optJSONObject("detail")?.optJSONArray("episodes")?.length() ?: 0
    private fun finished() {
        val a = active ?: return
        if (debug) System.err.println("播放结束事件：时间 %.1f / 时长 %.1f，第 %d 集".format(time / 1000.0, length / 1000.0, index + 1))
        // 只有播到片尾附近才算看完。离结尾还远（或连时长都没拿到）就「结束」了，是片源断流或播不了：
        // 从当前位置重新连接，几次都不行就停下来让用户换源，绝不自动跳到下一集
        val nearEnd = length > 0 && time >= length - 30_000
        if (!nearEnd) {
            if (recoveries < 3) { recoveries++; status = "播放中断，正在重新连接…"; open(a.getJSONObject("item"), index, time / 1000.0) }
            else { status = "这个片源一直播放中断，换一个试试"; error = true; playing = false }
            return
        }
        if (endedFor == "${item.optString("vodId")}#$index") return
        endedFor = "${item.optString("vodId")}#$index"; save()
        if (m.config.optBoolean("autoplayNext", true) && index + 1 < episodes) open(a.getJSONObject("item"), index + 1, 0.0)
    }
    fun toggle() { player?.controls()?.pause() }
    /** 开发用：广告段的开始时间（原片时间） */
    fun adStart(first: Boolean): Double? = (if (first) removals().firstOrNull() else removals().lastOrNull())?.let { AdTimeMap.clean(it.start, removals()) }
    fun seekSeconds(seconds: Double) {
        if (debug) System.err.println("跳转到 %.1f（当前 %.1f，可跳转=%s）".format(seconds, time / 1000.0, player?.status()?.isSeekable))
        player?.controls()?.setTime((seconds * 1000).toLong())
    }
    fun seekBy(seconds: Int) { val p = player ?: return; if (length > 0) p.controls().setTime((time + seconds * 1000L).coerceIn(0, length - 1000)) }
    fun seekTo(fraction: Float) { val p = player ?: return; if (length > 0) p.controls().setTime((length * fraction).toLong().coerceIn(0, length - 1000)) }
    fun changeRate(value: Float) { rate = value; player?.controls()?.setRate(value) }
    fun episode(delta: Int) { val a = active ?: return; val next = index + delta; if (next in 0 until episodes) open(a.getJSONObject("item"), next, 0.0) }
    fun release() {
        save(); m.backend.cancel(openingId); PlayProxy.close(proxy)
        player?.let { p -> runCatching { p.controls().stop(); p.release() } }
        org.libretv.tv.core.Pool.timer.schedule({ ui { previous?.close(); current?.close() } }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        m.refreshHome()
    }
}

private val hiddenCursor = PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "none"))

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(screen: Screen.Player) {
    val m = LocalModel.current; val window = LocalWindow.current; val chrome = LocalChrome.current; val closeWindow = LocalClose.current
    val s = remember { PlayerSession(m) }
    DisposableEffect(s) { debugPlayer = s; onDispose { if (debugPlayer === s) debugPlayer = null } }
    var controls by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf("") }   // episodes / sources / rate
    var fill by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(screen) { s.open(screen.item, screen.index, screen.position, screen.fromLibrary) }
    // 全屏：Windows 上由程序自己铺满屏幕（系统全屏切回后界面尺寸会错乱），macOS 用系统全屏
    val isFullscreen = if (chrome.custom) chrome.fullscreen else window.placement == WindowPlacement.Fullscreen
    fun setFullscreen(on: Boolean) { if (chrome.custom) chrome.changeFullscreen(on) else window.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating }
    DisposableEffect(Unit) { onDispose { s.release(); setFullscreen(false) } }
    LaunchedEffect(Unit) { focus.requestFocus(); while (true) { delay(500); if (System.currentTimeMillis() - lastMove > 3000 && s.playing && panel.isEmpty() && dragging == null) controls = false } }
    fun wake() { lastMove = System.currentTimeMillis(); controls = true }
    fun exit() { if (isFullscreen) setFullscreen(false) else m.back() }
    fun fullscreen() = setFullscreen(!isFullscreen)

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
        .onPointerEvent(PointerEventType.Move) { wake() }
        .pointerHoverIcon(if (controls) PointerIcon.Default else hiddenCursor)
        .pointerInput(Unit) { detectTapGestures(onTap = { if (panel.isNotEmpty()) panel = "" else { s.toggle(); wake() } }, onDoubleTap = { fullscreen() }) }
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            wake()
            when (e.key) {
                Key.Spacebar -> s.toggle(); Key.DirectionLeft -> s.seekBy(-10); Key.DirectionRight -> s.seekBy(10)
                Key.F -> fullscreen(); Key.Escape -> if (panel.isNotEmpty()) panel = "" else exit()
                Key.N -> s.episode(1); Key.P -> s.episode(-1)
                else -> return@onKeyEvent false
            }
            true
        }) {
        // 画面：完整显示（留黑边）或铺满裁剪
        s.frame?.let { image ->
            Canvas(Modifier.fillMaxSize()) {
                val iw = image.width.toFloat(); val ih = image.height.toFloat()
                val scale = if (fill) maxOf(size.width / iw, size.height / ih) else minOf(size.width / iw, size.height / ih)
                val w = iw * scale; val h = ih * scale
                drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
            }
        }
        val waiting = s.status.isNotEmpty() || s.buffering
        LaunchedEffect(waiting) { while (waiting) { s.sampleSpeed(); kotlinx.coroutines.delay(500) } }
        if (waiting) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            T(if (s.status.isNotEmpty()) s.status else "正在缓冲…", 16.sp, C.muted)
            if (!s.error && s.speed >= 0) { Spacer(Modifier.height(8.dp)); T(speedText(s.speed), 13.sp, C.muted) }
            if (s.error) { Spacer(Modifier.height(16.dp)); Pill("换源", primary = true) { panel = "sources" } }
        }
        AnimatedVisibility(controls || panel.isNotEmpty() || !s.playing, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0xB0000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC8000000)))))
                Row(Modifier.fillMaxWidth().windowDrag(chrome).padding(start = 20.dp, end = if (chrome.custom && !isFullscreen) 0.dp else 20.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Icon.BACK, 44.dp) { exit() }; Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        T(s.item.optString("name") + if (s.episodes > 1) " · 第 ${s.index + 1} 集" else "", 18.sp, weight = FontWeight.SemiBold, lines = 1)
                        T(listOf(s.item.optString("sourceName"), if (s.height > 0) quality(s.height) else "", s.adText).filter { it.isNotBlank() }.joinToString(" · "), 12.sp, C.muted, lines = 1)
                    }
                    Box(Modifier.align(Alignment.Top).padding(top = 0.dp)) { WindowButtons(chrome, 40.dp, closeWindow) }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(clock((dragging?.let { it * s.length } ?: s.time.toFloat()) / 1000.0), 13.sp, weight = FontWeight.Medium)
                        Spacer(Modifier.width(14.dp))
                        Seek(s.length, s.time, Modifier.weight(1f), { dragging = it }) { f -> dragging = null; s.seekTo(f) }
                        Spacer(Modifier.width(14.dp))
                        T(clock(s.length / 1000.0), 13.sp, C.muted)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Icon.REWIND, 44.dp) { s.seekBy(-10) }
                        IconButton(if (s.playing) Icon.PAUSE else Icon.PLAY, 52.dp, filled = true) { s.toggle() }
                        IconButton(Icon.FORWARD, 44.dp) { s.seekBy(10) }
                        if (s.episodes > 1) { Spacer(Modifier.width(8.dp)); IconButton(Icon.PREV, 40.dp) { s.episode(-1) }; IconButton(Icon.NEXT, 40.dp) { s.episode(1) } }
                        Spacer(Modifier.weight(1f))
                        if (s.episodes > 1) { Chip("选集", panel == "episodes") { panel = if (panel == "episodes") "" else "episodes" }; Spacer(Modifier.width(8.dp)) }
                        Chip("换源", panel == "sources") { panel = if (panel == "sources") "" else "sources" }; Spacer(Modifier.width(8.dp))
                        Chip("倍速 ${trim(s.rate)}×", panel == "rate") { panel = if (panel == "rate") "" else "rate" }; Spacer(Modifier.width(8.dp))
                        Chip(if (fill) "完整画面" else "铺满") { fill = !fill }; Spacer(Modifier.width(4.dp))
                        IconButton(if (isFullscreen) Icon.SHRINK else Icon.FULL, 44.dp) { fullscreen() }
                    }
                }
            }
        }
        if (panel.isNotEmpty()) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp).background(Color(0xF01C1C1E)).pointerInput(Unit) { detectTapGestures { } }) {
            when (panel) {
                "episodes" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
                    T("选集 · 共 ${s.episodes} 集", 17.sp, weight = FontWeight.Bold); Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { for (n in 0 until s.episodes) Chip("${n + 1}", n == s.index, minWidth = 56.dp) { panel = ""; s.open(s.item, n, 0.0) } }
                }
                "rate" -> Column(Modifier.padding(22.dp)) {
                    T("播放速度", 17.sp, weight = FontWeight.Bold); Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r -> Chip("${trim(r)}×", r == s.rate) { s.changeRate(r); panel = "" } } }
                }
                else -> SourcePanel(s) { panel = "" }
            }
        }
    }
}

private fun trim(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}.0" else v.toString()
private fun quality(h: Int) = when { h >= 2000 -> "4K"; h >= 1400 -> "2K"; h >= 1000 -> "1080P"; h >= 700 -> "720P"; h >= 460 -> "480P"; else -> "标清" }

/** 进度条：点击或拖动跳转 */
@Composable
private fun Seek(length: Long, time: Long, modifier: Modifier, onDrag: (Float) -> Unit, onSeek: (Float) -> Unit) {
    var width by remember { mutableStateOf(1f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val f = drag ?: if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f
    Canvas(modifier.height(22.dp).pointerHoverIcon(PointerIcon.Hand)
        .pointerInput(length) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(length) { detectHorizontalDragGestures(onDragEnd = { drag?.let(onSeek); drag = null }, onDragCancel = { drag = null }) { change: androidx.compose.ui.input.pointer.PointerInputChange, _: Float -> drag = (change.position.x / size.width).coerceIn(0f, 1f); onDrag(drag!!) } }) {
        width = size.width; val y = size.height / 2
        drawRoundRect(Color(0x55FFFFFF), Offset(0f, y - 2.dp.toPx()), Size(size.width, 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawRoundRect(Color.White, Offset(0f, y - 2.dp.toPx()), Size(size.width * f, 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), Offset(size.width * f, y))
    }
}

/** 换源：同一部影片在其他片源里的版本，附实测清晰度和响应时间 */
@Composable
private fun SourcePanel(s: PlayerSession, close: () -> Unit) {
    val m = LocalModel.current
    val rows = remember { mutableStateListOf<JSONObject>() }
    val details = remember { mutableStateOf(mapOf<String, String>()) }
    var status by remember { mutableStateOf("正在查找其他片源…") }
    fun key(i: JSONObject) = "${i.optString("sourceKey")}|${i.optString("vodId")}"
    DisposableEffect(Unit) {
        val id = m.request("switch-sources", JSONObject().put("title", s.item.optString("name")).put("refresh", false)) { event ->
            when (event.optString("event")) {
                "candidates" -> event.optJSONArray("value").objects().mapNotNull { it.optJSONObject("item") }.forEach { c -> if (rows.none { key(it) == key(c) }) rows.add(c) }
                "source-result", "quality-result" -> event.optJSONObject("value")?.let { v ->
                    val c = v.optJSONObject("item") ?: return@let
                    if (rows.none { key(it) == key(c) }) rows.add(c)
                    val text = if (v.has("ok") && !v.optBoolean("ok")) "不可用：${v.optString("error").ifBlank { "没有响应" }}" else listOf(v.optString("quality"), v.optInt("episodes").takeIf { it > 0 }?.let { "$it 集" }.orEmpty(), v.optInt("ms").takeIf { it > 0 }?.let { "$it 毫秒" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    if (text.isNotBlank()) details.value = details.value + (key(c) to text)
                }
                "sources-tested" -> status = "找到 ${rows.size} 个片源，正在检测清晰度…"
                "error" -> status = event.optString("error")
                "result" -> status = if (rows.isEmpty()) "没有找到其他片源" else "共 ${rows.size} 个片源，点一个切换"
            }
        }
        onDispose { m.backend.cancel(id) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        T("换源", 17.sp, weight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); T(status, 12.sp, C.muted); Spacer(Modifier.height(12.dp))
        rows.forEach { c ->
            val current = key(c) == key(s.item)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(if (current) C.hover else C.raised).press { close(); s.open(c, s.index, if (s.active != null) s.originalSeconds() else null) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                T(c.optString("sourceName") + if (current) "（当前）" else "", 14.sp, weight = FontWeight.SemiBold, lines = 1)
                T(details.value[key(c)] ?: c.optString("remarks").ifBlank { "正在检测…" }, 12.sp, C.muted, lines = 1)
            }
        }
    }
}

/** 下载速度：1.2 MB/s、640 KB/s */
fun speedText(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1048576.0)
    else -> "${bytesPerSecond / 1024} KB/s"
}
