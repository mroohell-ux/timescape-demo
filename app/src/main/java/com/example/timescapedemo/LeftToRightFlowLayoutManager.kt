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
 * Landscape rail variant: cards flow from left to right with the newest (index 0)
 * positioned on the right edge. Geometry mirrors the portrait rail but swaps the
 * primary axis calculations to horizontal space.
 */
class LeftToRightFlowLayoutManager(
    private val baseSidePx: Int,
    private val focusSidePx: Int,
    private val itemPitchPx: Int,
    private val bottomInsetPx: Int,
    private val leftInsetPx: Int = 0,
    private val topInsetPx: Int = 0,
    private val rightInsetPx: Int = 0,

    private val minEdgeScale: Float = 0.72f,
    private val depthScaleDrop: Float = 0.05f,
    private val edgeDownShiftPx: Int = 48,
    private val centerUpShiftPx: Int = 0,
    private val railCurvePow: Float = 1.1f,
    private val curveRotationRadiusItems: Float = 4.5f,
    private val curveRotationPow: Float = 1.12f,
    private val curveMaxRotationDeg: Float = 9f,
    private val curveExtraDownShiftPx: Int = 32
) : FlowLayoutManager() {

    override val isHorizontal: Boolean = true

    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    private var scrollXPx = 0f
    private var pendingRestore: PendingRestore? = null

    private data class CurvePlacement(
        val extraY: Float,
        val rotationDeg: Float
    )

    private data class ScatterOffsets(
        val shiftX: Float,
        val shiftY: Float,
        val rotationDeg: Float
    )

    private data class PendingRestore(
        val index: Int,
        val focus: Boolean
    )

    override fun canScrollVertically() = false
    override fun canScrollHorizontally() = true
    override fun isAutoMeasureEnabled() = true
    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    override fun nearestIndex(): Int {
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

    private fun screenCenter() = width / 2f
    private fun xLeft() = paddingLeft + leftInsetPx + baseSidePx / 2f
    private fun xRight() = width - paddingRight - rightInsetPx - baseSidePx / 2f
    private fun railY() = height - paddingBottom - bottomInsetPx - baseSidePx / 2f

    private fun scrollForIndex(index: Int): Float {
        if (itemCount == 0) return 0f
        return xRight() - index * itemPitchPx - screenCenter()
    }

    private fun minScroll(): Float {
        if (itemCount == 0) return 0f
        val lastIdx = max(0, itemCount - 1)
        return min(scrollForIndex(0), scrollForIndex(lastIdx))
    }

    private fun maxScroll(): Float {
        if (itemCount == 0) return 0f
        val lastIdx = max(0, itemCount - 1)
        return max(scrollForIndex(0), scrollForIndex(lastIdx))
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || width == 0 || height == 0) return
        pendingRestore?.let {
            if (applyRestore(it.index, it.focus)) pendingRestore = null
        }
        layoutAll(recycler)
    }

    private fun measureCardWithWidthAndCap(
        child: View,
        widthPx: Int,
        maxHeightPx: Int,
        cache: ChildCache,
        forceMeasure: Boolean
    ) {
        val lp = child.layoutParams as RecyclerView.LayoutParams
        var needsMeasure = forceMeasure || child.isLayoutRequested
        if (lp.width != widthPx || lp.height != RecyclerView.LayoutParams.WRAP_CONTENT) {
            lp.width = widthPx
            lp.height = RecyclerView.LayoutParams.WRAP_CONTENT
            child.layoutParams = lp
            needsMeasure = true
        }

        if (cache.lastMaxHeight != maxHeightPx) {
            cache.container?.setMaxHeightPx(maxHeightPx)
            cache.lastMaxHeight = maxHeightPx
            needsMeasure = true
        }

        if (needsMeasure || cache.lastMeasuredWidth != widthPx) {
            measureChildWithMargins(child, 0, 0)
            cache.lastMeasuredWidth = widthPx
        }
    }

    private fun applyTextByGain(cache: ChildCache, gain: Float, focused: Boolean): Boolean {
        var textChanged = false
        val title = cache.title
        val snippet = cache.snippet

        if (title != null) {
            if (focused) {
                val focusSize = 27f
                if (!cache.lastTitleFocused || abs(cache.lastTitleSp - focusSize) > 0.01f) {
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, focusSize)
                    cache.lastTitleSp = focusSize
                    cache.lastTitleFocused = true
                    textChanged = true
                }
            } else {
                val desiredSize = 21f + 5f * gain
                val bucketed = (desiredSize * 4f).roundToInt() / 4f
                if (
                    cache.lastTitleFocused ||
                    !cache.lastTitleSp.isFinite() ||
                    abs(cache.lastTitleSp - bucketed) >= 0.25f
                ) {
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, bucketed)
                    cache.lastTitleSp = bucketed
                    cache.lastTitleFocused = false
                    textChanged = true
                }
            }
        }

        if (snippet != null && cache.lastSnippetMaxLines != Integer.MAX_VALUE) {
            snippet.maxLines = Integer.MAX_VALUE
            cache.lastSnippetMaxLines = Integer.MAX_VALUE
            textChanged = true
        }

        return textChanged
    }

    private fun ensureCache(child: View): ChildCache {
        val existing = child.getTag(R.id.tag_card_cache) as? ChildCache
        if (existing != null) return existing
        val cache = ChildCache(
            container = child.findViewById(R.id.card_content),
            title = child.findViewById(R.id.title),
            snippet = child.findViewById(R.id.snippet)
        )
        child.setTag(R.id.tag_card_cache, cache)
        return cache
    }

    private data class ChildCache(
        val container: MaxHeightLinearLayout?,
        val title: TextView?,
        val snippet: TextView?,
        var lastMaxHeight: Int = -1,
        var lastMeasuredWidth: Int = -1,
        var lastTitleSp: Float = Float.NaN,
        var lastSnippetMaxLines: Int = -1,
        var lastTitleFocused: Boolean = false
    )

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cx = screenCenter()
        val baseY = railY()
        val xL = xLeft(); val xR = xRight()

        val layoutOverscan = 3
        val leftBound = xL - itemPitchPx * 1.5f
        val rightBound = xR + itemPitchPx * 1.5f
        val rawFirst = ceil((xR - scrollXPx - rightBound) / itemPitchPx.toDouble()).toInt() - layoutOverscan
        val rawLast = floor((xR - scrollXPx - leftBound) / itemPitchPx.toDouble()).toInt() + layoutOverscan
        val firstIdx = max(0, rawFirst)
        val lastIdx = min(itemCount - 1, rawLast)
        if (firstIdx > lastIdx) return

        val focusRadiusPx = itemPitchPx * 0.95f
        val heightCap = (height * 2f / 3f).roundToInt()

        val nearest = nearestIndex()
        val nearestX = xR - nearest * itemPitchPx - scrollXPx

        for (i in firstIdx..lastIdx) {
            val child = recycler.getViewForPosition(i)
            addView(child)
            val cache = ensureCache(child)

            val px = xR - i * itemPitchPx - scrollXPx
            val dist = abs(px - cx)

            val gain = exp(-(dist * dist) / (2f * focusRadiusPx * focusRadiusPx))

            val toDown = (1f - gain).pow(railCurvePow) * edgeDownShiftPx
            val toUp = gain * centerUpShiftPx
            val curve = computeCurvePlacement(px, cx)
            val py = baseY + toDown - toUp + curve.extraY

            val isSelected = (selectedIndex == i)

            val edgeSide = (baseSidePx * minEdgeScale).roundToInt()
            var side = (edgeSide + (baseSidePx - edgeSide) * interp.getInterpolation(gain)).roundToInt()
            if (isSelected) side = (side + (focusSidePx - side) * focusProgress).roundToInt()

            val textChanged = applyTextByGain(cache, gain, isSelected && focusProgress > 0.5f)

            measureCardWithWidthAndCap(child, side, heightCap, cache, textChanged)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            child.alpha = 0.92f + 0.08f * gain
            val dIdx = abs(nearestX - px) / itemPitchPx
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

    private fun computeCurvePlacement(px: Float, centerX: Float): CurvePlacement {
        if (itemPitchPx == 0) return CurvePlacement(0f, 0f)

        val signedStepsFromCenter = (px - centerX) / itemPitchPx
        val direction = if (signedStepsFromCenter >= 0f) 1f else -1f
        val magnitude = abs(signedStepsFromCenter) / curveRotationRadiusItems
        val eased = magnitude.coerceAtMost(1f).pow(curveRotationPow)
        val rotation = direction * eased * curveMaxRotationDeg
        val extraDown = eased * curveExtraDownShiftPx

        return CurvePlacement(extraDown, rotation)
    }

    private fun computeScatterOffsets(
        index: Int,
        gain: Float,
        isSelected: Boolean
    ): ScatterOffsets {
        val normalizedScroll = scrollXPx / itemPitchPx
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
