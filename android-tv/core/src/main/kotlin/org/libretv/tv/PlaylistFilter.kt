package org.libretv.tv

/** 对齐 src/lib/m3u8.ts 的三步保守过滤；保留 KEY/MAP 与正片时间轴边界。
 * 另可删除数据页按编码特征/已学习指纹标出的分片行，并回报原片时间轴上被删除的区间。
 */
object PlaylistFilter {
    private const val DISC = "#EXT-X-DISCONTINUITY"
    private val adUrl = Regex("adjump|advert|/ads?/", RegexOption.IGNORE_CASE)
    private val segmentTags = listOf("#EXTINF", "#EXT-X-BYTERANGE", "#EXT-X-PROGRAM-DATE-TIME", "#EXT-X-GAP", "#EXT-X-BITRATE")
    /** origin 为原始行号；补插的标签为 -1 */
    private class Line(val origin: Int, val text: String)
    class Removal(val start: Double, val duration: Double)
    class Result(val text: String, val removals: List<Removal>)

    private fun segment(line: String) = line.trim().let { it.isNotEmpty() && !it.startsWith('#') }
    private fun config(line: String) = line.trim().let { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-MAP") }
    private fun duration(line: String) = line.trim().substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
    private data class Group(val boundary: Int, val start: Int, val end: Int, val count: Int, val seconds: Double)
    private fun groups(lines: List<Line>): List<Group> {
        val boundaries = listOf(-1) + lines.indices.filter { lines[it].text.trim() == DISC } + lines.size
        return boundaries.zipWithNext().map { (before, end) ->
            val start = before + 1
            val content = lines.subList(start, end)
            Group(before, start, end, content.count { segment(it.text) }, content.sumOf { if (it.text.trim().startsWith("#EXTINF:")) duration(it.text) else 0.0 })
        }
    }
    private fun drop(lines: List<Line>, rejected: List<Group>): List<Line> {
        val removed = mutableSetOf<Int>()
        for (g in rejected) {
            if (g.boundary >= 0) removed.add(g.boundary)
            for (i in g.start until g.end) if (!config(lines[i].text)) removed.add(i)
        }
        return lines.filterIndexed { index, _ -> index !in removed }
    }
    private fun heuristics(input: List<Line>): List<Line> {
        var lines = drop(input, groups(input).filter { g ->
            g.boundary >= 0 && g.count > 0 && lines(input, g).filter { segment(it.text) }.all { adUrl.containsMatchIn(it.text) }
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
        return lines
    }
    private fun lines(all: List<Line>, g: Group) = all.subList(g.start, g.end)

    /** 删除标记的分片行：去掉其专属标签，保留 KEY/MAP 等向后生效的标签；中间删除处补边界以重置时间戳 */
    private fun dropFlagged(lines: List<Line>, flagged: Set<String>): List<Line> {
        val out = mutableListOf<Line>(); val pending = mutableListOf<Line>()
        var removedRun = false; var keptAny = false; var discSinceKept = false
        for (line in lines) {
            val text = line.text.trim()
            if (!segment(text)) { pending.add(line); continue }
            if (text in flagged) {
                pending.removeAll { p -> segmentTags.any { p.text.trim().startsWith(it) } }
                if (pending.any { it.text.trim() == DISC }) discSinceKept = true
                out.addAll(pending); pending.clear(); removedRun = true
            } else {
                if (removedRun && keptAny && !discSinceKept && pending.none { it.text.trim() == DISC }) out.add(Line(-1, DISC))
                out.addAll(pending); pending.clear(); out.add(line)
                keptAny = true; removedRun = false; discSinceKept = false
            }
        }
        return out + pending
    }

    fun strip(content: String): String = filter(content).text

    fun filter(content: String, flagged: Set<String> = emptySet()): Result {
        val hasDisc = content.contains(DISC)
        if (!hasDisc && flagged.isEmpty()) return Result(content, emptyList())
        val original = content.split('\n')
        var lines = original.mapIndexed { index, text -> Line(index, text) }
        if (hasDisc) lines = heuristics(lines)
        if (flagged.isNotEmpty()) lines = dropFlagged(lines, flagged)
        // 首个分片之前、末个分片之后的边界都已失去语义
        val first = lines.indexOfFirst { segment(it.text) }
        val last = lines.indexOfLast { segment(it.text) }
        lines = lines.filterIndexed { index, line -> line.text.trim() != DISC || first in 0 until index && index < last }
        return assemble(original, lines)
    }

    /** 未写 IV 的 AES-128 默认以媒体序号为 IV；删除分片后为保留的分片钉住原序号，避免解密错位 */
    private fun assemble(original: List<String>, kept: List<Line>): Result {
        val keptOrigins = kept.mapTo(HashSet()) { it.origin }
        val removedBefore = HashMap<Int, Int>(); val sequence = HashMap<Int, Long>(); val implicitKey = HashMap<Int, String>()
        val removals = mutableListOf<Removal>()
        var seq = 0L; var time = 0.0; var extinf = 0.0; var key: String? = null; var removed = 0
        original.forEachIndexed { index, raw ->
            val text = raw.trim()
            when {
                text.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = text.substringAfter(':').trim().toLongOrNull() ?: 0L
                text.startsWith("#EXT-X-KEY:") -> key = text.takeIf { it.contains("METHOD=AES-128") && !Regex("[:,]IV=").containsMatchIn(it) }
                text.startsWith("#EXTINF:") -> extinf = duration(text)
                segment(text) -> {
                    if (index in keptOrigins) {
                        removedBefore[index] = removed; sequence[index] = seq; key?.let { implicitKey[index] = it }
                    } else {
                        removed++
                        val previous = removals.lastOrNull()
                        if (previous != null && kotlin.math.abs(previous.start + previous.duration - time) < .001) removals[removals.lastIndex] = Removal(previous.start, previous.duration + extinf)
                        else removals.add(Removal(time, extinf))
                    }
                    time += extinf; extinf = 0.0; seq++
                }
            }
        }
        if (removed == 0 && kept.size == original.size) return Result(original.joinToString("\n"), emptyList())
        val out = ArrayList<String>(kept.size + 8)
        for (line in kept) {
            val pinned = implicitKey[line.origin]
            if (pinned != null && (removedBefore[line.origin] ?: 0) > 0) out.add("$pinned,IV=0x%032x".format(sequence[line.origin] ?: 0L))
            out.add(line.text)
        }
        return Result(out.joinToString("\n"), removals)
    }
}
