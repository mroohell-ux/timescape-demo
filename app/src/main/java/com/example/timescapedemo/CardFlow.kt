package com.example.timescapedemo

data class CardFlow(
    val id: Long,
    var name: String,
    val cards: MutableList<CardItem> = mutableListOf(),
    var lastViewedCardId: Long? = null,
    var lastViewedCardIndex: Int = 0,
    var lastViewedCardFocused: Boolean = false
)
