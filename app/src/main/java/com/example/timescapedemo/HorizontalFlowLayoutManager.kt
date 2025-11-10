package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.PointF
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
 * Landscape rail that mirrors the portrait effect while scrolling horizontally.
 * Cards overlap because the center-to-center pitch is smaller than their width
 * and the layout continuously eases size, alpha, and scatter as in portrait.
 */
class HorizontalFlowLayoutManager(
    private val baseSidePx: Int,
    private val focusSidePx: Int,
    private val itemPitchPx: Int,
    private val leftInsetPx: Int,
    private val rightInsetPx: Int,
    private val verticalCenterOffsetPx: Int = 0,

    private val minEdgeScale: Float = 0.72f,
    private val edgeAlphaMin: Float = 0.30f,
    private val depthScaleDrop: Float = 0.05f,

    private val edgeDownShiftPx: Int = 48,
    private val centerUpShiftPx: Int = 0,
    private val railCurvePow: Float = 1.1f,

    private val curveRotationRadiusItems: Float = 4.5f,
    private val curveRotationPow: Float = 1.12f,
    private val curveMaxRotationDeg: Float = 9f,
    private val curveExtraDownShiftPx: Int = 32
) : RecyclerView.LayoutManager(),
    RecyclerView.SmoothScroller.ScrollVectorProvider,
    FlowLayoutManager {

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
    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)

    override fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    override fun nearestIndex(): Int {
        if (itemCount == 0 || itemPitchPx == 0) return 0
        val idx = ((xRight() + scrollXPx - screenCenter()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    override fun offsetTo(index: Int): Int {
        val desiredScroll = desiredScrollFor(index)
        return (desiredScroll - scrollXPx).roundToInt()
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

    override fun scrollBy(recycler: RecyclerView, delta: Int) {
        recycler.smoothScrollBy(delta, 0)
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
    private fun xRight() = width - paddingRight - rightInsetPx - baseSidePx / 2f
    private fun xLeft() = paddingLeft + leftInsetPx + baseSidePx / 2f
    private fun railY() = height / 2f + verticalCenterOffsetPx

    private fun minScroll(): Float = screenCenter() - xRight()
    private fun maxScroll(): Float {
        if (itemCount <= 0) return minScroll()
        val lastCentered = screenCenter() - xRight() + (itemCount - 1) * itemPitchPx
        return max(minScroll(), lastCentered)
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

    private fun applyTextByGain(
        cache: ChildCache,
        @Suppress("UNUSED_PARAMETER") gain: Float,
        @Suppress("UNUSED_PARAMETER") focused: Boolean
    ): Boolean {
        var textChanged = false
        val snippet = cache.snippet
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
            snippet = child.findViewById(R.id.snippet)
        )
        child.setTag(R.id.tag_card_cache, cache)
        return cache
    }

    private data class ChildCache(
        val container: MaxHeightLinearLayout?,
        val snippet: TextView?,
        var lastMaxHeight: Int = -1,
        var lastMeasuredWidth: Int = -1,
        var lastSnippetMaxLines: Int = -1
    )

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        if (itemPitchPx == 0) return

        val cx = screenCenter()
        val baseY = railY()

        val layoutOverscan = 3
        val leftEdge = paddingLeft.toFloat()
        val rightEdge = (width - paddingRight).toFloat()

        val firstIdx = max(
            0,
            ceil((xRight() + scrollXPx - rightEdge - itemPitchPx) / itemPitchPx).toInt() - layoutOverscan
        )
        val lastIdx = min(
            itemCount - 1,
            floor((xRight() + scrollXPx - leftEdge + itemPitchPx) / itemPitchPx).toInt() + layoutOverscan
        )

        if (firstIdx > lastIdx) {
            for (i in childCount - 1 downTo 0) {
                val child = getChildAt(i) ?: continue
                removeAndRecycleView(child, recycler)
            }
            return
        }

        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val pos = getPosition(child)
            if (pos !in firstIdx..lastIdx) {
                removeAndRecycleView(child, recycler)
            }
        }

        val focusRadiusPx = itemPitchPx * 0.95f
        val heightCap = (height * 2f / 3f).roundToInt()

        val nearest = nearestIndex()
        val nearestX = xRight() - nearest * itemPitchPx + scrollXPx

        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: recycler.getViewForPosition(i).also { addView(it) }
            val cache = ensureCache(child)

            val px = xRight() - i * itemPitchPx + scrollXPx
            val dist = abs(px - cx)
            val gain = exp(-(dist * dist) / (2f * focusRadiusPx * focusRadiusPx))

            val toDown = (1f - gain).pow(railCurvePow) * edgeDownShiftPx
            val toUp = gain * centerUpShiftPx
            val curve = computeCurvePlacement(px, cx)
            val py = baseY + toDown - toUp + curve.extraY

            val isSelected = selectedIndex == i
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

            child.alpha = edgeAlphaMin + (1f - edgeAlphaMin) * gain
            val dIdx = abs(nearestX - px) / itemPitchPx
            val depthS = max(0.94f, 1f - depthScaleDrop * dIdx)
            child.scaleX = depthS
            child.scaleY = depthS
            val scatter = computeScatterOffsets(i, gain, isSelected)
            child.translationX = scatter.shiftX
            child.translationY = scatter.shiftY
            child.rotation = curve.rotationDeg + scatter.rotationDeg
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
        val desired = desiredScrollFor(clamped)
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

    private fun desiredScrollFor(index: Int): Float {
        return screenCenter() - (xRight() - index * itemPitchPx)
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = desiredScrollFor(targetPosition)
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
