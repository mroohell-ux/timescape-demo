package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class CardMoveEdgeDwellTest {
    @Test fun stableHoverRequiresTheFull650Milliseconds() {
        val dwell = CardMoveEdgeDwell()
        assertFalse(dwell.ready(1, false, 1000))
        assertEquals(650L, dwell.remaining(1000))
        for (time in 1001L..1649L) assertFalse(dwell.ready(1, false, time))
        assertEquals(1L, dwell.remaining(1649))
        assertTrue(dwell.ready(1, false, 1650))
        assertFalse(dwell.ready(1, false, 1650))
        assertFalse(dwell.ready(1, false, 2299))
        assertTrue(dwell.ready(1, false, 2300))
    }

    @Test fun preparationAndMotionNeverAccumulateTheNextDwell() {
        val dwell = CardMoveEdgeDwell()
        assertFalse(dwell.ready(1, false, 1000))
        assertTrue(dwell.ready(1, false, 1650))
        for (time in listOf(1650L, 1700L, 5000L, 9000L)) {
            assertFalse(dwell.ready(1, true, time))
        }
        assertFalse(dwell.ready(1, false, 9500))
        assertFalse(dwell.ready(1, false, 10149))
        assertTrue(dwell.ready(1, false, 10150))
    }

    @Test fun startingAnUnrelatedSwitchAlsoDiscardsPartialDwell() {
        val dwell = CardMoveEdgeDwell()
        assertFalse(dwell.ready(1, false, 1000))
        assertFalse(dwell.ready(1, true, 1600))
        assertFalse(dwell.ready(1, false, 1800))
        assertFalse(dwell.ready(1, false, 2449))
        assertTrue(dwell.ready(1, false, 2450))
    }

    @Test fun leavingOrChangingEdgesRestartsTheWait() {
        for (nextDirection in listOf(0, -1)) {
            val dwell = CardMoveEdgeDwell()
            assertFalse(dwell.ready(1, false, 1000))
            assertFalse(dwell.ready(nextDirection, false, 1600))
            assertFalse(dwell.ready(1, false, 1700))
            assertFalse(dwell.ready(1, false, 2349))
            assertTrue(dwell.ready(1, false, 2350))
        }
    }

    @Test fun cancellationCannotRetainAnArmedOrPartialWait() {
        val dwell = CardMoveEdgeDwell()
        assertFalse(dwell.ready(1, false, 1000))
        dwell.reset()
        assertFalse(dwell.ready(1, false, 2000))
        assertFalse(dwell.ready(1, false, 2649))
        assertTrue(dwell.ready(1, false, 2650))
        dwell.reset()
        assertFalse(dwell.ready(-1, false, 4000))
        assertTrue(dwell.ready(-1, false, 4650))
    }

    @Test fun onlyTheOuter12PercentArmsAnEdgeWithRtlMirroring() {
        for (rtl in listOf(false, true)) {
            val sign = if (rtl) -1 else 1
            assertEquals(-sign, cardMoveEdgeDirection(0f, 1000, rtl))
            assertEquals(-sign, cardMoveEdgeDirection(119f, 1000, rtl))
            for (x in listOf(120f, 180f, 500f, 820f, 880f)) {
                assertEquals(0, cardMoveEdgeDirection(x, 1000, rtl))
            }
            assertEquals(sign, cardMoveEdgeDirection(881f, 1000, rtl))
            assertEquals(sign, cardMoveEdgeDirection(1000f, 1000, rtl))
        }
        for (x in listOf(-1f, 1001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0, cardMoveEdgeDirection(x, 1000, false))
        }
        assertEquals(0, cardMoveEdgeDirection(0f, 0, false))
    }
}
