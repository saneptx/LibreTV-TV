package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackRulesTest {
    @Test fun previewCannotSeekBeforeStartOrPastEnd() {
        assertEquals(0.0, TvPlaybackRules.preview(3.0, -10, 100.0), .001)
        assertEquals(99.75, TvPlaybackRules.preview(95.0, 10, 100.0), .001)
        assertEquals(30.0, TvPlaybackRules.preview(20.0, 10, 100.0), .001)
        assertEquals(0.0, TvPlaybackRules.preview(Double.NaN, 10, 100.0), .001)
        assertEquals(0.0, TvPlaybackRules.preview(30.0, 10, 0.0), .001)
    }
    @Test fun unknownAndLiveDurationsDoNotOpenSeekPreview() {
        org.junit.Assert.assertFalse(TvPlaybackRules.canSeek(0.0))
        org.junit.Assert.assertFalse(TvPlaybackRules.canSeek(-1.0))
        org.junit.Assert.assertFalse(TvPlaybackRules.canSeek(Double.NaN))
        org.junit.Assert.assertFalse(TvPlaybackRules.canSeek(Double.POSITIVE_INFINITY))
        org.junit.Assert.assertTrue(TvPlaybackRules.canSeek(120.0))
    }
    @Test fun labelsHandleHoursAndInvalidNetworkSamples() {
        assertEquals("01:05", TvPlaybackRules.time(65.8))
        assertEquals("1:01:01", TvPlaybackRules.time(3661.0))
        assertEquals("00:00", TvPlaybackRules.time(-4.0))
        assertEquals("0 KB/s", TvPlaybackRules.speed(Double.NaN))
        assertEquals("< 1 KB/s", TvPlaybackRules.speed(500.0))
        assertEquals("256 KB/s", TvPlaybackRules.speed(256.0 * 1024))
        assertEquals("1.50 MB/s", TvPlaybackRules.speed(1.5 * 1024 * 1024))
    }
}
