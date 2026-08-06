package com.example.timescapedemo

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectedCardsOrderingTest {
    @Test
    fun movesCollectedCardsToFrontAfterMerge() {
        val cards = listOf(
            card(id = 1, isCollected = false),
            card(id = 2, isCollected = true),
            card(id = 3, isCollected = false),
            card(id = 4, isCollected = true)
        )

        assertEquals(listOf(2L, 4L, 1L, 3L), collectedCardsFirst(cards).map { it.id })
    }

    @Test
    fun preservesRelativeOrderWithinCollectedAndNormalGroups() {
        val cards = listOf(
            card(id = 10, isCollected = true),
            card(id = 11, isCollected = false),
            card(id = 12, isCollected = true),
            card(id = 13, isCollected = false),
            card(id = 14, isCollected = true)
        )

        assertEquals(listOf(10L, 12L, 14L, 11L, 13L), collectedCardsFirst(cards).map { it.id })
    }

    private fun card(id: Long, isCollected: Boolean): CardItem =
        CardItem(
            id = id,
            title = "Card $id",
            snippet = "Snippet $id",
            isCollected = isCollected
        )
}
