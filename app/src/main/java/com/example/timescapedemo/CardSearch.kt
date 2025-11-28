package com.example.timescapedemo

object CardSearch {
    fun filter(cards: List<CardItem>, query: String): List<CardItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return cards.toList()
        return cards.filter { card ->
            card.title.contains(trimmed, ignoreCase = true) ||
                card.snippet.contains(trimmed, ignoreCase = true)
        }
    }
}
