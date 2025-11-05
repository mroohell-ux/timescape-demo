package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.PointF
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Timescape-like vertical flow with variable-height cards:
 *  - Height grows with text (wrap-content), capped at 2/3 of the screen.
 *  - Width still follows center "gain" + focus animation.
 *  - Only near neighbors overlap slightly (itemPitchPx).
 *  - Cards remain STRAIGHT (no rotation).
 */
class RightRailFlowLayoutManager(
    private val baseSidePx: Int,          // width near center (non-focused)
    private val focusSidePx: Int,         // width when focused (main)
    private val itemPitchPx: Int,         // visual spacing (center-to-center)
    private val rightInsetPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0,

    // Visual tuning
    private val minEdgeScale: Float = 0.66f,
    private val depthScaleDrop: Float = 0.06f,

    // Horizontal rail shaping (controls inflow/outflow direction)
    private val edgeRightShiftPx: Int = 96,     // far cards to the RIGHT by this many px
    private val centerLeftShiftPx: Int = 48,    // center card to the LEFT by this many px
    private val railCurvePow: Float = 1.2f      // curvature; 1 = linear, >1 stronger S-curve
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    enum class PresentationMode { OVERVIEW, READING }

    // focus animation state
    private var presentationMode: PresentationMode = PresentationMode.OVERVIEW
    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    // scroll state
    private var scrollYPx = 0f

    override fun canScrollVertically() = true
    override fun canScrollHorizontally() = false
    override fun isAutoMeasureEnabled() = true
    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)

    // ---- Public helpers ----
    fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    fun setPresentationMode(mode: PresentationMode, anchorIndex: Int? = null) {
        if (presentationMode == mode && anchorIndex == null) return
        presentationMode = mode
        focusAnimator?.cancel()
        if (itemCount == 0) {
            selectedIndex = null
            focusProgress = if (mode == PresentationMode.READING) 1f else 0f
            requestLayout()
            return
        }
        when (mode) {
            PresentationMode.OVERVIEW -> {
                selectedIndex = null
                focusProgress = 0f
            }
            PresentationMode.READING -> {
                val target = anchorIndex ?: nearestIndex()
                selectedIndex = target
                focusProgress = 1f
            }
        }
        requestLayout()
    }

    /** Center index given current scroll (rounded to nearest). */
    fun nearestIndex(): Int {
        val idx = ((scrollYPx + screenCenter() - yTop()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    fun currentFocusedIndex(): Int? {
        if (itemCount == 0) return null
        return when (presentationMode) {
            PresentationMode.READING -> nearestIndex()
            PresentationMode.OVERVIEW -> selectedIndex?.takeIf { focusProgress > 0f }
        }
    }

    /** Pixel delta required to move `index` to vertical screen center. */
    fun offsetTo(index: Int): Int {
        val desiredScroll = yTop() + index * itemPitchPx - screenCenter()
        return (desiredScroll - scrollYPx).toInt()
    }

    fun focus(index: Int) {
        if (presentationMode == PresentationMode.READING) {
            selectedIndex = index
            focusProgress = 1f
            requestLayout()
            return
        }
        if (selectedIndex == index && focusProgress >= 0.999f) return
        selectedIndex = index
        animateFocus(1f)
    }
    fun clearFocus() {
        if (presentationMode == PresentationMode.READING) return
        if (selectedIndex == null && focusProgress == 0f) return
        animateFocus(0f) { selectedIndex = null }
    }

    private fun animateFocus(to: Float, end: (() -> Unit)? = null) {
        focusAnimator?.cancel()
        val from = focusProgress
        focusAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 280 else 220
            interpolator = interp
            addUpdateListener { focusProgress = it.animatedValue as Float; requestLayout() }
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

    // ---- Rail geometry ----
    private fun screenCenter() = height / 2f
    private fun yTop() = paddingTop + topInsetPx + baseSidePx / 2f
    private fun yBottom() = height - paddingBottom - bottomInsetPx - baseSidePx / 2f
    private fun railX() = width - paddingRight - rightInsetPx - baseSidePx / 2f

    /** Allow first/last items to be centered without dead space. */
    private fun minScroll() = yTop() - screenCenter()
    private fun maxScroll(): Float {
        val lastCentered = yTop() + (itemCount - 1).coerceAtLeast(0) * itemPitchPx - screenCenter()
        return max(minScroll(), lastCentered)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || width == 0 || height == 0) return
        layoutAll(recycler)
    }

    private fun measureCardWithWidthAndCap(child: View, widthPx: Int, maxHeightPx: Int) {
        // Width = exact, height = wrap_content; child caps itself via MaxHeightLinearLayout
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

        if (presentationMode == PresentationMode.OVERVIEW) {
            val titleSp = 18f + 4f * gain
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
            snippet?.maxLines = 3
        } else {
            if (focused) {
                title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
                snippet?.maxLines = Integer.MAX_VALUE
            } else {
                val titleSp = 21f + 4f * gain
                title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
                snippet?.maxLines = 6
            }
        }
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val baseX = railX()
        val yT = yTop(); val yB = yBottom()

        val isReading = presentationMode == PresentationMode.READING
        val focusIndex = if (isReading) nearestIndex() else selectedIndex
        if (isReading) {
            selectedIndex = focusIndex
            focusProgress = 1f
        }

        val baseSide = if (isReading) baseSidePx else (baseSidePx * 0.78f).roundToInt()
        val focusSide = if (isReading) focusSidePx else (baseSide * 1.08f).roundToInt()
        val edgeScale = if (isReading) minEdgeScale else 0.54f

        // Visible window of indices
        val firstIdx = max(0, floor(((scrollYPx + (yT - itemPitchPx)) - yT) / itemPitchPx).toInt())
        val lastIdx  = min(itemCount - 1, ceil(((scrollYPx + (yB - yT) + itemPitchPx) - yT) / itemPitchPx).toInt())

        // Size growth radius (how quickly a card "blooms" near center)
        val focusRadiusPx = itemPitchPx * 0.95f
        val heightCap = (height * 2f / 3f).roundToInt()

        for (i in firstIdx..lastIdx) {
            val child = recycler.getViewForPosition(i)
            addView(child)

            // Y position of item center for index i
            val py = yT + i * itemPitchPx - scrollYPx
            val dist = abs(py - cy)

            // Gain ~ 1 at center, ~ 0 at edges
            val gain = exp(-(dist * dist) / (2f * focusRadiusPx * focusRadiusPx))

            // ---- X rail shaping: edges right, center left (S-curve) ----
            val toRight = (1f - gain).pow(railCurvePow) * edgeRightShiftPx
            val toLeft  = gain * centerLeftShiftPx
            val px = baseX + toRight - toLeft

            val isSelected = (focusIndex == i)

            // Width: far small -> base -> (if selected) focused
            val edgeSide = (baseSide * edgeScale).roundToInt()
            var side = (edgeSide + (baseSide - edgeSide) * interp.getInterpolation(gain)).roundToInt()
            if (isSelected) side = (side + (focusSide - side) * focusProgress).roundToInt()

            // Measure: fixed width, wrap-content height, capped to 2/3 screen
            measureCardWithWidthAndCap(child, side, heightCap)

            applyTextByGain(child, gain, isSelected && focusProgress > 0.5f)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            // Alpha / depth (z-order so nearer items render above)
            child.alpha = if (isReading) 0.92f + 0.08f * gain else 0.84f + 0.12f * gain

            val centerIdxY = yT + nearestIndex() * itemPitchPx - scrollYPx
            val dIdx = abs(centerIdxY - py) / itemPitchPx
            val depthSBase = if (isReading) 1f - depthScaleDrop * dIdx else 0.92f + 0.08f * gain
            val depthS = max(0.9f, depthSBase)
            child.scaleX = depthS
            child.scaleY = depthS
            child.rotation = 0f
            val z = if (isSelected) 6000f else (1000f - dist / 10f)
            child.translationZ = z; child.elevation = z
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0 || height == 0) return 0
        if (dy != 0 && presentationMode == PresentationMode.OVERVIEW && selectedIndex != null && focusProgress > 0f) clearFocus()

        val old = scrollYPx
        val newY = (old + dy).coerceIn(minScroll(), maxScroll())
        val consumed = (newY - old).toInt()
        scrollYPx = newY

        detachAndScrapAttachedViews(recycler)
        layoutAll(recycler)

        if (presentationMode == PresentationMode.READING) {
            val target = nearestIndex()
            if (selectedIndex != target) {
                selectedIndex = target
                focusProgress = 1f
                requestLayout()
            }
        }

        return consumed
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = yTop() + targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollYPx) 1f else -1f
        return PointF(0f, dir)
    }
}
