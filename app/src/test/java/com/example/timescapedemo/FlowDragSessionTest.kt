package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class FlowDragSessionTest {
    private val chips = listOf(
        FlowChipBounds(1, 0f, 0f, 80f, 40f),
        FlowChipBounds(2, 88f, 0f, 168f, 40f),
        FlowChipBounds(3, 176f, 0f, 256f, 40f)
    )

    @Test fun centersAndGapsHaveDistinctActions() {
        assertEquals(FlowDragHit.Center(2), flowDragHit(chips, 1, 128f, 20f, false))
        assertEquals(FlowDragHit.Insert(1, 84f), flowDragHit(chips, 1, 84f, 20f, false))
        assertEquals(FlowDragHit.Insert(2, 172f), flowDragHit(chips, 1, 165f, 20f, false))
        assertEquals(FlowDragHit.Insert(0, 0f), flowDragHit(chips, 1, 1f, 20f, false))
        assertEquals(FlowDragHit.Insert(3, 256f), flowDragHit(chips, 1, 255f, 20f, false))
    }

    @Test fun sourceAndOutsideCannotMerge() {
        assertEquals(FlowDragHit.Outside, flowDragHit(chips, 1, 40f, 20f, false))
        assertEquals(FlowDragHit.Outside, flowDragHit(chips, 1, 128f, -1f, false))
        assertEquals(FlowDragHit.Outside, flowDragHit(chips, 1, 128f, 41f, false))
    }

    @Test fun onlyMiddleHalfWidthAndSeventyPercentHeightArmMerge() {
        assertEquals(FlowDragHit.Center(2), flowDragHit(chips, 1, 108f, 6f, false))
        assertEquals(FlowDragHit.Center(2), flowDragHit(chips, 1, 148f, 34f, false))
        assertTrue(flowDragHit(chips, 1, 107.9f, 20f, false) is FlowDragHit.Insert)
        assertTrue(flowDragHit(chips, 1, 148.1f, 20f, false) is FlowDragHit.Insert)
        assertTrue(flowDragHit(chips, 1, 128f, 5.9f, false) is FlowDragHit.Insert)
        assertTrue(flowDragHit(chips, 1, 128f, 34.1f, false) is FlowDragHit.Insert)
    }

    @Test fun mergeRequiresFullSixHundredMilliseconds() {
        val drag = FlowDragSession(1)
        drag.hover(FlowDragHit.Center(2), 1000)
        assertFalse(drag.mergeReady(1599))
        assertEquals(1L, drag.remainingDwell(1599))
        assertTrue(drag.mergeReady(1600))
    }

    @Test fun mergeProgressTracksDwellAndClearsWhenLeaving() {
        val drag = FlowDragSession(1)
        drag.hover(FlowDragHit.Center(2), 1000)
        assertEquals(0f, drag.mergeProgress(1000), 0f)
        assertEquals(.5f, drag.mergeProgress(1300), .0001f)
        assertTrue(drag.mergeProgress(1599) < 1f)
        assertEquals(1f, drag.mergeProgress(1600), 0f)
        assertEquals(1f, drag.mergeProgress(5000), 0f)
        drag.hover(FlowDragHit.Insert(1, 84f), 5001)
        assertEquals(0f, drag.mergeProgress(6000), 0f)
        drag.hover(FlowDragHit.Center(3), 6000)
        assertEquals(0f, drag.mergeProgress(6000), 0f)
    }

    @Test fun motionInsideSameCenterDoesNotRestartDwell() {
        val drag = FlowDragSession(1)
        drag.hover(FlowDragHit.Center(2), 1000)
        drag.hover(FlowDragHit.Center(2), 1400)
        assertTrue(drag.mergeReady(1600))
    }

    @Test fun leavingOrChangingTargetResetsMergeIntent() {
        val drag = FlowDragSession(1)
        drag.hover(FlowDragHit.Center(2), 0)
        assertTrue(drag.mergeReady(600))
        drag.hover(FlowDragHit.Outside, 610)
        assertFalse(drag.mergeReady(900))
        drag.hover(FlowDragHit.Center(2), 1000)
        assertFalse(drag.mergeReady(1599))
        drag.hover(FlowDragHit.Center(3), 1600)
        assertFalse(drag.mergeReady(2199))
        assertTrue(drag.mergeReady(2200))
        drag.hover(FlowDragHit.Insert(2, 172f), 2300)
        assertFalse(drag.mergeReady(3000))
    }

    @Test fun rtlInsertionUsesLogicalFlowOrder() {
        val rtl = chips.reversed()
        assertEquals(FlowDragHit.Center(2), flowDragHit(rtl, 1, 128f, 20f, true))
        assertEquals(FlowDragHit.Insert(0, 256f), flowDragHit(rtl, 1, 255f, 20f, true))
        assertEquals(FlowDragHit.Insert(1, 172f), flowDragHit(rtl, 1, 172f, 20f, true))
        assertEquals(FlowDragHit.Insert(2, 84f), flowDragHit(rtl, 1, 84f, 20f, true))
    }
}
