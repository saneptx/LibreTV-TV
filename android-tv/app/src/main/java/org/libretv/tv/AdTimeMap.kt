package org.libretv.tv

/** 原片时间与去广告后播放时间的换算；区间 start 为原片时间，与电脑服务的 timeMap 相同。 */
internal object AdTimeMap {
    fun original(position: Double, removals: List<PlaylistFilter.Removal>): Double {
        var result = position; var removed = 0.0
        for (r in removals) { if (position + .00001 >= r.start - removed) result += r.duration; removed += r.duration }
        return result
    }
    fun clean(position: Double, removals: List<PlaylistFilter.Removal>): Double {
        var removed = 0.0
        for (r in removals) {
            if (position >= r.start + r.duration) removed += r.duration
            else if (position > r.start) { removed += position - r.start; break }
            else break
        }
        return (position - removed).coerceAtLeast(0.0)
    }
}
