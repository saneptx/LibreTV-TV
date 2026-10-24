package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvFocusRulesTest {
    private val grid=(0 until 12).map { TvFocusRules.Box((it%4)*120.0,(it/4)*70.0,100.0,50.0) }
    @Test fun nativeFocusUsesSameRowsColumnsAndBoundariesAsWeb() {
        assertEquals(6,TvFocusRules.next(grid,5,66))
        assertEquals(4,TvFocusRules.next(grid,5,17))
        assertEquals(9,TvFocusRules.next(grid,5,130))
        assertEquals(1,TvFocusRules.next(grid,5,33))
        assertEquals(0,TvFocusRules.next(grid,0,17))
        assertEquals(11,TvFocusRules.next(grid,11,130))
        assertEquals(0,TvFocusRules.next(grid,-1,130))
        assertEquals(-1,TvFocusRules.next(emptyList(),-1,130))
    }
}
