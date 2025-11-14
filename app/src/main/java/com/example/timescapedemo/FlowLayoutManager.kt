package com.example.timescapedemo

import androidx.recyclerview.widget.RecyclerView

interface FlowLayoutManager {
    fun isFocused(index: Int): Boolean
    fun clearFocus()
    fun offsetTo(index: Int): Int
    fun focus(index: Int)
    fun nearestIndex(): Int
    fun restoreState(index: Int, focus: Boolean)
    fun smoothScrollBy(recycler: RecyclerView, delta: Int)
}
