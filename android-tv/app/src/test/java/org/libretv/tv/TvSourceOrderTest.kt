package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSourceOrderTest {
    @Test fun measuredResolutionOutranksLatencyAndTheCurrentSource() {
        val rows=listOf(TvSourceOrder.Value("current",720,true,1),TvSourceOrder.Value("unknown",0,true,1),
            TvSourceOrder.Value("4k",2160,true,500),TvSourceOrder.Value("failed",2160,false,1),
            TvSourceOrder.Value("1080-slow",1080,true,300),TvSourceOrder.Value("1080-fast",1080,true,100))
        assertEquals(listOf("4k","1080-fast","1080-slow","current","unknown","failed"),TvSourceOrder.sorted(rows))
    }
    @Test fun lateResolutionResortsAndEqualValuesKeepDiscoveryOrder() {
        val rows=listOf(TvSourceOrder.Value("low",720),TvSourceOrder.Value("late",0))
        val sorted=TvSourceOrder.sorted(rows.map { if(it.id=="late") it.copy(height=2160) else it })
        assertEquals(listOf("late","low"),sorted)
        assertEquals(listOf("a","b"),TvSourceOrder.sorted(listOf(TvSourceOrder.Value("a",1080),TvSourceOrder.Value("b",1080))))
    }
    @Test fun sameResolutionPrefersHigherBitrateBeforeLatency() {
        val rows=listOf(TvSourceOrder.Value("fast-low",1080,true,50,1800),TvSourceOrder.Value("slow-high",1080,true,400,4200),TvSourceOrder.Value("unknown",1080,true,10))
        assertEquals(listOf("slow-high","fast-low","unknown"),TvSourceOrder.sorted(rows))
    }
    @Test fun compatibilityLabelsUseNumericResolution() {
        assertEquals(1080,TvSourceOrder.heightFromLabel("1080P · 2.4M"))
        assertEquals(2160,TvSourceOrder.heightFromLabel("4K"))
        assertEquals(1080,TvSourceOrder.heightFromLabel("1080P"))
        assertEquals(0,TvSourceOrder.heightFromLabel("未知"))
    }
}
