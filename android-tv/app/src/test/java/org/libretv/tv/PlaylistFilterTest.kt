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
}
