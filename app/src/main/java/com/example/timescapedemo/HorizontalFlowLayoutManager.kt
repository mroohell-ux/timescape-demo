package com.example.timescapedemo

import android.content.Context
import android.graphics.PointF
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Simplified left-to-right layout for landscape orientation.
 * Cards keep their measured height and share a common width so we can
 * reproduce the “rail” look without rewriting the entire bespoke manager.
 */
class HorizontalFlowLayoutManager(
    context: Context,
    private val baseSidePx: Int,
    private val focusSidePx: Int,
    private val itemPitchPx: Int,
    private val bottomInsetPx: Int
) : LinearLayoutManager(context, HORIZONTAL, false), FlowLayoutManager {

    private var focusedIndex: Int? = null
    private var pendingRestore: PendingRestore? = null

    private data class PendingRestore(val index: Int, val focus: Boolean)

    override fun canScrollVertically(): Boolean = false
    override fun canScrollHorizontally(): Boolean = true

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        super.onLayoutChildren(recycler, state)
        applyUniformSizing()
        pendingRestore?.let { restore ->
            val view = findViewByPosition(restore.index)
            if (view != null) {
                val delta = offsetTo(restore.index)
                if (delta != 0) {
                    super.scrollHorizontallyBy(delta, recycler, state)
                }
                focusedIndex = if (restore.focus) restore.index else null
                pendingRestore = null
            }
        }
    }

    private fun applyUniformSizing() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val lp = child.layoutParams as RecyclerView.LayoutParams
            val side = if (focusedIndex == getPosition(child)) focusSidePx else baseSidePx
            if (lp.width != side) {
                lp.width = side
                child.layoutParams = lp
            }
            var changed = false
            if (lp.rightMargin != itemPitchPx) {
                lp.rightMargin = itemPitchPx
                changed = true
            }
            if (lp.leftMargin != itemPitchPx) {
                lp.leftMargin = itemPitchPx
                changed = true
            }
            if (changed) child.layoutParams = lp
            child.translationY = bottomInsetPx.toFloat()
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        val consumed = super.scrollHorizontallyBy(dx, recycler, state)
        applyUniformSizing()
        if (consumed != 0 && focusedIndex != null) clearFocus()
        return consumed
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val first = findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        val dir = if (targetPosition > first) 1 else -1
        return PointF(dir.toFloat(), 0f)
    }

    override fun nearestIndex(): Int {
        if (childCount == 0) return 0
        val center = width / 2f
        var nearestPos = RecyclerView.NO_POSITION
        var minDist = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            val childCenter = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2f
            val dist = abs(childCenter - center)
            if (dist < minDist) {
                minDist = dist
                nearestPos = position
            }
        }
        return nearestPos.coerceIn(0, max(0, itemCount - 1))
    }

    override fun offsetTo(index: Int): Int {
        val child = findViewByPosition(index) ?: return 0
        val childCenter = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2f
        val center = width / 2f
        return (childCenter - center).roundToInt()
    }

    override fun focus(index: Int) {
        val previous = focusedIndex
        focusedIndex = index
        if (previous != index) {
            val prevView = previous?.let { findViewByPosition(it) }
            val newView = findViewByPosition(index)
            prevView?.post { applyUniformSizing() }
            newView?.post { applyUniformSizing() }
        }
    }

    override fun clearFocus() {
        if (focusedIndex == null) return
        focusedIndex = null
        applyUniformSizing()
    }

    override fun isFocused(index: Int): Boolean = focusedIndex == index

    override fun restoreState(index: Int, focus: Boolean) {
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        scrollToPositionWithOffset(clamped, (width - baseSidePx) / 2)
        pendingRestore = PendingRestore(clamped, focus)
    }

    override fun scrollBy(recycler: RecyclerView, delta: Int) {
        recycler.smoothScrollBy(delta, 0)
    }
}
