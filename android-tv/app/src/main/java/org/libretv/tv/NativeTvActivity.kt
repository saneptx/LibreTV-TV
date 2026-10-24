package org.libretv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi

/** 原生电视界面；包内不可见的数据页只负责既有解析及持久化，不参与焦点或绘制。 */
@UnstableApi
class NativeTvActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var backend: NativeBackendClient
    private lateinit var standalone: StandaloneHost
    private lateinit var player: NativePlayerHost
    private lateinit var screen: NativeTvUi

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideBars()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val previous = getSharedPreferences("libretv_tv", MODE_PRIVATE).getString("server_url", null)
        val origin = localPageOrigin(previous ?: BuildConfig.LIBRETV_URL)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT); alpha = 0f
            isFocusable = false; isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "${settings.userAgentString} LibreTV-AndroidTV/0.4"
            webChromeClient = WebChromeClient()
        }
        standalone = StandaloneHost(this, web, origin)
        backend = NativeBackendClient(web, origin) { if (::screen.isInitialized) screen.onBoot(it) }
        player = NativePlayerHost(this, web, origin) { if (::screen.isInitialized) screen.onPlaybackState(it) }
        screen = NativeTvUi(this, backend, player) { web.reload() }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = standalone.intercept(request)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.isForMainFrame && (!standalone.sameOrigin(request.url) || request.url.path != "/native-tv")
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) screen.onBootError("本地数据加载失败：${error.description}")
            }
        }
        player.container.addView(web, FrameLayout.LayoutParams(1, 1))
        player.container.addView(screen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(player.container)
        web.loadUrl("$origin/native-tv")
    }
    private fun hideBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::screen.isInitialized && screen.dispatchKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() {
        super.onResume(); hideBars()
        if (::web.isInitialized) web.onResume()
        if (::player.isInitialized) player.resumeForLifecycle()
    }
    override fun onPause() {
        if (::screen.isInitialized) screen.savePlayback()
        if (::player.isInitialized) player.pauseForLifecycle()
        // 数据页必须完成退出时的 IndexedDB 写入，保持 JS 运行直到 onDestroy。
        super.onPause()
    }
    override fun onDestroy() {
        if (::screen.isInitialized) screen.release()
        if (::player.isInitialized) player.release()
        if (::backend.isInitialized) backend.release()
        if (::standalone.isInitialized) standalone.release()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
