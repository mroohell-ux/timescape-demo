package com.example.timescapedemo

import androidx.recyclerview.widget.RecyclerView

interface FlowLayoutManager {
    fun nearestIndex(): Int
    fun offsetTo(index: Int): Int
    fun focus(index: Int)
    fun clearFocus()
    fun isFocused(index: Int): Boolean
    fun restoreState(index: Int, focus: Boolean)
    fun scrollBy(recycler: RecyclerView, delta: Int)
}
