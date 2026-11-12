package org.libretv.tv

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 遥控器方向键的焦点规则，按「行」而不是按中心点距离选择：
 * - 左右：只在同一行（纵向有重叠）里移动，取最近的一个；行尾不跳到别的行。
 * - 上下：先找最近的一行，再在这一行里取与当前列最接近的一个；不会越过中间的行。
 * anchor 是连续上下移动时记住的列位置，经过整行宽的控件后仍回到原来的列。
 */
internal object TvFocusRules {
    data class Box(val x: Double, val y: Double, val width: Double, val height: Double)
    fun next(boxes: List<Box>, current: Int, direction: Int, threshold: Double = 8.0, anchor: Double? = null): Int {
        val from = boxes.getOrNull(current) ?: return if (boxes.isEmpty()) -1 else 0
        val horizontal = direction == 17 || direction == 66
        val forward = direction == 66 || direction == 130
        // 主轴：移动方向；交叉轴：与之垂直
        fun start(b: Box) = if (horizontal) b.x else b.y
        fun size(b: Box) = if (horizontal) b.width else b.height
        fun crossStart(b: Box) = if (horizontal) b.y else b.x
        fun crossSize(b: Box) = if (horizontal) b.height else b.width
        fun overlap(a0: Double, a1: Double, b0: Double, b1: Double) = min(a1, b1) - max(a0, b0)
        /** 沿移动方向，候选离当前控件边缘的距离；负数表示两者在主轴上重叠 */
        fun gap(b: Box) = if (forward) start(b) - (start(from) + size(from)) else start(from) - (start(b) + size(b))
        fun crossOverlap(b: Box) = overlap(crossStart(from), crossStart(from) + crossSize(from), crossStart(b), crossStart(b) + crossSize(b))
        fun crossCenter(b: Box) = crossStart(b) + crossSize(b) / 2
        val ahead = boxes.indices.filter { it != current && gap(boxes[it]) >= -min(size(from), size(boxes[it])) * .25 }
        if (horizontal) {
            val row = ahead.filter { crossOverlap(boxes[it]) >= min(crossSize(from), crossSize(boxes[it])) * .25 }
            return row.minWithOrNull(compareBy<Int> { max(0.0, gap(boxes[it])) }.thenBy { abs(crossCenter(boxes[it]) - crossCenter(from)) }) ?: current
        }
        if (ahead.isEmpty()) return fallback(boxes, current, direction, threshold)
        val nearest = boxes[ahead.minByOrNull { gap(boxes[it]) }!!]
        val row = ahead.filter { overlap(start(nearest), start(nearest) + size(nearest), start(boxes[it]), start(boxes[it]) + size(boxes[it])) >= min(size(nearest), size(boxes[it])) * .5 }
        val point = anchor?.takeIf { it >= crossStart(from) && it <= crossStart(from) + crossSize(from) } ?: crossCenter(from)
        fun distance(b: Box) = if (point < crossStart(b)) crossStart(b) - point else if (point > crossStart(b) + crossSize(b)) point - crossStart(b) - crossSize(b) else 0.0
        return row.minWithOrNull(compareBy<Int> { distance(boxes[it]) }.thenBy { abs(crossCenter(boxes[it]) - point) }) ?: current
    }
    /** 控件相互重叠等特殊布局：退回中心点距离 */
    private fun fallback(boxes: List<Box>, current: Int, direction: Int, threshold: Double): Int {
        val from = boxes[current]
        var best = current
        var bestScore = Double.POSITIVE_INFINITY
        boxes.forEachIndexed { index, box ->
            if (index == current) return@forEachIndexed
            val dx = box.x + box.width / 2 - from.x - from.width / 2
            val dy = box.y + box.height / 2 - from.y - from.height / 2
            val valid = if (direction == 33) dy < -threshold else dy > threshold
            val score = abs(dy) + abs(dx) * 2.4
            if (valid && score < bestScore) { best = index; bestScore = score }
        }
        return best
    }
}
