package com.example.timescapedemo

import androidx.annotation.ColorInt

data class StickyNote(
    val id: Long,
    var frontText: String,
    var backText: String,
    @ColorInt var color: Int,
    var rotation: Float
)
