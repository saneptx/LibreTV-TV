package org.libretv.tv

/** 对齐 src/lib/m3u8.ts 的三步保守过滤；保留 KEY/MAP 与正片时间轴边界。 */
internal object PlaylistFilter {
    private const val DISC = "#EXT-X-DISCONTINUITY"
    private val adUrl = Regex("adjump|advert|/ads?/", RegexOption.IGNORE_CASE)
    private fun segment(line: String) = line.trim().let { it.isNotEmpty() && !it.startsWith('#') }
    private fun config(line: String) = line.trim().let { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-MAP") }
    private data class Group(val boundary: Int, val start: Int, val end: Int, val count: Int, val seconds: Double)
    private fun groups(lines: List<String>): List<Group> {
        val boundaries = listOf(-1) + lines.indices.filter { lines[it].trim() == DISC } + lines.size
        return boundaries.zipWithNext().map { (before, end) ->
            val start = before + 1
            val content = lines.subList(start, end)
            Group(before, start, end, content.count(::segment), content.sumOf {
                if (it.trim().startsWith("#EXTINF:")) it.trim().substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 else 0.0
            })
        }
    }
    private fun drop(lines: List<String>, rejected: List<Group>): List<String> {
        val removed = mutableSetOf<Int>()
        for (g in rejected) {
            if (g.boundary >= 0) removed.add(g.boundary)
            for (i in g.start until g.end) if (!config(lines[i])) removed.add(i)
        }
        return lines.filterIndexed { index, _ -> index !in removed }
    }
    fun strip(content: String): String {
        if (!content.contains(DISC)) return content
        var lines = content.split('\n')
        lines = drop(lines, groups(lines).filter { g ->
            g.boundary >= 0 && g.count > 0 && lines.subList(g.start, g.end).filter(::segment).all { adUrl.containsMatchIn(it) }
        })
        val scanned = groups(lines)
        lines = drop(lines, scanned.filterIndexed { index, g ->
            index > 0 && index < scanned.lastIndex && g.count > 0 && g.seconds > 0 && g.seconds <= 30 &&
                scanned[index - 1].seconds >= 120 && scanned[index + 1].seconds >= 120
        })
        val leadGroups = groups(lines)
        val lead = leadGroups.getOrNull(1)
        if (lead != null && leadGroups.size >= 3 && leadGroups[0].count == 0 && lead.count in 1..20 && lead.seconds > 0 && lead.seconds <= 90) {
            val typical = if (leadGroups.size >= 5) {
                val others = leadGroups.drop(2).map { it.count }.sorted()
                val median = others[others.size / 2]
                kotlin.math.abs(lead.count - median) <= maxOf(3.0, median * .5)
            } else false
            if (!typical) lines = drop(lines, listOf(lead))
        }
        var seen = false
        return lines.filter { line ->
            if (segment(line)) seen = true
            seen || line.trim() != DISC
        }.joinToString("\n")
    }
}
