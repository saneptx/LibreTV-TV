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
    @Test fun compatibilityLabelsUseNumericResolution() {
        assertEquals(2160,TvSourceOrder.heightFromLabel("4K"))
        assertEquals(1080,TvSourceOrder.heightFromLabel("1080P"))
        assertEquals(0,TvSourceOrder.heightFromLabel("未知"))
    }
}
