package org.libretv.tv

internal object TvSourceOrder {
    data class Value(val id:String, val height:Int=0, val available:Boolean=true, val ms:Int=Int.MAX_VALUE)
    fun sorted(values:List<Value>):List<String> = values.sortedWith(compareBy<Value> { !it.available }
        .thenByDescending { it.height.coerceAtLeast(0) }.thenBy { if(it.ms>=0) it.ms else Int.MAX_VALUE }).map { it.id }
    fun heightFromLabel(label:String):Int = when(label.uppercase()) {
        "4K" -> 2160
        "2K" -> 1440
        else -> Regex("^(\\d+)P$",RegexOption.IGNORE_CASE).matchEntire(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
