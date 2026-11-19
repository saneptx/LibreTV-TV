package org.libretv.tv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibrarySyncTest {
    private var clock = 1_000_000L
    private fun temp(name: String) = File.createTempFile(name, ".json").apply { delete(); deleteOnExit() }
    private fun library() = Library(temp("library")) { clock }
    private fun entry(key: String, vod: String, title: String, index: Int = 0) = JSONObject().put("sourceKey", key).put("vodId", vod).put("title", title).put("episodeIndex", index).put("totalEpisodes", 10).put("playbackPosition", 0).put("duration", 0)
    /** 手机与电视：电视充当同步对象 */
    private inner class Pair2 {
        val phone = library(); val tv = library()
        val phoneSync = LibrarySync(phone, temp("phone-sync")) { clock }
        val tvSync = LibrarySync(tv, temp("tv-sync")) { clock }
        fun sync() = phoneSync.syncOnce("tv:1", { body, _ -> JSONObject(tvSync.servePeer(JSONObject(body.toString())).toString()) }, Cancel())
    }

    @Test fun historyKeepsProgressAndTrims() {
        val lib = library()
        lib.upsertHistory(entry("a", "1", "甲").put("playbackPosition", 120).put("duration", 600))
        clock += 10; lib.upsertHistory(entry("a", "1", "甲", 1))
        val saved = lib.historyEntry("a", "1")!!
        assertEquals(1, saved.optInt("episodeIndex")); assertEquals(120.0, saved.optDouble("playbackPosition"), 0.0); assertEquals(600.0, saved.optDouble("duration"), 0.0)
        for (i in 0 until 130) { clock++; lib.upsertHistory(entry("s", "v$i", "片$i")) }
        assertEquals(100, lib.history(1000).size)
        assertNull("最旧的被挤掉", lib.historyEntry("a", "1"))
        clock++; lib.updateHistoryProgress("s", "v129", 50.0, 100.0)
        assertEquals("刚更新的排最前", "s_v129", lib.history()[0].optString("id"))
    }
    @Test fun favoritesToggleAndImport() {
        val lib = library(); val card = JSONObject().put("name", " 片名 ").put("year", 2020)
        assertTrue(lib.toggleFavorite(card)); assertEquals("片名|2020", lib.favorites()[0].optString("key"))
        assertFalse(lib.toggleFavorite(card)); assertTrue(lib.favorites().isEmpty())
        assertEquals("删过的不再导入", 0, lib.importFavorites(listOf(card)))
        assertEquals(1, lib.importFavorites(listOf(JSONObject().put("name", "另一部").put("addedAt", 5))))
        assertEquals(5L, lib.favorites()[0].optLong("addedAt")); assertFalse(lib.favorites()[0].getJSONObject("card").has("addedAt"))
    }
    @Test fun persistsToDisk() {
        val file = temp("persist"); val lib = Library(file) { clock }
        lib.addSearch("沙丘"); lib.saveProgress("a", "1", 2, 33.0, 100.0); lib.flush()
        val reopened = Library(file) { clock }
        assertEquals("沙丘", reopened.searches()[0].optString("text")); assertEquals(33.0, reopened.progress("a", "1", 2)!!.optDouble("position"), 0.0)
    }

    @Test fun twoWaySyncAndDeletes() {
        val p = Pair2()
        clock += 10; p.phone.upsertHistory(entry("a", "1", "手机看的")); p.phone.addSearch("手机搜的")
        clock += 10; p.tv.upsertHistory(entry("b", "2", "电视看的")); p.tv.toggleFavorite(JSONObject().put("name", "电视收藏"))
        clock += 10; val first = p.sync()
        assertEquals(2, first.first); assertEquals(2, first.second)
        assertNotNull(p.tv.historyEntry("a", "1")); assertNotNull(p.phone.historyEntry("b", "2")); assertEquals(1, p.phone.favorites().size); assertEquals("手机搜的", p.tv.searches()[0].optString("text"))
        clock += 10; assertEquals("没有变化时不再传输", 0 to 0, p.sync())
        // 电视删除收藏、手机清空搜索：删除也同步过去
        clock += 10; p.tv.toggleFavorite(JSONObject().put("name", "电视收藏")); p.phone.clearSearches()
        clock += 10; p.sync()
        assertTrue(p.phone.favorites().isEmpty()); assertTrue(p.tv.searches().isEmpty())
        // 同一条记录两边都改：后改的赢
        clock += 10; p.phone.upsertHistory(entry("a", "1", "手机看的", 3))
        clock += 10; p.tv.upsertHistory(entry("a", "1", "手机看的", 5))
        clock += 10; p.sync()
        assertEquals(5, p.phone.historyEntry("a", "1")!!.optInt("episodeIndex")); assertEquals(5, p.tv.historyEntry("a", "1")!!.optInt("episodeIndex"))
    }
    @Test fun removingHistoryAlsoRemovesProgressAndSyncs() {
        val p = Pair2()
        clock += 10; p.phone.upsertHistory(entry("a", "1", "甲")); p.phone.saveProgress("a", "1", 0, 30.0, 100.0); p.phone.saveProgress("a", "1", 3, 40.0, 100.0)
        p.phone.upsertHistory(entry("a", "12", "乙")); p.phone.saveProgress("a", "12", 0, 50.0, 100.0)
        clock += 10; p.sync()
        assertNotNull(p.tv.progress("a", "1", 3))
        clock += 10; p.phone.removeHistory("a", "1")
        assertNull(p.phone.historyEntry("a", "1")); assertNull(p.phone.progress("a", "1", 0)); assertNull(p.phone.progress("a", "1", 3))
        assertNotNull("编号相近的另一部片不受影响", p.phone.progress("a", "12", 0)); assertNotNull(p.phone.historyEntry("a", "12"))
        clock += 10; p.sync()
        assertNull(p.tv.historyEntry("a", "1")); assertNull(p.tv.progress("a", "1", 3)); assertNotNull(p.tv.historyEntry("a", "12"))
        clock += 10; p.phone.toggleFavorite(JSONObject().put("name", "收藏")); p.phone.clearHistory()
        clock += 10; p.sync()
        assertTrue(p.tv.history().isEmpty()); assertNull(p.tv.progress("a", "12", 0)); assertEquals("收藏不受影响", 1, p.tv.favorites().size)
    }
    @Test fun deleteLosesToNewerEdit() {
        val p = Pair2()
        clock += 10; p.phone.upsertHistory(entry("a", "1", "片")); clock += 10; p.sync()
        clock += 10; p.tv.removeHistory("a", "1")
        clock += 10; p.phone.upsertHistory(entry("a", "1", "片", 2))   // 删除之后又看了
        clock += 10; p.sync()
        assertEquals(2, p.tv.historyEntry("a", "1")!!.optInt("episodeIndex")); assertNotNull(p.phone.historyEntry("a", "1"))
    }
    @Test fun wipedDeviceRecoversFromPeer() {
        val p = Pair2()
        clock += 10; p.phone.upsertHistory(entry("a", "1", "片")); clock += 10; p.sync()
        // 手机资料库被清空（同步进度还在）：下次同步从电视整份取回
        val stateFile = temp("state"); val wiped = library()
        val sync = LibrarySync(wiped, stateFile) { clock }
        clock += 10; sync.syncOnce("tv:1", { body, _ -> JSONObject(p.tvSync.servePeer(JSONObject(body.toString())).toString()) }, Cancel())
        assertNotNull(wiped.historyEntry("a", "1"))
        // 状态落盘后重新打开并清空资料库，依然能取回
        val again = library(); val sync2 = LibrarySync(again, stateFile) { clock }
        clock += 10; sync2.syncOnce("tv:1", { body, _ -> JSONObject(p.tvSync.servePeer(JSONObject(body.toString())).toString()) }, Cancel())
        assertNotNull(again.historyEntry("a", "1"))
    }
    @Test fun peerRestartTriggersFullResend() {
        val p = Pair2()
        clock += 10; p.phone.upsertHistory(entry("a", "1", "片")); clock += 10; p.sync()
        // 电视换了一台（资料是空的、标识不同）：手机把自己的全部重推过去
        val tv2 = library(); val tv2Sync = LibrarySync(tv2, temp("tv2")) { clock }
        clock += 10; p.phoneSync.syncOnce("tv:1", { body, _ -> JSONObject(tv2Sync.servePeer(JSONObject(body.toString())).toString()) }, Cancel())
        assertNotNull(tv2.historyEntry("a", "1"))
    }
    @Test fun largeLibraryPagesThroughPeer() {
        val p = Pair2(); val big = "海报".repeat(3000)
        for (i in 0 until 400) { clock++; p.tv.toggleFavorite(JSONObject().put("name", "片$i").put("pic", big)) }
        clock += 10; val result = p.sync()
        assertEquals(400, result.second); assertEquals(400, p.phone.favorites().size)
    }
    @Test fun legacyImportMergesByClock() {
        val lib = library()
        clock = 5000; lib.upsertHistory(entry("a", "1", "新的", 9))
        lib.importAll(JSONObject().put("history", org.json.JSONArray().put(entry("a", "1", "旧的", 1).put("id", "a_1").put("timestamp", 100)).put(entry("b", "2", "只有旧版有").put("id", "b_2").put("timestamp", 200)))
            .put("tombstones", org.json.JSONArray().put(JSONObject().put("table", "searchHistory").put("key", "x").put("at", 50))))
        assertEquals(9, lib.historyEntry("a", "1")!!.optInt("episodeIndex")); assertNotNull(lib.historyEntry("b", "2"))
        assertTrue(lib.collectChanges(0).any { it.optBoolean("deleted") && it.optString("key") == "x" })
    }
}
