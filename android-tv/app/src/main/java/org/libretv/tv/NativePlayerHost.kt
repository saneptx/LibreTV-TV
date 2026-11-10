package org.libretv.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.ArrayDeque

/** 原生视频在透明 WebView 下方；桥只对配置的服务 origin 的主框架开放。 */
@UnstableApi
internal class NativePlayerHost(private val activity: Activity, private val web: WebView, server: String, private val onState: ((JSONObject) -> Unit)? = null) {
    val container = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
    private val texture = TextureView(activity).apply { isFocusable = false; visibility = View.INVISIBLE }
    private val origin = Uri.parse(server).let { "${it.scheme}://${it.authority}" }
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var id = ""
    private var request = JSONObject()
    private var adFilter = true
    private var proxy = false
    private var rendered = false
    private var errorText = ""
    private var fit = "contain"
    private var resumeAfterPause = false
    private var videoSize = VideoSize.UNKNOWN
    private var videoDecoder = ""
    // 数据页识别出的额外广告分片行；timeMap 为原片时间轴上实际删除的区间，进度换算以它为准。
    private var adSegments = emptySet<String>()
    // 分析时读到的媒体列表原文；盗链站按请求插入广告，必须播放同一份列表删除行才对得上。
    private var playlists = emptyMap<String, String>()
    private var timeMap = emptyList<PlaylistFilter.Removal>()
    private var pendingOriginal: Double? = null
    // 多来源比对得到的夹带广告区间（原片时间），播放到时直接跳过；电脑增强时用服务端时间轴换算。
    private var skips = emptyList<PlaylistFilter.Removal>()
    private var serverTimeMap: List<PlaylistFilter.Removal>? = null
    private var skipped = 0
    private val skipCheck = Runnable { checkSkip() }
    private var generation = 0
    private var meter = ByteMeter()
    private val tick = object : Runnable {
        override fun run() { if (player != null) { emit(); handler.postDelayed(this, 500) } }
    }

