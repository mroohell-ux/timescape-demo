package com.example.timescapedemo

import org.junit.Assert.*
import org.junit.Test

class CardDeletionPositionTest {
    @Test
    fun deletingCurrentCardSelectsNextAndRetainsExpansion() {
        for (focused in listOf(false, true)) {
            val flow = flow(currentId = 3, focused = focused)
            assertEquals(CardFlowPosition(4, 2, focused), flow.removeCardPreservingPosition(3))
            assertPosition(flow, 4, 2, focused)
        }
    }

    @Test
    fun deletingLastCardSelectsPrevious() {
        for (focused in listOf(false, true)) {
            val flow = flow(currentId = 5, focused = focused)
            flow.removeCardPreservingPosition(5)
            assertPosition(flow, 4, 3, focused)
        }
    }

    @Test
    fun deletingFirstCardSelectsNext() {
        for (focused in listOf(false, true)) {
            val flow = flow(currentId = 1, focused = focused)
            flow.removeCardPreservingPosition(1)
            assertPosition(flow, 2, 0, focused)
        }
    }

    @Test
    fun deletingAnotherCardPreservesCurrentIdentityDespiteIndexChanges() {
        for (focused in listOf(false, true)) {
            for (deletedId in listOf(1L, 2L, 4L, 5L)) {
                val flow = flow(currentId = 3, focused = focused)
                flow.removeCardPreservingPosition(deletedId)
                assertPosition(flow, 3, if (deletedId < 3) 1 else 2, focused)
            }
        }
    }

    @Test
    fun deletingOnlyCardClearsPositionAndExpansion() {
        val flow = flow(currentId = 1, ids = listOf(1))
        flow.removeCardPreservingPosition(1)
        assertTrue(flow.cards.isEmpty())
        assertPosition(flow, null, 0, false)
    }

    @Test
    fun deletionUsesShuffledOrderRatherThanCardIds() {
        val flow = flow(currentId = 4, ids = listOf(5, 2, 4, 1, 3))
        flow.removeCardPreservingPosition(4)
        assertEquals(listOf(5L, 2L, 1L, 3L), flow.cards.map { it.id })
        assertPosition(flow, 1, 2, true)
    }

    @Test
    fun filteredDeletionSelectsNextVisibleCardAndLastFallsBackToPreviousVisible() {
        val flow = flow(currentId = 3)
        val displayed = flow.cards.filter { it.id % 2L == 1L }
        flow.lastViewedCardIndex = 1
        flow.removeCardPreservingPosition(3, displayed)
        assertPosition(flow, 5, 1, true)
        flow.removeCardPreservingPosition(5, flow.cards.filter { it.id % 2L == 1L })
        assertPosition(flow, 1, 0, true)
        assertEquals(listOf(1L, 2L, 4L), flow.cards.map { it.id })
    }

    @Test
    fun deletingHiddenCardKeepsFilteredSelection() {
        val flow = flow(currentId = 3)
        flow.lastViewedCardIndex = 1
        flow.removeCardPreservingPosition(2, flow.cards.filter { it.id % 2L == 1L })
        assertPosition(flow, 3, 1, true)
    }

    @Test
    fun deletingOnlySearchMatchClearsVisiblePositionWithoutRemovingOtherCards() {
        val flow = flow(currentId = 3)
        flow.removeCardPreservingPosition(3, flow.cards.filter { it.id == 3L })
        assertEquals(4, flow.cards.size)
        assertPosition(flow, null, 0, false)
    }

    @Test
    fun deletionResolvesMissingSavedIdFromCurrentIndex() {
        val flow = flow(currentId = 3)
        flow.lastViewedCardId = null
        flow.removeCardPreservingPosition(3)
        assertPosition(flow, 4, 2, true)
    }

    @Test
    fun deletionClearsOnlyDeletedBookmark() {
        val flow = flow(currentId = 3)
        flow.bookmarkedCardId = 4
        flow.bookmarkedCardIndex = 3
        flow.removeCardPreservingPosition(1)
        assertEquals(4L, flow.bookmarkedCardId)
        flow.removeCardPreservingPosition(4)
        assertNull(flow.bookmarkedCardId)
        assertEquals(0, flow.bookmarkedCardIndex)
        assertPosition(flow, 3, 1, true)
    }

    @Test
    fun committedPositionIsImmutableAndResolvesAgainstDisplayedList() {
        val flow = flow(currentId = 3)
        val position = flow.removeCardPreservingPosition(3)
        flow.lastViewedCardId = 1
        flow.lastViewedCardIndex = 0
        flow.lastViewedCardFocused = false
        val displayed = flow.cards.reversed()
        assertEquals(CardFlowPosition(4, 1, true), position.restoreTo(flow, displayed))
        assertPosition(flow, 4, 1, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCardIsNotReportedAsADeletion() {
        flow(currentId = 3).removeCardPreservingPosition(99)
    }

    private fun flow(
        currentId: Long,
        focused: Boolean = true,
        ids: List<Long> = listOf(1, 2, 3, 4, 5)
    ) = CardFlow(
        id = 10,
        name = "Disposable",
        cards = ids.map { CardItem(it, "Card $it", "Body $it") }.toMutableList(),
        lastViewedCardId = currentId,
        lastViewedCardIndex = ids.indexOf(currentId),
        lastViewedCardFocused = focused
    )

    private fun assertPosition(flow: CardFlow, id: Long?, index: Int, focused: Boolean) {
        assertEquals(id, flow.lastViewedCardId)
        assertEquals(index, flow.lastViewedCardIndex)
        assertEquals(focused, flow.lastViewedCardFocused)
    }
}
