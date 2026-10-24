package org.libretv.tv

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** 不渲染网页界面；通过指定 origin 的主框架复用已验证的离线业务与 IndexedDB。 */
internal class NativeBackendClient(private val web: WebView, private val origin: String, private val onBoot: (JSONObject) -> Unit) {
    private var sequence = 0
    private var disposed = false
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "LibreTvNativeUi", setOf(origin)) { _, message, source, mainFrame, _ ->
                if (!mainFrame || source.toString().trimEnd('/') != origin || disposed) return@addWebMessageListener
                val text = message.data ?: return@addWebMessageListener
                if (text.length > 2 * 1024 * 1024) return@addWebMessageListener
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return@addWebMessageListener
                if (event.optInt("version") != 1) return@addWebMessageListener
                val type = event.optString("event")
                if (type == "ready" || type == "fatal") { onBoot(event); return@addWebMessageListener }
                val id = event.optString("id")
                val callback = callbacks[id]
                if (type == "result" || type == "error") callbacks.remove(id)
                callback?.invoke(event)
            }
        } else error("请更新 Android System WebView")
    }
    fun request(op: String, args: JSONObject = JSONObject(), callback: ((JSONObject) -> Unit)? = null): String {
        val id = "ui-${++sequence}"
        if (disposed) return id
        if (callback != null) callbacks[id] = callback
        val request = JSONObject().put("id", id).put("op", op).put("args", args)
        web.evaluateJavascript("window.LibreTvBackend&&window.LibreTvBackend.request($request);", null)
        return id
    }
    fun cancel(id: String?) {
        if (id == null) return
        callbacks.remove(id)
        request("cancel", JSONObject().put("id", id))
    }
    fun release() {
        disposed = true; callbacks.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "LibreTvNativeUi")
    }
}
