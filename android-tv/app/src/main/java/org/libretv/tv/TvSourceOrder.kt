package org.libretv.tv

internal object TvSourceOrder {
    data class Value(val id:String, val height:Int=0, val available:Boolean=true, val ms:Int=Int.MAX_VALUE, val bitrate:Int=0)
    // 同分辨率时码率高的在前，再比响应速度
    fun sorted(values:List<Value>):List<String> = values.sortedWith(compareBy<Value> { !it.available }
        .thenByDescending { it.height.coerceAtLeast(0) }.thenByDescending { it.bitrate.coerceAtLeast(0) }.thenBy { if(it.ms>=0) it.ms else Int.MAX_VALUE }).map { it.id }
    // 标签可能带码率，如「1080P · 2.4M」，只取画质部分
    fun heightFromLabel(label:String):Int = when(val tier=label.substringBefore(" ·").trim().uppercase()) {
        "4K" -> 2160
        "2K" -> 1440
        else -> Regex("^(\\d+)P$",RegexOption.IGNORE_CASE).matchEntire(tier)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
