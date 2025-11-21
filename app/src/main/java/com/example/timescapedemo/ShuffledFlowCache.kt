package com.example.timescapedemo

object ShuffledFlowCache {
    private var cachedCards: List<CardItem>? = null

    fun store(cards: List<CardItem>) {
        cachedCards = cards.map { it.deepCopy() }
    }

    fun consume(): List<CardItem>? {
        val result = cachedCards
        cachedCards = null
        return result
    }
}
