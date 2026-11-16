package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSeriesTest {
    @Test
    fun 从片名推出系列名() {
        assertEquals("爱情公寓", TvSeries.base("爱情公寓3"))
        assertEquals("爱情公寓", TvSeries.base("爱情公寓"))
        assertEquals("庆余年", TvSeries.base("庆余年第二季"))
        assertEquals("庆余年", TvSeries.base("庆余年 第二季"))
        assertEquals("007", TvSeries.base("007：大破量子危机"))
        assertEquals("复仇者联盟", TvSeries.base("复仇者联盟3：无限战争"))
        assertEquals("爱，死亡和机器人", TvSeries.base("爱，死亡和机器人第一季"))
        assertEquals("黑鸽", TvSeries.base("黑鸽 第二季"))
        assertEquals("怪奇物语", TvSeries.base("怪奇物语 Season 4"))
        assertEquals("2046", TvSeries.base("2046"))
        assertEquals("1917", TvSeries.base("1917"))
    }

    @Test
    fun 同系列判断() {
        assertTrue(TvSeries.sameSeries("爱情公寓", "爱情公寓"))
        assertTrue(TvSeries.sameSeries("爱情公寓2", "爱情公寓"))
        assertTrue(TvSeries.sameSeries("爱情公寓5", "爱情公寓"))
        assertTrue(TvSeries.sameSeries("爱情公寓（电影版）", "爱情公寓"))
        assertTrue(TvSeries.sameSeries("庆余年第二季", "庆余年"))
        assertTrue(TvSeries.sameSeries("007：无暇赴死", "007"))
        assertFalse(TvSeries.sameSeries("繁花烬处", "繁花"))
        assertFalse(TvSeries.sameSeries("爱情公寓之青春", "爱情"))
        assertFalse(TvSeries.sameSeries("爱情公寓", "爱"))
    }

    @Test
    fun 按季数排序() {
        val names = listOf("爱情公寓5", "爱情公寓（电影版）", "爱情公寓", "爱情公寓3", "爱情公寓2", "爱情公寓4")
        assertEquals(listOf("爱情公寓", "爱情公寓2", "爱情公寓3", "爱情公寓4", "爱情公寓5", "爱情公寓（电影版）"), names.sortedWith(compareBy({ TvSeries.season(it, "爱情公寓") }, { it })))
        assertEquals(2, TvSeries.season("庆余年第二季", "庆余年"))
        assertEquals(12, TvSeries.season("名侦探柯南第十二季", "名侦探柯南"))
        assertEquals(21, TvSeries.season("某剧第二十一季", "某剧"))
        assertEquals(4, TvSeries.season("怪奇物语 Season 4", "怪奇物语"))
    }

    @Test
    fun 整理同系列结果() {
        val entries = listOf(
            Triple("爱情公寓", "正片", 9), Triple("爱情公寓1", "全20集", 3), Triple("爱情公寓第一季", "全20集", 6),
            Triple("爱情公寓2", "全20集", 8), Triple("爱情公寓第三季", "已完结", 2), Triple("爱情公寓3", "全24集", 35),
            Triple("爱情公寓4", "全24集", 9), Triple("爱情公寓5", "全24集", 7), Triple("繁花烬处", "全40集", 9),
        )
        val kept = TvSeries.arrange(entries, "爱情公寓", "爱情公寓3").map { entries[it].first }
        assertEquals(listOf("爱情公寓第一季", "爱情公寓2", "爱情公寓4", "爱情公寓5", "爱情公寓"), kept)
    }
}
