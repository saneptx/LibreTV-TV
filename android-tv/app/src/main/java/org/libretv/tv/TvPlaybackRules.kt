package org.libretv.tv

import kotlin.math.max

internal object TvPlaybackRules {
    fun canSeek(duration: Double)=duration.isFinite() && duration>0
    fun preview(position: Double, delta: Int, duration: Double): Double {
        if (!position.isFinite() || !duration.isFinite() || duration <= 0) return 0.0
        return (position + delta).coerceIn(0.0, max(0.0, duration - .25))
    }
    fun time(seconds: Double): String {
        val n = if (seconds.isFinite()) seconds.toInt().coerceAtLeast(0) else 0
        return if (n >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT,n / 3600, n / 60 % 60, n % 60) else "%02d:%02d".format(java.util.Locale.ROOT,n / 60, n % 60)
    }
    fun speed(bytes: Double): String = when {
        !bytes.isFinite() || bytes<=0 -> "0 KB/s"
        bytes<1024 -> "< 1 KB/s"
        bytes<1024*1024 -> "%.0f KB/s".format(java.util.Locale.ROOT,bytes/1024)
        else -> "%.2f MB/s".format(java.util.Locale.ROOT,bytes/1024/1024)
    }
}
