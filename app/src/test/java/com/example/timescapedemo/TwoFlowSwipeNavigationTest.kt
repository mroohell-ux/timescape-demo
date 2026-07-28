package com.example.timescapedemo

import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFlowSwipeNavigationTest {
    @Test
    fun rightAdvancesAndLeftReturns() {
        assertEquals(2, twoFlowSwipeTarget(currentIndex = 1, itemCount = 4, horizontalDistance = 80f))
        assertEquals(0, twoFlowSwipeTarget(currentIndex = 1, itemCount = 4, horizontalDistance = -80f))
    }

    @Test
    fun stopsAtSeriesEnds() {
        assertEquals(0, twoFlowSwipeTarget(currentIndex = 0, itemCount = 4, horizontalDistance = -80f))
        assertEquals(3, twoFlowSwipeTarget(currentIndex = 3, itemCount = 4, horizontalDistance = 80f))
    }
}
