package org.libretv.tv

import android.media.MediaCodecList
import android.os.Build
import org.json.JSONObject

/** 设备硬件视频解码能力，随电脑串流请求上报，电脑据此选择输出编码（HEVC 码率更低）。
 * 软件解码器（OMX.google / c2.android）不计入：4K 软解在电视芯片上跟不上。 */
internal object DecoderCapabilities {
    private val mimes = mapOf("hevc" to "video/hevc", "h264" to "video/avc", "av1" to "video/av01")
    val json: JSONObject by lazy {
        val result = JSONObject()
        runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            for ((name, mime) in mimes) {
                val hardware = codecs.filter { info -> info.supportedTypes.any { it.equals(mime, true) } && !software(info.name) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) }
                var best: JSONObject? = null
                for (info in hardware) {
                    val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: continue
                    val fps = { w: Int, h: Int -> listOf(60, 50, 30, 25, 24).firstOrNull { runCatching { video.areSizeAndRateSupported(w, h, it.toDouble()) }.getOrDefault(false) } ?: 0 }
                    val entry = JSONObject().put("maxWidth", video.supportedWidths.upper).put("maxHeight", video.supportedHeights.upper)
                        .put("fps1080", fps(1920, 1080)).put("fps2160", fps(3840, 2160))
                    if (best == null || entry.optInt("fps2160") > best.optInt("fps2160")) best = entry
                }
                best?.let { result.put(name, it) }
            }
        }
        result
    }
    private fun software(name: String) = name.startsWith("OMX.google.", true) || name.startsWith("c2.android.", true) || name.contains(".sw.", true)
}
