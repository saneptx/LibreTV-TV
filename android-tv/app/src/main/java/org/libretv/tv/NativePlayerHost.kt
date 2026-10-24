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
import androidx.media3.exoplayer.ExoPlayer
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
            meter = ByteMeter(); proxy = false; rendered = false; errorText = ""
            val renderers = DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
            val exo = ExoPlayer.Builder(activity, renderers).build()
            player = exo
            exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            exo.setHandleAudioBecomingNoisy(true)
            exo.setVideoTextureView(texture)
            exo.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { emit() }
                override fun onRenderedFirstFrame() { rendered = true; emit() }
                override fun onVideoSizeChanged(size: VideoSize) { videoSize = size; resize(); emit() }
                override fun onTracksChanged(tracks: Tracks) {
                    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    if (video.isNotEmpty() && video.none { it.isSupported }) {
                        errorText = "设备暂不支持此视频编码，请尝试换源"
                        exo.pause(); emit()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
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
            "filter" -> {
                val enabled = body.optBoolean("enabled", true)
                if (adFilter != enabled) {
                    adFilter = enabled
                    prepare(if (proxy) request.getString("proxyUrl") else request.getString("url"), exo.currentPosition, exo.playWhenReady)
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
            HlsMediaSource.Factory(resolving).setPlaylistParserFactory(FilterParserFactory(adFilter)).createMediaSource(item.build())
        } else DefaultMediaSourceFactory(resolving).createMediaSource(item.build())
        exo.setMediaSource(mediaSource, position.coerceAtLeast(0))
        exo.prepare(); exo.playWhenReady = autoplay
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
        if (onState != null) {
            onState.invoke(body)
            if (BuildConfig.DEBUG) web.evaluateJavascript("window.__libreTvNativePlayerState=$body;", null)
        } else web.evaluateJavascript("window.dispatchEvent(new CustomEvent('libretv-native-player-state',{detail:$body}));", null)
    }
    fun pauseForLifecycle() { resumeAfterPause = player?.playWhenReady == true; player?.pause(); texture.keepScreenOn = false }
    fun resumeForLifecycle() { if (resumeAfterPause) player?.play(); resumeAfterPause = false }
    fun stop() {
        if(onState!=null && BuildConfig.DEBUG) web.evaluateJavascript("window.__libreTvNativePlayerState=null;",null)
        handler.removeCallbacks(tick)
        player?.release(); player = null; id = ""; resumeAfterPause = false
        texture.visibility = View.INVISIBLE; texture.keepScreenOn = false; videoSize = VideoSize.UNKNOWN; fit = "contain"
    }
    fun release() {
        stop()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "LibreTvNativePlayer")
    }

    private class FilterParserFactory(private val enabled: Boolean) : HlsPlaylistParserFactory {
        private val base = DefaultHlsPlaylistParserFactory()
        private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            if (!enabled) parser.parse(uri, input) else {
                val text = input.bufferedReader().readText()
                parser.parse(uri, ByteArrayInputStream(PlaylistFilter.strip(text).toByteArray(Charsets.UTF_8)))
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
