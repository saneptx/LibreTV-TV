package org.libretv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 旧版本把设置和资料库存在内置网页的浏览器存储里。升级后第一次启动时，用一个不显示的网页把它们读出来交给新的数据层，
 * 之后不再创建任何网页。全新安装（没有旧数据目录）完全不会走到这里。
 * 网页只加载应用内置的一小段读取脚本，其余网络请求一律拒绝。
 */
internal object LegacyMigration {
    private const val PREFS = "supertv_core"
    private const val MAX_ATTEMPTS = 3
    private const val PAGE = """<!doctype html><meta charset="utf-8"><script>
(async () => {
  const out = {};
  try { const raw = localStorage.getItem('libretv-settings'); if (raw) { const p = JSON.parse(raw); out.settings = p && p.state ? p.state : p; } } catch (e) {}
  try { out.learnedAds = JSON.parse(localStorage.getItem('libretv-learned-ads-v1') || '[]'); } catch (e) {}
  try {
    const names = indexedDB.databases ? (await indexedDB.databases()).map((d) => d.name) : ['libretv'];
    if (names.includes('libretv')) {
      const db = await new Promise((res, rej) => { const r = indexedDB.open('libretv'); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
      const lib = {};
      for (const t of ['history', 'progress', 'searchHistory', 'favorites', 'tombstones']) {
        if (!db.objectStoreNames.contains(t)) continue;
        lib[t] = await new Promise((res, rej) => { const r = db.transaction(t).objectStore(t).getAll(); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
      }
      out.library = lib; db.close();
    }
  } catch (e) { out.error = String(e); }
  SuperTvMigrate.done(JSON.stringify(out));
})();
</script>"""

    private fun prefs(activity: Activity) = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
    /** 还没迁移过，并且这台设备上有旧版留下的浏览器数据 */
    fun needed(activity: Activity): Boolean {
        val p = prefs(activity)
        if (p.getBoolean("migrated", false)) return false
        if (!File(activity.applicationInfo.dataDir, "app_webview").isDirectory) { p.edit().putBoolean("migrated", true).apply(); return false }
        return true
    }

    /** 读取旧数据；done 在主线程回调，读不到时为 null（最多重试 3 次启动，之后放弃） */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(activity: Activity, done: (JSONObject?) -> Unit) {
        val p = prefs(activity); val main = Handler(Looper.getMainLooper())
        val origin = localPageOrigin(activity.getSharedPreferences("libretv_tv", Activity.MODE_PRIVATE).getString("server_url", null) ?: BuildConfig.LIBRETV_URL)
        var web: WebView? = null; var finished = false
        fun finish(data: JSONObject?) {
            if (finished) return
            finished = true; main.removeCallbacksAndMessages(null)
            if (data != null) p.edit().putBoolean("migrated", true).apply()
            else { val attempts = p.getInt("attempts", 0) + 1; p.edit().putInt("attempts", attempts).putBoolean("migrated", attempts >= MAX_ATTEMPTS).apply() }
            runCatching { web?.stopLoading(); web?.destroy() }; web = null
            done(data)
        }
        try {
            val view = WebView(activity); web = view
            view.settings.javaScriptEnabled = true; view.settings.domStorageEnabled = true; view.settings.blockNetworkLoads = true
            view.addJavascriptInterface(object { @JavascriptInterface fun done(text: String) {
                val data = runCatching { JSONObject(text) }.getOrNull()
                main.post { finish(data?.takeIf { !it.has("error") }) }
            } }, "SuperTvMigrate")
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (request.url.toString() == "$origin/supertv-migrate") WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(PAGE.toByteArray()))
                    else WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
            }
            main.postDelayed({ finish(null) }, 12_000)
            view.loadUrl("$origin/supertv-migrate")
        } catch (_: Throwable) { finish(null) }   // 设备上没有可用的 WebView 组件
    }
}

/** 两端入口共用：建立 Kotlin 数据层，必要时先迁移旧数据再开始。 */
internal object CoreBoot {
    fun open(activity: Activity, onBoot: (JSONObject) -> Unit): org.libretv.tv.core.CoreBackend {
        val sources = runCatching { org.json.JSONArray(activity.assets.open("sources.json").bufferedReader().use { it.readText() }) }.getOrDefault(org.json.JSONArray())
        val backend = org.libretv.tv.core.CoreBackend(File(activity.filesDir, "core"), sources, { Handler(Looper.getMainLooper()).post(it) }, onBoot)
        if (LegacyMigration.needed(activity)) LegacyMigration.run(activity) { data ->
            if (data != null) runCatching { backend.importLegacy(data) }
            backend.start()
        } else backend.start()
        return backend
    }
}
