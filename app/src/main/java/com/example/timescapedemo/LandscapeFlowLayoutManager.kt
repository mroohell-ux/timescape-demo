package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.PointF
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LandscapeFlowLayoutManager(
    private val baseSidePx: Int,
    private val focusSidePx: Int,
    private val itemPitchPx: Int,
    private val rightInsetPx: Int = 0,
    private val minEdgeScale: Float = 0.72f,
    private val edgeAlphaMin: Float = 0.30f,
    private val depthScaleDrop: Float = 0.05f
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider, FlowLayoutManager {

    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    private var scrollXPx = 0f
    private var pendingRestore: PendingRestore? = null

    private data class PendingRestore(
        val index: Int,
        val focus: Boolean
    )

    override fun canScrollHorizontally() = true
    override fun canScrollVertically() = false
    override fun isAutoMeasureEnabled() = true
    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    override fun nearestIndex(): Int {
        if (itemCount == 0) return 0
        val idx = ((xRight() - scrollXPx - screenCenter()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    override fun offsetTo(index: Int): Int {
        val desiredScroll = xRight() - index * itemPitchPx - screenCenter()
        return (desiredScroll - scrollXPx).toInt()
    }

    override fun focus(index: Int) {
        if (selectedIndex == index && focusProgress >= 0.999f) return
        selectedIndex = index
        animateFocus(1f)
    }

    override fun clearFocus() {
        if (selectedIndex == null && focusProgress == 0f) return
        animateFocus(0f) { selectedIndex = null }
    }

    private fun animateFocus(to: Float, end: (() -> Unit)? = null) {
        focusAnimator?.cancel()
        val from = focusProgress
        focusAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 280 else 220
            interpolator = interp
            addUpdateListener {
                focusProgress = it.animatedValue as Float
                requestLayout()
            }
            if (end != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (focusProgress <= 0.001f) end()
                    }
                })
            }
            start()
        }
    }

    private fun screenCenter() = width / 2f
    private fun yCenter() = height / 2f
    private fun xRight() = width - paddingRight - rightInsetPx - baseSidePx / 2f
    private fun minScroll(): Float {
        val lastCentered = xRight() - (itemCount - 1).coerceAtLeast(0) * itemPitchPx - screenCenter()
        return min(lastCentered, maxScroll())
    }
    private fun maxScroll() = xRight() - screenCenter()

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || width == 0 || height == 0) return
        pendingRestore?.let {
            if (applyRestore(it.index, it.focus)) pendingRestore = null
        }
        layoutAll(recycler)
    }

    private fun measureCardWithWidthAndCap(child: View, widthPx: Int, maxHeightPx: Int) {
        val lp = child.layoutParams
        if (lp.width != widthPx || lp.height != RecyclerView.LayoutParams.WRAP_CONTENT) {
            lp.width = widthPx
            lp.height = RecyclerView.LayoutParams.WRAP_CONTENT
            child.layoutParams = lp
        }
        child.findViewById<MaxHeightLinearLayout>(R.id.card_content)?.setMaxHeightPx(maxHeightPx)
        measureChildWithMargins(child, 0, 0)
    }

    private fun applyTextByGain(child: View, gain: Float, focused: Boolean) {
        val title = child.findViewById<TextView>(R.id.title)
        val snippet = child.findViewById<TextView>(R.id.snippet)
        if (focused) {
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
        } else {
            val titleSp = 21f + 5f * gain
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
        }
        snippet?.maxLines = Integer.MAX_VALUE
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cx = screenCenter()
        val layoutOverscan = 3
        val overscanPx = layoutOverscan * itemPitchPx
        val firstIdx = max(
            0,
            floor(((xRight() - scrollXPx) - (width + overscanPx)) / itemPitchPx).toInt()
        )
        val lastIdx = min(
            itemCount - 1,
            ceil((xRight() - scrollXPx + overscanPx) / itemPitchPx).toInt()
        )

        val focusRadiusPx = itemPitchPx * 0.95f
        val heightCap = (height * 2f / 3f).roundToInt()

        if (firstIdx > lastIdx) return

        val nearest = nearestIndex()
        val nearestX = xRight() - nearest * itemPitchPx - scrollXPx

        for (i in firstIdx..lastIdx) {
            val child = recycler.getViewForPosition(i)
            addView(child)

            val px = xRight() - i * itemPitchPx - scrollXPx
            val dist = abs(px - cx)
            val gain = exp(-(dist * dist) / (2f * focusRadiusPx * focusRadiusPx))
            val isSelected = selectedIndex == i

            val edgeSide = (baseSidePx * minEdgeScale).roundToInt()
            var side = (edgeSide + (baseSidePx - edgeSide) * interp.getInterpolation(gain)).roundToInt()
            if (isSelected) side = (side + (focusSidePx - side) * focusProgress).roundToInt()

            measureCardWithWidthAndCap(child, side, heightCap)
            applyTextByGain(child, gain, isSelected && focusProgress > 0.5f)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (yCenter() - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            val alpha = edgeAlphaMin + (1f - edgeAlphaMin) * gain
            child.alpha = alpha
            val dIdx = abs(nearestX - px) / itemPitchPx
            val depthS = max(0.94f, 1f - depthScaleDrop * dIdx)
            child.scaleX = depthS
            child.scaleY = depthS
            child.translationX = 0f
            child.translationY = 0f
            child.rotation = 0f
            val z = if (isSelected) 6000f else (1000f - dist / 10f)
            child.translationZ = z
            child.elevation = z
        }
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0 || width == 0) return 0
        if (dx != 0 && selectedIndex != null && focusProgress > 0f) clearFocus()

        val old = scrollXPx
        val newX = (old + dx).coerceIn(minScroll(), maxScroll())
        val consumed = (newX - old).toInt()
        scrollXPx = newX

        detachAndScrapAttachedViews(recycler)
        layoutAll(recycler)
        return consumed
    }

    override fun restoreState(index: Int, focus: Boolean) {
        if (applyRestore(index, focus)) {
            pendingRestore = null
            requestLayout()
        } else {
            pendingRestore = PendingRestore(index, focus)
            requestLayout()
        }
    }

    private fun applyRestore(index: Int, focus: Boolean): Boolean {
        if (itemCount == 0 || width == 0 || height == 0) return false
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        val desired = xRight() - clamped * itemPitchPx - screenCenter()
        scrollXPx = desired.coerceIn(minScroll(), maxScroll())
        focusAnimator?.cancel()
        if (focus) {
            selectedIndex = clamped
            focusProgress = 1f
        } else {
            selectedIndex = null
            focusProgress = 0f
        }
        return true
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = xRight() - targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollXPx) 1f else -1f
        return PointF(dir, 0f)
    }

    override fun smoothScrollBy(recycler: RecyclerView, delta: Int) {
        recycler.smoothScrollBy(delta, 0)
    }
}