    init {
        container.addView(texture, FrameLayout.LayoutParams(-1, -1))
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "LibreTvNativePlayer", setOf(origin)) { _, message, source, mainFrame, _ ->
                if (mainFrame && sameOrigin(source)) {
                    val body = message.data ?: return@addWebMessageListener
                    web.post { runCatching { command(JSONObject(body)) }.onFailure { Log.w(TAG, "Invalid player command", it) } }
                }
            }
        }
    }

    private fun sameOrigin(uri: Uri) = Uri.parse(origin).let {
        uri.scheme == it.scheme && uri.host == it.host && uri.port == it.port
    }
    private fun http(value: String): Uri {
        val uri = Uri.parse(value)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty())
        return uri
    }
    fun command(body: JSONObject) {
        if (body.optInt("version") != 1) return
        val session = body.optString("sessionId")
        if (session.isEmpty() || session.length > 128) return
        val type = body.optString("type")
        if (type == "load") {
            val next = body.getJSONObject("request")
            http(next.getString("url"))
            val fallback = next.optString("proxyUrl")
            if (fallback.isNotEmpty()) require(sameOrigin(http(fallback)) && http(fallback).path?.startsWith("/api/proxy") == true)
            stop()
            id = session; request = next; adFilter = next.optBoolean("adFilter", true)
            meter = ByteMeter(); proxy = false; rendered = false; errorText = ""; videoDecoder = ""
            adSegments = next.optJSONArray("adSegments")?.let { list -> (0 until minOf(list.length(), 400)).map { list.optString(it).trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toSet() } ?: emptySet()
            playlists = next.optJSONArray("playlists")?.let { list -> (0 until minOf(list.length(), 3)).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("text").startsWith("#EXTM3U") && it.optString("url").startsWith("http") }.associate { it.getString("url") to it.getString("text") } } ?: emptyMap()
            skips = emptyList(); skipped = 0
            serverTimeMap = next.optJSONArray("timeMap")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { PlaylistFilter.Removal(it.optDouble("start"), it.optDouble("duration")) } }
            timeMap = emptyList(); pendingOriginal = next.optDouble("position", 0.0).takeIf { it.isFinite() && it > 0 }
            val renderers = DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
            val builder = ExoPlayer.Builder(activity, renderers)
            if (next.optString("processing") == "enhance") {
                builder.setLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000,45000,4000,8000)
                    .setTargetBufferBytes(64*1024*1024)
                    .setPrioritizeTimeOverSizeThresholds(true).build())
            }
            val exo = builder.build()
            player = exo
            exo.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    if (player === exo) videoDecoder = decoderName
                }
            })
            exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            exo.setHandleAudioBecomingNoisy(true)
            exo.setVideoTextureView(texture)
            exo.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { if (player === this@NativePlayerHost.player) emit() }
                override fun onRenderedFirstFrame() { if (player === exo) { rendered = true; emit() } }
                override fun onVideoSizeChanged(size: VideoSize) { if (player === exo) { videoSize = size; resize(); emit() } }
                override fun onTracksChanged(tracks: Tracks) {
                    if (player !== exo) return
                    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    if (video.isNotEmpty() && video.none { it.isSupported }) {
                        errorText = "设备暂不支持此视频编码，请尝试换源"
                        exo.pause(); emit()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (player !== exo) return
                    Log.w(TAG, "Playback failed: ${error.errorCodeName}", error)
                    val fallbackUrl = request.optString("proxyUrl")
                    if (!proxy && fallbackUrl.isNotEmpty() && error.errorCode in 2000..2999) {
                        proxy = true
                        prepare(fallbackUrl, exo.currentPosition.coerceAtLeast(0), exo.playWhenReady)
                    } else {
                        errorText = "无法播放此片源，请尝试换源"
                        emit()
                    }
                }
            })
            texture.visibility = View.VISIBLE
            prepare(next.getString("url"), (next.optDouble("position", 0.0).coerceAtLeast(0.0) * 1000).toLong(), true)
            handler.post(tick)
            return
        }
        if (session != id) return
        val exo = player ?: return
        when (type) {
            "stop" -> stop()
            "play" -> exo.play()
            "pause" -> exo.pause()
            "seek" -> {
                val time = body.optDouble("position", 0.0)
                if (time.isFinite()) exo.seekTo((time * 1000).toLong().coerceIn(0, if (exo.duration > 0) exo.duration - 1 else Long.MAX_VALUE))
            }
            "rate" -> {
                val rate = body.optDouble("rate", 1.0)
                if (rate.isFinite()) exo.setPlaybackSpeed(rate.toFloat().coerceIn(.25f, 3f))
            }
            "fit" -> { fit = if (body.optString("fit") == "cover") "cover" else "contain"; resize() }
            "skip" -> {
                val list = body.optJSONArray("intervals")
                skips = (0 until minOf(list?.length() ?: 0, 100)).mapNotNull { list?.optJSONObject(it) }
                    .map { PlaylistFilter.Removal(it.optDouble("start"), it.optDouble("duration")) }
                    .filter { it.start.isFinite() && it.duration.isFinite() && it.start >= 0 && it.duration > 0 }.sortedBy { it.start }
                checkSkip()
            }
            "filter" -> {
                val enabled = body.optBoolean("enabled", true)
                if (adFilter != enabled) {
                    // 换算回原片时间再重建；新列表解析后按新的删除区间定位。
                    val original = AdTimeMap.original(exo.currentPosition / 1000.0, timeMap)
                    adFilter = enabled; pendingOriginal = original
                    prepare(if (proxy) request.getString("proxyUrl") else request.getString("url"), (original * 1000).toLong(), exo.playWhenReady)
                }
            }
        }
        emit()
    }

    private fun prepare(url: String, position: Long, autoplay: Boolean) {
        val exo = player ?: return
        errorText = ""; rendered = false
        val token = CookieManager.getInstance().getCookie(origin).orEmpty()
        val rawHeaders = request.optJSONObject("headers") ?: JSONObject()
        val headers = rawHeaders.keys().asSequence().filter { !it.equals("cookie", true) && !it.equals("host", true) }
            .associateWith { rawHeaders.optString(it) }
        val httpFactory = DefaultHttpDataSource.Factory().setUserAgent("LibreTV-AndroidTV/0.2")
            .setConnectTimeoutMs(10000).setReadTimeoutMs(if(request.optString("processing")=="enhance") 120000 else 15000).setAllowCrossProtocolRedirects(true).setTransferListener(meter)
        val resolving = ResolvingDataSource.Factory(httpFactory) { spec ->
            val extra = if (sameOrigin(spec.uri) && spec.uri.path?.startsWith("/api/proxy") == true) {
                if (token.isEmpty()) emptyMap() else mapOf("Cookie" to token)
            } else headers
            spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + extra).build()
        }
        val uri = http(url)
        val item = MediaItem.Builder().setMediaId(id).setUri(uri)
        val path = if (uri.path?.startsWith("/api/proxy") == true) uri.getQueryParameter("url").orEmpty() else url
        val isHls = Uri.parse(path).path?.endsWith(".m3u8", true) == true || !Uri.parse(path).path.orEmpty().matches(Regex(".*\\.(mp4|mkv|webm|mov)$", RegexOption.IGNORE_CASE))
        val mediaSource = if (isHls) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
            val token = ++generation
            val parsers = FilterParserFactory(adFilter, adSegments, playlists) { removals -> handler.post { if (token == generation && player === exo) applyTimeMap(removals) } }
            HlsMediaSource.Factory(resolving).setPlaylistParserFactory(parsers).createMediaSource(item.build())
        } else DefaultMediaSourceFactory(resolving).createMediaSource(item.build())
        exo.setMediaSource(mediaSource, position.coerceAtLeast(0))
        exo.prepare(); exo.playWhenReady = autoplay
    }

    private fun applyTimeMap(removals: List<PlaylistFilter.Removal>) {
        timeMap = removals
        val original = pendingOriginal ?: return
        pendingOriginal = null
        val cleaned = AdTimeMap.clean(original, timeMap)
        if (kotlin.math.abs(cleaned - original) > .25) player?.seekTo((cleaned * 1000).toLong())
    }

    private fun activeMap() = serverTimeMap ?: timeMap
    /** 进入夹带广告区间即跳到区间末尾；临近区间时按剩余时间精确排程，不依赖 500 ms 的状态节拍 */
    private fun checkSkip() {
        handler.removeCallbacks(skipCheck)
        val exo = player ?: return
        if (skips.isEmpty()) return
        val map = activeMap()
        val position = exo.currentPosition / 1000.0
        val original = AdTimeMap.original(position, map)
        val hit = skips.firstOrNull { original >= it.start - .05 && original < it.start + it.duration - .3 }
        if (hit != null && exo.playWhenReady) {
            val target = AdTimeMap.clean(hit.start + hit.duration, map)
            if (target > position + .3) { exo.seekTo((target * 1000).toLong()); skipped++ }
            return
        }
        val next = skips.firstOrNull { it.start > original } ?: return
        val wait = (AdTimeMap.clean(next.start, map) - position) / exo.playbackParameters.speed
        if (wait in 0.0..1.0 && exo.isPlaying) handler.postDelayed(skipCheck, (wait * 1000).toLong().coerceAtLeast(10))
    }

    private fun resize() {
        if (texture.width == 0 || texture.height == 0 || videoSize.height == 0 || videoSize.width == 0) return
        val viewRatio = texture.width.toFloat() / texture.height
        val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        val sx: Float; val sy: Float
        if (fit == "cover") {
            sx = maxOf(1f, ratio / viewRatio); sy = maxOf(1f, viewRatio / ratio)
        } else {
            sx = minOf(1f, ratio / viewRatio); sy = minOf(1f, viewRatio / ratio)
        }
        texture.setTransform(Matrix().apply { setScale(sx, sy, texture.width / 2f, texture.height / 2f) })
    }

    private fun emit() {
        val exo = player ?: return
        checkSkip()
        val counters = exo.videoDecoderCounters
        counters?.ensureUpdated()
        texture.keepScreenOn = exo.playWhenReady
        val body = JSONObject().put("sessionId", id).put("position", exo.currentPosition.coerceAtLeast(0) / 1000.0)
            .put("duration", exo.duration.coerceAtLeast(0) / 1000.0)
            .put("bufferedPosition", exo.bufferedPosition.coerceAtLeast(0) / 1000.0)
            .put("bufferedDuration", exo.totalBufferedDuration.coerceAtLeast(0) / 1000.0)
            .put("paused", !exo.playWhenReady).put("buffering", exo.playbackState == Player.STATE_BUFFERING)
            .put("ended", exo.playbackState == Player.STATE_ENDED).put("ready", exo.playbackState == Player.STATE_READY)
            .put("rate", exo.playbackParameters.speed.toDouble()).put("height", videoSize.height)
            .put("bytesPerSecond", meter.speed()).put("error", errorText).put("rendered", rendered)
            .put("route", if (request.optString("delivery") == "desktop") "desktop" else if (exo.currentMediaItem?.localConfiguration?.uri?.let { sameOrigin(it) && it.path?.startsWith("/api/proxy") == true } == true) "proxy" else "direct")
            .put("width", videoSize.width).put("fit", fit)
            .put("videoDecoder",videoDecoder).put("droppedFrames",counters?.droppedBufferCount ?: 0)
            .put("renderedFrames",counters?.renderedOutputBufferCount ?: 0)
            .put("videoBitrate",exo.videoFormat?.bitrate ?: -1).put("timeMap", JSONArray().apply { timeMap.forEach { put(JSONObject().put("start", it.start).put("duration", it.duration)) } })
            .put("skips", skips.size).put("skipped", skipped)
        if (onState != null) {
            onState.invoke(body)
            if (BuildConfig.DEBUG) web.evaluateJavascript("window.__libreTvNativePlayerState=$body;", null)
        } else web.evaluateJavascript("window.dispatchEvent(new CustomEvent('libretv-native-player-state',{detail:$body}));", null)
    }
    fun pauseForLifecycle() { resumeAfterPause = player?.playWhenReady == true; player?.pause(); texture.keepScreenOn = false }
    fun resumeForLifecycle() { if (resumeAfterPause) player?.play(); resumeAfterPause = false }
    fun stop() {
        handler.removeCallbacks(tick); handler.removeCallbacks(skipCheck)
        // release() may synchronously invoke listeners, including timeout errors.
        // Retire the instance first so its callbacks cannot revive a stopped session.
        val previous = player
        player = null; id = ""; resumeAfterPause = false; generation++; timeMap = emptyList(); pendingOriginal = null; skips = emptyList(); serverTimeMap = null
        previous?.release()
        texture.visibility = View.INVISIBLE; texture.keepScreenOn = false; videoSize = VideoSize.UNKNOWN; fit = "contain"; videoDecoder = ""
        if(onState!=null && BuildConfig.DEBUG) web.evaluateJavascript("window.__libreTvNativePlayerState=null;",null)
    }
    fun release() {
        stop()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "LibreTvNativePlayer")
    }

    private class FilterParserFactory(private val enabled: Boolean, private val flagged: Set<String>, private val analyzed: Map<String, String>, private val onMedia: (List<PlaylistFilter.Removal>) -> Unit) : HlsPlaylistParserFactory {
        private val base = DefaultHlsPlaylistParserFactory()
        private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            val downloaded = input.bufferedReader().readText()
            val text = if (enabled) analyzed[uri.toString()] ?: downloaded else downloaded
            // 主列表不含分片，不能用它的空结果覆盖媒体列表的删除区间。
            val media = text.contains("#EXTINF")
            if (!enabled) { if (media) onMedia(emptyList()); parser.parse(uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }
            else {
                val result = PlaylistFilter.filter(text, flagged)
                if (media) onMedia(result.removals)
                parser.parse(uri, ByteArrayInputStream(result.text.toByteArray(Charsets.UTF_8)))
            }
        }
        override fun createPlaylistParser() = wrap(base.createPlaylistParser())
        override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?) =
            wrap(base.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))
    }
    private class ByteMeter : TransferListener {
        private val samples = ArrayDeque<Pair<Long, Int>>()
        private val start = SystemClock.elapsedRealtime()
        @Synchronized override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, bytes: Int) {
            if (network && bytes > 0) { samples.addLast(SystemClock.elapsedRealtime() to bytes); prune() }
        }
        private fun prune() { val cutoff = SystemClock.elapsedRealtime() - 2000; while (samples.isNotEmpty() && samples.first.first <= cutoff) samples.removeFirst() }
        @Synchronized fun speed(): Double { prune(); return samples.sumOf { it.second.toLong() } * 1000.0 / (SystemClock.elapsedRealtime() - start).coerceIn(250, 2000) }
        override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) {}
        override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) {}
        override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) {}
    }
    companion object { private const val TAG = "LibreTVNativePlayer" }
}
