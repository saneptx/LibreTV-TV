package org.libretv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URI

/** 电视端只承载界面，搜索、鉴权和代理仍由用户部署的 LibreTV 服务提供。 */
class MainActivity : Activity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        hideSystemBars()
        if (serverUrl() == null) showServerSetup() else showWebView()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun serverUrl(): String? = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(SERVER_URL_KEY, null)

    private fun normaliseServerUrl(value: String): String? {
        return try {
            var candidate = value.trim().trimEnd('/')
            if (candidate.isEmpty()) return null
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) candidate = "http://$candidate"
            val uri = URI(candidate)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
            if (uri.path.trimEnd('/') == "/tv") candidate else "$candidate/tv"
        } catch (_: Exception) {
            null
        }
    }

    private fun showServerSetup(message: String? = null) {
        webView?.destroy()
        webView = null
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(32), dp(64), dp(32))
            setBackgroundColor(Color.rgb(7, 16, 29))
        }
        val title = TextView(this).apply {
            text = "连接 LibreTV 服务"
            setTextColor(Color.rgb(244, 248, 255))
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        val description = TextView(this).apply {
            text = "请输入运行 LibreTV 的电脑、NAS 或服务器地址。电视和服务器需要能够互相访问。"
            setTextColor(Color.rgb(170, 190, 214))
            textSize = 18f
            setPadding(0, dp(16), 0, dp(20))
        }
        val address = EditText(this).apply {
            hint = "例如：192.168.1.10:8080"
            setSingleLine(true)
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(115, 139, 165))
            setBackgroundColor(Color.rgb(20, 37, 57))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setText(serverUrl()?.removeSuffix("/tv") ?: BuildConfig.LIBRETV_URL.removeSuffix("/tv"))
        }
        val error = TextView(this).apply {
            text = message.orEmpty()
            setTextColor(Color.rgb(255, 133, 146))
            textSize = 16f
            setPadding(0, dp(12), 0, 0)
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        val save = Button(this).apply {
            text = "保存并连接"
            textSize = 20f
            isAllCaps = false
            setOnClickListener {
                val url = normaliseServerUrl(address.text.toString())
                if (url == null) {
                    error.text = "请输入有效地址，例如 http://192.168.1.10:8080"
                    error.visibility = View.VISIBLE
                    address.requestFocus()
                    return@setOnClickListener
                }
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(SERVER_URL_KEY, url).apply()
                showWebView()
            }
        }
        content.addView(title)
        content.addView(description)
        content.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        content.addView(error)
        content.addView(save, LinearLayout.LayoutParams(dp(220), dp(64)).apply { topMargin = dp(22) })
        setContentView(ScrollView(this).apply { addView(content) })
        address.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        val url = serverUrl() ?: return showServerSetup()
        try {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            webView = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = "${settings.userAgentString} LibreTV-AndroidTV/0.1"
                cookies.setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) view.post { showServerSetup("无法连接到服务器：${error.description}") }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame && response.statusCode >= 500) {
                            view.post { showServerSetup("服务器返回 ${response.statusCode}，请确认 LibreTV 已启动") }
                        }
                    }
                }
                loadUrl(url)
                requestFocus()
            }
            setContentView(webView)
        } catch (error: Exception) {
            showServerSetup("无法启动内置浏览器（${error.javaClass.simpleName}）。请检查投影仪的 Android System WebView 是否已启用并更新。")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showServerSetup()
            return true
        }
        val currentWebView = webView
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
        val script = """(function(){var nodes=Array.prototype.slice.call(document.querySelectorAll('[data-tv-focus]:not([disabled])')).filter(function(n){return n.offsetParent!==null});if(!nodes.length)return;var active=document.activeElement;if(nodes.indexOf(active)<0){nodes[0].focus();return;}var from=active.getBoundingClientRect(),fx=from.left+from.width/2,fy=from.top+from.height/2,h='$key'==='ArrowLeft'||'$key'==='ArrowRight',best=null;nodes.forEach(function(n){if(n===active)return;var r=n.getBoundingClientRect(),dx=r.left+r.width/2-fx,dy=r.top+r.height/2-fy,valid=('$key'==='ArrowRight'&&dx>8)||('$key'==='ArrowLeft'&&dx<-8)||('$key'==='ArrowDown'&&dy>8)||('$key'==='ArrowUp'&&dy<-8);if(!valid)return;var score=(h?Math.abs(dx):Math.abs(dy))+(h?Math.abs(dy):Math.abs(dx))*2.4;if(!best||score<best.score)best={node:n,score:score};});if(best){best.node.focus({preventScroll:true});best.node.scrollIntoView({block:'nearest',inline:'nearest'});}})();"""
        webView?.evaluateJavascript(script, null)
        return true
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "libretv_tv"
        const val SERVER_URL_KEY = "server_url"
    }
}
