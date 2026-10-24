package org.libretv.tv

import java.net.URI

/** 只复用浏览器的存储身份，不复用旧版服务路径或认证信息。 */
internal fun localPageOrigin(previous: String?): String {
    val fallback = "http://127.0.0.1:8080"
    return try {
        val uri = URI(previous.orEmpty().trim())
        val scheme = uri.scheme?.lowercase() ?: return fallback
        val host = uri.host?.lowercase() ?: return fallback
        if (scheme !in setOf("http", "https") || uri.userInfo != null || uri.port !in -1..65535) return fallback
        val authority = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val port = uri.port.takeUnless { it == -1 || scheme == "http" && it == 80 || scheme == "https" && it == 443 }
        "$scheme://$authority${port?.let { ":$it" }.orEmpty()}"
    } catch (_: Exception) { fallback }
}
