package org.libretv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网发现：手机用它找运行着 SuperTV 的电视 / 投影仪（_supertv._tcp），电视用它找电脑服务（_supertv-hub._tcp）。
 * 只把找到的名称、地址、端口交给手机界面；连接、配对、同步都由界面经原生网络通道完成。
 */
internal class TvDiscovery(private val context: Context, private val serviceType: String = TvCompanion.SERVICE_TYPE) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = ConcurrentHashMap<String, JSONObject>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(queue) { if (resolving) null else queue.removeFirstOrNull()?.also { resolving = true } } ?: return
        fun finish() { synchronized(queue) { resolving = false }; resolveNext() }
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) = finish()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress?.takeIf { ':' !in it }
                    if (host != null && info.port in 1..65535) found[info.serviceName] = JSONObject().put("name", info.serviceName.removePrefix("SuperTV ").ifBlank { "电视" })
                        .put("host", host).put("port", info.port).put("id", info.attributes["id"]?.let { String(it, Charsets.UTF_8) }.orEmpty())
                    finish()
                }
            })
        }.onFailure { finish() }
    }

    fun start() {
        synchronized(this) {
            if (listener != null) return
            val created = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, code: Int) { synchronized(this@TvDiscovery) { if (listener === this) listener = null } }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onServiceFound(info: NsdServiceInfo) { synchronized(queue) { queue.addLast(info) }; resolveNext() }
                override fun onServiceLost(info: NsdServiceInfo) { found.remove(info.serviceName) }
            }
            runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, created); listener = created }
        }
    }
    fun list(): String = JSONArray(found.values.toList()).toString()
    fun stop() {
        synchronized(this) { listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null }
        found.clear(); synchronized(queue) { queue.clear() }
    }
    /**
     * 已配对的电视（含口令）存在应用自己的偏好设置里并立即落盘：
     * 网页本地存储是延迟写入的，应用被系统回收或更新时可能丢掉刚完成的配对。
     */
    private val prefs = context.getSharedPreferences("supertv_tv_link", Context.MODE_PRIVATE)
    fun load(): String = prefs.getString("state", "") ?: ""
    fun save(value: String) { if (value.length <= 65536) prefs.edit().putString("state", value).commit() }
    /** 配对时显示在电视上的手机名称 */
    fun deviceName(): String =
        (runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "手机").take(40)
}
