package com.example.timescapedemo

data class CardFlow(
    val id: Long,
    var name: String,
    val cards: MutableList<CardItem> = mutableListOf(),
    var lastViewedCardId: Long? = null,
    var lastViewedCardIndex: Int = 0,
    var lastViewedCardFocused: Boolean = false,
    var bookmarkedCardId: Long? = null,
    var bookmarkedCardIndex: Int = 0
) {
    fun removeCardPreservingPosition(
        cardId: Long,
        displayedCards: List<CardItem> = cards
    ): CardFlowPosition {
        val cardIndex = cards.indexOfFirst { it.id == cardId }
        require(cardIndex >= 0) { "Card $cardId does not belong to Flow $id" }
        val position = CardFlowPosition(lastViewedCardId, lastViewedCardIndex, lastViewedCardFocused)
            .afterRemoving(cardId, displayedCards)
        cards.removeAt(cardIndex)
        if (bookmarkedCardId == cardId) {
            bookmarkedCardId = null
            bookmarkedCardIndex = 0
        }
        return position.restoreTo(this, displayedCards.filterNot { it.id == cardId })
    }
}

data class CardFlowPosition(val cardId: Long?, val index: Int, val focused: Boolean) {
    fun afterRemoving(removedCardId: Long, displayedCards: List<CardItem>): CardFlowPosition {
        val currentIndex = displayedCards.indexOfFirst { it.id == cardId }
            .takeIf { it >= 0 }
            ?: index.coerceIn(0, (displayedCards.size - 1).coerceAtLeast(0))
        val currentId = displayedCards.getOrNull(currentIndex)?.id
        val remaining = displayedCards.filterNot { it.id == removedCardId }
        val targetIndex = remaining.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 }
            ?: currentIndex.coerceIn(0, (remaining.size - 1).coerceAtLeast(0))
        return CardFlowPosition(
            remaining.getOrNull(targetIndex)?.id,
            targetIndex,
            focused && remaining.isNotEmpty()
        )
    }

    fun resolved(displayedCards: List<CardItem>): CardFlowPosition {
        val indexById = displayedCards.indexOfFirst { it.id == cardId }.takeIf { it >= 0 }
        val resolvedIndex = (indexById ?: index).coerceIn(0, (displayedCards.size - 1).coerceAtLeast(0))
        return CardFlowPosition(
            displayedCards.getOrNull(resolvedIndex)?.id,
            resolvedIndex,
            focused && indexById != null
        )
    }

    fun restoreTo(flow: CardFlow, displayedCards: List<CardItem>): CardFlowPosition {
        val position = resolved(displayedCards)
        flow.lastViewedCardId = position.cardId
        flow.lastViewedCardIndex = position.index
        flow.lastViewedCardFocused = position.focused
        return position
    }
}
