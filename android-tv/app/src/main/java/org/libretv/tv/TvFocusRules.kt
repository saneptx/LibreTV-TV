package org.libretv.tv

/** 与网页 nextTvFocus 相同的中心点距离规则；复刻已有遥控器焦点顺序。 */
internal object TvFocusRules {
    data class Box(val x: Double, val y: Double, val width: Double, val height: Double)
    fun next(boxes: List<Box>, current: Int, direction: Int, threshold: Double = 8.0): Int {
        val from = boxes.getOrNull(current) ?: return if (boxes.isEmpty()) -1 else 0
        val horizontal = direction == 17 || direction == 66
        var best = current
        var bestScore = Double.POSITIVE_INFINITY
        boxes.forEachIndexed { index, box ->
            if (index == current) return@forEachIndexed
            val dx = box.x + box.width / 2 - from.x - from.width / 2
            val dy = box.y + box.height / 2 - from.y - from.height / 2
            val valid = when (direction) { 17 -> dx < -threshold; 66 -> dx > threshold; 33 -> dy < -threshold; 130 -> dy > threshold; else -> false }
            val score = if (horizontal) kotlin.math.abs(dx) + kotlin.math.abs(dy) * 2.4 else kotlin.math.abs(dy) + kotlin.math.abs(dx) * 2.4
            if (valid && score < bestScore) { best = index; bestScore = score }
        }
        return best
    }
}
