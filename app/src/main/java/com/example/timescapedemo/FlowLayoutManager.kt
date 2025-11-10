package com.example.timescapedemo

import androidx.recyclerview.widget.RecyclerView

/**
 * Shared contract for card flow layout managers so the pager/controller can
 * operate agnostic of the actual scroll orientation.
 */
abstract class FlowLayoutManager : RecyclerView.LayoutManager(),
    RecyclerView.SmoothScroller.ScrollVectorProvider {

    /** True when the layout scrolls horizontally (left/right). */
    abstract val isHorizontal: Boolean

    abstract fun isFocused(index: Int): Boolean

    /** Index closest to the primary axis center given the current scroll. */
    abstract fun nearestIndex(): Int

    /** Required scroll delta (px) to center `index` on the primary axis. */
    abstract fun offsetTo(index: Int): Int

    abstract fun focus(index: Int)

    abstract fun clearFocus()

    abstract fun restoreState(index: Int, focus: Boolean)
}
