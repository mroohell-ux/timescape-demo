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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private val edgeAlphaMin: Float = 0.30f,
    private val depthScaleDrop: Float = 0.06f,

    // Horizontal rail shaping (controls inflow/outflow direction)
    private val edgeRightShiftPx: Int = 96,     // far cards to the RIGHT by this many px
    private val centerLeftShiftPx: Int = 48,    // center card to the LEFT by this many px
    private val railCurvePow: Float = 1.2f,     // curvature; 1 = linear, >1 stronger S-curve

    // S-curve styling
    private val curveRotationRadiusItems: Float = 4.5f,
    private val curveRotationPow: Float = 1.18f,
    private val curveMaxRotationDeg: Float = 11f,
    private val curveExtraRightShiftPx: Int = 54
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    // focus animation state
    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    // scroll state
    private var scrollYPx = 0f

    private data class CurvePlacement(
        val extraX: Float,
        val rotationDeg: Float
    )

    private data class ScatterOffsets(
        val shiftX: Float,
        val shiftY: Float,
        val rotationDeg: Float
    )

    override fun canScrollVertically() = true
    override fun canScrollHorizontally() = false
    override fun isAutoMeasureEnabled() = true
    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)

    // ---- Public helpers ----
    fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    /** Center index given current scroll (rounded to nearest). */
    fun nearestIndex(): Int {
        val idx = ((scrollYPx + screenCenter() - yTop()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    /** Pixel delta required to move `index` to vertical screen center. */
    fun offsetTo(index: Int): Int {
        val desiredScroll = yTop() + index * itemPitchPx - screenCenter()
        return (desiredScroll - scrollYPx).toInt()
    }

    fun focus(index: Int) {
        if (selectedIndex == index && focusProgress >= 0.999f) return
        selectedIndex = index
        animateFocus(1f)
    }
    fun clearFocus() {
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

        // Text grows a bit near center; we no longer clamp max lines (height is capped globally)
        if (focused) {
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
        } else {
            val titleSp = 21f + 5f * gain
            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
        }
        // Leave snippet lines unconstrained; overall height is capped by MaxHeightLinearLayout.
        snippet?.maxLines = Integer.MAX_VALUE
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val baseX = railX()
        val yT = yTop(); val yB = yBottom()

        // Visible window of indices
        val firstIdx = max(0, floor(((scrollYPx + (yT - itemPitchPx)) - yT) / itemPitchPx).toInt())
        val lastIdx  = min(itemCount - 1, ceil(((scrollYPx + (yB - yT) + itemPitchPx) - yT) / itemPitchPx).toInt())

        // Size growth radius (how quickly a card "blooms" near center)
        val focusRadiusPx = itemPitchPx * 0.95f
        val heightCap = (height * 2f / 3f).roundToInt()

        val nearest = nearestIndex()
        val nearestY = yT + nearest * itemPitchPx - scrollYPx

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
            val curve = computeCurvePlacement(py, cy)
            val px = baseX + toRight - toLeft + curve.extraX

            val isSelected = (selectedIndex == i)

            // Width: far small -> base -> (if selected) focused
            val edgeSide = (baseSidePx * minEdgeScale).roundToInt()
            var side = (edgeSide + (baseSidePx - edgeSide) * interp.getInterpolation(gain)).roundToInt()
            if (isSelected) side = (side + (focusSidePx - side) * focusProgress).roundToInt()

            // Measure: fixed width, wrap-content height, capped to 2/3 screen
            measureCardWithWidthAndCap(child, side, heightCap)

            applyTextByGain(child, gain, isSelected && focusProgress > 0.5f)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            // Alpha / depth (z-order so nearer items render above)
            child.alpha = 0.92f + 0.08f * gain
            val dIdx = abs(nearestY - py) / itemPitchPx
            val depthS = max(0.94f, 1f - depthScaleDrop * dIdx)
            child.scaleX = depthS
            child.scaleY = depthS
            val scatter = computeScatterOffsets(i, gain, isSelected)
            child.translationX = scatter.shiftX
            child.translationY = scatter.shiftY
            child.rotation = curve.rotationDeg + scatter.rotationDeg
            val z = if (isSelected) 6000f else (1000f - dist / 10f)
            child.translationZ = z; child.elevation = z
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0 || height == 0) return 0
        if (dy != 0 && selectedIndex != null && focusProgress > 0f) clearFocus()

        val old = scrollYPx
        val newY = (old + dy).coerceIn(minScroll(), maxScroll())
        val consumed = (newY - old).toInt()
        scrollYPx = newY

        detachAndScrapAttachedViews(recycler)
        layoutAll(recycler)
        return consumed
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = yTop() + targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollYPx) 1f else -1f
        return PointF(0f, dir)
    }

    private fun computeCurvePlacement(py: Float, centerY: Float): CurvePlacement {
        if (itemPitchPx == 0) return CurvePlacement(0f, 0f)

        val signedStepsFromCenter = (py - centerY) / itemPitchPx
        val direction = if (signedStepsFromCenter >= 0f) 1f else -1f
        val magnitude = abs(signedStepsFromCenter) / curveRotationRadiusItems
        val eased = magnitude.coerceAtMost(1f).pow(curveRotationPow)
        val rotation = direction * eased * curveMaxRotationDeg
        val extraRight = eased * curveExtraRightShiftPx

        return CurvePlacement(extraRight, rotation)
    }

    private fun computeScatterOffsets(
        index: Int,
        gain: Float,
        isSelected: Boolean
    ): ScatterOffsets {
        val normalizedScroll = scrollYPx / itemPitchPx
        val basePhase = index * 0.83f + normalizedScroll * 0.9f
        val secondaryPhase = index * 1.27f - normalizedScroll * 0.45f
        val tertiaryPhase = (index + normalizedScroll) * 0.55f

        val waveA = sin(basePhase)
        val waveB = cos(secondaryPhase)
        val waveC = sin(tertiaryPhase + waveB * 0.35f)

        val scatterStrength = (0.12f + (1f - gain) * 0.88f).coerceIn(0f, 1f)
        val focusDamp = if (isSelected) (1f - focusProgress).coerceAtLeast(0f) else 1f
        val amount = scatterStrength * focusDamp

        val shiftX = (waveA * 14f + waveB * 8f) * amount
        val shiftY = (waveB * 6f + waveC * 10f) * amount
        val rotation = (waveA * 2.6f + waveC * 1.1f) * amount

        return ScatterOffsets(shiftX, shiftY, rotation)
    }
}
