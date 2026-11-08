package org.libretv.tv

import org.junit.Assert.*
import org.junit.Test

class PlaylistFilterTest {
    private fun group(name: String, seconds: Int = 5, count: Int = 2) =
        "#EXT-X-DISCONTINUITY\n" + (1..count).joinToString("\n") { "#EXTINF:$seconds,\n$name-$it.ts" }
    @Test fun removesMarkedAdsAndPreservesEncryption() {
        val input = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" + group("content", 60) + "\n" + group("/ads/ad") + "\n" + group("movie", 60)
        val result = PlaylistFilter.strip(input)
        assertFalse(result.contains("/ads/")); assertTrue(result.contains("content-1.ts")); assertTrue(result.contains("#EXT-X-KEY"))
        assertTrue(result.contains("#EXT-X-DISCONTINUITY"))
    }
    @Test fun doesNotRemoveNormalPackagingGroups() {
        val input = "#EXTM3U\n" + (1..6).joinToString("\n") { group("movie$it") }
        val result = PlaylistFilter.strip(input)
        for (i in 1..6) assertTrue(result.contains("movie$i-1.ts"))
    }
    @Test fun removesShortLeadAndRetainsKeyAndMap() {
        val input = "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXT-X-KEY:URI=\"k\"\n#EXT-X-MAP:URI=\"init\"\n#EXTINF:15,\nad.ts\n" + group("movie", 120)
        val result = PlaylistFilter.strip(input)
        assertFalse(result.contains("ad.ts")); assertTrue(result.contains("movie-1.ts"))
        assertTrue(result.contains("#EXT-X-KEY")); assertTrue(result.contains("#EXT-X-MAP"))
    }
    @Test fun removesOnlyShortInterstitialBetweenLongGroups() {
        val input = "#EXTM3U\n#EXTINF:180,\nfirst.ts\n" + group("short", 10, 1) + "\n" + group("end", 180, 1)
        assertFalse(PlaylistFilter.strip(input).contains("short-1.ts"))
    }
    @Test fun removesFlaggedLinesAndReportsOriginalIntervals() {
        val input = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nfilm0.ts\n#EXTINF:5,\nhttps://x.test/ad0.ts\n#EXTINF:5,\nhttps://x.test/ad1.ts\n#EXTINF:10,\nfilm1.ts\n#EXT-X-ENDLIST"
        val result = PlaylistFilter.filter(input, setOf("https://x.test/ad0.ts", "https://x.test/ad1.ts"))
        assertFalse(result.text.contains("ad0.ts")); assertTrue(result.text.contains("#EXT-X-TARGETDURATION"))
        assertEquals(listOf("#EXTINF:10,", "film0.ts", "#EXT-X-DISCONTINUITY", "#EXTINF:10,", "film1.ts"),
            result.text.split('\n').filter { it.startsWith("#EXTINF") || it.endsWith(".ts") || it == "#EXT-X-DISCONTINUITY" })
        assertEquals(1, result.removals.size); assertEquals(10.0, result.removals[0].start, 1e-9); assertEquals(10.0, result.removals[0].duration, 1e-9)
    }
    @Test fun pinsImplicitAesIvAfterRemoval() {
        val input = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXTINF:4,\nad.ts\n#EXTINF:4,\nb.ts"
        val lines = PlaylistFilter.filter(input, setOf("ad.ts")).text.split('\n')
        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x%032x".format(9L), lines[lines.indexOf("b.ts") - 1])
        assertFalse(lines.subList(0, lines.indexOf("a.ts")).any { it.contains("IV=") })
    }
    @Test fun leadingFlaggedSegmentKeepsHeaderAndNoBoundary() {
        val result = PlaylistFilter.filter("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:3,\nad.ts\n#EXTINF:9,\nfilm.ts", setOf("ad.ts")).text
        assertTrue(result.startsWith("#EXTM3U\n#EXT-X-VERSION:3")); assertFalse(result.contains("DISCONTINUITY")); assertFalse(result.contains("#EXTINF:3"))
    }
    @Test fun unchangedPlaylistHasNoRemovals() {
        val input = "#EXTM3U\n#EXTINF:4,\na.ts"
        val result = PlaylistFilter.filter(input, setOf("other.ts"))
        assertEquals(input, result.text); assertTrue(result.removals.isEmpty())
    }
    @Test fun timeMapConvertsBetweenOriginalAndCleanTime() {
        val removals = listOf(PlaylistFilter.Removal(10.0, 5.0), PlaylistFilter.Removal(40.0, 10.0))
        assertEquals(30.0, AdTimeMap.original(25.0, removals), 1e-9)
        assertEquals(25.0, AdTimeMap.clean(30.0, removals), 1e-9)
        assertEquals(10.0, AdTimeMap.clean(12.0, removals), 1e-9)
        assertEquals(35.0, AdTimeMap.clean(50.0, removals), 1e-9)
        assertEquals(5.0, AdTimeMap.original(5.0, removals), 1e-9)
    }
}
