package org.libretv.tv.core

/** 从分片开头的若干字节里读出视频信息（分辨率、流类型、起始时间戳），用于画质探测和去广告分析。纯函数。 */
object Codec {
    class Size(val width: Int, val height: Int)
    private const val TS = 188
    private const val MAX_ES = 256 * 1024
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xff
    /** 越界返回 -1（对应 JS 里的 undefined） */
    private fun ByteArray.at(i: Int) = if (i in indices) this[i].toInt() and 0xff else -1

    private class BitReader(private val data: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var value = 0
            repeat(n) { val index = pos shr 3; if (index >= data.size) throw IllegalStateException("位流已耗尽"); value = (value shl 1) or ((data.u(index) shr (7 - (pos and 7))) and 1); pos++ }
            return value
        }
        fun ue(): Int { var zeros = 0; while (bits(1) == 0) { zeros++; if (zeros > 32) throw IllegalStateException("非法的 exp-golomb 编码") }; return if (zeros == 0) 0 else (1 shl zeros) - 1 + bits(zeros) }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -k / 2 }
    }
    fun unescapeRbsp(src: ByteArray, from: Int = 0, to: Int = src.size): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from); var zeros = 0
        for (i in from until to) { val b = src.u(i); if (zeros == 2 && b == 3) { zeros = 0; continue }; out.write(b); zeros = if (b == 0) zeros + 1 else 0 }
        return out.toByteArray()
    }
    private val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    fun parseH264Sps(rbsp: ByteArray): Size? = try {
        val r = BitReader(rbsp)
        val profile = r.bits(8); r.bits(8); r.bits(8); r.ue()
        var chroma = 1
        if (profile in highProfiles) {
            chroma = r.ue(); if (chroma == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1)
            if (r.bits(1) == 1) for (i in 0 until (if (chroma != 3) 8 else 12)) { if (r.bits(1) == 0) continue; repeat(if (i < 6) 16 else 64) { r.se() } }
        }
        r.ue()
        when (r.ue()) { 0 -> r.ue(); 1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } } }
        r.ue(); r.bits(1)
        val widthMbs = r.ue() + 1; val heightUnits = r.ue() + 1; val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var width = widthMbs * 16; var height = (2 - frameMbsOnly) * heightUnits * 16
        if (r.bits(1) == 1) {
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            val (sw, sh) = when (chroma) { 1 -> 2 to 2; 2 -> 2 to 1; else -> 1 to 1 }
            width -= (left + right) * sw; height -= (top + bottom) * sh * (2 - frameMbsOnly)
        }
        if (width <= 0 || height <= 0) null else Size(width, height)
    } catch (_: Exception) { null }

    fun findH264Sps(es: ByteArray): ByteArray? {
        val starts = ArrayList<Int>()
        var i = 2
        while (i < es.size && starts.size < 64) { if (es.u(i) == 1 && es.u(i - 1) == 0 && es.u(i - 2) == 0) starts.add(i + 1); i++ }
        for (n in starts.indices) {
            val from = starts[n]; val to = if (n + 1 < starts.size) starts[n + 1] - 3 else es.size
            if (to <= from) continue
            if (es.u(from) and 0x1f == 7) return unescapeRbsp(es, from + 1, to)
        }
        return null
    }

    private fun tsOffset(buf: ByteArray): Int {
        for (i in 0 until minOf(buf.size, TS * 4)) if (buf.u(i) == 0x47 && buf.at(i + TS) == 0x47 && buf.at(i + TS * 2) == 0x47) return i
        return if (buf.at(0) == 0x47) 0 else -1
    }
    private class Packet(val pid: Int, val start: Boolean, val from: Int, val to: Int)
    private inline fun packets(buf: ByteArray, start: Int, each: (Packet) -> Boolean) {
        var i = start
        while (i + TS <= buf.size) {
            val at = i; i += TS
            if (buf.u(at) != 0x47) continue
            val pid = ((buf.u(at + 1) and 0x1f) shl 8) or buf.u(at + 2)
            if (buf.u(at + 3) and 0x10 == 0) continue
            var begin = at + 4
            if (buf.u(at + 3) and 0x20 != 0) begin += 1 + buf.u(at + 4)
            if (begin >= at + TS) continue
            if (!each(Packet(pid, buf.u(at + 1) and 0x40 != 0, begin, at + TS))) return
        }
    }
    private fun pat(buf: ByteArray, p: Packet): Int {
        val len = p.to - p.from; fun b(i: Int) = buf.u(p.from + i)
        val begin = 1 + b(0)
        if (begin + 8 > len || b(begin) != 0) return -1
        val end = begin + 3 + (((b(begin + 1) and 0x0f) shl 8) or b(begin + 2)) - 4
        var q = begin + 8
        while (q + 4 <= end && q + 4 <= len) { if ((b(q) shl 8) or b(q + 1) != 0) return ((b(q + 2) and 0x1f) shl 8) or b(q + 3); q += 4 }
        return -1
    }
    /** PMT 里的各条流：(类型, PID) */
    private fun pmt(buf: ByteArray, p: Packet): List<Pair<Int, Int>> {
        val len = p.to - p.from; fun b(i: Int) = buf.u(p.from + i)
        val begin = 1 + b(0)
        if (begin + 12 > len || b(begin) != 0x02) return emptyList()
        val end = begin + 3 + (((b(begin + 1) and 0x0f) shl 8) or b(begin + 2)) - 4
        var q = begin + 12 + (((b(begin + 10) and 0x0f) shl 8) or b(begin + 11))
        val streams = ArrayList<Pair<Int, Int>>()
        while (q + 5 <= end && q + 5 <= len) { streams.add(b(q) to (((b(q + 1) and 0x1f) shl 8) or b(q + 2))); q += 5 + (((b(q + 3) and 0x0f) shl 8) or b(q + 4)) }
        return streams
    }
    private fun streams(buf: ByteArray, start: Int): List<Pair<Int, Int>> {
        var pmtPid = -1; var found = emptyList<Pair<Int, Int>>()
        packets(buf, start) { p ->
            if (!p.start) return@packets true
            if (pmtPid < 0 && p.pid == 0) { pmtPid = pat(buf, p); return@packets true }
            if (pmtPid >= 0 && p.pid == pmtPid) { found = pmt(buf, p); return@packets false }
            true
        }
        return found
    }
    fun parseTsVideoResolution(buf: ByteArray): Size? {
        val start = tsOffset(buf); if (start < 0) return null
        val videoPid = streams(buf, start).firstOrNull { it.first == 0x1b }?.second ?: -1
        val es = java.io.ByteArrayOutputStream()
        packets(buf, start) { p ->
            if (videoPid >= 0 && p.pid != videoPid) return@packets true
            var from = p.from
            if (p.start && p.to - p.from > 9 && buf.u(from) == 0 && buf.u(from + 1) == 0 && buf.u(from + 2) == 1) {
                val esBegin = 9 + buf.u(from + 8)
                if (esBegin >= p.to - p.from) return@packets true
                from += esBegin
            }
            es.write(buf, from, p.to - from)
            es.size() < MAX_ES
        }
        if (es.size() == 0) return null
        return findH264Sps(es.toByteArray())?.let { parseH264Sps(it) }
    }
    /** 流类型 + 分辨率组成的指纹：广告与正片的编码参数通常不同 */
    fun parseTsSignature(buf: ByteArray): String? {
        val start = tsOffset(buf); if (start < 0) return null
        val types = streams(buf, start).map { it.first }
        if (types.isEmpty()) return null
        val size = parseTsVideoResolution(buf) ?: return null
        return "ts|${types.joinToString(",") { it.toString(16) }}|${size.width}x${size.height}"
    }
    /** 第一个 PES 的显示时间戳（秒）；优先视频流 */
    fun parseTsFirstPts(buf: ByteArray): Double? {
        val start = tsOffset(buf); if (start < 0) return null
        var pmtPid = -1; var videoPid = -1; var fallback: Double? = null; var result: Double? = null
        packets(buf, start) { p ->
            if (!p.start) return@packets true
            if (p.pid == 0) { if (pmtPid < 0) pmtPid = pat(buf, p); return@packets true }
            if (p.pid == pmtPid) { if (videoPid < 0) videoPid = pmt(buf, p).firstOrNull { it.first in setOf(0x02, 0x1b, 0x24) }?.second ?: -1; return@packets true }
            val f = p.from
            if (p.to - f < 14 || buf.u(f) != 0 || buf.u(f + 1) != 0 || buf.u(f + 2) != 1 || buf.u(f + 7) and 0x80 == 0) return@packets true
            val pts = (buf.u(f + 9) and 0x0e) * 536870912.0 + buf.u(f + 10) * 4194304.0 + (buf.u(f + 11) and 0xfe) * 16384.0 + buf.u(f + 12) * 128.0 + (buf.u(f + 13) shr 1)
            if (p.pid == videoPid) { result = pts / 90000; return@packets false }
            if (fallback == null) fallback = pts / 90000
            true
        }
        return result ?: fallback
    }

    private val visualBoxes = setOf("avc1", "avc3", "hvc1", "hev1", "mp4v", "encv", "dvh1", "dvhe")
    private val containers = mapOf("moov" to 8, "trak" to 8, "mdia" to 8, "minf" to 8, "stbl" to 8, "stsd" to 16)
    private fun u32(buf: ByteArray, o: Int) = (buf.u(o).toLong() shl 24) + (buf.u(o + 1) shl 16) + (buf.u(o + 2) shl 8) + buf.u(o + 3)
    private fun boxType(buf: ByteArray, o: Int) = String(charArrayOf(buf.u(o).toChar(), buf.u(o + 1).toChar(), buf.u(o + 2).toChar(), buf.u(o + 3).toChar()))
    private fun scanBoxes(buf: ByteArray, from: Int, to: Int): Size? {
        var p = from
        while (p + 8 <= to) {
            val type = boxType(buf, p + 4)
            if (type.any { it.code < 0x20 || it.code > 0x7e }) return null
            var size = u32(buf, p); var header = 8
            if (size == 1L) { if (p + 16 > to || u32(buf, p + 8) != 0L) return null; size = u32(buf, p + 12); header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) return null
            if (type in visualBoxes) {
                val o = p + 8 + 24
                if (o + 4 <= buf.size) { val w = (buf.u(o) shl 8) or buf.u(o + 1); val h = (buf.u(o + 2) shl 8) or buf.u(o + 3); if (w > 0 && h > 0) return Size(w, h) }
            }
            containers[type]?.let { child -> scanBoxes(buf, p + child, (p + size).toInt())?.let { return it } }
            p += size.toInt()
        }
        return null
    }
    fun parseMp4VideoResolution(buf: ByteArray): Size? = scanBoxes(buf, 0, buf.size)
    fun looksLikeMp4(buf: ByteArray): Boolean = buf.size >= 12 && boxType(buf, 4) in setOf("ftyp", "styp", "moov", "moof")

    /** AES-128-CBC 解密分片开头（只为读头部信息，不去填充） */
    fun decryptPrefix(body: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size >= 16 && iv.size >= 16)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.copyOf(16), "AES"), javax.crypto.spec.IvParameterSpec(iv.copyOf(16)))
        return cipher.doFinal(body.copyOf(body.size / 16 * 16))
    }
}
