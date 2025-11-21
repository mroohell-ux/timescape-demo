package com.example.timescapedemo

data class GlobalSearchPayload(val query: String, val cards: List<CardItem>)

object GlobalSearchCache {
    private var cached: GlobalSearchPayload? = null

    fun store(payload: GlobalSearchPayload) {
        cached = payload.copy(cards = payload.cards.map { it.deepCopy() })
    }

    fun consume(): GlobalSearchPayload? {
        val result = cached
        cached = null
        return result
    }
}
