package org.libretv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi

/** 电视界面随 APK 分发，搜索和播放均由设备直接访问网络。 */
@UnstableApi
class MainActivity : Activity() {
    private var webView: WebView? = null
    private var nativePlayer: NativePlayerHost? = null
    private var standalone: StandaloneHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 桌面图标、模拟器和退出重开使用同一套已复刻的电视界面。
        // 旧网页入口仅保留给调试时显式对照，不能因少了预览参数而自动切回。
        // 手机 / 平板：显示随包分发的手机版界面（与网页端同一套设计），数据由设备直连片源
        phone = !isTelevision()
        if (phone) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            showWebView(); return
        }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra("legacy_web_ui", false))) {
            startActivity(android.content.Intent(this, NativeTvActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish(); return
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        hideSystemBars()
        showWebView()
    }

    private var phone = false
    private fun isTelevision(): Boolean {
        val mode = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType
        return mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION || packageManager.hasSystemFeature("android.software.leanback")
            || !packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
    // 手机上网页播放器进入全屏：视频层盖住整个窗口并横屏，退出时恢复竖屏
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private fun exitFullscreen() {
        fullscreenView?.let { (window.decorView as ViewGroup).removeView(it) }; fullscreenView = null
        fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = 0 }
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun hideSystemBars() {
        if (phone && fullscreenView == null) return
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun serverUrl(): String? = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(SERVER_URL_KEY, null)

    // 保留旧版 origin，可直接继续使用原来的 IndexedDB、收藏、进度和 localStorage。
    // 页面完全由 APK 拦截返回，不再连接这个地址，也不在设备监听端口。
    private fun localOrigin() = localPageOrigin(serverUrl() ?: BuildConfig.LIBRETV_URL)

    private fun showLocalError(message: String) {
        standalone?.release(); standalone = null
        nativePlayer?.release(); nativePlayer = null
        webView?.destroy(); webView = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 32, 64, 32)
            setBackgroundColor(Color.rgb(7, 16, 29))
            addView(TextView(this@MainActivity).apply {
                text = "无法打开本地界面：$message"; textSize = 22f; setTextColor(Color.WHITE)
            })
            addView(Button(this@MainActivity).apply { text = "重试"; setOnClickListener { showWebView() } })
        }
        setContentView(content)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        val url = if (phone) "${localOrigin()}/" else "${localOrigin()}/tv"
        try {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            webView = WebView(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = "${settings.userAgentString} ${if (phone) "SuperTV-Android" else "LibreTV-AndroidTV/0.3"}"
                cookies.setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        if (!phone) { callback.onCustomViewHidden(); return }
                        exitFullscreen()
                        fullscreenView = view; fullscreenCallback = callback
                        view.setBackgroundColor(Color.BLACK)
                        (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        hideSystemBars()
                    }
                    override fun onHideCustomView() { if (fullscreenView != null) exitFullscreen() }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, nextUrl: String, favicon: Bitmap?) {
                        nativePlayer?.stop()
                        standalone?.cancelRequests()
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val path = request.url.path?.trimEnd('/')
                        return standalone?.sameOrigin(request.url) != true || path !in (if (phone) setOf("", "/watch", "/library") else setOf("", "/tv", "/watch"))
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        standalone?.intercept(request)

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) view.post { showLocalError(error.description.toString()) }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 500) {
                            view.post { showLocalError("本地资源返回 ${response.statusCode}") }
                        }
                    }
                }
                requestFocus()
            }
            val currentWeb = webView ?: return
            standalone = StandaloneHost(this, currentWeb, localOrigin(), phone)
            if (phone) {
                // 手机用网页播放器；系统栏和输入法占掉的区域留出来，其余铺满
                currentWeb.setBackgroundColor(Color.rgb(8, 8, 8))
                val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(currentWeb, FrameLayout.LayoutParams(-1, -1)) }
                root.setOnApplyWindowInsetsListener { v, insets ->
                    @Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                    insets
                }
                setContentView(root)
            } else {
                nativePlayer = NativePlayerHost(this, currentWeb, url)
                setContentView(nativePlayer!!.container.apply {
                    addView(currentWeb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                })
            }
            currentWeb.loadUrl(url)
        } catch (error: Exception) {
            showLocalError("无法启动内置浏览器（${error.javaClass.simpleName}），请检查 Android System WebView 是否已启用。")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            webView?.evaluateJavascript("window.dispatchEvent(new CustomEvent('libretv-tv-settings'));", null)
            return true
        }
        val currentWebView = webView
        if (keyCode == KeyEvent.KEYCODE_BACK && fullscreenView != null) { currentWebView?.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null); exitFullscreen(); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK && currentWebView != null) {
            currentWebView.evaluateJavascript("Boolean(document.querySelector('[role=dialog]:not([data-tv-auth])'))") { hasDialog ->
                if (hasDialog == "true") {
                    currentWebView.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));", null)
                } else if (currentWebView.canGoBack()) {
                    currentWebView.goBack()
                } else {
                    finish()
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * WebView 会在 Activity.onKeyDown 之前消费方向键；必须在 dispatchKeyEvent
     * 这一层截获，才能可靠地把投影仪遥控器交给网页的电视模式导航。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (phone) return super.dispatchKeyEvent(event)
        // 播放页有自己的遥控器状态机，方向键用于进度/面板，不能走首页空间导航。
        val currentUrl = webView?.url?.let { Uri.parse(it) }
        if (currentUrl?.path == "/watch" && currentUrl.getQueryParameter("tv") == "1") {
            val playbackKey = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
                KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
                KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> "Escape"
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_F2 -> "ContextMenu"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> "MediaPlayPause"
                else -> null
            }
            if (playbackKey != null) {
                val singlePress = playbackKey in setOf("Enter", "ContextMenu", "MediaPlayPause", "Escape")
                if (event.action == KeyEvent.ACTION_DOWN && (!singlePress || event.repeatCount == 0)) {
                    webView?.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('libretv-tv-key',{detail:{key:'$playbackKey'}}));",
                        null
                    )
                }
                return true
            }
        }
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            else -> null
        }
        if (key == null || webView == null) return super.dispatchKeyEvent(event)
        // 某些投影仪不会把确认键转换成网页 click。页面监听此事件并按当前焦点
        // 直接执行打开操作；电视端卡片提供默认播放地址，绕过该 WebView 对
        // React click 的兼容问题。仍交给 WebView 处理默认行为，以保持搜索框可提交。
        if (key == "Enter") {
            if (event.action == KeyEvent.ACTION_DOWN) {
                webView?.evaluateJavascript(
                    "(function(){var el=document.activeElement,href=el&&el.getAttribute('data-tv-watch-url');if(href)window.location.assign(href);})()",
                    null
                )
            }
            return super.dispatchKeyEvent(event)
        }
        // 消费 key-up，避免 WebView 在 key-down 转发完成后再次处理该按键。
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val script = """(function(){var scope=document.querySelector('[data-tv-dialog]')||document;var nodes=Array.prototype.slice.call(scope.querySelectorAll('[data-tv-focus]:not([disabled])')).filter(function(n){return n.offsetParent!==null});if(!nodes.length)return;var active=document.activeElement;if(nodes.indexOf(active)<0){nodes[0].focus();return;}var from=active.getBoundingClientRect(),fx=from.left+from.width/2,fy=from.top+from.height/2,h='$key'==='ArrowLeft'||'$key'==='ArrowRight',best=null;nodes.forEach(function(n){if(n===active)return;var r=n.getBoundingClientRect(),dx=r.left+r.width/2-fx,dy=r.top+r.height/2-fy,valid=('$key'==='ArrowRight'&&dx>8)||('$key'==='ArrowLeft'&&dx<-8)||('$key'==='ArrowDown'&&dy>8)||('$key'==='ArrowUp'&&dy<-8);if(!valid)return;var score=(h?Math.abs(dx):Math.abs(dy))+(h?Math.abs(dy):Math.abs(dx))*2.4;if(!best||score<best.score)best={node:n,score:score};});if(best){best.node.focus({preventScroll:true});best.node.scrollIntoView({block:'nearest',inline:'nearest'});}})();"""
        webView?.evaluateJavascript(script, null)
        return true
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webView?.onResume()
        nativePlayer?.resumeForLifecycle()
    }

    override fun onPause() {
        nativePlayer?.pauseForLifecycle()
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        standalone?.release()
        standalone = null
        nativePlayer?.release()
        nativePlayer = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "libretv_tv"
        const val SERVER_URL_KEY = "server_url"
    }
}
