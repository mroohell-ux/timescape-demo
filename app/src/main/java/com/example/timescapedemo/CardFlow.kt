package com.example.timescapedemo

data class CardFlow(
    val id: Long,
    var name: String,
    val cards: MutableList<CardItem> = mutableListOf()
)
