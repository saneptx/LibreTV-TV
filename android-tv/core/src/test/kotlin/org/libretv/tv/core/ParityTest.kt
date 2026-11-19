package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与原网页版（TypeScript）实现逐项对照：core-fixtures.json 由原实现对同一批输入算出的结果生成，
 * Kotlin 实现必须给出相同的结果。
 */
class ParityTest {
    private val fixtures = JSONObject(javaClass.classLoader!!.getResourceAsStream("core-fixtures.json")!!.bufferedReader().use { it.readText() })
    private fun cases(name: String) = fixtures.getJSONArray(name).objects()
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray?) = b?.joinToString("") { "%02x".format(it) }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else optString(key)
    /** 按 JSON 语义比较（对象不看键的顺序，数字按数值，缺失与 null 等同） */
    private fun same(expected: Any?, actual: Any?, path: String) {
        val e = if (expected == JSONObject.NULL) null else expected; val a = if (actual == JSONObject.NULL) null else actual
        when {
            e is JSONObject && a is JSONObject -> (e.keys().asSequence() + a.keys().asSequence()).toSet().forEach { same(e.opt(it), a.opt(it), "$path.$it") }
            e is JSONArray && a is JSONArray -> { assertEquals("$path 长度", e.length(), a.length()); for (i in 0 until e.length()) same(e.opt(i), a.opt(i), "$path[$i]") }
            e is Number && a is Number -> assertEquals(path, e.toDouble(), a.toDouble(), 1e-6)
            else -> assertEquals(path, e, a)
        }
    }

    @Test fun relevance() { for (c in cases("relevance")) assertEquals("「${c.optString("name")}」对「${c.optString("query")}」", c.getBoolean("expected"), CmsParser.isRelevant(c.optString("name"), c.optString("query"))) }
    @Test fun normalize() { for (c in cases("normalize")) assertEquals(c.optString("input"), c.optString("expected"), CmsParser.normalizeTitle(c.optString("input"))) }
    @Test fun episodes() { for (c in cases("episodes")) same(c.getJSONArray("expected"), JSONArray(CmsParser.extractEpisodes(c.optString("input"))), c.optString("input")) }
    @Test fun detail() {
        val d = fixtures.getJSONObject("detail")
        same(d.getJSONObject("expected"), CmsParser.parseDetail(d.getJSONObject("data"), d.getJSONObject("source")), "detail")
        same(d.getJSONObject("contentOnly"), CmsParser.parseDetail(JSONObject().put("list", JSONArray().put(JSONObject().put("vod_name", "x").put("vod_content", "看 \$https://c/v/index.m3u8 和 \$http://d/2.m3u8"))), d.getJSONObject("source")), "contentOnly")
        val p = fixtures.getJSONObject("detailPage")
        same(p.getJSONObject("expected"), CmsParser.parseDetailPage(p.optString("html"), p.getJSONObject("source")), "detailPage")
        val s = fixtures.getJSONObject("searchList")
        same(s.getJSONArray("expected"), JSONArray(CmsParser.parseSearchList(s.getJSONObject("data"), s.getJSONObject("source"))), "searchList")
    }
    @Test fun adult() { for (c in cases("adult")) assertEquals(c.optString("input"), c.getBoolean("expected"), CmsParser.isAdult(c.nullable("input"))) }
    @Test fun remarks() {
        for (c in cases("remarks")) assertEquals(c.optString("input"), c.nullable("expected"), Remarks.format(c.nullable("input")))
        for (c in cases("remarkGroups")) assertEquals(c.optString("input"), c.nullable("expected"), Remarks.inferGroup((0 until c.getJSONArray("input").length()).map { i -> c.getJSONArray("input").let { if (it.isNull(i)) null else it.optString(i) } }))
    }
    @Test fun qualityLabels() { for (c in cases("qualityLabels")) assertEquals("${c.optInt("input")}", c.nullable("expected"), Quality.label(c.optInt("input"))) }
    @Test fun qualityMerge() {
        val q = fixtures.getJSONObject("qualityMerge"); val now = q.getLong("now")
        val merged = Quality.merge(q.getJSONObject("current"), q.getJSONObject("incoming"), now)
        same(q.getJSONObject("expected"), merged, "merge")
        same(q.getJSONObject("again"), Quality.merge(merged, JSONObject().put("a", JSONObject().put("from", "play").put("height", 480).put("timestamp", now)), now), "again")
    }
    @Test fun prefixHash() { for (c in cases("prefixHash")) assertEquals(c.optString("expected"), Ads.prefixHash(bytes(c.optString("hex")))) }
    @Test fun codec() {
        for (c in cases("codec")) {
            val buf = bytes(c.optString("hex")); val name = c.optString("name")
            fun size(s: Codec.Size?) = s?.let { JSONObject().put("width", it.width).put("height", it.height) }
            same(c.opt("ts"), size(Codec.parseTsVideoResolution(buf)), "$name.ts"); same(c.opt("mp4"), size(Codec.parseMp4VideoResolution(buf)), "$name.mp4")
            assertEquals("$name.sig", c.nullable("sig"), Codec.parseTsSignature(buf)); assertEquals("$name.looksMp4", c.getBoolean("looksMp4"), Codec.looksLikeMp4(buf))
            same(c.opt("pts"), Codec.parseTsFirstPts(buf), "$name.pts")
        }
    }
    @Test fun playlists() {
        for (c in cases("playlists")) {
            val name = c.optString("name"); val (groups, total) = Ads.playlistGroups(c.optString("text"), c.optString("base"))
            assertEquals("$name.total", c.getDouble("total"), total, 1e-6)
            same(c.getJSONArray("groups"), JSONArray(groups.map { g -> JSONObject().put("start", g.start).put("duration", g.duration).put("lines", JSONArray(g.lines)).put("firstUrl", g.firstUrl).put("dir", g.dir)
                .put("keyUri", g.keyUri ?: JSONObject.NULL).put("iv", hex(g.keyIv) ?: JSONObject.NULL).put("initUrl", g.initUrl ?: JSONObject.NULL) }), "$name.groups")
            same(c.getJSONArray("heuristic"), JSONArray(Ads.heuristicRemovedLines(c.optString("text"))), "$name.heuristic")
        }
    }
    @Test fun decisions() {
        for (c in cases("decisions")) {
            val name = c.optString("name")
            val groups = c.getJSONArray("groups").objects().map { g -> Ads.Group(g.getDouble("start"), g.getDouble("duration"), mutableListOf(g.optString("line")), g.optString("line"), g.optString("dir"), null, null, null).apply { sig = g.nullable("sig"); hash = g.nullable("hash"); pts = if (g.isNull("pts")) null else g.getDouble("pts") } }
            val media = Ads.Media("u", "", c.getDouble("total"), groups, c.getJSONArray("heuristic").strings()).apply { contentDir = c.nullable("contentDir") }
            val learned = c.getJSONArray("learned").objects().map { Ads.Learned(it.optString("hash"), it.nullable("sig")?.takeIf { _ -> it.has("sig") }, it.nullable("dir")?.takeIf { _ -> it.has("dir") }, it.getDouble("duration"), null, it.optLong("at")) }
            same(c.getJSONArray("spans"), JSONArray(Ads.timelineAdGroups(groups, c.getDouble("total")).map { span -> JSONArray(span.map { groups.indexOf(it) }) }), "$name.spans")
            val d = Ads.decide(listOf(media), learned)
            same(c.getJSONObject("expected"), JSONObject().put("extraLines", JSONArray(d.extraLines)).put("heuristicLines", JSONArray(d.heuristicLines)).put("codecGroups", d.codecGroups).put("learnedGroups", d.learnedGroups).put("repeatGroups", d.repeatGroups).put("timelineGroups", d.timelineGroups), "$name.decision")
        }
    }
    @Test fun probeParsing() {
        for (c in cases("bestHeight")) same(c.opt("expected"), QualityProbe.parseBestHeight(c.optString("text")), c.optString("text"))
        for (c in cases("mediaInfo")) { val i = QualityProbe.parseMediaPlaylist(c.optString("text"))
            same(c.getJSONObject("expected"), JSONObject().put("initUri", i.initUri ?: JSONObject.NULL).put("firstSegment", i.firstSegment ?: JSONObject.NULL).put("keyMethod", i.keyMethod ?: JSONObject.NULL).put("keyUri", i.keyUri ?: JSONObject.NULL).put("keyIv", hex(i.keyIv) ?: JSONObject.NULL).put("sequence", i.sequence), "mediaInfo") }
        for (c in cases("episodeOrder")) same(c.getJSONArray("expected"), JSONArray(QualityProbe.episodeOrder(c.getJSONArray("input").strings())), "episodeOrder")
    }
    @Test fun subscriptions() {
        for (c in cases("subscriptions")) {
            val result = runCatching { Subscriptions.parse(Subscriptions.parseJson(c.optString("text"))) }
            if (c.isNull("sources")) { assertTrue("应当失败：${c.optString("text").take(40)}", result.isFailure); continue }
            val parsed = result.getOrThrow()
            assertEquals(c.nullable("name"), parsed.name)
            same(c.getJSONArray("sources"), JSONArray(parsed.sources.map { JSONObject().put("name", it.optString("name")).put("url", it.optString("url")).put("detail", it.str("detail") ?: JSONObject.NULL).put("isAdult", it.optBoolean("isAdult")) }), "sources")
        }
        for (c in cases("subKeys")) assertEquals(c.optString("expected"), Settings.subKeyPrefix(c.optString("input")))
    }
    @Test fun desktop() {
        for (c in cases("sourcePosition")) { val removals = Desktop.intervals(c.getJSONArray("removals"))
            assertEquals(c.getDouble("expected"), Desktop.sourcePosition(c.getDouble("position"), removals), 1e-6); assertEquals(c.getDouble("duration"), Desktop.sourceDuration(c.getDouble("position"), removals), 1e-6) }
        for (c in cases("desktopAddress")) { val result = runCatching { Desktop.address(c.optString("input")) }
            if (c.isNull("value")) assertTrue("应当拒绝 ${c.optString("input")}", result.isFailure) else assertEquals(c.optString("input"), c.optString("value"), result.getOrThrow()) }
        for (c in cases("processing")) { val info = Desktop.validProcessingInfo(c.getJSONObject("info"))
            assertEquals(c.getJSONObject("info").toString(), c.getBoolean("valid"), info != null)
            if (info != null) same(c.getJSONObject("presentation"), Desktop.presentation(info), "presentation") }
    }
    @Test fun groups() {
        val g = fixtures.getJSONObject("groups")
        val actual = Catalog(SourceCache(java.io.File.createTempFile("cache", ".json").apply { delete() })).groups(g.getJSONArray("list").objects())
        same(g.getJSONArray("expected"), JSONArray(actual), "groups")
    }
}
