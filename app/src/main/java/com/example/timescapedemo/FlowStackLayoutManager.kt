//package com.example.timescapedemo
//
//import android.graphics.PointF
//import android.util.TypedValue
//import android.view.View
//import android.widget.TextView
//import androidx.recyclerview.widget.RecyclerView
//import kotlin.math.abs
//import kotlin.math.exp
//import kotlin.math.max
//import kotlin.math.min
//import kotlin.math.roundToInt
//
///**
// * Timescape-style horizontal flow:
// * - Cards spawn on the RIGHT (low alpha), brighten at CENTER, fade toward LEFT.
// * - Out-flow on the left is CLAMPED closer to center so both sides look symmetric.
// * - Tap a card -> Main Card (bigger, flat, centered). First drag clears focus.
// * - Pixel-based scrolling for smooth swipes & flings.
// */
//class FlowStackLayoutManager(
//    private val cardWpx: Int,              // normal card size (px) - matches item_card.xml
//    private val cardHpx: Int,
//    private val focusedWpx: Int,           // focused (main) card size (px)
//    private val focusedHpx: Int,
//    private val verticalGapPx: Int,        // vertical spacing between cards
//    private val stepsPerScreen: Float = 6f,
//    private val diagTiltDeg: Float = -12f, // tilt for non-focused cards
//    private val depthScaleDrop: Float = 0.10f,
//    private val edgeAlphaMin: Float = 0.25f,
//    // how far the LEFT stack can travel from center relative to the right spread (0..1).
//    // 1.0 = perfectly symmetric to the right side; 0.6 keeps it closer to center.
//    private val leftSpreadFactor: Float = 0.65f,
//    // controls how "curvy" the flow is; lower = smoother, less abrupt
//    private val sigmoidTension: Float = 1.6f
//) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {
//
//    private var scrollXPx = 0f
//    private var focusedIndex: Int? = null
//
//    override fun canScrollHorizontally() = true
//    override fun canScrollVertically() = false
//    override fun isAutoMeasureEnabled() = true
//
//    override fun generateDefaultLayoutParams() =
//        RecyclerView.LayoutParams(cardWpx, cardHpx)
//
//    /** Public focus API */
//    fun focus(index: Int) {
//        focusedIndex = index
//        scrollXPx = index * stepPx() // bring that item to center
//        requestLayout()
//    }
//    fun clearFocus() { focusedIndex = null; requestLayout() }
//    fun isFocused(index: Int) = focusedIndex == index
//    fun hasMainCardFocused() = focusedIndex != null
//
//    private fun stepPx(): Float = (width / stepsPerScreen).coerceAtLeast(1f)
//    private fun maxScroll(): Float = ((itemCount - 1).coerceAtLeast(0)) * stepPx()
//
//    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
//        detachAndScrapAttachedViews(recycler)
//        if (itemCount == 0 || width == 0 || height == 0) return
//        layoutAll(recycler)
//    }
//
//    private fun setSizeAndMeasure(child: View, w: Int, h: Int) {
//        val lp = child.layoutParams
//        if (lp.width != w || lp.height != h) {
//            lp.width = w; lp.height = h
//            child.layoutParams = lp
//        }
//        measureChildWithMargins(child, 0, 0)
//    }
//
//    private fun applyTextFocus(child: View, focused: Boolean) {
//        val title = child.findViewById<TextView>(R.id.title)
//        val snippet = child.findViewById<TextView>(R.id.snippet)
//        if (focused) {
//            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
//            snippet?.maxLines = 4
//        } else {
//            title?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
//            snippet?.maxLines = 2
//        }
//    }
//
//    private fun layoutAll(recycler: RecyclerView.Recycler) {
//        val centerX = width / 2f
//        val centerY = height / 2f
//
//        // Right-most usable X (cards "flow in" from here)
//        val rightX = width - paddingRight - cardWpx / 2f
//        // Left side is intentionally CLAMPED closer to the center (bias)
//        val rightSpread = rightX - centerX
//        val leftXClamped = centerX - rightSpread * leftSpreadFactor
//
//        val step = stepPx()
//        val centerIndex = (scrollXPx / step)
//        val window = 3 + (stepsPerScreen * 1.5f).toInt()
//        val first = max(0, (centerIndex - window).toInt())
//        val last  = min(itemCount - 1, (centerIndex + window).toInt())
//
//        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
//
//        for (i in first..last) {
//            val child = recycler.getViewForPosition(i)
//            addView(child)
//
//            val isFocused = (focusedIndex == i)
//            val rel = i - (focusedIndex?.toFloat() ?: centerIndex)
//
//            if (isFocused) {
//                // Main card: bigger, flat, centered
//                setSizeAndMeasure(child, focusedWpx, focusedHpx)
//                applyTextFocus(child, true)
//                val w = getDecoratedMeasuredWidth(child)
//                val h = getDecoratedMeasuredHeight(child)
//                val l = (centerX - w / 2f).toInt()
//                val t = (centerY - h / 2f).toInt()
//                layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//                child.scaleX = 1f; child.scaleY = 1f
//                child.rotation = 0f
//                child.alpha = 1f
//                child.translationZ = 6000f; child.elevation = 6000f
//                child.cameraDistance = 8000f
//                continue
//            }
//
//            // Non-focused: horizontal flow with vertical stacking
//            setSizeAndMeasure(child, cardWpx, cardHpx)
//            applyTextFocus(child, false)
//
//            // Map relative index -> 0..1 via gentler sigmoid
//            val u = 1f / (1f + exp(-(-rel * (sigmoidTension / stepsPerScreen))))
//            // Piecewise mapping so u=0.5 is exactly centerX, and LEFT side is clamped near center
//            val cx = if (u <= 0.5f) {
//                // Right -> Center
//                lerp(rightX, centerX, u / 0.5f)
//            } else {
//                // Center -> Left (clamped)
//                lerp(centerX, leftXClamped, (u - 0.5f) / 0.5f)
//            }
//            val cy = centerY + rel * verticalGapPx
//
//            val w = getDecoratedMeasuredWidth(child)
//            val h = getDecoratedMeasuredHeight(child)
//            val l = (cx - w / 2f).toInt()
//            val t = (cy - h / 2f).toInt()
//            layoutDecoratedWithMargins(child, l, t, l + w, t + h)
//
//            // Fade in at center, fade toward edges (respecting clamp)
//            val centerFactor = 1f - abs(2f * u - 1f)   // 1 at center, 0 at u≈0 or 1
//            val alpha = edgeAlphaMin + (1f - edgeAlphaMin) * centerFactor
//
//            // Depth scale falloff by distance in the stack
//            val d = abs(rel)
//            val s = max(0.82f, 1f - depthScaleDrop * d)
//
//            child.scaleX = s
//            child.scaleY = s
//            child.rotation = diagTiltDeg
//            child.alpha = alpha.coerceIn(0.15f, 1f)
//
//            val z = 1000f - d * 10f
//            child.translationZ = z; child.elevation = z
//            child.cameraDistance = 8000f
//        }
//    }
//
//    override fun scrollHorizontallyBy(
//        dx: Int,
//        recycler: RecyclerView.Recycler,
//        state: RecyclerView.State
//    ): Int {
//        if (itemCount == 0 || width == 0) return 0
//
//        // If user starts dragging while focused, clear focus so scrolling works
//        if (dx != 0 && focusedIndex != null) focusedIndex = null
//
//        val old = scrollXPx
//        val newX = (old + dx).coerceIn(0f, maxScroll())
//        val consumed = (newX - old).toInt()
//        scrollXPx = newX
//
//        detachAndScrapAttachedViews(recycler)
//        layoutAll(recycler)
//
//        // Return the exact consumed pixels (no ±1 hack) for smoother feel
//        return consumed
//    }
//
//    fun nearestIndex(): Int {
//        val step = stepPx()
//        return (scrollXPx / step).roundToInt().coerceIn(0, max(0, itemCount - 1))
//    }
//
//    fun offsetTo(index: Int): Int {
//        val step = stepPx()
//        val deltaPx = index * step - scrollXPx
//        return deltaPx.roundToInt()
//    }
//
//    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
//        val dir = if (targetPosition * stepPx() > scrollXPx) 1f else -1f
//        return PointF(dir, 0f)
//    }
//}
