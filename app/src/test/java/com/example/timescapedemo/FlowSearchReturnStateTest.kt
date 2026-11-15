package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class FlowSearchReturnStateTest {
    @Test
    fun browsingResultsDoesNotOverwriteOriginalCardOrExpansion() {
        for (focused in listOf(false, true)) {
            val flow = flow(10, focused)
            val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
            flow.lastViewedCardId = 5
            flow.lastViewedCardIndex = 0
            flow.lastViewedCardFocused = !focused
            assertEquals(CardFlowPosition(3, 2, focused), state.positionFor(flow))
            assertEquals(10L, state.flowId)
        }
    }

    @Test
    fun restoresEveryFlowVisitedDuringSearchAndKeepsOriginalFlow() {
        val first = flow(10, true)
        val second = flow(20, false)
        val state = FlowSearchReturnState.capture(listOf(first, second), first.id)
        for (flow in listOf(first, second)) {
            flow.lastViewedCardId = 1
            flow.lastViewedCardIndex = 0
            flow.lastViewedCardFocused = false
        }
        assertEquals(CardFlowPosition(3, 2, true), state.positionFor(first))
        assertEquals(CardFlowPosition(3, 2, false), state.positionFor(second))
        assertEquals(first.id, state.flowId)
    }

    @Test
    fun clearingAndRetypingWithinOneSessionRetainsTheSameReturnPosition() {
        val flow = flow(10, true)
        val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
        repeat(3) {
            flow.lastViewedCardId = 5
            flow.lastViewedCardIndex = 0
            flow.lastViewedCardFocused = false
            val position = checkNotNull(state.positionFor(flow))
            position.restoreTo(flow, flow.cards)
            assertEquals(CardFlowPosition(3, 2, true), position)
        }
    }

    @Test
    fun returnPositionTracksIdentityAcrossReorderingAndEarlierDeletion() {
        val flow = flow(10, true)
        val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
        state.onCardRemoved(flow, 1)
        flow.cards.removeAt(0)
        flow.cards.reverse()
        assertEquals(CardFlowPosition(3, 2, true), state.positionFor(flow))
    }

    @Test
    fun deletingOriginalCardSelectsNextAndLastDeletionFallsBackToPrevious() {
        val flow = flow(10, true)
        val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
        for (deleted in listOf(3L, 4L, 5L)) {
            state.onCardRemoved(flow, deleted)
            flow.cards.removeAll { it.id == deleted }
        }
        assertEquals(CardFlowPosition(2, 1, true), state.positionFor(flow))
    }

    @Test
    fun emptyFlowClearsReturnSelection() {
        val flow = flow(10, true)
        val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
        flow.cards.clear()
        assertEquals(CardFlowPosition(null, 0, false), state.positionFor(flow))
    }

    @Test
    fun savedSearchPositionDoesNotMutateTheCurrentFilteredSelection() {
        val flow = flow(10, true)
        val state = FlowSearchReturnState.capture(listOf(flow), flow.id)
        flow.lastViewedCardId = 5
        flow.lastViewedCardIndex = 0
        flow.lastViewedCardFocused = false
        assertEquals(CardFlowPosition(3, 2, true), state.positionFor(flow))
        assertEquals(5L, flow.lastViewedCardId)
        assertEquals(0, flow.lastViewedCardIndex)
        assertFalse(flow.lastViewedCardFocused)
    }

    @Test
    fun newSearchCapturesNewOriginRatherThanReusingPreviousSession() {
        val flow = flow(10, true)
        val first = FlowSearchReturnState.capture(listOf(flow), flow.id)
        flow.lastViewedCardId = 4
        flow.lastViewedCardIndex = 3
        flow.lastViewedCardFocused = false
        val second = FlowSearchReturnState.capture(listOf(flow), flow.id)
        assertEquals(CardFlowPosition(3, 2, true), first.positionFor(flow))
        assertEquals(CardFlowPosition(4, 3, false), second.positionFor(flow))
    }

    private fun flow(id: Long, focused: Boolean) = CardFlow(
        id, "Disposable",
        (1L..5L).map { CardItem(it, "Card $it", "Body $it") }.toMutableList(),
        3, 2, focused
    )
}
