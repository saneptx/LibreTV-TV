package org.libretv.tv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PasteImportTest {
    private fun settings() = Settings(File.createTempFile("settings", ".json").apply { delete(); deleteOnExit() },
        JSONArray().put(JSONObject().put("name", "内置").put("url", "https://builtin.example/api.php/provide/vod/")))

    @Test fun linesWithNamesApisAndSubscriptions() {
        val pasted = Subscriptions.parsePasted("""
            甲资源 https://a.example/api.php/provide/vod
            乙资源,https://b.example/api.php/provide/vod/
            丙${'$'}http://c.example/inc/api.php?ac=list
            https://d.example/api.php/provide/vod
            这是一行说明文字
            订阅：https://list.example/sources.json
            https://raw.example/user/repo/main/tvbox.json。
            https://a.example/api.php/provide/vod
        """.trimIndent())
        assertEquals(listOf("甲资源" to "https://a.example/api.php/provide/vod", "乙资源" to "https://b.example/api.php/provide/vod", "丙" to "http://c.example/inc/api.php?ac=list", "d.example" to "https://d.example/api.php/provide/vod"),
            pasted.sources.map { it.optString("name") to it.optString("url") })
        assertEquals(listOf("https://list.example/sources.json", "https://raw.example/user/repo/main/tvbox.json"), pasted.subscriptions)
    }
    @Test fun wholeJsonConfig() {
        val pasted = Subscriptions.parsePasted("""{"sites":[{"name":"站A","type":1,"api":"https://sa.example/api.php/provide/vod/"},{"name":"蜘蛛","type":3,"api":"csp_X"}]}""")
        assertEquals(listOf("https://sa.example/api.php/provide/vod"), pasted.sources.map { it.optString("url") })
        assertTrue(pasted.note.contains("跳过 1 个"))
        assertEquals(2, Subscriptions.parsePasted("""[{"name":"一","url":"https://one.example/vod"},{"url":"https://two.example/vod"}]""").sources.size)
    }
    @Test fun rejectsEmptyOrUnrecognised() {
        assertTrue(runCatching { Subscriptions.parsePasted("   ") }.isFailure)
        assertTrue(runCatching { Subscriptions.parsePasted("今天天气不错\n没有任何地址") }.isFailure)
        assertTrue(runCatching { Subscriptions.parsePasted("{ 不是 json") }.isFailure)
    }
    @Test fun importSkipsExistingAndSelectsNew() {
        val s = settings()
        val pasted = Subscriptions.parsePasted("内置重复 https://builtin.example/api.php/provide/vod\n新的 https://new.example/api.php/provide/vod\n新的又一次 https://new.example/api.php/provide/vod/")
        val result = Subscriptions.importPasted(s, pasted, null)
        assertEquals(1, result.optInt("added")); assertEquals(1, result.optInt("skipped"))
        val added = s.customSources().single()
        assertEquals("新的", added.optString("name")); assertTrue(added.optString("key") in s.selectedKeys())
        assertFalse("再导一次不会重复", s.addCustomSource("x", "https://new.example/api.php/provide/vod"))
        assertEquals(2, s.activeSources().size)
    }
    @Test fun removeAndRestoreSources() {
        val s = Settings(File.createTempFile("settings", ".json").apply { delete(); deleteOnExit() },
            JSONArray().put(JSONObject().put("name", "内置一").put("url", "https://one.example/vod")).put(JSONObject().put("name", "内置二").put("url", "https://two.example/vod")))
        s.addCustomSource("自己加的", "https://mine.example/vod")
        assertEquals(3, s.allSources().size); assertEquals(3, s.activeSources().size)
        // 手动添加的：直接移除
        assertTrue(s.removeSource("custom_0")); assertTrue(s.customSources().isEmpty()); assertTrue(s.hiddenUrls().isEmpty())
        // 内置的：不再显示、不再搜索，但旧记录仍能解析到它
        assertTrue(s.removeSource("env_0"))
        assertEquals(listOf("env_1"), s.allSources().map { it.optString("key") }); assertEquals(1, s.activeSources().size)
        assertEquals("内置一", s.resolveSource("env_0")?.optString("name"))
        assertFalse("已删除的不能再删一次", s.removeSource("env_0"))
        // 重新添加同一个地址等于恢复
        assertTrue(s.addCustomSource("随便", "https://one.example/vod/")); assertEquals(2, s.allSources().size); assertTrue(s.customSources().isEmpty())
        s.removeSource("env_0"); s.removeSource("env_1"); assertTrue(s.activeSources().isEmpty())
        assertEquals(2, s.restoreHiddenSources()); assertEquals(2, s.activeSources().size)
    }
    @Test fun subscriptionSourcesStayDeletedAcrossResync() {
        val s = settings(); val url = "https://list.example/sources.json"
        val list = listOf(JSONObject().put("name", "A").put("url", "https://a.example/vod"), JSONObject().put("name", "B").put("url", "https://b.example/vod"))
        s.addSubscription(url); assertEquals(2, s.applySubscriptionSources(url, list))
        val keyB = s.customSources().first { it.optString("name") == "B" }.optString("key")
        assertTrue(s.removeSource(keyB))
        assertEquals("再次同步不会把删掉的加回来", 1, s.applySubscriptionSources(url, list)); assertEquals(listOf("A"), s.customSources().map { it.optString("name") })
        // 删除整个订阅：它带来的片源一起消失，内置的不受影响
        assertTrue(s.removeSubscription(url)); assertTrue(s.customSources().isEmpty()); assertTrue(s.subscriptions().isEmpty()); assertEquals(1, s.activeSources().size)
    }
}
