package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvFocusRulesTest {
    private fun box(x: Int, y: Int, w: Int, h: Int) = TvFocusRules.Box(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
    private val grid=(0 until 12).map { TvFocusRules.Box((it%4)*120.0,(it/4)*70.0,100.0,50.0) }
    @Test fun gridMovesAlongRowsAndColumnsAndStopsAtEdges() {
        assertEquals(6,TvFocusRules.next(grid,5,66))
        assertEquals(4,TvFocusRules.next(grid,5,17))
        assertEquals(9,TvFocusRules.next(grid,5,130))
        assertEquals(1,TvFocusRules.next(grid,5,33))
        assertEquals(0,TvFocusRules.next(grid,0,17))
        assertEquals(11,TvFocusRules.next(grid,11,130))
        assertEquals(0,TvFocusRules.next(grid,-1,130))
        assertEquals(-1,TvFocusRules.next(emptyList(),-1,130))
        // 行尾按右不跳到下一行
        assertEquals(3,TvFocusRules.next(grid,3,66))
    }
    @Test fun verticalMoveStopsAtTheNearestRowEvenWhenItIsOffToTheSide() {
        // 0：第三列的搜索词；1：下一行只有最左边一张卡片；2–4：再下一行的三张卡片
        val home=listOf(box(400,0,160,30), box(0,60,160,100), box(0,200,160,100), box(200,200,160,100), box(400,200,160,100))
        assertEquals(1,TvFocusRules.next(home,0,130))
        assertEquals(2,TvFocusRules.next(home,1,130))
        assertEquals(1,TvFocusRules.next(home,4,33))
        // 记住原来的列：从第三列经过只有一张卡片的行再往下，回到第三列
        assertEquals(4,TvFocusRules.next(listOf(home[0],box(0,60,600,100),home[2],home[3],home[4]),1,130,anchor=480.0))
    }
    @Test fun listRowsWithTrailingButtonsAreReachable() {
        // 面板右上角的关闭按钮，下面两行各有「播放」「删除」
        val panel=listOf(box(700,0,80,36), box(600,80,60,36), box(680,80,60,36), box(600,140,60,36), box(680,140,60,36))
        assertEquals(2,TvFocusRules.next(panel,0,130))
        assertEquals(1,TvFocusRules.next(panel,2,17))
        assertEquals(4,TvFocusRules.next(panel,2,130))
        assertEquals(3,TvFocusRules.next(panel,1,130))
        assertEquals(0,TvFocusRules.next(panel,1,33))
    }
    @Test fun sideBySideColumnsStayInTheirOwnColumn() {
        // 左右两栏行高不同：左栏向下仍在左栏
        val columns=listOf(box(0,100,200,40), box(0,150,200,40), box(300,120,200,40), box(300,170,200,40))
        assertEquals(1,TvFocusRules.next(columns,0,130))
        assertEquals(2,TvFocusRules.next(columns,0,66))
        assertEquals(3,TvFocusRules.next(columns,2,130))
    }
}
