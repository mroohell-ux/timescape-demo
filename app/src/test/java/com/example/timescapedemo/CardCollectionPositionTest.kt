package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class CardCollectionPositionTest {
    @Test
    fun collectingCurrentCardSelectsNextAndPreservesExpansion() {
        for (focused in listOf(false, true)) {
            val flow = flow(focused = focused)
            assertEquals(CardFlowPosition(4, 3, focused), flow.toggleCollectionPreservingPosition(3))
            assertEquals(listOf(3L, 1L, 2L, 4L, 5L), flow.cards.map { it.id })
            assertTrue(flow.cards.first().isCollected)
            assertEquals(2, flow.cards.first().collectionOriginalIndex)
        }
    }

    @Test
    fun collectingFirstCardStillSelectsNext() {
        val flow = flow(currentId = 1)
        assertEquals(CardFlowPosition(2, 1, true), flow.toggleCollectionPreservingPosition(1))
    }

    @Test
    fun collectingLastCardSelectsPrevious() {
        val flow = flow(currentId = 5)
        assertEquals(CardFlowPosition(4, 4, true), flow.toggleCollectionPreservingPosition(5))
    }

    @Test
    fun collectingAnotherCardKeepsCurrentIdentity() {
        for (focused in listOf(false, true)) {
            for (id in listOf(1L, 2L, 4L, 5L)) {
                val flow = flow(focused = focused)
                val position = flow.toggleCollectionPreservingPosition(id)
                assertEquals(3L, position.cardId)
                assertEquals(focused, position.focused)
                assertEquals(flow.cards.indexOfFirst { it.id == 3L }, position.index)
            }
        }
    }

    @Test
    fun onlyCardStaysSelected() {
        for (focused in listOf(false, true)) {
            val flow = flow(currentId = 1, ids = listOf(1), focused = focused)
            assertEquals(CardFlowPosition(1, 0, focused), flow.toggleCollectionPreservingPosition(1))
            assertTrue(flow.cards.single().isCollected)
        }
    }

    @Test
    fun onlySearchMatchStaysSelectedWithoutSelectingHiddenCards() {
        val flow = flow()
        val visible = flow.cards.filter { it.id == 3L }
        assertEquals(CardFlowPosition(3, 0, true), flow.toggleCollectionPreservingPosition(3, visible))
        assertEquals(5, flow.cards.size)
    }

    @Test
    fun filteredCollectionSkipsHiddenNextCardAndUsesVisiblePreviousAtEnd() {
        val flow = flow()
        var visible = flow.cards.filter { it.id % 2L == 1L }
        flow.lastViewedCardIndex = 1
        assertEquals(CardFlowPosition(5, 2, true), flow.toggleCollectionPreservingPosition(3, visible))
        visible = flow.cards.filter { it.id % 2L == 1L }
        assertEquals(CardFlowPosition(1, 2, true), flow.toggleCollectionPreservingPosition(5, visible))
    }

    @Test
    fun collectingHiddenCardKeepsVisibleSelection() {
        val flow = flow()
        flow.lastViewedCardIndex = 1
        assertEquals(CardFlowPosition(3, 1, true),
            flow.toggleCollectionPreservingPosition(2, flow.cards.filter { it.id % 2L == 1L }))
    }

    @Test
    fun shuffledCollectionUsesDisplayOrderInsteadOfCardIds() {
        val flow = flow(currentId = 4, ids = listOf(5, 2, 4, 1, 3))
        assertEquals(CardFlowPosition(1, 3, true), flow.toggleCollectionPreservingPosition(4))
        assertEquals(listOf(4L, 5L, 2L, 1L, 3L), flow.cards.map { it.id })
    }

    @Test
    fun uncollectingRestoresOriginalPositionAndShowsNextOriginalNeighbor() {
        val flow = flow()
        flow.toggleCollectionPreservingPosition(3)
        CardFlowPosition(3, 0, true).restoreTo(flow, flow.cards)
        assertEquals(CardFlowPosition(1, 0, true), flow.toggleCollectionPreservingPosition(3))
        assertEquals((1L..5L).toList(), flow.cards.map { it.id })
        assertFalse(flow.cards[2].isCollected)
        assertNull(flow.cards[2].collectionOriginalIndex)
    }

    @Test
    fun uncollectingLegacyCardWithoutRestoreIndexKeepsValidPosition() {
        val flow = flow(currentId = 1)
        flow.cards[0].isCollected = true
        assertEquals(CardFlowPosition(2, 1, true), flow.toggleCollectionPreservingPosition(1))
        assertEquals((1L..5L).toList(), flow.cards.map { it.id })
    }

    @Test
    fun collectionUsesNewCardSnapshotAndPreservesUnrelatedData() {
        val flow = flow()
        val before = flow.cards[2]
        flow.bookmarkedCardId = 3
        flow.bookmarkedCardIndex = 2
        flow.toggleCollectionPreservingPosition(3)
        val after = flow.cards.first()
        assertNotSame(before, after)
        assertFalse(before.isCollected)
        assertNull(before.collectionOriginalIndex)
        assertEquals(before.title, after.title)
        assertEquals(before.snippet, after.snippet)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals(3L, flow.bookmarkedCardId)
        assertEquals(2, flow.bookmarkedCardIndex)
    }

    @Test
    fun capturedPositionSurvivesMutableStateChangesAndReordering() {
        val flow = flow()
        val position = flow.toggleCollectionPreservingPosition(3)
        flow.lastViewedCardId = 3
        flow.lastViewedCardIndex = 0
        flow.lastViewedCardFocused = false
        assertEquals(CardFlowPosition(4, 1, true), position.restoreTo(flow, flow.cards.reversed()))
    }

    @Test
    fun searchOriginRemainsTheOriginalCardAfterCollection() {
        val flow = flow()
        val search = FlowSearchReturnState.capture(listOf(flow), flow.id)
        flow.toggleCollectionPreservingPosition(3)
        assertEquals(CardFlowPosition(3, 0, true), search.positionFor(flow))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCardIsNotReportedAsCollected() {
        flow().toggleCollectionPreservingPosition(99)
    }

    private fun flow(
        currentId: Long = 3,
        ids: List<Long> = listOf(1, 2, 3, 4, 5),
        focused: Boolean = true
    ) = CardFlow(
        10, "Disposable", ids.map { CardItem(it, "Card $it", "Body $it") }.toMutableList(),
        currentId, ids.indexOf(currentId), focused
    )
}
